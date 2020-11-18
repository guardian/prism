package collectors

import agent._
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.amazonaws.services.s3.model.{AmazonS3Exception, ListBucketsRequest, Bucket => AWSBucket}
import conf.AWS
import controllers.routes
import org.joda.time.DateTime
import play.api.mvc.Call
import utils.Logging

import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.control.NonFatal
import scala.concurrent.duration._
import scala.language.postfixOps


class BucketCollectorSet(accounts: Accounts) extends CollectorSet[Bucket](ResourceType("bucket"), accounts) {
  val lookupCollector: PartialFunction[Origin, Collector[Bucket]] = {
    case amazon: AmazonOrigin => AWSBucketCollector(amazon, resource, amazon.crawlRate(resource.name))
  }
}

case class AWSBucketCollector(origin: AmazonOrigin, resource: ResourceType, crawlRate: CrawlRate) extends Collector[Bucket] with Logging {

  val client: AmazonS3 = AmazonS3ClientBuilder.standard()
    .withCredentials(origin.credentials.provider)
    .withRegion(origin.awsRegion)
    .withClientConfiguration(AWS.clientConfig)
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
  override def callFromArn: (String) => Call = arn => routes.Api.bucket(arn)
}