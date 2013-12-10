package deployinfo

import org.joda.time.{Duration, DateTimeZone, DateTime}
import org.joda.time.format.{DateTimeFormatterBuilder, DateTimeFormat}
import model.DataContainer
import conf.Configuration

object DeployInfo {
  def apply(): DeployInfo = DeployInfo(DeployInfoJsonInputFile(Nil,None,Map.empty), new DateTime(0L))

  def transpose[A](xs: List[List[A]]): List[List[A]] = xs.filter(_.nonEmpty) match {
    case Nil => Nil
    case ys: List[List[A]] => ys.map{ _.head }::transpose(ys.map{ _.tail })
  }
}

case class DeployInfo(input:DeployInfoJsonInputFile, lastUpdated:DateTime) extends DataContainer {
  lazy val isStale = new Duration(lastUpdated, new DateTime).getStandardMinutes > Configuration.deployinfo.staleMinutes

  val name = getClass.getSimpleName

  val data = input.data mapValues { dataList =>
    dataList.map { data => Data(data.app, data.stage, data.value, data.comment) }
  }

  lazy val knownKeys: List[String] = data.keys.toList.sorted

  def dataForKey(key: String): List[Data] = data.get(key).getOrElse(List.empty)
  def knownDataStages(key: String) = data.get(key).toList.flatMap {_.map(_.stage).distinct.sortWith(_.toString < _.toString)}
  def knownDataApps(key: String): List[String] = data.get(key).toList.flatMap{_.map(_.app).distinct.sortWith(_.toString < _.toString)}

  def stageAppToDataMap(key: String): Map[(String,String),List[Data]] = data.get(key).map {_.groupBy(key => (key.stage,key.app))}.getOrElse(Map.empty)

  def firstMatchingData(key: String, app:String, stage:String): Option[Data] = {
    val matchingList = data.getOrElse(key, List.empty)
    matchingList.find(data => data.appRegex.findFirstMatchIn(app).isDefined && data.stageRegex.findFirstMatchIn(stage).isDefined)
  }
}

case class Data(
  app: String,
  stage: String,
  value: String,
  comment: Option[String]
) {
  lazy val appRegex = ("^%s$" format app).r
  lazy val stageRegex = ("^%s$" format stage).r
}