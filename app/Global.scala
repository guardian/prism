import collection.mutable
import conf.PlayRequestMetrics
import controllers.PrismAgents
import play.api.mvc.WithFilters
import utils.{Logging, Lifecycle, ScheduledAgent}
import play.api.Application

object Global extends WithFilters(new GzipFilter() :: new JsonpFilter() :: PlayRequestMetrics.asFilters : _*) with Logging {

  val lifecycleSingletons = mutable.Buffer[Lifecycle]()

  def onStart(app: Application, prismAgents: PrismAgents) {
    // list of singletons - note these are inside onStart() to ensure logging has fully initialised
    lifecycleSingletons ++= List(
      ScheduledAgent
    )
    lifecycleSingletons ++= prismAgents.allAgents

    log.info("Calling init() on Lifecycle singletons: %s" format lifecycleSingletons.map(_.getClass.getName).mkString(", "))
    lifecycleSingletons foreach { singleton =>
      try {
        singleton.init(app)
      } catch {
        case t:Throwable => log.error("Caught unhandled exception whilst calling init() on Lifecycle singleton", t)
      }
    }
  }

  def onStop(app: Application) {
    log.info("Calling shutdown() on Lifecycle singletons: %s" format lifecycleSingletons.reverse.map(_.getClass.getName).mkString(", "))
    lifecycleSingletons.reverse.foreach { singleton =>
      try {
        singleton.shutdown(app)
      } catch {
        case t:Throwable => log.error("Caught unhandled exception whilst calling shutdown() on Lifecycle singleton", t)
      }
    }
    lifecycleSingletons.clear()
  }

}