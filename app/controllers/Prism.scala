//package controllers
//
//import agent.CollectorAgent
//import collectors._
//
//object Prism {
//  val lazyStartup = conf.PrismConfiguration.accounts.lazyStartup
//  val instanceAgent = new CollectorAgent[Instance](InstanceCollectorSet, lazyStartup)
//  val lambdaAgent = new CollectorAgent[Lambda](LambdaCollectorSet, lazyStartup)
//  val dataAgent = new CollectorAgent[Data](DataCollectorSet, lazyStartup)
//  val securityGroupAgent = new CollectorAgent[SecurityGroup](SecurityGroupCollectorSet, lazyStartup)
//  val imageAgent = new CollectorAgent[Image](ImageCollectorSet, lazyStartup)
//  val launchConfigurationAgent = new CollectorAgent[LaunchConfiguration](LaunchConfigurationCollectorSet, lazyStartup)
//  val serverCertificateAgent = new CollectorAgent[ServerCertificate](ServerCertificateCollectorSet, lazyStartup)
//  val acmCertificateAgent = new CollectorAgent[AcmCertificate](AmazonCertificateCollectorSet, lazyStartup)
//  val route53ZoneAgent = new CollectorAgent[Route53Zone](Route53ZoneCollectorSet, lazyStartup)
//  val elbAgent = new CollectorAgent[LoadBalancer](LoadBalancerCollectorSet, lazyStartup)
//  val bucketAgent = new CollectorAgent[Bucket](BucketCollectorSet, lazyStartup)
//  val reservationAgent = new CollectorAgent[Reservation](ReservationCollectorSet, lazyStartup)
//  val allAgents = Seq(instanceAgent, lambdaAgent, dataAgent, securityGroupAgent, imageAgent, launchConfigurationAgent,
//    serverCertificateAgent, acmCertificateAgent, route53ZoneAgent, elbAgent, bucketAgent, reservationAgent)
//}