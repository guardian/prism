package collectors

import agent._
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClientBuilder
import com.amazonaws.services.elasticloadbalancing.model.{DescribeLoadBalancersRequest, LoadBalancerDescription}
import controllers.routes
import play.api.mvc.Call
import utils.{Logging, PaginatedAWSRequest}

import collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

object LoadBalancerCollectorSet extends CollectorSet[LoadBalancer](ResourceType("loadBalancers", 1 hour, 5 minutes)) {
  val lookupCollector: PartialFunction[Origin, Collector[LoadBalancer]] = {
    case amazon: AmazonOrigin => LoadBalancerCollector(amazon, resource)
  }
}

case class LoadBalancerCollector(origin: AmazonOrigin, resource: ResourceType) extends Collector[LoadBalancer] with Logging {

  val client = AmazonElasticLoadBalancingClientBuilder.standard()
    .withCredentials(origin.credentials.provider)
    .withRegion(origin.awsRegion)
    .build()

  def crawl: Iterable[LoadBalancer] = PaginatedAWSRequest.run(client.describeLoadBalancers)(new DescribeLoadBalancersRequest()).map{ elb =>
    LoadBalancer.fromApiData(elb, origin)
  }
}

object LoadBalancer {
  def fromApiData(loadBalancer: LoadBalancerDescription, origin: AmazonOrigin) = {
    LoadBalancer(
      arn = s"arn:aws:elasticloadbalancing:${origin.region}:${origin.accountNumber.getOrElse("")}:loadbalancer/${loadBalancer.getLoadBalancerName}",
      name = loadBalancer.getLoadBalancerName,
      dnsName = loadBalancer.getDNSName,
      vpcId = Option(loadBalancer.getVPCId),
      scheme = Option(loadBalancer.getScheme),
      availabilityZones = loadBalancer.getAvailabilityZones.asScala.toList,
      subnets = loadBalancer.getSubnets.asScala.toList
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
  def callFromArn: (String) => Call = arn => routes.Application.index()//routes.Api.elb(arn)
}