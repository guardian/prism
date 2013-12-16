package deployinfo

import akka.actor.ActorSystem
import scala.concurrent.duration._
import utils.Logging
import conf.{DeployInfoMode, Configuration}
import utils.ScheduledAgent
import java.io.{FileNotFoundException, File}
import java.net.{URLConnection, URL, URLStreamHandler}
import io.Source
import utils.LifecycleWithoutApp
import org.joda.time.{DateTime, Duration}
import scala.collection.mutable
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.language.postfixOps
import java.util.concurrent.TimeoutException
import play.api.libs.json._

object DeployInfoManager extends LifecycleWithoutApp with Logging {

  private val classpathHandler = new URLStreamHandler {
    override def openConnection(u: URL): URLConnection = {
      Option(getClass.getResource(u.getPath)).map(_.openConnection()).getOrElse{
        throw new FileNotFoundException("%s not found on classpath" format u.getPath)
      }
    }
  }

  private def getDeployInfo = {
    import sys.process._
    log.info("Populating deployinfo ...")
    val deployInfoJsonOption: Option[String] = Configuration.deployinfo.mode match {
      case DeployInfoMode.Execute =>
        if (new File(Configuration.deployinfo.location).exists) {
          val buffer = mutable.Buffer[String]()
          val process = Configuration.deployinfo.location.run(ProcessLogger( (s) => buffer += s, _ => ()))
          try {
            val futureExitValue = Await.result(future {
              process.exitValue()
            }, Configuration.deployinfo.timeoutSeconds.seconds)
            if (futureExitValue == 0) Some(buffer.mkString("")) else None
          } catch {
            case t:TimeoutException =>
              process.destroy()
              log.error("The deployinfo process didn't finish quickly enough, tried to terminate the process")
              None
          }
        } else {
          log.warn("No file found at '%s', defaulting to empty DeployInfo" format (Configuration.deployinfo.location))
          None
        }
      case DeployInfoMode.URL =>
        val url = Configuration.deployinfo.location match {
          case classPathLocation if classPathLocation.startsWith("classpath:") => new URL(null, classPathLocation, classpathHandler)
          case otherURL => new URL(otherURL)
        }
        log.info("URL: %s" format url)
        Some(Source.fromURL(url).getLines.mkString)
    }

    deployInfoJsonOption.map{ deployInfoJson =>
      val json = Json.parse(deployInfoJson)
      val deployInfo = json \ "response" match {
        case response:JsObject => {
          val updateTime = (response \ "updateTime").asOpt[String].map(s => new DateTime(s))
          DeployInfoJsonReader.parse(response \ "results").copy(lastUpdated = updateTime.getOrElse(new DateTime()))
        }
        case _ => DeployInfoJsonReader.parse(deployInfoJson)
      }


      log.info("Successfully retrieved deployinfo (%d data found)" format deployInfo.data.values.map(_.size).fold(0)(_+_))

      deployInfo
    }
  }

  val system = ActorSystem("deploy")
  var agent: Option[ScheduledAgent[DeployInfo]] = None

  def init() {
    log.info("Initialising")
    val refreshSeconds = Configuration.deployinfo.refreshSeconds.seconds
    agent = Some(if (conf.Configuration.deployinfo.lazyStartup) {
      ScheduledAgent[DeployInfo](0 seconds, refreshSeconds, DeployInfo()){ original =>
        getDeployInfo.getOrElse(original)
      }
    } else {
      val initial = getDeployInfo
      assert(initial.isDefined, "Failed to parse deploy info during non-deferred startup")
      ScheduledAgent[DeployInfo](refreshSeconds, refreshSeconds, initial.get){ original =>
        getDeployInfo.getOrElse(original)
      }
    })
  }

  def deployInfo = agent.map(_()).getOrElse(DeployInfo())
  def isStale = deployInfo.isStale

  def dataList = deployInfo.data

  def shutdown() {
    agent.foreach(_.shutdown())
    agent = None
  }
}