import { AccessScope } from '@guardian/cdk/lib/constants';
import {
	GuDistributionBucketParameter,
	GuPrivateConfigBucketParameter,
} from '@guardian/cdk/lib/constructs/core';
import type { GuStackProps } from '@guardian/cdk/lib/constructs/core/stack';
import { GuStack } from '@guardian/cdk/lib/constructs/core/stack';
import {
	GuAllowPolicy,
	GuAssumeRolePolicy,
	GuGetS3ObjectsPolicy,
} from '@guardian/cdk/lib/constructs/iam';
import { GuEc2AppExperimental } from '@guardian/cdk/lib/experimental/patterns/ec2-app';
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
	UserData,
} from 'aws-cdk-lib/aws-ec2';
import { Bucket } from 'aws-cdk-lib/aws-s3';

interface PrismProps extends Omit<GuStackProps, 'description' | 'stack'> {
	domainName: string;
	minimumInstances: number;

	/**
	 * Which application build to run.
	 * This will typically match the build number provided by CI.
	 */
	buildIdentifier: string;

	instanceMetricGranularity: '1Minute' | '5Minute';
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

		const { buildIdentifier, instanceMetricGranularity } = props;
		const { stack, stage } = this;

		const distBucket = Bucket.fromBucketName(
			this,
			'DistBucket',
			GuDistributionBucketParameter.getInstance(this).valueAsString,
		);

		const configBucket = Bucket.fromBucketName(
			this,
			'ConfigBucket',
			new GuPrivateConfigBucketParameter(this).valueAsString,
		);
		const configObjectKey = `${stack}/${app}/${stage}.conf`;

		const userData = UserData.forLinux();

		userData.addS3DownloadCommand({
			bucket: configBucket,
			bucketKey: configObjectKey,
			localFile: `/etc/gu/${app}/${stage}.conf`,
		});

		const debianFilename = `${app}-${buildIdentifier}.deb`;
		const debianFile = userData.addS3DownloadCommand({
			bucket: distBucket,
			bucketKey: `${stack}/${stage}/${app}/${debianFilename}`,
			localFile: `/${app}/${debianFilename}`,
		});
		userData.addCommands(`dpkg -i ${debianFile}`);

		const pattern = new GuEc2AppExperimental(this, {
			buildIdentifier,
			applicationPort: 9000,
			app,
			applicationLogging: {
				enabled: true,
			},
			imageRecipe: 'arm64-focal-java11-deploy-infrastructure',
			instanceType: InstanceType.of(InstanceClass.T4G, InstanceSize.MEDIUM),
			userData,
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
					new GuGetS3ObjectsPolicy(this, 'DataPolicy', {
						bucketName: 'prism-data',
					}),
					new GuGetS3ObjectsPolicy(this, 'ReadConfigPolicy', {
						bucketName: configBucket.bucketName,
						paths: [configObjectKey],
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
			instanceMetricGranularity,
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
