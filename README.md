# Marauder (as in [Marauder's Map](http://harrypotter.wikia.com/wiki/Marauder's_Map))

Small command-line tool to locate services.

Uses the [RiffRaff's host deploy info](https://riffraff.gutools.co.uk/deployinfo/hosts)
API to locate any service deployed via RiffRaff.

## Install

Obviously, you need Ruby.

Build the gem and install it:

```
$ bundle install
$ gem build marauder.gemspec
$ gem install marauder-0.1.0.gem
```

## Usage

To find a service, just specify its name and the stage you're interested in, e.g.:

```
$ marauder r2frontend qa
QA      r2frontend      10-252-163-240.gc2.dev.dc1.gnm  Mon May 06 05:18:35 UTC 2013
QA      r2frontend      10-252-163-239.gc2.dev.dc1.gnm  Mon May 06 05:18:54 UTC 2013
```

You can match names and stages using suffixes:

```
$ marauder flex api rel
RELEASE flexible-api    10-252-163-100.gc2.dev.dc1.gnm  Tue May 28 16:14:31 UTC 2013
RELEASE flexible-api    10-252-163-99.gc2.dev.dc1.gnm   Tue May 28 16:14:45 UTC 2013
```

All parameters (stage and name) are optional:

```
$ marauder frontend interactive
PROD    frontend::interactive   ec2-54-217-106-20.eu-west-1.compute.amazonaws.com       Tue Sep 17 15:03:57 UTC 2013
CODE    frontend::interactive   ec2-54-217-185-66.eu-west-1.compute.amazonaws.com       Tue Sep 17 22:37:58 UTC 2013
PROD    frontend::interactive   ec2-46-137-138-116.eu-west-1.compute.amazonaws.com      Wed Sep 18 02:52:51 UTC 2013
PROD    frontend::interactive   ec2-54-217-32-255.eu-west-1.compute.amazonaws.com       Wed Sep 18 02:52:52 UTC 2013
```
