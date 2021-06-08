package collectors

import agent._
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.{FunctionConfiguration, ListTagsRequest, PackageType, Runtime}
import conf.AWS
import controllers.routes
import play.api.mvc.Call
import utils.Logging

import scala.jdk.CollectionConverters._
import scala.language.postfixOps

class LambdaCollectorSet(accounts: Accounts) extends CollectorSet[Lambda](ResourceType("lambda"), accounts, Some(Regional)) {
  val lookupCollector: PartialFunction[Origin, Collector[Lambda]] = {
    case amazon: AmazonOrigin => AWSLambdaCollector(amazon, resource, amazon.crawlRate(resource.name))
  }
}

case class AWSLambdaCollector(origin: AmazonOrigin, resource: ResourceType, crawlRate: CrawlRate) extends Collector[Lambda] with Logging {

  val client = LambdaClient
    .builder
    .credentialsProvider(origin.credentials.provider)
    .region(origin.awsRegionV2)
    .overrideConfiguration(AWS.clientConfig)
    .build

  def crawl: Iterable[Lambda] = {
    client.listFunctionsPaginator().asScala.flatMap(_.functions.asScala).map { lambda =>
      val tags = client.listTags(ListTagsRequest.builder.resource(lambda.functionArn).build).tags.asScala.toMap
      Thread.sleep(100) // this avoids ThrottlingException back from AWS
      Lambda.fromApiData(
        lambda,
        origin.region,
        tags
      )
    }
  }
}

object Lambda extends Logging{
  private def getRuntime(lambda: FunctionConfiguration): Option[String] = {
    if(lambda.runtime == Runtime.UNKNOWN_TO_SDK_VERSION) {
      log.warn(s"Lambda runtime ${lambda.runtimeAsString} isn't recognised in the AWS SDK. Is there a later version of the AWS SDK available?")
    }

    /*
    From the docs:
      If the service returns an enum value that is not available in the current SDK version, runtime will return Runtime.UNKNOWN_TO_SDK_VERSION.
      The raw value returned by the service is available from runtimeAsString.

    That is `runtimeAsString` is the safest string representation of a lambda runtime as `Runtime.UNKNOWN_TO_SDK_VERSION.toString` yields a NPE.

    See: https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/lambda/model/FunctionConfiguration.html#runtimeAsString--
     */
    Option(lambda.runtimeAsString) // remove `null`s
  }

  private def getPackageType(lambda: FunctionConfiguration): Option[String] = {
    if(lambda.packageType == PackageType.UNKNOWN_TO_SDK_VERSION){
      log.warn(s"Lambda package type ${lambda.packageTypeAsString} isn't recognised in the AWS SDK. Is there a later version of the AWS SDK available?")
    }

    Option(lambda.packageTypeAsString) // remove `null`s
  }

  def fromApiData(lambda: FunctionConfiguration, region: String, tags: Map[String, String]): Lambda = Lambda(
    arn = lambda.functionArn(),
    name = lambda.functionName,
    region,
    runtime = getRuntime(lambda),
    packageType = getPackageType(lambda),
    tags,
    app = tags.get("App").map(_.split(",").toList).getOrElse(Nil),
    guCdkVersion = tags.get("gu:cdk:version"),
    guCdkPatternName = tags.get("gu:cdk:pattern-name"),
    stage = tags.get("Stage"),
    stack = tags.get("Stack")
  )
}

case class Lambda(
  arn: String,
  name: String,
  region: String,
  runtime: Option[String],
  packageType: Option[String],
  tags: Map[String, String],
  override val app: List[String],
  override val guCdkVersion: Option[String],
  override val guCdkPatternName: Option[String],
  override val stage: Option[String],
  override val stack: Option[String]
) extends IndexedItemWithCoreTags {
  override def callFromArn: (String) => Call = arn => routes.Api.instance(arn)
}
