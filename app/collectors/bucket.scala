package collectors

import java.time.Instant

import agent._
import conf.AWS
import controllers.routes
import play.api.mvc.Call
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{GetBucketLocationRequest, ListBucketsRequest, S3Exception, Bucket => AWSBucket}
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}
import utils.Logging

import scala.jdk.CollectionConverters._
import scala.language.{postfixOps, reflectiveCalls}
import scala.util.control.NonFatal


class BucketCollectorSet(accounts: Accounts) extends CollectorSet[Bucket](ResourceType("bucket"), accounts, Some(Global)) {
  val lookupCollector: PartialFunction[Origin, Collector[Bucket]] = {
    case amazon: AmazonOrigin => AWSBucketCollector(amazon, resource, amazon.crawlRate(resource.name))
  }
}

case class AWSBucketCollector(origin: AmazonOrigin, resource: ResourceType, crawlRate: CrawlRate) extends Collector[Bucket] with Logging {

  val s3Configuration = S3Configuration.builder.useArnRegionEnabled(true).build

  // We have hardcoded the region of the S3 Client, so that we can receive data on S3 buckets in all AWS regions
  // https://stackoverflow.com/questions/46769493/how-enable-force-global-bucket-access-in-aws-s3-sdk-java-2-0 
  val client = S3Client
    .builder
    .credentialsProvider(origin.credentials.provider)
    .region(Region.EU_WEST_1)
    .overrideConfiguration(AWS.clientConfig)
    .serviceConfiguration(s3Configuration)
    .build

  // We need to create a second S3 client to get the correct createdTime as documented here:
  // https://stackoverflow.com/questions/54353373/getting-incorrect-creation-dates-using-aws-s3
  val clientForCorrectCreatedTime = S3Client
    .builder
    .credentialsProvider(origin.credentials.provider)
    .region(Region.US_EAST_1)
    .build

  def crawl: Iterable[Bucket] = {
    val request = ListBucketsRequest.builder.build

    val listBuckets = client.listBuckets(request).buckets().asScala.toList
    log.info(s"Total number of buckets with S3 Client region EU-WEST-1 for account ${origin.account} ${listBuckets.length}")

    val listBucketsForCorrectCreatedTime = clientForCorrectCreatedTime.listBuckets(request).buckets.asScala.toList
    log.info(s"Total number of buckets with S3 Client region US-EAST-1 for account ${origin.account} ${listBucketsForCorrectCreatedTime.length}")

    listBuckets.zip(listBucketsForCorrectCreatedTime).map{ case (bucket, bucketWithCorrectCreatedTime) =>
      Bucket.fromApiData(bucket, client, origin, bucketWithCorrectCreatedTime)
    }
  }
}

object Bucket extends Logging {

  private def arn(bucketName: String) = s"arn:aws:s3:::$bucketName" 

  def fromApiData(bucket: AWSBucket, client: S3Client, origin: AmazonOrigin, bucketWithCorrectCreatedTime: AWSBucket): Bucket = {
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
      createdTime = bucketWithCorrectCreatedTime.creationDate
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