Prism API
=========

The Prism API provides near real-time information about infrastructure.

Entities
--------

Prism collects data on the following entities:

 - [Instances](https://prism.gutools.co.uk/instances)
 - [Security Groups](https://prism.gutools.co.uk/security-groups)
 - [Stages](https://prism.gutools.co.uk/stages)
 - [Stacks](https://prism.gutools.co.uk/stacks)
 - [Apps](https://prism.gutools.co.uk/apps)
 - [Buckets]((https://prism.gutools.co.uk/buckets)
 - [VPCs](https://prism.gutools.co.uk/vpcs)
 - [AWS Accounts](https://prism.gutools.co.uk/sources/accounts)

Endpoints
---------

The endpoints on Prism all have a similar structure for querying.

Fields that are prefixed with an `_` control the query format in some way - they are meta paramters. Fields that are not prefixed with an `_` are interpreted as field filters. Field filters are only useful when the endpoint returns an array of objects, and they are used to filter the objects that are returned. You simply add a query parameter with the same name as a top level field and the parameter value will be used to find matching objects.

### Some simple examples

Parameter | Description
--------- | -----------
`_brief` or `_brief=true` | return only the ID of objects in a list
`_length` or `_length=true` | add companion fields to objects detailing the size of any arrays that are members of the object
`stage=TEST` | return objects whose stage field has the value of TEST
`stage!=TEST`	| invert - return objects whose stage field does not have the value of TEST
`role~=id.*` | regexs - return objects whose role field matches the regular expression id.* (i.e. starts with id)
`role!~=id.*` | inverted regex - return objects whose role field does not match the regular expression
`stage=TEST&role~=id.*`	| multiple fields - an exact match of stage and a regex match of role; this returns objects whose stage field has value of TEST AND whose role field matches the regular expression
`stage=TEST&stage=CODE` | fields can be specified twice - here we return objects whose stage field has the value of TEST OR CODE
`stage!=TEST&stage!=CODE` | this makes no sense - here we return objects whose stage field has a value that is not TEST OR not CODE (i.e. all objects)
`tag.MongoReplSet=prodprv1` | Nested fields can be filtered using periods to separate the fields

Note that multiple filters for the same field have the sense of an OR operation and then the filters for each individual field have the sense of an AND operation.

Response format
---------------

A typical Prism response includes the following top level fields:

 - `status` - success or error from the API itself
 - `lastUpdated` - an ISO date indicating the time that the underlying sources were last crawled successfully
 - `stale` - whether prism believes that the data is stale based on the configured shelf life of sources
 - `staleSources` - a list of any sources that haven't been crawled successfully but may have contributed to the API response
 - `data` - the API response
 - `sources` - the list of sources that were interrogated to assemble the response

 
 Adding an AWS account to Prism
 ------------------------------

* Create a new stack with `cloudformation/prism-role.template`. In the AWS account requiring Prism. Give it a Stack Name of PrismAccess. When prompted add the Prism account parameter.  The format is `arn:aws:iam::<account-number>:root`, please ask a Guardian developer for the account-number.
* The output of the stack created is a Key named Role. Take the value of Role (`arn:aws:iam:[account-number]/PrismAccess-PrismRole-[code]`) and add it the spreadsheet listing all AWS Role values (ask a fellow Guardian developer for access to the sheet).
* In DeployTools AWS account go the `config-deploy` DynamoDB table. Select the App `prism`, Stage `PROD` item. Choose `Text` view. Details of the AWS account requiring prism will need to be added here. There are many examples to follow, the format of the name must be the `[aws-account-name].role` (we strongly recommend that this matches the short name used by janus) with the output Role value of the step above. Save and close.
* Re-deploy latest prism for the changes to be picked up.

Using prism from the command line
------------------------------
There is a [ruby gem](./marauder/README.md) that allows you to query the api from the command line.
