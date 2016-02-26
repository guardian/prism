package collectors

import agent._
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClient
import controllers.routes
import org.joda.time.{DateTime, Duration}
import play.api.mvc.Call
import utils.Logging
import collection.JavaConverters._
import com.amazonaws.services.identitymanagement.model.{ServerCertificateMetadata, ListServerCertificatesRequest}

import scala.util.Try

object ServerCertificateCollectorSet extends CollectorSet[ServerCertificate](ResourceType("server-certificates", Duration.standardMinutes(14L))) {
  val lookupCollector: PartialFunction[Origin, Collector[ServerCertificate]] = {
    case amazon: AmazonOrigin => AWSServerCertificateCollector(amazon, resource)
  }
}

case class AWSServerCertificateCollector(origin: AmazonOrigin, resource: ResourceType) extends Collector[ServerCertificate] with Logging {

  val client = new AmazonIdentityManagementClient(origin.credentials.provider)
  client.setRegion(origin.awsRegion)

  private def crawlWithMarker(marker: Option[String]): Iterable[ServerCertificate] = {
    val request = new ListServerCertificatesRequest().withMarker(marker.orNull)
    val result = client.listServerCertificates(request)
    val configs = result.getServerCertificateMetadataList.asScala.map {
      ServerCertificate.fromApiData(_, origin)
    }
    Option(result.getMarker) match {
      case None => configs
      case t @ Some(token) => configs ++ crawlWithMarker(t)
    }
  }

  def crawl: Iterable[ServerCertificate] = crawlWithMarker(None)
}

object ServerCertificate {
  def fromApiData(metadata: ServerCertificateMetadata, origin: AmazonOrigin) = ServerCertificate(
    arn = metadata.getArn,
    id = metadata.getServerCertificateId,
    name = metadata.getServerCertificateName,
    path = metadata.getPath,
    uploadedAt = Try(new DateTime(metadata.getUploadDate)).toOption,
    expiryDate = Try(new DateTime(metadata.getExpiration)).toOption
  )
}

case class ServerCertificate(
  arn: String,
  id: String,
  name: String,
  path: String,
  uploadedAt: Option[DateTime],
  expiryDate: Option[DateTime]
) extends IndexedItem {
  def callFromArn: (String) => Call = arn => routes.Api.serverCertificate(arn)
}