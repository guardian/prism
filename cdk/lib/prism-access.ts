import { ArnPrincipal, Effect, Policy, PolicyStatement } from "@aws-cdk/aws-iam";
import type { App } from "@aws-cdk/core";
import { CfnOutput } from "@aws-cdk/core";
import type { GuStackProps } from "@guardian/cdk/lib/constructs/core";
import { GuArnParameter, GuStack } from "@guardian/cdk/lib/constructs/core";
import { GuRole } from "@guardian/cdk/lib/constructs/iam";

export class PrismAccess extends GuStack {
  constructor(scope: App, id: string, props: GuStackProps) {
    super(scope, id, props);

    const parameters = {
      PrismAccount: new GuArnParameter(this, "PrismAccount", {
        description: "The ARN of the account in which Prism is running - looks like arn:aws:iam::<account-number>:root",
      }),
    };

    const prismRole = new GuRole(this, "PrismRole", {
      assumedBy: new ArnPrincipal(parameters.PrismAccount.valueAsString),
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
