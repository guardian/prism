import { BlockDeviceVolume, EbsDeviceVolumeType, HealthCheck } from "@aws-cdk/aws-autoscaling";
import { Peer, Port } from "@aws-cdk/aws-ec2";
import type { App } from "@aws-cdk/core";
import { Duration } from "@aws-cdk/core";
import { Stage } from "@guardian/cdk/lib/constants";
import { GuAutoScalingGroup, GuUserData } from "@guardian/cdk/lib/constructs/autoscaling";
import { GuDistributionBucketParameter } from "@guardian/cdk/lib/constructs/core";
import { AppIdentity } from "@guardian/cdk/lib/constructs/core/identity";
import type { GuStackProps } from "@guardian/cdk/lib/constructs/core/stack";
import { GuStack } from "@guardian/cdk/lib/constructs/core/stack";
import { GuSecurityGroup, GuVpc } from "@guardian/cdk/lib/constructs/ec2";
import {
  GuAllowPolicy,
  GuAssumeRolePolicy,
  GuDynamoDBReadPolicy,
  GuGetS3ObjectsPolicy,
  GuInstanceRole,
} from "@guardian/cdk/lib/constructs/iam";
import { GuHttpsClassicLoadBalancer } from "@guardian/cdk/lib/constructs/loadbalancing";

export class PrismStack extends GuStack {
  private static app: AppIdentity = {
    app: "prism",
  };

  constructor(scope: App, id: string, props: GuStackProps) {
    super(scope, id, props);

    /*
    Looks like some @guardian/cdk constructs are not applying the App tag.
    I suspect since https://github.com/guardian/cdk/pull/326.
    Until that is fixed, we can safely, manually apply it to all constructs in tree from `this` as it's a single app stack.
    TODO: remove this once @guardian/cdk has been fixed.
     */
    AppIdentity.taggedConstruct(PrismStack.app, this);

    const vpc = GuVpc.fromIdParameter(this, "vpc");
    const subnets = GuVpc.subnetsfromParameter(this);

    const role = new GuInstanceRole(this, {
      ...PrismStack.app,
      additionalPolicies: [
        new GuAllowPolicy(this, "DescribeEC2BonusPolicy", {
          resources: ["*"],
          actions: ["EC2:Describe*"],
        }),
        new GuDynamoDBReadPolicy(this, "ConfigPolicy", { tableName: "config-deploy" }),
        new GuGetS3ObjectsPolicy(this, "DataPolicy", {
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
      existingLogicalId: "AppServerSecurityGroup",
      ...PrismStack.app,
    });

    const userData = new GuUserData(this, {
      ...PrismStack.app,
      distributable: {
        bucket: GuDistributionBucketParameter.getInstance(this),
        fileName: "prism.deb",
        executionStatement: `dpkg -i /${PrismStack.app.app}/prism.deb`,
      },
    });

    const asg = new GuAutoScalingGroup(this, "AutoscalingGroup", {
      ...PrismStack.app,
      existingLogicalId: "AutoscalingGroup",
      vpc,
      vpcSubnets: { subnets },
      role: role,
      userData: userData.userData,
      stageDependentProps: {
        [Stage.CODE]: {
          minimumInstances: 1,
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
      existingLogicalId: "LoadBalancer",
    });

    appServerSecurityGroup.connections.allowFrom(loadBalancer, Port.tcp(9000), "Port 9000 LB to fleet");
  }
}
