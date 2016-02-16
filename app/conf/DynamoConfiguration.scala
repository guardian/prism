package conf

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.regions.Region
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import com.amazonaws.services.dynamodbv2.document.{DynamoDB, Item, PrimaryKey, TableKeysAndAttributes}
import com.typesafe.config.ConfigFactory
import play.api.Configuration
import play.api.Mode
import utils.Logging

import scala.collection.JavaConverters._

case class Identity(stack: String, app: String, stage: String)
case class ConfigSegment(app: String, stage: String)

object DynamoConfiguration {
  def apply(credProvider: AWSCredentialsProvider, region: Region,
            identity: Identity, prefix:String="config-"): ConfigurationSource = {
    new DynamoConfiguration(credProvider, region, identity, prefix)
  }
}

class DynamoConfiguration(credProvider: AWSCredentialsProvider, region: Region,
                          identity: Identity, prefix:String) extends ConfigurationSource with Logging {

  def configuration(mode: Mode.Mode): Configuration = {

    if (mode == Mode.Test)
      Configuration.empty
    else {
      val client = new AmazonDynamoDBClient(credProvider)
      client.setRegion(region)
      val dynamoDb = new DynamoDB(client)

      val tableName = s"$prefix${identity.stack}"

      val configs = fromTable(
        dynamoDb,
        tableName,
        configSegmentsFromIdentity(identity)
      )

      val finalConfig = configs.flatMap{ case (segment, config) => config }.foldLeft[Configuration](Configuration.empty){ _ ++ _ }

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