import { App } from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { Prism } from './prism';

describe('The PrismEc2App stack', () => {
	it('matches the snapshot', () => {
		const app = new App();
		const stack = new Prism(app, 'prism', {
			stage: 'PROD',
			domainName: 'prism.gutools.co.uk',
			minimumInstances: 2,
		});
		expect(Template.fromStack(stack).toJSON()).toMatchSnapshot();
	});
});
