Prism API
=========

The Prism API provides near real-time information about infrastructure.

Entities
--------

Prism collects data on the following entities:

 - Instances (AWS and Openstack)
 - Hardware (physical servers - by virtue of data from a CMDB or similar)
 - Security Groups
 - Stages
 - Stacks
 - Apps

Endpoints
---------

The endpoints on Prism all have a similar structure for querying.

Fields that are prefixed with an `_` control the query format in some way - they are meta paramters. Fields that are not prefixed with an `_` are interpreted as field filters. Field filters are only useful when the endpoint returns an array of objects, and they are used to filter the objects that are returned. You simply add a query parameter with the same name as a top level field and the parameter value will be used to find matching objects.

### Some simple examples

Parameter | Description
--------- | -----------
`_expand` or `_expand=true` | return all fields of objects in a list
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

An example response can be seen below:

```json
{
  "status":"success",
  "lastUpdated":"2014-03-27T10:30:06.033Z",
  "stale":false,
  "staleSources":[],
  "data": {
    "instances":[{ 
      "id":"arn:openstack:ec2:dc1:websys:instance/i-00000ba0",
      "meta":{
        "href":"http://prism.gutools.co.uk/instances/arn:openstack:ec2:dc1:websys:instance%2Fi-00000ba0",
        "origin":{
          "vendor":"openstack",
          "accountName":"websys@dc1",
          "region":"dc1",
          "tenant":"websys"
        }
      }
    },
    {
      "id":"arn:openstack:ec2:dc1:websys:instance/i-00000b7f",
      "meta":{
        "href":"http://prism.gutools.co.uk/instances/arn:openstack:ec2:dc1:websys:instance%2Fi-00000b7f",
        "origin":{
          "vendor":"openstack",
          "accountName":"websys@dc1",
          "region":"dc1",
          "tenant":"websys"
        }
      }
    }]
  },
  "sources":[{
    "resource":"instance",
    "origin":{
      "vendor":"openstack",
      "accountName":"websys@dc1",
      "region":"dc1",
      "tenant":"websys"
    },
    "status":"success",
    "createdAt":"2014-03-27T10:30:06.033Z",
    "age":35,
    "stale":false
  }]
}
```
