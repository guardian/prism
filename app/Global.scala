import collection.mutable
import deployinfo.DeployInfoManager
import play.api.mvc.WithFilters
import utils.{JsonpFilter, Logging, Lifecycle, ScheduledAgent}
import play.api.Application
import scala.concurrent.ExecutionContext.Implicits.global

object Global extends WithFilters(new JsonpFilter()) with Logging {

  val lifecycleSingletons = mutable.Buffer[Lifecycle]()

  override def onStart(app: Application) {
    // list of singletons - note these are inside onStart() to ensure logging has fully initialised
    lifecycleSingletons ++= List(
      ScheduledAgent,
      DeployInfoManager
    )

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