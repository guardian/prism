package agent

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.internal.StaticCredentialsProvider
import com.amazonaws.regions.{Region, Regions}
import com.amazonaws.services.s3.AmazonS3Client
import play.api.libs.json.{JsValue, Json, JsObject}
import java.net.{URI, URLConnection, URL, URLStreamHandler}
import java.io.FileNotFoundException
import utils.Logging

import scala.io.Source
import com.amazonaws.auth.{DefaultAWSCredentialsProviderChain, AWSCredentialsProvider, STSAssumeRoleSessionCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClient
import scala.util.Try
import scala.util.control.NonFatal
import scala.language.postfixOps
import collectors.Instance

object Accounts extends Logging {
  val ArnIamAccountExtractor = """arn:aws:iam::(\d+):user.*""".r
  import conf.PrismConfiguration.accounts._
  val all:Seq[Origin] = aws.list.map { awsOrigin =>
    Try {
      val iamClient = new AmazonIdentityManagementClient(awsOrigin.credsProvider)
      val ArnIamAccountExtractor(derivedAccountNumber) = iamClient.getUser.getUser.getArn
      awsOrigin.copy(accountNumber = Some(derivedAccountNumber))
    } recover {
      case NonFatal(e) =>
        if (awsOrigin.accountNumber.isDefined) awsOrigin else {
          log.warn(s"Failed to extract the account number for $awsOrigin", e)
          awsOrigin.copy(accountNumber = Some("?????????"))
        }
    } get
  } ++ json.list ++ googleDoc.list

  def forResource(resource:String) = all.filter(origin => origin.resources.isEmpty || origin.resources.contains(resource))
}

trait Origin {
  def vendor: String
  def account: String
  def filterMap: Map[String,String] = Map.empty
  def resources: Set[String]
  def transformInstance(input: Instance): Instance = input
  def standardFields: Map[String, String] = Map("vendor" -> vendor, "accountName" -> account)
  def jsonFields: Map[String, String]
  def toJson: JsObject = JsObject((standardFields ++ jsonFields).mapValues(Json.toJson(_)).toSeq)
}

object AmazonOrigin {
  val ArnIamAccountExtractor = """arn:aws:iam::(\d+):role.*""".r
  def apply(account:String, region:String, accessKey:Option[String], role:Option[String], profile:Option[String],
            resources:Set[String], stagePrefix: Option[String], secretKey:Option[String],
            additionalImageOwners: Seq[String]): AmazonOrigin = {
    val (credsId, credsProvider) = (accessKey, secretKey, role, profile) match {
      case (_, _, Some(r), Some(p)) =>
        (s"$p/$r", new STSAssumeRoleSessionCredentialsProvider(new ProfileCredentialsProvider(p), r, "prism"))
      case (_, _, Some(r), _) =>
        (r, new STSAssumeRoleSessionCredentialsProvider(r, "prism"))
      case (_, _, _, Some(p)) =>
        (p, new ProfileCredentialsProvider(p))
      case (Some(ak), Some(sk), _, _) =>
        (ak, new StaticCredentialsProvider(new BasicAWSCredentials(ak, sk)))
      case _ => ("default", new DefaultAWSCredentialsProviderChain())
    }
    val accountNumber = role.flatMap {
      case ArnIamAccountExtractor(accountId) => Some(accountId)
      case _ => None
    }
    AmazonOrigin(account, region, credsId, credsProvider, resources, stagePrefix, accountNumber, additionalImageOwners)
  }
}

case class AmazonOrigin(account:String, region:String, credsId: String,
                        credsProvider: AWSCredentialsProvider, resources:Set[String],
                        stagePrefix: Option[String], accountNumber:Option[String],
                        additionalImageOwners: Seq[String]) extends Origin {
  lazy val vendor = "aws"
  override lazy val filterMap = Map("vendor" -> vendor, "region" -> region, "accountName" -> account)
  override def transformInstance(input:Instance): Instance = stagePrefix.map(input.prefixStage).getOrElse(input)
  val jsonFields = Map("region" -> region) ++ accountNumber.map("accountNumber" -> _)
  val awsRegion = Region.getRegion(Regions.fromName(region))
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
      case Some(role) if role.startsWith("arn:") => new STSAssumeRoleSessionCredentialsProvider(role, "prismS3")
      case Some(profile) => new ProfileCredentialsProvider(profile)
      case _ => new DefaultAWSCredentialsProviderChain()
    }
  }

  def data(resource:ResourceType):JsValue = {
    val jsonText: String = new URI(url.replace("%resource%", resource.name)) match {
      case classPathLocation if classPathLocation.getScheme == "classpath" =>
        Source.fromURL(new URL(null, classPathLocation.toString, classpathHandler), "utf-8").getLines().mkString
      case s3Location if s3Location.getScheme == "s3" =>
        val s3Client = new AmazonS3Client(credsFromS3Url(s3Location))
        val obj = s3Client.getObject(s3Location.getHost, s3Location.getPath.stripPrefix("/"))
        Source.fromInputStream(obj.getObjectContent).getLines().mkString
      case otherURL =>
        Source.fromURL(otherURL.toURL, "utf-8").getLines().mkString
    }

    Json.parse(jsonText)
  }
  val jsonFields = Map("url" -> url)
}
case class GoogleDocOrigin(name: String, docUrl:URL, resources:Set[String]) extends Origin {
  lazy val vendor = "google-doc"
  lazy val account = name
  val jsonFields = Map("name" -> name, "docUrl" -> docUrl.toString)
}