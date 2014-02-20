# Marauder (as in [Marauder's Map](http://harrypotter.wikia.com/wiki/Marauder's_Map))

Small command-line tool to locate services.

Uses the [Prism](http://prism.gutools.co.uk/) API to locate any service across the Guardian estate.

## Install

You need Ruby, bundler and rake.

Build the gem and install it:

```
$ bundle install
$ rake build
$ gem install pkg/marauder-0.6.1.gem
```

**Note**: If you are installing on a host that only has Ruby 1.8.7 then you should install httparty version 
0.11.0 using `gem install httparty -v 0.11.0` as later versions of httparty will not install successfully.

## Usage

For inline usage help, run `marauder help` or `marauder help <command>` for help on a specific sub command.

### List hosts

To find a service, just specify its name and the stage you're interested in, e.g.:

```
$ marauder hosts r2frontend qa
QA	r2frontend	10-252-163-240.gc2.dev.dc1.gnm	2013-05-06T05:18:35.000Z
QA	r2frontend	10-252-163-239.gc2.dev.dc1.gnm	2013-05-06T05:18:54.000Z
```

You can match names and stages using suffixes:

```
$ marauder hosts flex api rel
RELEASE	flexible-api	10-252-163-100.gc2.dev.dc1.gnm	2013-05-28T16:14:31.000Z
RELEASE	flexible-api	10-252-163-99.gc2.dev.dc1.gnm 	2013-05-28T16:14:45.000Z
```

All parameters (stage and name) are optional:

```
$ marauder hosts frontend router
PROD	frontend::router	ec2-54-220-181-89.eu-west-1.compute.amazonaws.com 	2013-11-06T17:38:39.000Z
PROD	frontend::router	ec2-54-228-78-179.eu-west-1.compute.amazonaws.com 	2013-11-06T17:46:55.000Z
CODE	frontend::router	ec2-54-216-101-152.eu-west-1.compute.amazonaws.com	2013-11-12T09:24:38.000Z
PROD	frontend::router	ec2-54-216-197-194.eu-west-1.compute.amazonaws.com	2013-11-06T17:40:48.000Z
```

All of the above examples use loose regex matching on the stage and mainclass of an instance. For
more control you can use the filter style documented on the 
[Prism homepage](http://prism.gutools.co.uk/). Any filter with an equals sign in it will be passed
straight onto Prism and behave as if you were using the API directly:

```
$ marauder hosts stage=PROD region=eu-west-1 createdAt~=2011-.*
PROD	arts_music           	ec2-79-125-89-233.eu-west-1.compute.amazonaws.com	2011-06-02T11:28:53.000Z
PROD	soulmates-scheduler  	ec2-79-125-101-87.eu-west-1.compute.amazonaws.com	2011-12-20T16:13:00.000Z
PROD	soulmatesadmin       	ec2-46-137-52-22.eu-west-1.compute.amazonaws.com 	2011-07-06T15:03:38.000Z
...
```

To list just the names of all matching hosts, use the `--short` option:

```
$ marauder hosts -s flex release
10-252-163-100.gc2.dev.dc1.gnm
10-252-163-99.gc2.dev.dc1.gnm
10-252-167-70.gc2.dev.dc1.gnm
10-252-167-71.gc2.dev.dc1.gnm
10-252-163-101.gc2.dev.dc1.gnm
10-252-163-102.gc2.dev.dc1.gnm
10-252-139-70.gc2.dev.dc1.gnm
10-252-139-71.gc2.dev.dc1.gnm
```

This can be useful to pipe into other commands

### SSH

To run a command on all matching hosts, use the `ssh` command, supplying the command to run using `-c` (you'll most likely need to quote your command in single quotes to avoid shell expansions):

```
$ marauder ssh flex api release -c 'uptime'
ssh into 2 hosts and run `uptime`...

== 10-252-163-100.gc2.dev.dc1.gnm ==
 12:46:24 up 27 days,  4:32,  0 users,  load average: 0.05, 0.09, 0.04

== 10-252-163-99.gc2.dev.dc1.gnm ==
 12:46:24 up 27 days,  4:32,  0 users,  load average: 0.04, 0.02, 0.00
```
