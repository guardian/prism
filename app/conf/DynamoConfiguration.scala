package conf

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder
import com.amazonaws.services.dynamodbv2.document.{DynamoDB, Item, PrimaryKey, TableKeysAndAttributes}
import com.typesafe.config.ConfigFactory
import play.api.{Configuration, Mode}
import utils.Logging

import scala.jdk.CollectionConverters._

case class Identity(stack: String, app: String, stage: String)
case class ConfigSegment(app: String, stage: String)

object DynamoConfiguration {
  def apply(credProvider: AWSCredentialsProvider, region: Regions,
            identity: Identity, prefix:String="config-"): ConfigurationSource = {
    new DynamoConfiguration(credProvider, region, identity, prefix)
  }
}

class DynamoConfiguration(credProvider: AWSCredentialsProvider, region: Regions,
                          identity: Identity, prefix:String) extends ConfigurationSource with Logging {

  def configuration(mode: Mode): Configuration = {

    if (mode == Mode.Test)
      Configuration.empty
    else {
      val client = AmazonDynamoDBClientBuilder.standard()
        .withCredentials(credProvider)
        .withRegion(region)
        .build()
      val dynamoDb = new DynamoDB(client)

      val tableName = s"$prefix${identity.stack}"

      val configs = fromTable(
        dynamoDb,
        tableName,
        configSegmentsFromIdentity(identity)
      )

      // TODO: Is this okay?
      // val finalConfig = configs.flatMap{ case (_, config) => config }.foldLeft[Configuration](Configuration.empty){ _ ++ _ }
      val finalConfig = configs.flatMap{ case (_, config) => config }.foldRight[Configuration](Configuration.empty){ _.withFallback(_) }

      finalConfig
    }
  }

  def fromTable(dynamoDb: DynamoDB, tableName: String, configSegments: Seq[ConfigSegment]): Seq[(ConfigSegment, Option[Configuration])] = {
    val primaryKeys = configSegments.map{ segment => new PrimaryKey("App", segment.app, "Stage", segment.stage) }

    val tableKeysAndAttributes = new TableKeysAndAttributes(tableName).withPrimaryKeys(primaryKeys:_*)

    val result = dynamoDb.batchGetItem(tableKeysAndAttributes)
    val items = result.getTableItems.asScala.get(tableName).toSeq.flatMap(_.asScala)
    val configs = items.map{ item =>
      val app = item.getString("App")
      val stage = item.getString("Stage")
      ConfigSegment(app, stage) -> fromItem(item, s"Dynamo DB table $tableName [App=$app, Stage=$stage]")
    }.toMap

    configSegments.map{ segment => segment -> configs.get(segment) }
  }

  def fromItem(item: Item, originDescription: String): Configuration = {
    Configuration(ConfigFactory.parseMap(item.getMap("Config"), originDescription))
  }

  def configSegmentsFromIdentity(identity: Identity) = Seq(
    ConfigSegment("global", "global"),
    ConfigSegment(identity.app, "global"),
    ConfigSegment(identity.app, identity.stage)
  )
}