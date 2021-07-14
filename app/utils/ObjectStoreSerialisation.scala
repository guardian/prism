package utils

import agent.ApiDatum
import com.fasterxml.jackson.databind.util.ByteBufferBackedInputStream
import play.api.libs.json.{Format, Json, OFormat}

import java.nio.ByteBuffer

object ObjectStoreSerialisation {
  def deserialise[T](byteBuffer: ByteBuffer)(implicit format: Format[T]): Either[String, ApiDatum[T]] = {
    val json = Json.parse(new ByteBufferBackedInputStream(byteBuffer))
    Json.fromJson[ApiDatum[T]](json).asEither.left.map(_.toString())
  }

  def serialise[T](datum: ApiDatum[T])(implicit format: OFormat[T]): ByteBuffer = {
    val json = Json.toJsObject(datum)
    ByteBuffer.wrap(Json.toBytes(json))
  }
}
