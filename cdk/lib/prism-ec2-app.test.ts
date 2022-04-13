import { App } from "aws-cdk-lib";
import { Template } from "aws-cdk-lib/assertions";
import { PrismEc2App } from "./prism-ec2-app";

describe("The PrismEc2App stack", () => {
  it("matches the snapshot", () => {
    const app = new App();
    const stack = new PrismEc2App(app, "prism", {
      stage: "PROD",
      domainName: "prism.gutools.co.uk",
      minimumInstances: 2,
    });
    expect(Template.fromStack(stack).toJSON()).toMatchSnapshot();
  });
});
