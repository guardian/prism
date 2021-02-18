import "@aws-cdk/assert/jest";
import { SynthUtils } from "@aws-cdk/assert";
import { App } from "@aws-cdk/core";
import { PrismAccess } from "./prism-access";

describe("The PrismAccess stack", () => {
  it("matches the snapshot", () => {
    const app = new App();
    const role = new PrismAccess(app, "prism-access", {
      app: "prism",
      migratedFromCloudFormation: true,
      stack: "deploy",
    });

    expect(SynthUtils.toCloudFormation(role)).toMatchSnapshot();
  });
});
