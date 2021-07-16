package collectors

import java.time.Instant

import agent._
import conf.AwsClientConfig
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.iam.model.{ListServerCertificatesRequest, ServerCertificateMetadata}
import utils.Logging

import scala.jdk.CollectionConverters._
import scala.language.postfixOps
import scala.util.Try

class ServerCertificateCollectorSet(accounts: Accounts) extends CollectorSet[ServerCertificate](ResourceType("server-certificates"), accounts, Some(Global)) {
  val lookupCollector: PartialFunction[Origin, Collector[ServerCertificate]] = {
    case amazon: AmazonOrigin => AWSServerCertificateCollector(amazon, resource, amazon.crawlRate(resource.name))
  }
}

case class AWSServerCertificateCollector(origin: AmazonOrigin, resource: ResourceType, crawlRate: CrawlRate) extends Collector[ServerCertificate] with Logging {

  val client: IamClient = IamClient
    .builder
    .credentialsProvider(origin.credentials.provider)
    .region(origin.awsRegionV2)
    .overrideConfiguration(AwsClientConfig.clientConfig)
    .build

  def crawl: Iterable[ServerCertificate] = {
    client.listServerCertificatesPaginator(ListServerCertificatesRequest.builder.build).serverCertificateMetadataList.asScala.map(
      ServerCertificate.fromApiData(_, origin)
    )
  }
}

object ServerCertificate {
  def fromApiData(metadata: ServerCertificateMetadata, origin: AmazonOrigin): ServerCertificate = ServerCertificate(
    arn = metadata.arn,
    id = metadata.serverCertificateId,
    name = metadata.serverCertificateName,
    path = metadata.path,
    uploadedAt = Try(metadata.uploadDate).toOption,
    expiryDate = Try(metadata.expiration).toOption
  )
}

case class ServerCertificate(
  arn: String,
  id: String,
  name: String,
  path: String,
  uploadedAt: Option[Instant],
  expiryDate: Option[Instant]
) extends IndexedItem {
//  def callFromArn: (String) => Call = arn => routes.Api.serverCertificate(arn)
}