import { BlockDeviceVolume, EbsDeviceVolumeType, HealthCheck } from "@aws-cdk/aws-autoscaling";
import { Peer } from "@aws-cdk/aws-ec2";
import type { App } from "@aws-cdk/core";
import { Duration } from "@aws-cdk/core";
import { GuAutoScalingGroup } from "@guardian/cdk/lib/constructs/autoscaling";
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
    const s3Key = [distBucket, this.stack, this.stage, this.app, `${this.app}.deb`].join("/");

    // TODO move to UserData.forLinux
    const userData = `#!/bin/bash -ev

      aws --region ${this.region} s3 cp s3://${s3Key} /tmp/
      dpkg -i /tmp/prism.deb`;

    const asg = new GuAutoScalingGroup(this, "AutoscalingGroup", {
      overrideId: true,
      vpc,
      vpcSubnets: { subnets },
      role: role,
      userData: userData,
      minCapacity: 2,
      maxCapacity: 4,
      desiredCapacity: 2,
      healthCheck: HealthCheck.elb({
        grace: Duration.seconds(500),
      }),
      securityGroup: appServerSecurityGroup,
      blockDevices: [
        {
          deviceName: "/dev/sda1",
          volume: BlockDeviceVolume.ebs(8, {
            volumeType: EbsDeviceVolumeType.GP2,
          }),
        },
      ],
    });

    new GuHttpsClassicLoadBalancer(this, "LoadBalancer", {
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
  }
}
