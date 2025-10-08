/**
 * The config-deploy table contains Prism configuration for each stage.
 * The format is HOCON as DynamoDb's JSON format.
 * This script scans the table, unmarshalls each row from DynamoDb JSON to JSON, and writes the result to `config/*.conf`.
 * These files have then been copied to S3 to support https://github.com/guardian/prism/pull/1051.
 * The S3 bucket can be found in the SSM Parameter `/account/services/private.config.bucket`.
 *
 * @usage:
 * ```bash
 * npm run dynamo-download
 * ```
 */

import fs from 'node:fs';
import { DynamoDB, ScanCommand } from '@aws-sdk/client-dynamodb';
import { unmarshall } from '@aws-sdk/util-dynamodb';

const client = new DynamoDB({ profile: 'deployTools', region: 'eu-west-1' });
const scanCommand = new ScanCommand({ TableName: 'config-deploy' });

interface Record {
	App: string;
	Stage: string;
	Config: unknown;
}

void client.send(scanCommand).then(({ Items = [] }) => {
	Items.forEach((item) => {
		const { Stage, Config } = unmarshall(item) as Record;
		fs.writeFileSync(`config/${Stage}.conf`, JSON.stringify(Config, null, 2));
	});
});
