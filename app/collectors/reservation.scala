package collectors

import agent._
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.{DescribeReservedInstancesRequest, RecurringCharge => AWSRecurringCharge, ReservedInstances}
import controllers.routes
import org.joda.time.{DateTime, Duration}
import play.api.mvc.Call
import utils.Logging

import collection.JavaConverters._
import scala.util.Try

object ReservationCollectorSet extends CollectorSet[Reservation](ResourceType("reservation", Duration.standardMinutes(15L))) {
  val lookupCollector: PartialFunction[Origin, Collector[Reservation]] = {
    case amazon: AmazonOrigin => AWSReservationCollector(amazon, resource)
  }
}

case class AWSReservationCollector(origin: AmazonOrigin, resource: ResourceType) extends Collector[Reservation] with Logging {

  val client = new AmazonEC2Client(origin.credentials.provider)
  client.setRegion(origin.awsRegion)

  def crawl: Iterable[Reservation] = {
    client.describeReservedInstances(new DescribeReservedInstancesRequest()).getReservedInstances.asScala.map {
      Reservation.fromApiData(_, origin)
    }
  }
}

case class Reservation(
  arn: String,
  id: String,
  group: String,
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
    val group = reservationInstance.getAvailabilityZone
    val arn = s"arn:aws:ec2:$group:${origin.accountNumber.getOrElse("")}:reservation/${reservationInstance.getReservedInstancesId}"
    val recurringCharges = reservationInstance.getRecurringCharges.asScala.map(RecurringCharge.fromApiData(_)).toList
    Reservation(
      arn = arn,
      id = reservationInstance.getReservedInstancesId,
      group = group,
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
