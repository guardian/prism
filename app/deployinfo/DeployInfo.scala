package deployinfo

import org.joda.time.{DateTimeZone, DateTime}
import org.joda.time.format.{DateTimeFormatterBuilder, DateTimeFormat}

object DeployInfo {
  def apply(): DeployInfo = DeployInfo(DeployInfoJsonInputFile(Nil,None,Map.empty), None)

  def transpose[A](xs: List[List[A]]): List[List[A]] = xs.filter(_.nonEmpty) match {
    case Nil => Nil
    case ys: List[List[A]] => ys.map{ _.head }::transpose(ys.map{ _.tail })
  }

  def transposeHostsByGroup(hosts: List[Host]): List[Host] = {
    val listOfGroups = hosts.groupBy(_.tags.get("group").getOrElse("")).toList.sortBy(_._1).map(_._2)
    transpose(listOfGroups).fold(Nil)(_ ::: _)
  }
}

case class DeployInfo(input:DeployInfoJsonInputFile, createdAt:Option[DateTime]) {
  val formatter = {
    val builder = new DateTimeFormatterBuilder()
    val rb18 = DateTimeFormat.forPattern("EEE MMM dd HH:mm:ss 'UTC' yyyy")
    val rb19 = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
    builder.append(rb18.getPrinter, Array(rb18.getParser,rb19.getParser))
    builder.toFormatter.withZone(DateTimeZone.UTC)
  }

  def asHost(host: DeployInfoHost) = {
    Host(
      id = host.arn,
      name = host.hostname,
      mainclasses = host.app.split(",").toList,
      stage = host.stage,
      group = host.group,
      role = host.role,
      stack = host.stack,
      apps = host.apps.getOrElse(Nil),
      dnsName = host.dnsname,
      createdAt = formatter.parseDateTime(host.created_at),
      instanceName = host.instancename,
      internalName = host.internalname,
      region = host.region,
      account = host.account,
      vendor = host.vendor,
      tags = host.tags
    )
  }

  def filterHosts(p: Host => Boolean) = this.copy(input = input.copy(hosts = input.hosts.filter(jsonHost => p(asHost(jsonHost)))))

  val hosts = input.hosts.map(asHost).groupBy(_.id).values.map { toMerge => toMerge.reduce(_+_) }.toList
  val data = input.data mapValues { dataList =>
    dataList.map { data => Data(data.app, data.stage, data.value, data.comment) }
  }

  lazy val knownHostStages: List[String] = hosts.map(_.stage).distinct.sorted
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

case class Host(
    id: String,
    name: String,
    mainclasses: List[String],
    stage: String,
    group: String,
    role: String,
    stack: Option[String],
    apps: List[String],
    dnsName: String,
    createdAt: DateTime,
    instanceName: String,
    internalName: String,
    region: String,
    account: String,
    vendor: String,
    tags: Map[String, String] = Map.empty
) {
  def +(other:Host):Host = {
    this.copy(
      mainclasses = (this.mainclasses ++ other.mainclasses).distinct,
      apps = (this.apps ++ other.apps).distinct,
      tags = this.tags ++ other.tags
    )
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