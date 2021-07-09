import type { CfnAutoScalingGroup } from "@aws-cdk/aws-autoscaling";
import { BlockDeviceVolume, EbsDeviceVolumeType } from "@aws-cdk/aws-autoscaling";
import { Peer } from "@aws-cdk/aws-ec2";
import type { App } from "@aws-cdk/core";
import { Duration } from "@aws-cdk/core";
import { AccessScope, GuPlayApp } from "@guardian/cdk";
import type { AppIdentity } from "@guardian/cdk/lib/constructs/core/identity";
import type { GuStackProps } from "@guardian/cdk/lib/constructs/core/stack";
import { GuStack } from "@guardian/cdk/lib/constructs/core/stack";
import {
  GuAllowPolicy,
  GuAssumeRolePolicy,
  GuDynamoDBReadPolicy,
  GuGetS3ObjectsPolicy,
} from "@guardian/cdk/lib/constructs/iam";

export class PrismEc2App extends GuStack {
  private static app: AppIdentity = {
    app: "prism",
  };

  constructor(scope: App, id: string, props: GuStackProps) {
    super(scope, id, props);

    const pattern = new GuPlayApp(this, {
      ...PrismEc2App.app,
      userData: {
        distributable: {
          fileName: "prism.deb",
          executionStatement: `dpkg -i /${PrismEc2App.app.app}/prism.deb`,
        },
      },
      certificateProps: {
        CODE: { domainName: "prism.code.dev-gutools.co.uk" },
        PROD: { domainName: "prism.gutools.co.uk" },
      },
      monitoringConfiguration: {
        snsTopicName: "devx-alerts",
        http5xxAlarm: false,
        unhealthyInstancesAlarm: true,
      },
      access: { scope: AccessScope.INTERNAL, cidrRanges: [Peer.ipv4("10.0.0.0/8")] },
      roleConfiguration: {
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
      },
      scaling: {
        CODE: { minimumInstances: 1 },
        PROD: { minimumInstances: 2 },
      },
      blockDevices: [
        {
          deviceName: "/dev/sda1",
          volume: BlockDeviceVolume.ebs(8, {
            volumeType: EbsDeviceVolumeType.GP2,
          }),
        },
      ],
    });

    // The pattern does not currently offer support for customising healthchecks via props
    pattern.targetGroup.configureHealthCheck({
      path: "/management/healthcheck",
      unhealthyThresholdCount: 10,
      interval: Duration.seconds(5),
      timeout: Duration.seconds(3),
    });

    // Similarly the pattern does not offer support for extending the default ASG grace period via props
    const cfnAsg = pattern.autoScalingGroup.node.defaultChild as CfnAutoScalingGroup;
    cfnAsg.healthCheckGracePeriod = 800;
  }
}
