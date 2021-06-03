package collectors

import agent._
import conf.AWS
import controllers.routes
import play.api.libs.json.{JsString, JsValue, Json}
import play.api.mvc.Call
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.cloudformation.model.{DescribeStackResourcesRequest, DescribeStacksRequest, GetStackPolicyRequest, GetStackPolicyResponse, ListStacksRequest, Stack, StackResource, StackStatus, StackSummary}
import utils.Logging

import java.time.Instant
import scala.jdk.CollectionConverters._
import scala.language.postfixOps
import scala.util.Try

class CloudformationStackCollectorSet(accounts: Accounts) extends CollectorSet[CloudformationStack](ResourceType("cloudformationStacks"), accounts, Some(Regional)) {

  val lookupCollector: PartialFunction[Origin, Collector[CloudformationStack]] = {
    case amazon: AmazonOrigin => CloudformationStackCollector(amazon, resource, amazon.crawlRate(resource.name))
  }
}

case class CloudformationStackCollector(origin: AmazonOrigin, resource: ResourceType, crawlRate: CrawlRate) extends Collector[CloudformationStack] with Logging {

  val client: CloudFormationClient = CloudFormationClient
    .builder
    .credentialsProvider(origin.credentials.provider)
    .region(origin.awsRegionV2)
    .overrideConfiguration(AWS.clientConfig)
    .build

  def crawl: Iterable[CloudformationStack] = {
    client.describeStacksPaginator(DescribeStacksRequest.builder.build).asScala
      .flatMap(_.stacks.asScala)
      .filterNot(_.stackStatusAsString == StackStatus.DELETE_COMPLETE.toString)
      .map{ stack =>
        val resources = client.describeStackResources(DescribeStackResourcesRequest.builder.stackName(stack.stackName).build)
        val policy = client.getStackPolicy(GetStackPolicyRequest.builder.stackName(stack.stackName).build)
        CloudformationStack.fromApiData(stack, resources.stackResources.asScala, Option(policy.stackPolicyBody))
      }
  }
}

object CloudformationStack {
  def fromApiData(stack: Stack, resources: Iterable[StackResource], policy: Option[String]): CloudformationStack = {
    CloudformationStack(
      arn = stack.stackId,
      name = stack.stackName,
      status = stack.stackStatusAsString,
      statusReason = Option(stack.stackStatusReason),
      description = Option(stack.description),
      creationTime = stack.creationTime,
      driftInformation = Option(stack.driftInformation).map { driftInformation =>
        CloudformationStackDriftInformation(
          lastCheckTimestamp = Option(driftInformation.lastCheckTimestamp),
          status = driftInformation.stackDriftStatusAsString
        )
      },
      lastUpdatedTime = Option(stack.lastUpdatedTime),
      parentId = Option(stack.parentId),
      rootId = Option(stack.rootId),
      tags = stack.tags.asScala.map { tag =>
        tag.key -> tag.value
      }.toMap,
      disableRollback = Option(stack.disableRollback),
      terminationProtection = Option(stack.enableTerminationProtection),
      outputs = Option(stack.outputs).toList.flatMap(_.asScala).map { output =>
        CloudformationStackOutput(
          description = Option(output.description),
          exportName = Option(output.exportName),
          key = None,
          value = None,
          // commented out whilst we figure out whether it's safe to display all values
          // key = Option(output.outputKey),
          // value = Option(output.outputValue)
        )
      },
      parameters = Option(stack.parameters).toList.flatMap(_.asScala).map { param =>
        CloudformationStackParameter(
          key = Option(param.parameterKey),
          value = None,
          resolvedValue = None
// commented out whilst we figure out whether it's safe to display all values in case more secrets are added without NoEcho
//          value = Option(param.parameterValue),
//          resolvedValue = Option(param.resolvedValue)
        )
      },
      resources = resources.map { resource =>
        resource.description()
        CloudformationStackResource(
          logicalResourceId = resource.logicalResourceId,
          physicalResourceId = Option(resource.physicalResourceId),
          description = Option(resource.description),
          driftInformation = Option(resource.driftInformation).map { driftInformation =>
            CloudformationStackDriftInformation(
              lastCheckTimestamp = Option(driftInformation.lastCheckTimestamp),
              status = driftInformation.stackResourceDriftStatusAsString
            )
          },
          status = resource.resourceStatusAsString,
          statusReason = Option(resource.resourceStatusReason),
          resourceType = resource.resourceType,
          timestamp = resource.timestamp
        )
      }.toList,
      policy = policy.map { p =>
        Try(Json.parse(p)).toOption.getOrElse(JsString(s"Unable to parse policy $policy"))
      }
    )
  }
}

case class CloudformationStackDriftInformation(
  lastCheckTimestamp: Option[Instant],
  status: String
)

case class CloudformationStackOutput(
  description: Option[String],
  exportName: Option[String],
  key: Option[String],
  value: Option[String]
)

case class CloudformationStackParameter(
  key: Option[String],
// commented out whilst we figure out whether it's safe to display all values in case more secrets are added without NoEcho
  value: Option[String],
  resolvedValue: Option[String]
)

case class CloudformationStackResource(
  logicalResourceId: String,
  physicalResourceId: Option[String],
  description: Option[String],
  driftInformation: Option[CloudformationStackDriftInformation],
  status: String,
  statusReason: Option[String],
  resourceType: String,
  timestamp: Instant
)

case class CloudformationStack(
  arn: String,
  name: String,
  status: String,
  statusReason: Option[String],
  description: Option[String],
  creationTime: Instant,
  driftInformation: Option[CloudformationStackDriftInformation],
  lastUpdatedTime: Option[Instant],
  parentId: Option[String],
  rootId: Option[String],
  tags: Map[String, String],
  disableRollback: Option[Boolean],
  terminationProtection: Option[Boolean],
  outputs: List[CloudformationStackOutput],
  parameters: List[CloudformationStackParameter],
  resources: List[CloudformationStackResource],
  policy: Option[JsValue]
) extends IndexedItem {
  def callFromArn: (String) => Call = arn => routes.Api.cloudformationStack(arn)
}
