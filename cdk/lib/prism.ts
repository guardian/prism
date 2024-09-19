import { GuPlayApp } from '@guardian/cdk';
import { AccessScope } from '@guardian/cdk/lib/constants';
import type { GuStackProps } from '@guardian/cdk/lib/constructs/core/stack';
import { GuStack } from '@guardian/cdk/lib/constructs/core/stack';
import {
	GuAllowPolicy,
	GuAssumeRolePolicy,
	GuDynamoDBReadPolicy,
	GuGetS3ObjectsPolicy,
} from '@guardian/cdk/lib/constructs/iam';
import type { App } from 'aws-cdk-lib';
import { Duration } from 'aws-cdk-lib';
import type { CfnAutoScalingGroup } from 'aws-cdk-lib/aws-autoscaling';
import {
	BlockDeviceVolume,
	EbsDeviceVolumeType,
} from 'aws-cdk-lib/aws-autoscaling';
import {
	InstanceClass,
	InstanceSize,
	InstanceType,
	Peer,
} from 'aws-cdk-lib/aws-ec2';

interface PrismProps extends Omit<GuStackProps, 'description' | 'stack'> {
	domainName: string;
	minimumInstances: number;

	/**
	 * Which application build to run.
	 * This will typically match the build number provided by CI.
	 */
	buildIdentifier: string;
}

export class Prism extends GuStack {
	constructor(scope: App, id: string, props: PrismProps) {
		const app = 'prism';
		super(scope, id, {
			...props,
			description: 'Prism - service discovery',
			stack: 'deploy',
			app,
		});

		const { buildIdentifier } = props;

		const filename = `${app}-${buildIdentifier}.deb`;

		const pattern = new GuPlayApp(this, {
			app,
			applicationLogging: {
				enabled: true,
			},
			imageRecipe: 'arm64-focal-java11-deploy-infrastructure',
			instanceType: InstanceType.of(InstanceClass.T4G, InstanceSize.MEDIUM),
			userData: {
				distributable: {
					fileName: filename,
					executionStatement: `dpkg -i /${app}/${filename}`,
				},
			},
			certificateProps: {
				domainName: props.domainName,
			},
			monitoringConfiguration:
				this.stage === 'PROD'
					? {
							snsTopicName: 'devx-alerts',
							http5xxAlarm: false,
							unhealthyInstancesAlarm: true,
						}
					: { noMonitoring: true },
			access: {
				scope: AccessScope.INTERNAL,
				cidrRanges: [Peer.ipv4('10.0.0.0/8')],
			},
			roleConfiguration: {
				additionalPolicies: [
					new GuAllowPolicy(this, 'DescribeEC2BonusPolicy', {
						resources: ['*'],
						actions: ['EC2:Describe*'],
					}),
					new GuDynamoDBReadPolicy(this, 'ConfigPolicy', {
						tableName: 'config-deploy',
					}),
					new GuGetS3ObjectsPolicy(this, 'DataPolicy', {
						bucketName: 'prism-data',
					}),
					new GuAssumeRolePolicy(this, 'CrawlerPolicy', {
						resources: [
							'arn:aws:iam::*:role/*Prism*',
							'arn:aws:iam::*:role/*prism*',
						],
					}),
				],
			},
			scaling: {
				minimumInstances: props.minimumInstances,
			},
			blockDevices: [
				{
					deviceName: '/dev/sda1',
					volume: BlockDeviceVolume.ebs(8, {
						volumeType: EbsDeviceVolumeType.GP2,
					}),
				},
			],
		});

		// The pattern does not currently offer support for customising healthchecks via props
		pattern.targetGroup.configureHealthCheck({
			path: '/management/healthcheck',
			unhealthyThresholdCount: 10,
			interval: Duration.seconds(5),
			timeout: Duration.seconds(3),
		});

		// Similarly the pattern does not offer support for extending the default ASG grace period via props
		const cfnAsg = pattern.autoScalingGroup.node
			.defaultChild as CfnAutoScalingGroup;
		cfnAsg.healthCheckGracePeriod = Duration.minutes(15).toSeconds();
	}
}
