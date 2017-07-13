package collectors

import agent._
import com.amazonaws.services.certificatemanager.AWSCertificateManagerClientBuilder
import com.amazonaws.services.certificatemanager.model.{CertificateDetail, DescribeCertificateRequest, ListCertificatesRequest, RenewalSummary, DomainValidation => AwsDomainValidation}
import controllers.routes
import org.joda.time.{DateTime, Duration}
import play.api.mvc.Call
import utils.Logging

import scala.collection.JavaConverters._
import scala.util.Try

object AmazonCertificateCollectorSet extends CollectorSet[AcmCertificate](ResourceType("acm-certificates", Duration.standardMinutes(14L))) {
  val lookupCollector: PartialFunction[Origin, Collector[AcmCertificate]] = {
    case amazon: AmazonOrigin => AWSAcmCertificateCollector(amazon, resource)
  }
}

case class AWSAcmCertificateCollector(origin: AmazonOrigin, resource: ResourceType) extends Collector[AcmCertificate] with Logging {

  val client = AWSCertificateManagerClientBuilder.standard()
    .withCredentials(origin.credentials.provider)
    .withRegion(origin.awsRegion)
    .build()

  private def crawlWithMarker(token: Option[String]): Iterable[AcmCertificate] = {
    val request = new ListCertificatesRequest().withNextToken(token.orNull)
    val result = client.listCertificates(request)
    val configs = result.getCertificateSummaryList.asScala.map { cert =>
      val requestDetails = new DescribeCertificateRequest().withCertificateArn(cert.getCertificateArn)
      val resultDetails = client.describeCertificate(requestDetails)
      AcmCertificate.fromApiData(resultDetails.getCertificate, origin)
    }
    Option(result.getNextToken) match {
      case None => configs
      case t@Some(_) => configs ++ crawlWithMarker(t)
    }
  }

  def crawl: Iterable[AcmCertificate] = crawlWithMarker(None)
}

case class DomainValidation(
                             domainName: String,
                             validationEmails: List[String],
                             validationDomain: String,
                             validationStatus: String
                           )

object DomainValidation {
  def fromApiData(dv: AwsDomainValidation): DomainValidation = {
    DomainValidation(
      dv.getDomainName,
      Option(dv.getValidationEmails).toList.flatMap(_.asScala.toList),
      dv.getValidationDomain,
      dv.getValidationStatus
    )
  }
}

case class RenewalInfo(renewalStatus: String, domainValidationOptions: List[DomainValidation])

object RenewalInfo {
  def fromApiData(ri: RenewalSummary): RenewalInfo = {
    RenewalInfo(ri.getRenewalStatus, ri.getDomainValidationOptions.asScala.map(DomainValidation.fromApiData).toList)
  }
}

object AcmCertificate {
  def fromApiData(cert: CertificateDetail, origin: AmazonOrigin) = AcmCertificate(
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
                              domainValidationOptions: List[DomainValidation],
                              renewalStatus: Option[RenewalInfo]
                            ) extends IndexedItem {
  def callFromArn: (String) => Call = arn => routes.Api.acmCertificate(arn)
}