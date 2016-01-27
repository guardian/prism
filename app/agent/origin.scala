package agent

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.internal.StaticCredentialsProvider
import play.api.libs.json.{JsValue, Json, JsObject}
import java.net.{URLConnection, URL, URLStreamHandler}
import java.io.FileNotFoundException
import utils.Logging

import scala.io.Source
import com.amazonaws.auth.{AWSCredentialsProvider, STSAssumeRoleSessionCredentialsProvider, BasicAWSCredentials}
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
        log.warn(s"Failed to extract the account number for $awsOrigin", e)
        if (awsOrigin.accountNumber.isDefined) awsOrigin else
          awsOrigin.copy(accountNumber = Some("?????????"))
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
            resources:Set[String], stagePrefix: Option[String], secretKey:Option[String]): AmazonOrigin = {
    val (credsId, credsProvider) = (accessKey, secretKey, role, profile) match {
      case (_, _, Some(r), Some(p)) =>
        (s"$p/$r", new STSAssumeRoleSessionCredentialsProvider(new ProfileCredentialsProvider(p), r, "prism"))
      case (_, _, Some(r), _) =>
        (r, new STSAssumeRoleSessionCredentialsProvider(r, "prism"))
      case (_, _, _, Some(p)) =>
        (p, new ProfileCredentialsProvider(p))
      case (Some(ak), Some(sk), _, _) =>
        (ak, new StaticCredentialsProvider(new BasicAWSCredentials(ak, sk)))
      case _ => throw new IllegalArgumentException("Must specify either a role, profile or static credentials for an account")
    }
    val accountNumber = role.flatMap {
      case ArnIamAccountExtractor(accountId) => Some(accountId)
      case _ => None
    }
    AmazonOrigin(account, region, credsId, credsProvider, resources, stagePrefix, accountNumber)
  }
}

case class AmazonOrigin(account:String, region:String, credsId: String, credsProvider: AWSCredentialsProvider,
                        resources:Set[String], stagePrefix: Option[String], accountNumber:Option[String]) extends Origin {
  lazy val vendor = "aws"
  override lazy val filterMap = Map("vendor" -> vendor, "region" -> region, "accountName" -> account)
  override def transformInstance(input:Instance): Instance = stagePrefix.map(input.prefixStage).getOrElse(input)
  val jsonFields = Map("region" -> region) ++ accountNumber.map("accountNumber" -> _)

}
case class JsonOrigin(vendor:String, account:String, url:String, resources:Set[String]) extends Origin {
  private val classpathHandler = new URLStreamHandler {
    override def openConnection(u: URL): URLConnection = {
      Option(getClass.getResource(u.getPath)).map(_.openConnection()).getOrElse{
        throw new FileNotFoundException("%s not found on classpath" format u.getPath)
      }
    }
  }

  def data(resource:ResourceType):JsValue = {
    val actualUrl = url.replace("%resource%", resource.name) match {
      case classPathLocation if classPathLocation.startsWith("classpath:") => new URL(null, classPathLocation, classpathHandler)
      case otherURL => new URL(otherURL)
    }
    val jsonText = Source.fromURL(actualUrl, "utf-8").getLines().mkString
    Json.parse(jsonText)
  }
  val jsonFields = Map("url" -> url)
}
case class GoogleDocOrigin(name: String, docUrl:URL, resources:Set[String]) extends Origin {
  lazy val vendor = "google-doc"
  lazy val account = name
  val jsonFields = Map("name" -> name, "docUrl" -> docUrl.toString)
}