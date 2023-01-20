package utils

import play.api.Application

/** Any objects with this trait mixed in will automatically get instantiated and
  * lifecycled. init() called by the Global onStart() and shutdown called by
  * onStop().
  */
trait Lifecycle {
  def init(app: Application): Unit
  def shutdown(app: Application): Unit
}

trait LifecycleWithoutApp extends Lifecycle {
  def init(app: Application): Unit = { init() }
  def shutdown(app: Application): Unit = { shutdown() }
  def init(): Unit
  def shutdown(): Unit
}
