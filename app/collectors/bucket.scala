package collectors

import agent._
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.amazonaws.services.s3.model.{AmazonS3Exception, ListBucketsRequest, Bucket => AWSBucket}
import controllers.routes
import org.joda.time.{DateTime, Duration}
import play.api.mvc.Call
import utils.Logging

import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.control.NonFatal
import scala.concurrent.duration._
import scala.language.postfixOps


case class AWSBucketCollector(origin: AmazonOrigin, resource: ResourceType) extends Collector[Bucket] with Logging {

  val client = AmazonS3ClientBuilder.standard()
    .withCredentials(origin.credentials.provider)
    .withRegion(origin.awsRegion)
    .build()

  def crawl: Iterable[Bucket] = {
    val request = new ListBucketsRequest()
    client.listBuckets(request).asScala.flatMap {
      Bucket.fromApiData(_, client)
    }
  }
}

object Bucket {

  private def arn(bucketName: String) = s"arn:aws:s3:::$bucketName" 

  def fromApiData(bucket: AWSBucket, client: AmazonS3): Option[Bucket] = {
    val bucketName = bucket.getName
    try {
      Some(Bucket(
        arn = arn(bucketName),
        name = bucketName,
        region = client.getBucketLocation(bucket.getName),
        createdTime = Try(new DateTime(bucket.getCreationDate)).toOption
      ))
    } catch {
      case e:AmazonS3Exception if e.getErrorCode == "NoSuchBucket" => None
      case NonFatal(t) =>
        throw new IllegalStateException(s"Failed when building info for bucket $bucketName", t)
    }
  }

  implicit val fields = new Fields[Bucket] {
    override def defaultFields: Seq[String] = Seq("name", "region", "createdTime")
  }
}

case class Bucket(
  arn: String,
  name: String,
  region: String,
  createdTime: Option[DateTime]
) extends IndexedItem {
  override def callFromArn: (String) => Call = arn => routes.Api.bucket(arn)
}

object BucketCollectorSet extends CollectorSet[Bucket](ResourceType("bucket", 1 hour, 5 minutes)) {
  val lookupCollector: PartialFunction[Origin, Collector[Bucket]] = {
    case amazon: AmazonOrigin => AWSBucketCollector(amazon, resource)
  }
}