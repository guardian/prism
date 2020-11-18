package collectors

import agent._
import com.amazonaws.services.identitymanagement.{AmazonIdentityManagement, AmazonIdentityManagementClientBuilder}
import com.amazonaws.services.identitymanagement.model.{ListServerCertificatesRequest, ServerCertificateMetadata}
import conf.AWS
import controllers.routes
import org.joda.time.{DateTime, Duration}
import play.api.mvc.Call
import utils.{Logging, PaginatedAWSRequest}

import scala.collection.JavaConverters._
import scala.util.Try
import scala.concurrent.duration._
import scala.language.postfixOps

class ServerCertificateCollectorSet(accounts: Accounts) extends CollectorSet[ServerCertificate](ResourceType("server-certificates"), accounts) {
  val lookupCollector: PartialFunction[Origin, Collector[ServerCertificate]] = {
    case amazon: AmazonOrigin => AWSServerCertificateCollector(amazon, resource, amazon.crawlRate(resource.name))
  }
}

case class AWSServerCertificateCollector(origin: AmazonOrigin, resource: ResourceType, crawlRate: CrawlRate) extends Collector[ServerCertificate] with Logging {

  val client: AmazonIdentityManagement = AmazonIdentityManagementClientBuilder.standard()
    .withCredentials(origin.credentials.provider)
    .withRegion(origin.awsRegion)
    .withClientConfiguration(AWS.clientConfig)
    .build()

  def crawl: Iterable[ServerCertificate] =
    PaginatedAWSRequest.run(client.listServerCertificates)(new ListServerCertificatesRequest()).map(ServerCertificate.fromApiData(_, origin))
}

object ServerCertificate {
  def fromApiData(metadata: ServerCertificateMetadata, origin: AmazonOrigin): ServerCertificate = ServerCertificate(
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