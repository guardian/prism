import collection.mutable
import conf.PlayRequestMetrics
import controllers.Prism
import play.api.mvc.WithFilters
import utils.{JsonpFilter, Logging, Lifecycle, ScheduledAgent}
import play.api.Application
import scala.concurrent.ExecutionContext.Implicits.global
import play.filters.gzip.GzipFilter

object Global extends WithFilters(new GzipFilter() :: new JsonpFilter() :: PlayRequestMetrics.asFilters : _*) with Logging {

  val lifecycleSingletons = mutable.Buffer[Lifecycle]()

  override def onStart(app: Application) {
    // list of singletons - note these are inside onStart() to ensure logging has fully initialised
    lifecycleSingletons ++= List(
      ScheduledAgent
    )
    lifecycleSingletons ++= Prism.allAgents

    log.info("Calling init() on Lifecycle singletons: %s" format lifecycleSingletons.map(_.getClass.getName).mkString(", "))
    lifecycleSingletons foreach { singleton =>
      try {
        singleton.init(app)
      } catch {
        case t:Throwable => log.error("Caught unhandled exception whilst calling init() on Lifecycle singleton", t)
      }
    }
  }

  override def onStop(app: Application) {
    log.info("Calling shutdown() on Lifecycle singletons: %s" format lifecycleSingletons.reverse.map(_.getClass.getName).mkString(", "))
    lifecycleSingletons.reverse.foreach { singleton =>
      try {
        singleton.shutdown(app)
      } catch {
        case t:Throwable => log.error("Caught unhandled exception whilst calling shutdown() on Lifecycle singleton", t)
      }
    }
  }

}