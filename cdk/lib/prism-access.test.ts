import { App } from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { PrismAccess } from './prism-access';

describe('The PrismAccess stack', () => {
	it('matches the snapshot', () => {
		const app = new App();
		const stack = new PrismAccess(app, 'prism-access');

		expect(Template.fromStack(stack).toJSON()).toMatchSnapshot();
	});
});
