import "@aws-cdk/assert/jest";
import { SynthUtils } from "@aws-cdk/assert";
import { App } from "@aws-cdk/core";
import { PrismEc2App } from "./prism-ec2-app";

describe("The PrismEc2App stack", () => {
  it("matches the snapshot", () => {
    const app = new App();
    const stack = new PrismEc2App(app, "prism", { stack: "deploy" });
    expect(SynthUtils.toCloudFormation(stack)).toMatchSnapshot();
  });
});
