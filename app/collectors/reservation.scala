package collectors

import java.time.Instant

import agent._
import conf.AWS
import controllers.routes
import play.api.mvc.Call
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.{DescribeReservedInstancesRequest, ReservedInstances, RecurringCharge => AwsRecurringCharge}
import utils.Logging

import scala.jdk.CollectionConverters._
import scala.language.postfixOps

class ReservationCollectorSet(accounts: Accounts) extends CollectorSet[Reservation](ResourceType("reservation"), accounts) {
  val lookupCollector: PartialFunction[Origin, Collector[Reservation]] = {
    case amazon: AmazonOrigin => AWSReservationCollector(amazon, resource, amazon.crawlRate(resource.name))
  }
}

case class AWSReservationCollector(origin: AmazonOrigin, resource: ResourceType, crawlRate: CrawlRate) extends Collector[Reservation] with Logging {

  val client = Ec2Client
    .builder()
    .credentialsProvider(origin.credentials.providerV2)
    .region(origin.awsRegionV2)
    .overrideConfiguration(AWS.clientConfigV2)
    .build()

  def crawl: Iterable[Reservation] = {
    client.describeReservedInstances(DescribeReservedInstancesRequest.builder.build).reservedInstances.asScala.map {
      Reservation.fromApiData(_, origin)
    }
  }
}

case class Reservation(
  arn: String,
  id: String,
  region: String,
  instanceType: String,
  instanceCount: Int,
  productDescription: String,
  fixedPrice: Float,
  usagePrice: Float,
  recurringCharges: List[RecurringCharge],
  state: String,
  currencyCode: String,
  duration: Long,
  instanceTenancy: String,
  offeringType: String,
  startTime: Instant,
  endTime: Instant,
) extends IndexedItem {
  override def callFromArn: (String) => Call = arn => routes.Api.reservation(arn)

}

object Reservation {
  def fromApiData(reservationInstance: ReservedInstances, origin: AmazonOrigin): Reservation = {
    val region = reservationInstance.availabilityZone
    val arn = s"arn:aws:ec2:$region:${origin.accountNumber.getOrElse("")}:reservation/${reservationInstance.reservedInstancesId}"
    val recurringCharges = reservationInstance.recurringCharges.asScala.map(RecurringCharge.fromApiData).toList
    Reservation(
      arn = arn,
      id = reservationInstance.reservedInstancesId,
      region = region,
      instanceType = reservationInstance.instanceTypeAsString,
      instanceCount = reservationInstance.instanceCount,
      productDescription = reservationInstance.productDescriptionAsString,
      fixedPrice = reservationInstance.fixedPrice,
      usagePrice = reservationInstance.usagePrice,
      recurringCharges = recurringCharges,
      state = reservationInstance.stateAsString,
      currencyCode = reservationInstance.currencyCodeAsString,
      duration = reservationInstance.duration,
      instanceTenancy = reservationInstance.instanceTenancyAsString,
      offeringType = reservationInstance.offeringTypeAsString,
      startTime = reservationInstance.start,
      endTime = reservationInstance.end
    )
  }
}

case class RecurringCharge(
  frequency: String,
  amount: Double
)

object RecurringCharge {
  def fromApiData(recurringCharge: AwsRecurringCharge): RecurringCharge = {
    RecurringCharge(
      frequency = recurringCharge.frequencyAsString,
      amount = recurringCharge.amount
    )
  }
}
