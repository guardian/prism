package utils

import collectors.Lambda
import com.amazonaws.{AmazonWebServiceRequest, AmazonWebServiceResult}
import com.amazonaws.services.autoscaling.model.{DescribeLaunchConfigurationsRequest, DescribeLaunchConfigurationsResult, LaunchConfiguration}
import com.amazonaws.services.certificatemanager.model.{CertificateSummary, ListCertificatesRequest, ListCertificatesResult}
import com.amazonaws.services.ec2.model._
import com.amazonaws.services.elasticloadbalancing.model.{DescribeLoadBalancersRequest, DescribeLoadBalancersResult, LoadBalancerDescription}
import com.amazonaws.services.identitymanagement.model.{ListServerCertificatesRequest, ListServerCertificatesResult, ServerCertificateMetadata}
import com.amazonaws.services.lambda.model.{FunctionConfiguration, ListFunctionsRequest, ListFunctionsResult}
import com.amazonaws.services.route53.model._

import scala.collection.JavaConverters._

trait Paging[Request, Result, A, Item] {
  def getPageMarker(result: Result): Option[A]
  def withPageMarker(request: Request, marker: Option[A]): Request
  def getItemsFromResult(result: Result): Iterable[Item]
}

object Paging {
  def instance[Request, Result, A, Item](
                                    get: Result => Option[A],
                                    set: Request => Option[A] => Request,
                                    resultItems: Result => Iterable[Item]
                                  ): Paging[Request, Result, A, Item] = new Paging[Request, Result, A, Item] {
    override def getPageMarker(result: Result): Option[A] = get(result)
    override def withPageMarker(request: Request, marker: Option[A]): Request =
      set(request)(marker)
    override def getItemsFromResult(result: Result): Iterable[Item] = resultItems(result)
  }

  implicit def listZonesMarking: Paging[ListHostedZonesRequest, ListHostedZonesResult, String, HostedZone] =
    Paging.instance(r => Option(r.getMarker), r => m => r.withMarker(m.orNull), r => r.getHostedZones.asScala)

  case class ListResourceRecordSetMarker(recordName: String, recordType: String, recordIdentifier: String)

  implicit def listResourceRecordSets: Paging[ListResourceRecordSetsRequest, ListResourceRecordSetsResult, ListResourceRecordSetMarker, ResourceRecordSet] = {
    val resultToMaybeMarker: ListResourceRecordSetsResult => Option[ListResourceRecordSetMarker] = result => {
      if (result.isTruncated) {
        Some(ListResourceRecordSetMarker(result.getNextRecordName, result.getNextRecordType, result.getNextRecordIdentifier))
      } else {
        None
      }
    }
    Paging.instance(resultToMaybeMarker, request => marker => {
      request
        .withStartRecordName(marker.map(_.recordName).orNull)
        .withStartRecordType(marker.map(_.recordType).orNull)
        .withStartRecordIdentifier(marker.map(_.recordIdentifier).orNull)
    }, r => r.getResourceRecordSets.asScala.toList)
  }

  implicit def describeInstances: Paging[DescribeInstancesRequest, DescribeInstancesResult, String, (Reservation, Instance)] =
    Paging.instance(r => Option(r.getNextToken), r => t => r.withNextToken(t.orNull), r => r.getReservations.asScala.flatMap(r => r.getInstances.asScala.map(r -> _)))

  implicit def describeLambdas: Paging[ListFunctionsRequest, ListFunctionsResult, String, FunctionConfiguration] =
    Paging.instance(r => Option(r.getNextMarker), r => m => r.withMarker(m.orNull), r => r.getFunctions.asScala)

  implicit def describeLaunchConfigs: Paging[DescribeLaunchConfigurationsRequest, DescribeLaunchConfigurationsResult, String, LaunchConfiguration] =
    Paging.instance(r => Option(r.getNextToken), r => t => r.withNextToken(t.orNull), r => r.getLaunchConfigurations.asScala)

  implicit def describeSecurityGroups: Paging[DescribeSecurityGroupsRequest, DescribeSecurityGroupsResult, String, SecurityGroup] =
    Paging.instance(r => Option(r.getNextToken), r => t => r.withNextToken(t.orNull), r => r.getSecurityGroups.asScala)

  implicit def describeServerCertificates: Paging[ListServerCertificatesRequest, ListServerCertificatesResult, String, ServerCertificateMetadata] =
    Paging.instance(r => Option(r.getMarker), r => m => r.withMarker(m.orNull), r => r.getServerCertificateMetadataList.asScala)

  implicit def describeAcmCertificates: Paging[ListCertificatesRequest, ListCertificatesResult, String, CertificateSummary] =
    Paging.instance(r => Option(r.getNextToken), r => t => r.withNextToken(t.orNull), r => r.getCertificateSummaryList.asScala)

  implicit def describeLoadBalancers: Paging[DescribeLoadBalancersRequest, DescribeLoadBalancersResult, String, LoadBalancerDescription] =
    Paging.instance(r => Option(r.getNextMarker), r => m => r.withMarker(m.orNull), r => r.getLoadBalancerDescriptions.asScala)
}

object PaginatedAWSRequest {

  def run[Request <: AmazonWebServiceRequest, Result <: AmazonWebServiceResult[_], Item, A]
  (awsCall: Request => Result)(request: Request)
  (implicit marking: Paging[Request, Result, A, Item]): Iterable[Item] = {

    def recurse( request: Request,
                 results: List[Item],
                 timesThrottled: Int ): Iterable[Item] = {
      val result = awsCall(request)
      val newResults = results ++ marking.getItemsFromResult(result)
      val marker = marking.getPageMarker(result)

      marker match {
        case None | Some("") =>
          newResults

        case otherMarker =>
          recurse(marking.withPageMarker(request, otherMarker), newResults, timesThrottled)
      }
    }

    recurse(request, List.empty, 0)
  }
}