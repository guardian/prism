import { GuArnParameter, GuStack } from "@guardian/cdk/lib/constructs/core";
import { AppIdentity } from "@guardian/cdk/lib/constructs/core/identity";
import { GuRole } from "@guardian/cdk/lib/constructs/iam";
import { CfnOutput } from "aws-cdk-lib";
import type { App } from "aws-cdk-lib";
import { ArnPrincipal, Effect, Policy, PolicyStatement } from "aws-cdk-lib/aws-iam";

export class PrismAccess extends GuStack {
  private static app: AppIdentity = {
    app: "prism",
  };

  constructor(scope: App, id: string) {
    super(scope, id, {
      description: "CloudFormation template to create the prism role.",
      stack: "deploy",
      stage: "INFRA", // singleton stack
    });

    /*
    Looks like some @guardian/cdk constructs are not applying the App tag.
    I suspect since https://github.com/guardian/cdk/pull/326.
    Until that is fixed, we can safely, manually apply it to all constructs in tree from `this` as it's a single app stack.
    TODO: remove this once @guardian/cdk has been fixed.
     */
    AppIdentity.taggedConstruct(PrismAccess.app, this);

    const parameters = {
      PrismAccount: new GuArnParameter(this, "PrismAccount", {
        description: "The ARN of the account in which Prism is running - looks like arn:aws:iam::<account-number>:root",
      }),
    };

    /*
     * This is the external prism role in each account which is used by prism to crawl data from that account.
     */
    const prismRole = new GuRole(this, "PrismRole", {
      description: "Role Prism uses to crawl resources in this account",
      assumedBy: new ArnPrincipal(parameters.PrismAccount.valueAsString),
    });

    this.overrideLogicalId(prismRole, {
      logicalId: "PrismRole",
      reason: "We override this to ensure that we do not replace the existing resource",
    });

    new Policy(this, "PrismPolicy", {
      policyName: "PrismCollection",
      roles: [prismRole],
      statements: [
        new PolicyStatement({
          effect: Effect.ALLOW,
          resources: ["*"],
          actions: [
            "ec2:Describe*",
            "iam:Get*",
            "iam:List*",
            "autoscaling:Describe*",
            "s3:ListAllMyBuckets",
            "s3:GetBucketLocation",
            "acm:ListCertificates",
            "acm:DescribeCertificate",
            "route53:List*",
            "route53:Get*",
            "elasticloadbalancing:Describe*",
            "lambda:ListFunctions",
            "lambda:ListTags",
            "rds:Describe*",
            "cloudformation:Describe*",
            "cloudformation:Get*",
          ],
        }),
      ],
    });

    new CfnOutput(this, "Role", {
      value: prismRole.roleArn,
      description: "Prism Role",
    });
  }
}
