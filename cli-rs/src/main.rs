use std::error::Error;
use snafu::{ResultExt, OptionExt, Snafu};
use structopt::StructOpt;
use serde_json::Value;
use serde::Deserialize;
use prettytable::{Table, format, Row, Cell};

#[derive(Debug, Snafu)]
enum CliError {
    #[snafu(display("Could not access prism path {}: {}", path, source))]
    PrismError {
        path: String,
        #[snafu(source(from(reqwest::Error, Box::new)))]
        source: Box<reqwest::Error>,
    },
    JsonError {
        #[snafu(source(from(reqwest::Error, Box::new)))]
        source: Box<reqwest::Error>
    },
    #[snafu(display("Couldn't locate appropriate key in data from endpoint {}", endpoint))]
    KeyError {
        endpoint: String,
    },
    #[snafu(display("Expected array from endpoint {}", endpoint))]
    DataError {
        endpoint: String,
    }
}

type Result<T, E = CliError> = std::result::Result<T, E>;

/// Parse a single key-value pair
fn parse_key_val<T, U>(s: &str) -> Result<(T, U), Box<dyn Error>>
where
    T: std::str::FromStr,
    T::Err: Error + 'static,
    U: std::str::FromStr,
    U::Err: Error + 'static,
{
    let pos = s
        .find('=')
        .ok_or_else(|| format!("invalid KEY=value: no `=` found in `{}`", s))?;
    Ok((s[..pos].parse()?, s[pos + 1..].parse()?))
}

#[derive(StructOpt)]
struct Cli {
    /// Activate debug mode
    #[structopt(short, long)]
    debug: bool,

    /// Only print selected field
    #[structopt(short, long)]
    short: bool,

    /// Specify the fields to display
    #[structopt(short, long, number_of_values = 1)]
    fields: Option<Vec<String>>,

    /// Endpoint (e.g. instances, certificates etc.)
    #[structopt(short, long, default_value="instances")]
    endpoint: String,

    /// Free form query
    #[structopt(parse(try_from_str = parse_key_val))]
    query: Vec<(String, String)>
}

#[derive(Deserialize, Debug)]
#[serde(rename_all = "camelCase")]
struct PrismOrigin {
    account_name: String,
    owner_id: Option<String>,
    region: String,
    account_number: String,
    vendor: String,
    credentials: String
}

#[derive(Deserialize, Debug)]
#[serde(rename_all = "camelCase")]
struct PrismSource {
    resource: String,
    origin: PrismOrigin,
    status: String,
    created_at: String,
    item_count: u32,
    age: u32,
    stale: bool
}

#[derive(Deserialize, Debug)]
#[serde(rename_all = "camelCase")]
struct PrismResponse {
    status: String,
    last_updated: String,
    stale: bool,
    data: Value,
    sources: Vec<PrismSource>
}

async fn call_prism(path: String, query_params: Vec<(String, String)>) -> Result<PrismResponse> {
    let client = reqwest::Client::new();
    let url = format!("https://prism.gutools.co.uk{}", path);
    let request = client.get(&url)
        .query(&query_params);

    println!("Calling {:?}", request);

    let response = request.send()
        .await
        .context(PrismError { path })?
        .json::<PrismResponse>()
        .await
        .context(JsonError);
    response
}

async fn get_fields(response: &PrismResponse, endpoint: &str, fields: &[String]) -> Result<Vec<Vec<Option<String>>>> {
    let data_of_interest = response
        .data
        .pointer(&format!("/{}", endpoint))
        .context(KeyError { endpoint })?
        .as_array()
        .context(DataError { endpoint })?;

    let field_values: Vec<Vec<Option<String>>> = data_of_interest.into_iter()
        .map(|item| {
            fields.iter().map(|field| {
                item.pointer(&format!("/{}", field)).and_then(|v| v.as_str()).map(|v| v.to_string())
            })
            .collect()
        })
        .collect();
    
    Ok(field_values)
}

fn make_table(fields: &[String], contents: Vec<Vec<Option<String>>>) -> Result<Table> {
    let mut table = Table::new();
    let titles = Row::new(fields.iter().map(|field| Cell::new(field)).collect());
    table.set_titles(titles);
    for row in contents {
        let table_row = Row::new(row.iter().map(|value| {
            match value {
                Some(exists) => Cell::new(exists),
                None => Cell::new("")
            }
        }).collect());
        table.add_row(table_row);
    };
    Ok(table)
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Cli::from_args();
    println!("Arguments are... fields: {:?} query: {:?}", args.fields, args.query);
    let requested_fields = args.fields.unwrap_or(vec![String::from("stack"), String::from("stage"), String::from("app/0"), String::from("arn")]);
    let response = call_prism(format!("/{}", args.endpoint), args.query).await?;
    let contents = get_fields(&response, &args.endpoint, &requested_fields).await?;
    let table = &mut make_table(&requested_fields, contents)?;
    table.set_format(*format::consts::FORMAT_NO_BORDER_LINE_SEPARATOR);
    table.printstd();
    //println!("Field values of {:?}: {:?}", requested_fields, fields);
    Ok(())
}
