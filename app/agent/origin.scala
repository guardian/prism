package agent

import java.io.FileNotFoundException
import java.net.{URI, URL, URLConnection, URLStreamHandler}

import collectors.Instance
import com.amazonaws.auth._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions.Regions
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder
import conf.{AWS, PrismConfiguration}
import play.api.libs.json.{JsObject, JsValue, Json}
import utils.Logging

import scala.io.Source
import scala.language.postfixOps
import scala.util.Try
import scala.util.control.NonFatal
import scala.util.matching.Regex

class Accounts(prismConfiguration: PrismConfiguration) extends Logging {
  val ArnIamAccountExtractor: Regex = """arn:aws:iam::(\d+):user.*""".r
  val all:Seq[Origin] = (prismConfiguration.accounts.aws.list ++ prismConfiguration.accounts.amis.list).map { awsOrigin =>
    Try {
      val iamClient = AmazonIdentityManagementClientBuilder.standard()
        .withCredentials(awsOrigin.credentials.provider)
        .withRegion(AWS.connectionRegion)
        .build()

      val ArnIamAccountExtractor(derivedAccountNumber) = iamClient.getUser.getUser.getArn
      awsOrigin.copy(accountNumber = Some(derivedAccountNumber))
    } recover {
      case NonFatal(e) =>
        if (awsOrigin.accountNumber.isDefined) awsOrigin else {
          log.warn(s"Failed to extract the account number for $awsOrigin", e)
          awsOrigin.copy(accountNumber = Some("?????????"))
        }
    } get
  } ++ prismConfiguration.accounts.json.list

  def forResource(resource:String): Seq[Origin] = all.filter(origin => origin.resources.isEmpty || origin.resources.contains(resource))
}

trait Origin {
  def vendor: String
  def account: String
  def filterMap: Map[String,String] = Map.empty
  def resources: Set[String]
  def transformInstance(input: Instance): Instance = input
  def standardFields: Map[String, String] = Map("vendor" -> vendor, "accountName" -> account)
  def jsonFields: Map[String, String]
  def toJson: JsObject = JsObject((standardFields ++ jsonFields).view.mapValues(Json.toJson(_)).toSeq)
}

case class Credentials(accessKey: Option[String], role: Option[String], profile: Option[String], region: String)(secretKey: Option[String]) {
  val (id, provider) = (accessKey, secretKey, role, profile) match {
    case (_, _, Some(r), Some(p)) =>
      val stsClient = AWSSecurityTokenServiceClientBuilder.standard()
        .withCredentials(new ProfileCredentialsProvider(p))
        .withRegion(region)
        .build()
      (s"$p/$r", new STSAssumeRoleSessionCredentialsProvider.Builder(r, "prism").withStsClient(stsClient).build())
    case (_, _, Some(r), _) =>
      (r, new STSAssumeRoleSessionCredentialsProvider.Builder(r, "prism").build())
    case (_, _, _, Some(p)) =>
      (p, new ProfileCredentialsProvider(p))
    case (Some(ak), Some(sk), _, _) =>
      (ak, new AWSStaticCredentialsProvider(new BasicAWSCredentials(ak, sk)))
    case _ => ("default", new DefaultAWSCredentialsProviderChain())
  }
}

object AmazonOrigin {
  val ArnIamAccountExtractor: Regex = """arn:aws:iam::(\d+):role.*""".r
  def apply(account:String, region:String, resources:Set[String], stagePrefix: Option[String],
            credentials: Credentials, ownerId: Option[String]): AmazonOrigin = {
    val accountNumber = credentials.role.flatMap {
      case ArnIamAccountExtractor(accountId) => Some(accountId)
      case _ => None
    }
    AmazonOrigin(account, region, credentials, resources, stagePrefix, accountNumber, ownerId)
  }

  def amis(name: String, region: String, accountNumber: Option[String], credentials: Credentials,
           ownerId: Option[String]): AmazonOrigin = {
    AmazonOrigin(name, region, credentials, Set("images"), None, accountNumber, ownerId)
  }
}

case class AmazonOrigin(account:String, region:String, credentials: Credentials, resources:Set[String],
                        stagePrefix: Option[String], accountNumber:Option[String], ownerId: Option[String]) extends Origin {
  lazy val vendor = "aws"
  override lazy val filterMap = Map("vendor" -> vendor, "region" -> region, "accountName" -> account)
  override def transformInstance(input:Instance): Instance = stagePrefix.map(input.prefixStage).getOrElse(input)
  val jsonFields: Map[String, String] = Map("region" -> region, "credentials" -> credentials.id) ++
    accountNumber.map("accountNumber" -> _) ++
    ownerId.map("ownerId" -> _)
  val awsRegion: Regions = Regions.fromName(region)
}
case class JsonOrigin(vendor:String, account:String, url:String, resources:Set[String]) extends Origin with Logging {
  private val classpathHandler = new URLStreamHandler {
    override def openConnection(u: URL): URLConnection = {
      Option(getClass.getResource(u.getPath)).map(_.openConnection()).getOrElse{
        throw new FileNotFoundException("%s not found on classpath" format u.getPath)
      }
    }
  }

  def credsFromS3Url(url: URI): AWSCredentialsProvider = {
    Option(url.getUserInfo) match {
      case Some(role) if role.startsWith("arn:") => new STSAssumeRoleSessionCredentialsProvider.Builder(role, "prismS3").build()
      case Some(profile) => new ProfileCredentialsProvider(profile)
      case _ => new DefaultAWSCredentialsProviderChain()
    }
  }

  // TODO: Fix these warnings
  def data(resource:ResourceType):JsValue = {
    val jsonText: String = new URI(url.replace("%resource%", resource.name)) match {
      case classPathLocation if classPathLocation.getScheme == "classpath" =>
        Source.fromURL(new URL(null, classPathLocation.toString, classpathHandler), "utf-8").getLines().mkString
      case s3Location if s3Location.getScheme == "s3" =>
        val s3Client = AmazonS3ClientBuilder.standard()
          .withCredentials(credsFromS3Url(s3Location))
          .withRegion(AWS.connectionRegion)
          .build()
        val obj = s3Client.getObject(s3Location.getHost, s3Location.getPath.stripPrefix("/"))
        Source.fromInputStream(obj.getObjectContent).getLines().mkString
      case otherURL =>
        Source.fromURL(otherURL.toURL, "utf-8").getLines().mkString
    }

    Json.parse(jsonText)
  }
  val jsonFields = Map("url" -> url)
}