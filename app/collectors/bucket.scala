package collectors

import agent._
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.amazonaws.services.s3.model.{AmazonS3Exception, ListBucketsRequest, Bucket => AWSBucket}
import controllers.routes
import org.joda.time.{DateTime, Duration}
import play.api.mvc.Call
import utils.Logging

import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.control.NonFatal
import scala.concurrent.duration._
import scala.language.postfixOps


object BucketCollectorSet extends CollectorSet[Bucket](ResourceType("bucket", 1 hour, 5 minutes)) {
  val lookupCollector: PartialFunction[Origin, Collector[Bucket]] = {
    case amazon: AmazonOrigin => AWSBucketCollector(amazon, resource)
  }
}

case class AWSBucketCollector(origin: AmazonOrigin, resource: ResourceType) extends Collector[Bucket] with Logging {

  val client = AmazonS3ClientBuilder.standard()
    .withCredentials(origin.credentials.provider)
    .withRegion(origin.awsRegion)
    .build()

  def crawl: Iterable[Bucket] = {
    val request = new ListBucketsRequest()
    client.listBuckets(request).asScala
      .flatMap {
        Bucket.fromApiData(_, client)
      }
  }
}

object Bucket {

  private def arn(bucketName: String) = s"arn:aws:s3:::$bucketName" 

  def fromApiData(bucket: AWSBucket, client: AmazonS3): Option[Bucket] = {
    val bucketName = bucket.getName
    try {
      val bucketRegion = client.getBucketLocation(bucket.getName)
      if (bucketRegion == client.getRegionName) {
        Some(Bucket(
          arn = arn(bucketName),
          name = bucketName,
          region = bucketRegion,
          createdTime = Try(new DateTime(bucket.getCreationDate)).toOption
        ))
      } else {
        None
      }
    } catch {
      case e:AmazonS3Exception if e.getErrorCode == "NoSuchBucket" => None
      case e:AmazonS3Exception if e.getErrorCode == "AuthorizationHeaderMalformed" => None
      case NonFatal(t) =>
        throw new IllegalStateException(s"Failed when building info for bucket $bucketName", t)
    }
  }
}

case class Bucket(
  arn: String,
  name: String,
  region: String,
  createdTime: Option[DateTime]
) extends IndexedItem {
  override def callFromArn: (String) => Call = arn => routes.Application.index() //routes.Api.data(arn)
}