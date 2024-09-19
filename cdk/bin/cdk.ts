#!/usr/bin/env node
import 'source-map-support/register';
import { RiffRaffYamlFile } from '@guardian/cdk/lib/riff-raff-yaml-file';
import { App } from 'aws-cdk-lib';
import { Prism } from '../lib/prism';
import { PrismAccess } from '../lib/prism-access';

const app = new App();

new Prism(app, 'Prism-CODE', {
	stage: 'CODE',
	domainName: 'prism.code.dev-gutools.co.uk',
	minimumInstances: 1,
	cloudFormationStackName: 'prism-CODE',
	env: { region: 'eu-west-1' },
});

new Prism(app, 'Prism-PROD', {
	stage: 'PROD',
	domainName: 'prism.gutools.co.uk',
	minimumInstances: 2,
	cloudFormationStackName: 'prism-PROD',
	env: { region: 'eu-west-1' },
});

new PrismAccess(app, 'PrismAccessStackSet');

const riffRaff = new RiffRaffYamlFile(app);
const { deployments, allowedStages } = riffRaff.riffRaffYaml;

const stackSetDeploymentName = 'cfn-eu-west-1-deploy-prism-access';
if (!deployments.get(stackSetDeploymentName)) {
	throw new Error(
		'Failed to remove CloudFormation deployment of the stack set from riff-raff.yaml',
	);
}
// Riff-Raff cannot deploy stack sets. Remove it from the riff-raff.yaml file.
deployments.delete(stackSetDeploymentName);
// The stack set uses the `INFRA` stage. The application stack (`Prism`) does not. Remove that too.
allowedStages.delete('INFRA');

riffRaff.synth();
