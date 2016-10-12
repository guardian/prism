package collectors

import agent._
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.{DescribeReservedInstancesRequest, ReservedInstances}
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
  region: String,
  instanceType: String,
  instanceCount: Int,
  startTime: Option[DateTime],
  endTime: Option[DateTime]
) extends IndexedItem {
  override def callFromArn: (String) => Call = arn => routes.Api.reservation(arn)

}

object Reservation {
  def fromApiData(reservationInstance: ReservedInstances, origin: AmazonOrigin): Reservation = {
    val region = reservationInstance.getAvailabilityZone
    val arn = s"arn:aws:ec2:$region:${origin.accountNumber.getOrElse("")}:reservation/${reservationInstance.getReservedInstancesId}"
    Reservation(
      arn = arn,
      region = region,
      instanceType = reservationInstance.getInstanceType,
      instanceCount = reservationInstance.getInstanceCount,
      startTime = Try(new DateTime(reservationInstance.getStart)).toOption,
      endTime = Try(new DateTime(reservationInstance.getEnd)).toOption
    )
  }
}