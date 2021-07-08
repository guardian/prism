#!/usr/bin/env node
import "source-map-support/register";
import { App } from "@aws-cdk/core";
import { PrismStack } from "../lib/prism";
import { PrismAccess } from "../lib/prism-access";
import { PrismEc2App } from "../lib/prism-ec2-app";

const app = new App();

new PrismStack(app, "Prism", {
  description: "Prism - service discovery",
  migratedFromCloudFormation: true,
  stack: "deploy",
});

new PrismEc2App(app, "PrismEc2App", {
  description: "Prism - service discovery",
  stack: "deploy",
});

new PrismAccess(app, "PrismAccess", {
  description: "CloudFormation template to create the prism role.",
  migratedFromCloudFormation: true,
  stack: "deploy",
});
