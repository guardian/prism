package collectors

import agent._
import com.amazonaws.services.lambda.model.{AWSLambdaException, FunctionConfiguration, ListFunctionsRequest, ListTagsRequest}
import com.amazonaws.services.lambda.{AWSLambda, AWSLambdaClientBuilder}
import controllers.routes
import play.api.mvc.Call
import utils.{Logging, PaginatedAWSRequest}

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.language.postfixOps

object LambdaCollectorSet extends CollectorSet[Lambda](ResourceType("lambda", 1 hour, 5 minutes)) {
  val lookupCollector: PartialFunction[Origin, Collector[Lambda]] = {
    case amazon: AmazonOrigin => AWSLambdaCollector(amazon, resource)
  }
}

case class AWSLambdaCollector(origin: AmazonOrigin, resource: ResourceType) extends Collector[Lambda] with Logging {

  val client = AWSLambdaClientBuilder.standard()
    .withCredentials(origin.credentials.provider)
    .withRegion(origin.awsRegion)
    .build()

  def crawl: Iterable[Lambda] = try {
    PaginatedAWSRequest.run(client.listFunctions)(new ListFunctionsRequest()).map { lambda => {
      val tags = client.listTags(new ListTagsRequest().withResource(lambda.getFunctionArn)).getTags.toMap
      Thread.sleep(100) // this avoids ThrottlingException back from AWS
      Lambda.fromApiData(
        lambda,
        client,
        origin.region,
        tags
      )
    }
    }
  }
  catch {
    case e: AWSLambdaException if e.getErrorCode == "AccessDeniedException" =>
      log.warn(s"Could not get lambda information for ${origin.account}", e)
      Nil
  }
}

object Lambda {

  def fromApiData(lambda: FunctionConfiguration, client: AWSLambda, region: String, tags: Map[String, String]): Lambda = Lambda(
    arn = lambda.getFunctionArn,
    name = lambda.getFunctionName,
    region,
    runtime = lambda.getRuntime,
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