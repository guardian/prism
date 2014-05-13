package collectors

import org.joda.time.Duration
import play.api.mvc.Call
import controllers.routes
import agent._

object OwnerCollectorSet extends CollectorSet[Owner](ResourceType("owner", Duration.standardMinutes(15L))) {
  val lookupCollector: PartialFunction[Origin, Collector[Owner]] = {
    case googleDoc:GoogleDocOrigin => GoogleDocOwnerCollector(googleDoc, resource)
  }
}

case class Owner(id:String, stack:String, apps:Seq[String], name:String, email:String, phone:Option[String]) extends IndexedItem {
  def callFromId: (String) => Call = id => routes.Api.owner(id)
}

case class GoogleDocOwnerCollector(origin: GoogleDocOrigin, resource: ResourceType) extends GoogleDocCollector[Owner] {
  def toOption(string:String): Option[String] = {
    if (string.isEmpty) None else Some(string)
  }

  def crawl: Iterable[Owner] = csvData.tail.zipWithIndex.map{ case (record, index) =>
    record match {
      case List(stack, app, name, email, phone) =>
        val appList = app.split(",").filterNot(_.isEmpty)
        Owner(index.toString, stack, appList, name, email, toOption(phone))
    }
  }
}

