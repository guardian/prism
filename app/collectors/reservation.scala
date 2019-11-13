package collectors

import agent._
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder
import com.amazonaws.services.ec2.model.{DescribeReservedInstancesRequest, ReservedInstances, RecurringCharge => AWSRecurringCharge}
import controllers.routes
import org.joda.time.{DateTime, Duration}
import play.api.mvc.Call
import utils.Logging

import scala.collection.JavaConverters._
import scala.util.Try

import scala.concurrent.duration._
import scala.language.postfixOps

class ReservationCollectorSet(accounts: Accounts) extends CollectorSet[Reservation](ResourceType("reservation", 15 minutes, 1 minute), accounts) {
  val lookupCollector: PartialFunction[Origin, Collector[Reservation]] = {
    case amazon: AmazonOrigin => AWSReservationCollector(amazon, resource)
  }
}

case class AWSReservationCollector(origin: AmazonOrigin, resource: ResourceType) extends Collector[Reservation] with Logging {

  val client = AmazonEC2ClientBuilder.standard()
    .withCredentials(origin.credentials.provider)
    .withRegion(origin.awsRegion)
    .build()

  def crawl: Iterable[Reservation] = {
    client.describeReservedInstances(new DescribeReservedInstancesRequest()).getReservedInstances.asScala.map {
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
  startTime: Option[DateTime],
  endTime: Option[DateTime]
) extends IndexedItem {
  override def callFromArn: (String) => Call = arn => routes.Api.reservation(arn)

}

object Reservation {
  def fromApiData(reservationInstance: ReservedInstances, origin: AmazonOrigin): Reservation = {
    val region = reservationInstance.getAvailabilityZone
    val arn = s"arn:aws:ec2:$region:${origin.accountNumber.getOrElse("")}:reservation/${reservationInstance.getReservedInstancesId}"
    val recurringCharges = reservationInstance.getRecurringCharges.asScala.map(RecurringCharge.fromApiData(_)).toList
    Reservation(
      arn = arn,
      id = reservationInstance.getReservedInstancesId,
      region = region,
      instanceType = reservationInstance.getInstanceType,
      instanceCount = reservationInstance.getInstanceCount,
      productDescription = reservationInstance.getProductDescription,
      fixedPrice = reservationInstance.getFixedPrice,
      usagePrice = reservationInstance.getUsagePrice,
      recurringCharges = recurringCharges,
      state = reservationInstance.getState,
      currencyCode = reservationInstance.getCurrencyCode,
      duration = reservationInstance.getDuration,
      instanceTenancy = reservationInstance.getInstanceTenancy,
      offeringType = reservationInstance.getOfferingType,
      startTime = Try(new DateTime(reservationInstance.getStart)).toOption,
      endTime = Try(new DateTime(reservationInstance.getEnd)).toOption
    )
  }
}

case class RecurringCharge(
  frequency: String,
  amount: Double
)

object RecurringCharge {
  def fromApiData(recurringCharge: AWSRecurringCharge): RecurringCharge = {
    RecurringCharge(
      frequency = recurringCharge.getFrequency,
      amount = recurringCharge.getAmount
    )
  }
}
