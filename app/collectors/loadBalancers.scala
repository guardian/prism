package collectors

import agent._
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient
import software.amazon.awssdk.services.elasticloadbalancing.model.{DescribeLoadBalancersRequest, LoadBalancerDescription}
import conf.AWS
import controllers.routes
import play.api.mvc.Call
import utils.Logging

import scala.jdk.CollectionConverters._
import scala.language.postfixOps

class LoadBalancerCollectorSet(accounts: Accounts) extends CollectorSet[LoadBalancer](ResourceType("loadBalancers"), accounts, Some(Regional)) {
  val lookupCollector: PartialFunction[Origin, Collector[LoadBalancer]] = {
    case amazon: AmazonOrigin => LoadBalancerCollector(amazon, resource, amazon.crawlRate(resource.name))
  }
}

case class LoadBalancerCollector(origin: AmazonOrigin, resource: ResourceType, crawlRate: CrawlRate) extends Collector[LoadBalancer] with Logging {

  val client = ElasticLoadBalancingClient
    .builder
    .credentialsProvider(origin.credentials.provider)
    .region(origin.awsRegionV2)
    .overrideConfiguration(AWS.clientConfig)
    .build

  def crawl: Iterable[LoadBalancer] = {
    client.describeLoadBalancersPaginator(DescribeLoadBalancersRequest.builder.build).loadBalancerDescriptions.asScala.map { elb =>
      LoadBalancer.fromApiData(elb, origin)
    }
  }
}

object LoadBalancer {
  def fromApiData(loadBalancer: LoadBalancerDescription, origin: AmazonOrigin): LoadBalancer = {
    LoadBalancer(
      arn = s"arn:aws:elasticloadbalancing:${origin.region}:${origin.accountNumber.getOrElse("")}:loadbalancer/${loadBalancer.loadBalancerName}",
      name = loadBalancer.loadBalancerName,
      dnsName = loadBalancer.dnsName,
      vpcId = Option(loadBalancer.vpcId),
      scheme = Option(loadBalancer.scheme),
      availabilityZones = loadBalancer.availabilityZones.asScala.toList,
      subnets = loadBalancer.subnets.asScala.toList
    )
  }
}

case class LoadBalancer(
                        arn: String,
                        name: String,
                        dnsName: String,
                        vpcId: Option[String],
                        scheme: Option[String],
                        availabilityZones: List[String],
                        subnets: List[String]
                      ) extends IndexedItem {
  def callFromArn: (String) => Call = arn => routes.Api.elb(arn)
}