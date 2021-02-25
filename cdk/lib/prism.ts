import { BlockDeviceVolume, EbsDeviceVolumeType, HealthCheck } from "@aws-cdk/aws-autoscaling";
import { Peer, Port } from "@aws-cdk/aws-ec2";
import type { App } from "@aws-cdk/core";
import { Duration } from "@aws-cdk/core";
import { Stage } from "@guardian/cdk/lib/constants";
import { GuAutoScalingGroup, GuUserData } from "@guardian/cdk/lib/constructs/autoscaling";
import { GuDistributionBucketParameter } from "@guardian/cdk/lib/constructs/core";
import type { GuStackProps } from "@guardian/cdk/lib/constructs/core/stack";
import { GuStack } from "@guardian/cdk/lib/constructs/core/stack";
import { GuSecurityGroup, GuVpc } from "@guardian/cdk/lib/constructs/ec2";
import {
  GuAllowPolicy,
  GuAssumeRolePolicy,
  GuDynamoDBReadPolicy,
  GuGetS3ObjectPolicy,
  GuInstanceRole,
} from "@guardian/cdk/lib/constructs/iam";
import { GuHttpsClassicLoadBalancer } from "@guardian/cdk/lib/constructs/loadbalancing";

export class PrismStack extends GuStack {
  constructor(scope: App, id: string, props: GuStackProps) {
    super(scope, id, props);

    const vpc = GuVpc.fromIdParameter(this, "vpc");
    const subnets = GuVpc.subnetsfromParameter(this);

    const role = new GuInstanceRole(this, "InstanceRole", {
      additionalPolicies: [
        new GuAllowPolicy(this, "DescribeEC2BonusPolicy", {
          resources: ["*"],
          actions: ["EC2:Describe*"],
        }),
        new GuDynamoDBReadPolicy(this, "ConfigPolicy", { tableName: "config-deploy" }),
        new GuGetS3ObjectPolicy(this, "DataPolicy", {
          overrideId: true,
          bucketName: "prism-data",
        }),
        new GuAssumeRolePolicy(this, "CrawlerPolicy", {
          resources: ["arn:aws:iam::*:role/*Prism*", "arn:aws:iam::*:role/*prism*"],
        }),
      ],
    });

    const appServerSecurityGroup = new GuSecurityGroup(this, "AppServerSecurityGroup", {
      description: "application servers",
      vpc,
      allowAllOutbound: true,
      overrideId: true,
    });

    const distBucket: string = this.getParam(GuDistributionBucketParameter.parameterName).valueAsString;

    const userData = new GuUserData(this, {
      distributable: {
        bucketName: distBucket,
        fileName: "prism.deb",
        executionStatement: `dpkg -i /${this.app}/prism.deb`,
      },
    });

    const asg = new GuAutoScalingGroup(this, "AutoscalingGroup", {
      overrideId: true,
      vpc,
      vpcSubnets: { subnets },
      role: role,
      userData: userData.userData,
      stageDependentProps: {
        [Stage.CODE]: {
          minimumInstances: 2,
        },
        [Stage.PROD]: {
          minimumInstances: 2,
        },
      },
      healthCheck: HealthCheck.elb({
        grace: Duration.seconds(500),
      }),
      additionalSecurityGroups: [appServerSecurityGroup],
      blockDevices: [
        {
          deviceName: "/dev/sda1",
          volume: BlockDeviceVolume.ebs(8, {
            volumeType: EbsDeviceVolumeType.GP2,
          }),
        },
      ],
    });

    const loadBalancer = new GuHttpsClassicLoadBalancer(this, "LoadBalancer", {
      vpc,
      crossZone: true,
      subnetSelection: { subnets },
      targets: [asg],
      healthCheck: {
        path: "/management/healthcheck",
        unhealthyThreshold: 10,
        interval: Duration.seconds(5),
        timeout: Duration.seconds(3),
      },
      listener: {
        allowConnectionsFrom: [Peer.ipv4("10.0.0.0/8")],
      },
    });

    appServerSecurityGroup.connections.allowFrom(loadBalancer, Port.tcp(9000), "Port 9000 LB to fleet");
  }
}
