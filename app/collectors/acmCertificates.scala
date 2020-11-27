package collectors

import java.time.Instant

import agent._
import conf.AWS
import controllers.routes
import play.api.mvc.Call
import software.amazon.awssdk.services.acm.AcmClient
import software.amazon.awssdk.services.acm.model.{CertificateDetail, DescribeCertificateRequest, ListCertificatesRequest, RenewalSummary, ResourceRecord, DomainValidation => AwsDomainValidation}
import utils.Logging

import scala.jdk.CollectionConverters._
import scala.language.postfixOps


class AmazonCertificateCollectorSet(accounts: Accounts) extends CollectorSet[AcmCertificate](ResourceType("acmCertificates"), accounts) {
  val lookupCollector: PartialFunction[Origin, Collector[AcmCertificate]] = {
    case amazon: AmazonOrigin => AWSAcmCertificateCollector(amazon, resource, amazon.crawlRate(resource.name))
  }
}

case class AWSAcmCertificateCollector(origin: AmazonOrigin, resource: ResourceType, crawlRate: CrawlRate) extends Collector[AcmCertificate] with Logging {

  val client: AcmClient = AcmClient
    .builder()
    .credentialsProvider(origin.credentials.providerV2)
    .region(origin.awsRegionV2)
    .overrideConfiguration(AWS.clientConfigV2)
    .build()

  def crawl: Iterable[AcmCertificate] = {
    client.listCertificatesPaginator(ListCertificatesRequest.builder.build).certificateSummaryList.asScala.map{ cert =>
      val requestDetails = DescribeCertificateRequest.builder.certificateArn(cert.certificateArn)
      val resultDetails = client.describeCertificate(requestDetails.build)
      AcmCertificate.fromApiData(resultDetails.certificate, origin)
    }
  }
}

case class DomainResourceRecord(
  name: String,
  resourceType: String,
  value: String
)

object DomainResourceRecord {
  def fromApiData(drr: ResourceRecord): DomainResourceRecord = {
    DomainResourceRecord(
      name = drr.name,
      resourceType = drr.typeAsString,
      value = drr.value
    )
  }
}

case class DomainValidation(
                             domainName: String,
                             validationEmails: List[String],
                             validationDomain: String,
                             validationStatus: String,
                             validationMethod: String,
                             resourceRecord: Option[DomainResourceRecord]
                           )

object DomainValidation {
  def fromApiData(dv: AwsDomainValidation): DomainValidation = {
    DomainValidation(
      dv.domainName,
      Option(dv.validationEmails).toList.flatMap(_.asScala.toList),
      dv.validationDomain,
      dv.validationStatusAsString,
      dv.validationMethodAsString,
      Option(dv.resourceRecord).map(DomainResourceRecord.fromApiData)
    )
  }
}

case class RenewalInfo(renewalStatus: String, domainValidationOptions: List[DomainValidation])

object RenewalInfo {
  def fromApiData(ri: RenewalSummary): RenewalInfo = {
    RenewalInfo(ri.renewalStatusAsString, Option(ri.domainValidationOptions).map(_.asScala).getOrElse(Nil).map(DomainValidation.fromApiData).toList)
  }
}

object AcmCertificate {
  def fromApiData(cert: CertificateDetail, origin: AmazonOrigin): AcmCertificate = AcmCertificate(
    arn = cert.certificateArn,
    domainName = cert.domainName,
    subjectAlternativeNames = cert.subjectAlternativeNames.asScala.toList,
    certificateType = cert.typeAsString,
    status = cert.statusAsString,
    issuer = cert.issuer,
    inUseBy = cert.inUseBy.asScala.toList,
    notBefore = Option(cert.notBefore),
    notAfter = Option(cert.notAfter),
    createdAt = Option(cert.createdAt),
    issuedAt = Option(cert.issuedAt),
    failureReason = Option(cert.failureReasonAsString),
    subject = cert.subject,
    keyAlgorithm = cert.keyAlgorithmAsString,
    signatureAlgorithm = cert.signatureAlgorithm,
    serial = cert.serial,
    validationMethod = cert.domainValidationOptions.asScala.headOption.map(_.validationMethodAsString),
    domainValidationOptions = cert.domainValidationOptions.asScala.toList.map(DomainValidation.fromApiData),
    renewalStatus = Option(cert.renewalSummary).map(RenewalInfo.fromApiData)
  )
}

case class AcmCertificate(
                              arn: String,
                              domainName: String,
                              subjectAlternativeNames: List[String],
                              certificateType: String,
                              status: String,
                              issuer: String,
                              inUseBy: List[String],
                              notBefore: Option[Instant],
                              notAfter: Option[Instant],
                              createdAt: Option[Instant],
                              issuedAt: Option[Instant],
                              failureReason: Option[String],
                              subject: String,
                              keyAlgorithm: String,
                              signatureAlgorithm: String,
                              serial: String,
                              validationMethod: Option[String],
                              domainValidationOptions: List[DomainValidation],
                              renewalStatus: Option[RenewalInfo]
                            ) extends IndexedItem {
  def callFromArn: (String) => Call = arn => routes.Api.acmCertificate(arn)
}