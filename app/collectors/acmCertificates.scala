package collectors

import agent._
import com.amazonaws.services.certificatemanager.{AWSCertificateManager, AWSCertificateManagerClientBuilder}
import com.amazonaws.services.certificatemanager.model.{CertificateDetail, DescribeCertificateRequest, ListCertificatesRequest, RenewalSummary, ResourceRecord, DomainValidation => AwsDomainValidation}
import controllers.routes
import org.joda.time.{DateTime, Duration}
import play.api.mvc.Call
import utils.{Logging, PaginatedAWSRequest}

import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.concurrent.duration._
import scala.language.postfixOps


class AmazonCertificateCollectorSet(accounts: Accounts) extends CollectorSet[AcmCertificate](ResourceType("acmCertificates", 1 hour, 1 minute), accounts) {
  val lookupCollector: PartialFunction[Origin, Collector[AcmCertificate]] = {
    case amazon: AmazonOrigin => AWSAcmCertificateCollector(amazon, resource)
  }
}

case class AWSAcmCertificateCollector(origin: AmazonOrigin, resource: ResourceType) extends Collector[AcmCertificate] with Logging {

  val client: AWSCertificateManager = AWSCertificateManagerClientBuilder.standard()
    .withCredentials(origin.credentials.provider)
    .withRegion(origin.awsRegion)
    .build()

  def crawl: Iterable[AcmCertificate] = PaginatedAWSRequest.run(client.listCertificates)(new ListCertificatesRequest()).map{ cert =>
    val requestDetails = new DescribeCertificateRequest().withCertificateArn(cert.getCertificateArn)
    val resultDetails = client.describeCertificate(requestDetails)
    AcmCertificate.fromApiData(resultDetails.getCertificate, origin)
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
      name = drr.getName,
      resourceType = drr.getType,
      value = drr.getValue
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
      dv.getDomainName,
      Option(dv.getValidationEmails).toList.flatMap(_.asScala.toList),
      dv.getValidationDomain,
      dv.getValidationStatus,
      dv.getValidationMethod,
      Option(dv.getResourceRecord).map(DomainResourceRecord.fromApiData)
    )
  }
}

case class RenewalInfo(renewalStatus: String, domainValidationOptions: List[DomainValidation])

object RenewalInfo {
  def fromApiData(ri: RenewalSummary): RenewalInfo = {
    RenewalInfo(ri.getRenewalStatus, Option(ri.getDomainValidationOptions).map(_.asScala).getOrElse(Nil).map(DomainValidation.fromApiData).toList)
  }
}

object AcmCertificate {
  def fromApiData(cert: CertificateDetail, origin: AmazonOrigin): AcmCertificate = AcmCertificate(
    arn = cert.getCertificateArn,
    domainName = cert.getDomainName,
    subjectAlternativeNames = cert.getSubjectAlternativeNames.asScala.toList,
    certificateType = cert.getType,
    status = cert.getStatus,
    issuer = cert.getIssuer,
    inUseBy = cert.getInUseBy.asScala.toList,
    notBefore = Option(cert.getNotBefore).flatMap(dt => Try(new DateTime(dt)).toOption),
    notAfter = Option(cert.getNotAfter).flatMap(dt => Try(new DateTime(dt)).toOption),
    createdAt = Option(cert.getCreatedAt).flatMap(dt => Try(new DateTime(dt)).toOption),
    issuedAt = Option(cert.getIssuedAt).flatMap(dt => Try(new DateTime(dt)).toOption),
    failureReason = Option(cert.getFailureReason),
    subject = cert.getSubject,
    keyAlgorithm = cert.getKeyAlgorithm,
    signatureAlgorithm = cert.getSignatureAlgorithm,
    serial = cert.getSerial,
    validationMethod = cert.getDomainValidationOptions.asScala.headOption.map(_.getValidationMethod),
    domainValidationOptions = cert.getDomainValidationOptions.asScala.toList.map(DomainValidation.fromApiData),
    renewalStatus = Option(cert.getRenewalSummary).map(RenewalInfo.fromApiData)
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
                              notBefore: Option[DateTime],
                              notAfter: Option[DateTime],
                              createdAt: Option[DateTime],
                              issuedAt: Option[DateTime],
                              failureReason: Option[String],
                              subject: String,
                              keyAlgorithm: String,
                              signatureAlgorithm: String,
                              serial: String,
                              validationMethod: Option[String],
                              domainValidationOptions: List[DomainValidation],
                              renewalStatus: Option[RenewalInfo]
                            ) extends IndexedItem {
  def callFromArn: (String) => Call = arn => routes.Application.index() //routes.Api.acmCertificate(arn)
}