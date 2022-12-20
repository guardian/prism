package agent

import java.io.FileNotFoundException
import java.net.{URI, URL, URLConnection, URLStreamHandler}

import collectors.Instance
import conf.{AWS, PrismConfiguration}
import play.api.libs.json.{JsObject, JsValue, Json}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, AwsCredentialsProvider, ProfileCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest
import utils.{AWSCredentialProviders, Logging, Marker}

import scala.io.Source
import scala.language.postfixOps
import scala.util.Try
import scala.util.control.NonFatal
import scala.util.matching.Regex

class Accounts(prismConfiguration: PrismConfiguration) extends Logging {
  val all:Seq[Origin] = (prismConfiguration.accounts.aws.list ++ prismConfiguration.accounts.amis.list).map { awsOrigin =>
    if (awsOrigin.accountNumber.isDefined) {
      awsOrigin
    } else {
      Try {
        val stsClient = StsClient.builder().credentialsProvider(awsOrigin.credentials.provider)
          .region(Region.AWS_GLOBAL)
          .build
        val accountNumber = stsClient.getCallerIdentity.account()
        awsOrigin.copy(accountNumber = Some(accountNumber))
      } recover {
        case NonFatal(e) =>
          log.warn(s"Failed to extract the account number for $awsOrigin", e)
          awsOrigin.copy(accountNumber = Some("?????????"))
      } get
    }
  } ++ prismConfiguration.accounts.json.list

  def forResource(resource:String): Seq[Origin] = all.filter(origin => origin.resources.isEmpty || origin.resources.contains(resource))
}

trait Origin extends Marker {
  def vendor: String
  def account: String
  def filterMap: Map[String,String] = Map.empty
  def resources: Set[String]
  def crawlRate: Map[String, CrawlRate]
  def transformInstance(input: Instance): Instance = input
  def standardFields: Map[String, String] = Map("vendor" -> vendor, "accountName" -> account)
  def jsonFields: Map[String, String]
  def toJson: JsObject = JsObject((standardFields ++ jsonFields).view.mapValues(Json.toJson(_)).toSeq)
}

case class Credentials(accessKey: Option[String], role: Option[String], profile: Option[String], regionName: String)(secretKey: Option[String]) {
  val region: Region = Region.of(regionName)
  val (id, provider) = (accessKey, secretKey, role, profile) match {
    case (_, _, Some(r), Some(p)) =>
      val stsClient = StsClient.builder
        .credentialsProvider(ProfileCredentialsProvider.builder.profileName(p).build)
        .region(region)
        .build
      val req: AssumeRoleRequest = AssumeRoleRequest.builder
        .roleSessionName("prism")
        .roleArn(r)
        .build
      (
        s"$p/$r",
        StsAssumeRoleCredentialsProvider.builder.stsClient(stsClient).refreshRequest(req).build
      )
    case (_, _, Some(r), _) =>
      val req: AssumeRoleRequest = AssumeRoleRequest.builder
        .roleSessionName("prism")
        .roleArn(r)
        .build
      val stsClient = StsClient.builder
        .region(region)
        .build
      (
        r,
        StsAssumeRoleCredentialsProvider.builder.stsClient(stsClient).refreshRequest(req).build
      )
    case (_, _, _, Some(p)) =>
      (
        p,
        ProfileCredentialsProvider.builder.profileName(p).build
      )
    case (Some(ak), Some(sk), _, _) =>
      (
        ak,
        StaticCredentialsProvider.create(AwsBasicCredentials.create(ak, sk))
      )
    case _ =>
      (
        "default",
        AWSCredentialProviders.deployToolsCredentialsProviderChain,
      )
  }
}

object AmazonOrigin {
  val ArnIamAccountExtractor: Regex = """arn:aws:iam::(\d+):role.*""".r
  def apply(account:String, region:String, resources:Set[String], stagePrefix: Option[String],
            credentials: Credentials, ownerId: Option[String], crawlRates: Map[String, CrawlRate]): AmazonOrigin = {
    val accountNumber = credentials.role.flatMap {
      case ArnIamAccountExtractor(accountId) => Some(accountId)
      case _ => None
    }
    AmazonOrigin(account, region, credentials, resources, stagePrefix, accountNumber, ownerId, crawlRates)
  }

  def amis(name: String, region: String, accountNumber: Option[String], credentials: Credentials,
           ownerId: Option[String], crawlRates: Map[String, CrawlRate]): AmazonOrigin = {
    AmazonOrigin(name, region, credentials, Set("images"), None, accountNumber, ownerId, crawlRates)
  }
}

case class AmazonOrigin(account:String, region:String, credentials: Credentials, resources:Set[String],
                        stagePrefix: Option[String], accountNumber:Option[String], ownerId: Option[String], crawlRate: Map[String, CrawlRate]) extends Origin {
  lazy val vendor = "aws"
  override lazy val filterMap = Map("vendor" -> vendor, "region" -> region, "accountName" -> account)
  override def transformInstance(input:Instance): Instance = stagePrefix.map(input.prefixStage).getOrElse(input)
  val jsonFields: Map[String, String] = Map("region" -> region, "credentials" -> credentials.id) ++
    accountNumber.map("accountNumber" -> _) ++
    ownerId.map("ownerId" -> _)
  val awsRegionV2: Region = Region.of(region)

  override def toMarkerMap: Map[String, Any] = Map("region" -> awsRegionV2.id)
}
case class JsonOrigin(vendor:String, account:String, url:String, resources:Set[String], crawlRate: Map[String, CrawlRate]) extends Origin with Logging {
  private val classpathHandler = new URLStreamHandler {
    override def openConnection(u: URL): URLConnection = {
      Option(getClass.getResource(u.getPath)).map(_.openConnection()).getOrElse{
        throw new FileNotFoundException("%s not found on classpath" format u.getPath)
      }
    }
  }

  def credsFromS3Url(url: URI): AwsCredentialsProvider = {
    Option(url.getUserInfo) match {
      case Some(role) if role.startsWith("arn:") =>
        val request: AssumeRoleRequest = AssumeRoleRequest.builder
          .roleSessionName("prismS3")
          .roleArn(role)
          .build
        StsAssumeRoleCredentialsProvider.builder.refreshRequest(request).build
      case Some(profile) => AWSCredentialProviders.profileCredentialsProvider(profile)
      case _ => AWSCredentialProviders.deployToolsCredentialsProviderChain
    }
  }

  def data(resource:ResourceType):JsValue = {
    val source: Source = new URI(url.replace("%resource%", resource.name)) match {
      case classPathLocation if classPathLocation.getScheme == "classpath" =>
        Source.fromURL(new URL(null, classPathLocation.toString, classpathHandler), "utf-8")
      case s3Location if s3Location.getScheme == "s3" =>
        val s3Client = S3Client.builder
          .credentialsProvider(credsFromS3Url(s3Location))
          .region(AWS.connectionRegion)
          .build
        val obj = s3Client.getObjectAsBytes(
          GetObjectRequest.builder.bucket(s3Location.getHost).key(s3Location.getPath.stripPrefix("/")).build
        )
        Source.fromBytes(obj.asByteArray)
      case otherURL =>
        Source.fromURL(otherURL.toURL, "utf-8")
    }
    val jsonText: String = try {
      source.getLines().mkString
    } finally {
      source.close()
    }
    Json.parse(jsonText)
  }
  val jsonFields = Map("url" -> url)

  override def toMarkerMap: Map[String, Any] = jsonFields
}
