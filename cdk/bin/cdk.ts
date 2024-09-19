#!/usr/bin/env node
import "source-map-support/register";
import { App } from "aws-cdk-lib";
import { PrismAccess } from "../lib/prism-access";
import { PrismEc2App } from "../lib/prism-ec2-app";

const app = new App();

new PrismEc2App(app, "Prism-CODE", {
	stage: "CODE",
	domainName: "prism.code.dev-gutools.co.uk",
	minimumInstances: 1,
	cloudFormationStackName: "prism-CODE",
	env: { region: "eu-west-1" },
});

new PrismEc2App(app, "Prism-PROD", {
	stage: "PROD",
	domainName: "prism.gutools.co.uk",
	minimumInstances: 2,
	cloudFormationStackName: "prism-PROD",
	env: { region: "eu-west-1" },
});

new PrismAccess(app, "PrismAccessStackSet");
