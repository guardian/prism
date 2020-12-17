package collectors

import java.time.Instant

import agent._
import conf.AWS
import controllers.routes
import play.api.mvc.Call
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}
import software.amazon.awssdk.services.s3.model.{GetBucketLocationRequest, ListBucketsRequest, S3Exception, Bucket => AWSBucket}
import utils.Logging

import scala.jdk.CollectionConverters._
import scala.language.{postfixOps, reflectiveCalls}
import scala.util.Try
import scala.util.control.NonFatal


class BucketCollectorSet(accounts: Accounts) extends CollectorSet[Bucket](ResourceType("bucket"), accounts, Some(Global)) {
  val lookupCollector: PartialFunction[Origin, Collector[Bucket]] = {
    case amazon: AmazonOrigin => AWSBucketCollector(amazon, resource, amazon.crawlRate(resource.name))
  }
}

case class AWSBucketCollector(origin: AmazonOrigin, resource: ResourceType, crawlRate: CrawlRate) extends Collector[Bucket] with Logging {

  val s3Configuration = S3Configuration.builder.useArnRegionEnabled(true).build

  val client = S3Client
    .builder
    .credentialsProvider(origin.credentials.provider)
    .region(Region.US_EAST_1)
    .overrideConfiguration(AWS.clientConfig)
    .serviceConfiguration(s3Configuration)
    .build

  def crawl: Iterable[Bucket] = {
    val request = ListBucketsRequest.builder.build
    val listBuckets = client.listBuckets(request).buckets().asScala.toList
    log.info(s"Total number of buckets for account ${origin.account} ${listBuckets.length}")
      listBuckets.map {
        Bucket.fromApiData(_, client, origin)
      }
  }
}

object Bucket extends Logging {

  private def arn(bucketName: String) = s"arn:aws:s3:::$bucketName" 

  def fromApiData(bucket: AWSBucket, client: S3Client, origin: AmazonOrigin): Bucket = {
    val bucketName = bucket.name
    val bucketRegion = try {
      Option(
        client.getBucketLocation(GetBucketLocationRequest.builder.bucket(bucketName).build).locationConstraintAsString
      )
        .filterNot(region => "" == region)
        .orElse(Some(Region.US_EAST_1.id))
    } catch {
      case e:S3Exception if e.awsErrorDetails.errorCode == "NoSuchBucket" =>
        log.info(s"NoSuchBucket for $bucketName in account ${origin.account}", e)
        None
      case e:S3Exception if e.awsErrorDetails.errorCode == "AuthorizationHeaderMalformed" =>
        log.info(s"AuthorizationHeaderMalformed for $bucketName in account ${origin.account}", e)
        None
      case NonFatal(t) =>
        throw new IllegalStateException(s"Failed when building info for bucket $bucketName", t)
    }
    Bucket(
      arn = arn(bucketName),
      name = bucketName,
      region = bucketRegion,
      createdTime = bucket.creationDate
    )
  }
}

case class Bucket(
  arn: String,
  name: String,
  region: Option[String],
  createdTime: Instant
) extends IndexedItem {
  override def callFromArn: (String) => Call = arn => routes.Api.bucket(arn)
}