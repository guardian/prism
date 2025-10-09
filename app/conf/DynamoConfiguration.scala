package conf

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder
import com.amazonaws.services.dynamodbv2.document.{
  DynamoDB,
  Item,
  PrimaryKey,
  TableKeysAndAttributes
}
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import play.api.{Configuration, Mode}
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import utils.Logging

import scala.jdk.CollectionConverters._

case class Identity(stack: String, app: String, stage: String)
case class ConfigSegment(app: String, stage: String)

object DynamoConfiguration {
  def apply(
      credProvider: AWSCredentialsProvider,
      region: Regions,
      identity: Identity,
      prefix: String = "config-"
  ): ConfigurationSource = {
    new DynamoConfiguration(credProvider, region, identity, prefix)
  }
}

class DynamoConfiguration(
    credProvider: AWSCredentialsProvider,
    region: Regions,
    identity: Identity,
    prefix: String
) extends ConfigurationSource
    with Logging {

  def configuration(mode: Mode): Configuration = {

    if (mode == Mode.Test)
      Configuration.empty
    else {
      val client = AmazonDynamoDBClientBuilder
        .standard()
        .withCredentials(credProvider)
        .withRegion(region)
        .build()
      val dynamoDb = new DynamoDB(client)

      val tableName = s"$prefix${identity.stack}"

      val configs = fromTable(
        dynamoDb,
        tableName,
        configSegmentsFromIdentity(identity)
      )

      val finalConfig = configs
        .flatMap { case (_, config) => config }
        .foldRight[Configuration](Configuration.empty) { _.withFallback(_) }

      finalConfig
    }
  }

  def fromTable(
      dynamoDb: DynamoDB,
      tableName: String,
      configSegments: Seq[ConfigSegment]
  ): Seq[(ConfigSegment, Option[Configuration])] = {
    val primaryKeys = configSegments.map { segment =>
      new PrimaryKey("App", segment.app, "Stage", segment.stage)
    }

    val tableKeysAndAttributes =
      new TableKeysAndAttributes(tableName).withPrimaryKeys(primaryKeys: _*)

    val result = dynamoDb.batchGetItem(tableKeysAndAttributes)
    val items =
      result.getTableItems.asScala.get(tableName).toSeq.flatMap(_.asScala)
    val configs = items.map { item =>
      val app = item.getString("App")
      val stage = item.getString("Stage")
      ConfigSegment(app, stage) -> fromItem(
        item,
        s"Dynamo DB table $tableName [App=$app, Stage=$stage]"
      )
    }.toMap

    configSegments.map { segment => segment -> configs.get(segment) }
  }

  def fromItem(item: Item, originDescription: String): Configuration = {
    val config = Configuration(
      ConfigFactory.parseMap(item.getMap("Config"), originDescription)
    )

    // Now that we've a HOCON configuration from the DynamoDb row, copy it to S3.
    writeHoconConfigToS3(config, item.getString("Stage"))

    config
  }

  private def writeHoconConfigToS3(config: Configuration, stage: String) = {
    val content: String = config.underlying
      .root()
      .render(
        ConfigRenderOptions
          .defaults()

          // Preserve HOCON format
          .setJson(false)

          // Remove comments from line 93 above (`s"Dynamo DB table $tableName [App=$app, Stage=$stage]"`)
          .setOriginComments(false)
      )

    val client = S3Client.builder
      .credentialsProvider(ProfileCredentialsProvider.create("deployTools"))
      .region(Region.EU_WEST_1)
      .build()

    val bucket = System.getenv("PRISM_CONFIG_BUCKET")
    val objectKey = s"deploy/prism/$stage.conf"

    val request =
      PutObjectRequest
        .builder()
        .bucket(bucket)
        .key(objectKey)
        .build()

    val response = client.putObject(request, RequestBody.fromString(content))

    println(
      s"Copied configuration to s3://$bucket/$objectKey (size: ${response.size()})"
    )
  }

  // The rows to read from DynamoDb
  def configSegmentsFromIdentity(identity: Identity) = Seq(
    ConfigSegment("global", "global"),
    ConfigSegment(identity.app, "global"),
    ConfigSegment(identity.app, "DEV"),
    ConfigSegment(identity.app, "CODE"),
    ConfigSegment(identity.app, "PROD")
  )
}
