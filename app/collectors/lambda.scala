package collectors

import agent._
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.{FunctionConfiguration, ListTagsRequest}
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
    .builder()
    .credentialsProvider(origin.credentials.providerV2)
    .region(origin.awsRegionV2)
    .overrideConfiguration(AWS.clientConfigV2)
    .build()

  def crawl: Iterable[Lambda] = {
    client.listFunctionsPaginator().asScala.flatMap(_.functions.asScala).map { lambda =>
      val tags = client.listTags(ListTagsRequest.builder().resource(lambda.functionArn).build()).tags.asScala.toMap
      Thread.sleep(100) // this avoids ThrottlingException back from AWS
      Lambda.fromApiData(
        lambda,
        origin.region,
        tags
      )
    }
  }
}

object Lambda {

  def fromApiData(lambda: FunctionConfiguration, region: String, tags: Map[String, String]): Lambda = Lambda(
    arn = lambda.functionArn(),
    name = lambda.functionName,
    region,
    runtime = lambda.runtime.toString,
    tags,
    stage = tags.get("Stage"),
    stack = tags.get("Stack")
  )
}

case class Lambda(
  arn: String,
  name: String,
  region: String,
  runtime: String,
  tags: Map[String, String],
  override val stage: Option[String],
  override val stack: Option[String]
) extends IndexedItemWithStage with IndexedItemWithStack {
  override def callFromArn: (String) => Call = arn => routes.Api.instance(arn)
}