package collectors

import java.time.Instant

import agent._
import conf.AWS
import software.amazon.awssdk.services.rds.RdsClient
import software.amazon.awssdk.services.rds.model.{DBInstance, DescribeDbInstancesRequest}
import utils.Logging

import scala.jdk.CollectionConverters._
import scala.language.postfixOps

class RdsCollectorSet(accounts: Accounts) extends CollectorSet[Rds](ResourceType("rds"), accounts, Some(Regional)) {
  val lookupCollector: PartialFunction[Origin, Collector[Rds]] = {
    case amazon:AmazonOrigin => AWSRdsCollector(amazon, resource, amazon.crawlRate(resource.name))
  }
}

case class AWSRdsCollector(origin:AmazonOrigin, resource: ResourceType, crawlRate: CrawlRate) extends Collector[Rds] with Logging {

  val client: RdsClient = RdsClient
    .builder
    .credentialsProvider(origin.credentials.provider)
    .region(origin.awsRegionV2)
    .overrideConfiguration(AWS.clientConfig)
    .build

  def crawl:Iterable[Rds] =
    client.describeDBInstancesPaginator(DescribeDbInstancesRequest.builder.build).dbInstances.asScala.map(Rds.fromApiData(_))
}

object Rds {
  def fromApiData(db: DBInstance): Rds = Rds(
    arn = db.dbInstanceArn,
    allocatedStorage = db.allocatedStorage,
    availabilityZone = db.availabilityZone,
    secondaryAvailabilityZone = Option(db.secondaryAvailabilityZone),
    engineVersion = db.engineVersion,
    instanceCreateTime = db.instanceCreateTime,
    dbInstanceClass = db.dbInstanceClass,
    dbInstanceStatus = db.dbInstanceStatus,
    caCertificateIdentifier = db.caCertificateIdentifier,
    dbiResourceId = db.dbiResourceId,
    dbInstanceIdentifier = db.dbInstanceIdentifier,
    engine = db.engine,
    publiclyAccessible = db.publiclyAccessible,
    iamDatabaseAuthenticationEnabled = db.iamDatabaseAuthenticationEnabled,
    performanceInsightsEnabled = db.performanceInsightsEnabled,
    multiAZ = db.multiAZ,
    storageEncrypted = db.storageEncrypted,
    vpcId = db.dbSubnetGroup.vpcId,
    dbSubnetGroupName = db.dbSubnetGroup.dbSubnetGroupName,
    vpcSecurityGroupId = db.vpcSecurityGroups.asScala.map(_.vpcSecurityGroupId).toList,
    storageType = db.storageType,
    autoMinorVersionUpgrade = db.autoMinorVersionUpgrade,
    tags = db.tagList.asScala.map(t => t.key -> t.value).toMap,
  )
}

case class Rds(
               arn: String,
               allocatedStorage: Int,
               availabilityZone: String,
               secondaryAvailabilityZone: Option[String],
               engineVersion: String,
               instanceCreateTime: Instant,
               dbInstanceClass: String,
               dbInstanceStatus: String,
               caCertificateIdentifier: String,
               dbiResourceId: String,
               dbInstanceIdentifier: String,
               engine: String,
               publiclyAccessible: Boolean,
               iamDatabaseAuthenticationEnabled: Boolean,
               performanceInsightsEnabled: Boolean,
               multiAZ: Boolean,
               storageEncrypted: Boolean,
               vpcId: String,
               dbSubnetGroupName: String,
               vpcSecurityGroupId: List[String],
               storageType: String,
               autoMinorVersionUpgrade: Boolean,
               tags: Map[String, String] = Map.empty
              ) extends IndexedItemWithStage with IndexedItemWithStack {
//  def callFromArn: String => Call = arn => routes.Api.rds(arn)
}