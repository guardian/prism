package collectors

import agent._
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder
import com.amazonaws.services.identitymanagement.model.{ListServerCertificatesRequest, ServerCertificateMetadata}
import controllers.routes
import org.joda.time.{DateTime, Duration}
import play.api.mvc.Call
import utils.{Logging, PaginatedAWSRequest}

import scala.collection.JavaConverters._
import scala.util.Try

object ServerCertificateCollectorSet extends CollectorSet[ServerCertificate](ResourceType("server-certificates", Duration.standardMinutes(14L))) {
  val lookupCollector: PartialFunction[Origin, Collector[ServerCertificate]] = {
    case amazon: AmazonOrigin => AWSServerCertificateCollector(amazon, resource)
  }
}

case class AWSServerCertificateCollector(origin: AmazonOrigin, resource: ResourceType) extends Collector[ServerCertificate] with Logging {

  val client = AmazonIdentityManagementClientBuilder.standard()
    .withCredentials(origin.credentials.provider)
    .withRegion(origin.awsRegion)
    .build()

  def crawl: Iterable[ServerCertificate] =
    PaginatedAWSRequest.run(client.listServerCertificates)(new ListServerCertificatesRequest()).map(ServerCertificate.fromApiData(_, origin))
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