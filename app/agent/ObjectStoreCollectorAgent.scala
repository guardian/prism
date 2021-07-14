package agent

import play.api.libs.json.Format
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, ListObjectsV2Request}
import utils.ObjectStoreSerialisation

import scala.jdk.CollectionConverters._

class ObjectStoreCollectorAgent[T<:IndexedItem](s3Client: S3Client, bucket: String, prefix: String)(implicit tFormat: Format[T]) extends CollectorAgentTrait[T] {
  private val listObjectsRequest = ListObjectsV2Request.builder
    .bucket(bucket)
    .prefix(prefix)
    .build

  /* todo: put a very short cache around all of this (1-2 seconds???) to provide a
     ceiling of the number of S3 transfers we make */
  override def get(): Iterable[ApiDatum[T]] = {
    val s3Objects = s3Client
      .listObjectsV2Paginator(listObjectsRequest).asScala
      .flatMap(_.contents.asScala)
    s3Objects.flatMap { obj =>
    val getObjectRequest = GetObjectRequest.builder.bucket(bucket).key(obj.key).build
      val response = s3Client.getObjectAsBytes(getObjectRequest)
      // TODO: deal with errors rather than discarding
      ObjectStoreSerialisation.deserialise(response.asByteBuffer).toOption
    }
  }

  override def init(): Unit = {
  }

  override def shutdown(): Unit = {
  }
}
