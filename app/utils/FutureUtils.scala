package utils

import scala.util.Try
import scala.concurrent.{ExecutionContext, Future, Promise}

object FutureUtils {
  implicit class TryFuture2toFuture[A](t: Try[Future[A]]) {
    def toFuture:Future[Future[A]] = Promise().complete(t).future
    def toFlatFuture(implicit c: ExecutionContext):Future[A] = toFuture.flatMap(f => f)
  }
  implicit class Try2toFuture[A](t: Try[A]) {
    def toFuture: Future[A] = Promise().complete(t).future
  }
}