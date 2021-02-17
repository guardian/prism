# Prism Infrastructure

This directory defines the components to be deployed to AWS defined as CDK stacks:
- [Prism](./lib/prism.ts) for the application
- [Prism Access](./lib/prism-access.ts) for the stack set that creates a role for Prism to assume in another account

## Useful commands

We follow the [`script/task`](https://github.com/github/scripts-to-rule-them-all) pattern,
find useful scripts within the [`script`](./script) directory for common tasks.

- `./script/setup` to install dependencies
- `./script/start` to run the Jest unit tests in watch mode
- `./script/lint` to lint the code using ESLint
- `./script/test` to lint, run tests and generate templates of the CDK stacks
- `./script/build` to compile TypeScript to JS and generate templates of the CDK stacks
- `./script/ci` to perform CI tasks: lint, build, test and generate templates

There are also some other commands defined in `package.json`, including:

- `yarn lint --fix` attempt to autofix any linter errors
- `yarn format` format the code using Prettier
- `yarn watch` watch for changes and compile

However, it's advised you configure your IDE to format on save to avoid horrible "correct linting" commits.
