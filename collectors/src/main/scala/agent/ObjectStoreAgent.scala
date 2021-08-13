package agent

import play.api.libs.json.Format
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, ListObjectsV2Request}
import utils.{Logging, ObjectStoreSerialisation}

import scala.jdk.CollectionConverters._

class ObjectStoreAgent[T<:IndexedItem](collectorSet: CollectorSet[T], s3Client: S3Client, bucket: String)(implicit tFormat: Format[T])
  extends CollectorAgentTrait[T] with Logging {

  private val resourceName: String = collectorSet.resource.name

  private val listObjectsRequest = ListObjectsV2Request.builder
    .bucket(bucket)
    .prefix(s"$resourceName/")
    .build

  /* todo: put a very short cache around all of this (1-2 seconds???) to provide a
     ceiling of the number of S3 transfers we make */
  override def get(): Iterable[ApiDatum[T]] = {
    val s3Objects = s3Client
      .listObjectsV2Paginator(listObjectsRequest).asScala
      .flatMap(_.contents.asScala)
      .toList
    log.info(s"Getting data for $resourceName; found ${s3Objects.length} files: ${s3Objects.map(_.key).mkString(";")}")
    s3Objects.flatMap { obj =>
      val getObjectRequest = GetObjectRequest.builder.bucket(bucket).key(obj.key).build
      val response = s3Client.getObjectAsBytes(getObjectRequest)
      // TODO: deal with errors rather than discarding
      ObjectStoreSerialisation.deserialise(response.asByteBuffer) match {
        case Right(v) => Some(v)
        case Left(err) =>
          log.warn(s"Trouble in paradise when getting data from ${obj.key}: $err")
          None
      }
    }
  }

  def put(datum: Datum[T]): Unit = {

  }

  override def init(): Unit = {
  }

  override def shutdown(): Unit = {
  }
}
