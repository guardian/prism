import "@aws-cdk/assert/jest";
import { SynthUtils } from "@aws-cdk/assert";
import { App } from "@aws-cdk/core";
import { PrismStack } from "./prism";

describe("The Prism stack", () => {
  it("matches the snapshot", () => {
    const app = new App();
    const stack = new PrismStack(app, "prism", { migratedFromCloudFormation: true, stack: "deploy" });

    expect(SynthUtils.toCloudFormation(stack)).toMatchSnapshot();
  });
});
