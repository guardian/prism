package collectors

import agent._
import com.amazonaws.services.s3.AmazonS3Client
import controllers.routes
import org.joda.time.{DateTime, Duration}
import play.api.mvc.Call
import utils.Logging
import collection.JavaConverters._
import com.amazonaws.services.s3.model.{Bucket => AWSBucket, ListBucketsRequest}

import scala.util.Try

object BucketCollectorSet extends CollectorSet[Bucket](ResourceType("bucket", Duration.standardMinutes(15L))) {
  val lookupCollector: PartialFunction[Origin, Collector[Bucket]] = {
    case amazon: AmazonOrigin => AWSBucketCollector(amazon, resource)
  }
}

case class AWSBucketCollector(origin: AmazonOrigin, resource: ResourceType) extends Collector[Bucket] with Logging {

  val client = new AmazonS3Client(origin.credentials.provider)
  client.setRegion(origin.awsRegion)

  def crawl: Iterable[Bucket] = {
    val request = new ListBucketsRequest()
    client.listBuckets(request).asScala.map {
      Bucket.fromApiData(_, client)
    }
  }
}

object Bucket {

  private def arn(bucketName: String) = s"arn:aws:s3:::$bucketName" 

  def fromApiData(bucket: AWSBucket, client: AmazonS3Client): Bucket = {
    val bucketName = bucket.getName
    Bucket(
      arn = arn(bucketName),
      name = bucketName,
      region = client.getBucketLocation(bucket.getName),
      createdTime = Try(new DateTime(bucket.getCreationDate)).toOption
    )
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