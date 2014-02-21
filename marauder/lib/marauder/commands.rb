#!/usr/bin/env ruby

# FIXME: use bundler?
require 'marauder/marauder'
require 'httparty'
require 'net/ssh'
require 'commander/import'
require 'yaml'

program :version, Marauder::VERSION
program :description, 'command-line tool to locate infrastructure'

# Load config file
CONFIG_FILE = "#{ENV['HOME']}/.config/marauder/defaults.yaml"

if File.exists?(CONFIG_FILE)
  config = YAML.load_file(CONFIG_FILE)
else
  STDERR.puts "Well that doesn't look right..."
  STDERR.puts "  ... prism-marauder now requires a configuration file which tells it how to connect to Prism"
  STDERR.puts
  STDERR.puts "You need a file at #{CONFIG_FILE} that at a very minimum contains:"
  STDERR.puts "    ---"
  STDERR.puts "    prism-url: http://<prism-host>"
  STDERR.puts
  STDERR.puts "Good luck on your quest"
  raise ArgumentError, "Missing configuration file"
end

PRISM_URL = config['prism-url']

class Api 
  include HTTParty
  #debug_output $stderr
  disable_rails_query_string_format
end

# If you're sshing into too many hosts, you might be doing something wrong
MAX_SSH_HOSTS = 4

LOGGED_IN_USER = ENV['USER']

def table(rows)
  lengths = rows.map { |row| row.map { |value| value.nil? ? 0 : value.size } }
  col_widths = lengths.transpose.map { |column| column.max }
  rows.map { |row| 
    col_widths.each_with_index.map { |width, index| 
      (row[index] || "").ljust(width)
    }.join("\t")
  }.join("\n")
end

def tokenize(s)
  separators = ['-', '_', '::']
  separators.inject([s]) do |tokens, sep|
    tokens.map {|t| t.split(sep)}.flatten
  end
end

def prism_query(path, filter)
  prism_filters = filter.select{ |f| f =~ /=/ }

  api_query = Hash[prism_filters.map { |f|
    param = f.split('=')
    [param[0], param[1]]
  }.group_by { |pair| 
    pair[0] 
  }.map { |key, kvs| 
    [key, kvs.map{|v| v[1]}]
  }]

  data = Api.get("#{PRISM_URL}#{path}", :query => {:_expand => true}.merge(api_query))

  if data["stale"]
    update_time = data["lastUpdated"]
    STDERR.puts "WARNING: Prism reports that the data returned from #{path} is stale, it was last updated at #{update_time}"
  end

  data
end

def token_filter(things_to_filter, filter)
  dumb_filters = filter.reject{ |f| f =~ /=/ }
  query = dumb_filters.map(&:downcase).map{|s| Regexp.new("^#{s}.*")}

  things_to_filter.select do |thing|
    query.all? do |phrase|
      tokens = yield thing
      tokens.compact.any? {|token| phrase.match(token.downcase)}
    end
  end
end

def find_hosts(filter)
  find_instances(filter) + find_hardware(filter)
end

def find_instances(filter)
  data = prism_query('/instances', filter)
  hosts = data["data"]["instances"]
  token_filter(hosts, filter){ |host|
    host["mainclasses"].map{|mc| tokenize(mc)}.flatten + host["mainclasses"] + [host["stage"], host["stack"]] + host["app"]
  }
end

def find_hardware(filter)
  data = prism_query('/hardware', filter)
  hardware = data["data"]["hardware"]
  token_filter(hardware, filter){ |h| [h["dnsName"], h["stage"], h["stack"]] + h["app"] }
end

def user_for_host(hostname)
  Net::SSH.configuration_for(hostname)[:user]
end

def display_results(matching, short, noun)
  if matching.empty?
      STDERR.puts "No #{noun} found"
    else
      if short 
        matching.each { |host| puts host['dnsName'] }
      else
        puts table(matching.map { |host|
          app = host['app'].join(',')
          app = host['mainclasses'].join(',') if app.length == 0
          [host['stage'], host['stack'], app, host['dnsName'], host['createdAt']] 
        })
      end
    end
end

###### COMMANDS ######

command :hosts do |c|
  c.description = 'List all hosts (hardware or instances) that match the search filter' 
  c.syntax = 'marauder hosts <filter>'
  c.option '-s', '--short', 'Only return hostnames'
  c.action do |args, options|
    display_results(find_hosts(args), options.short, 'hosts')
  end
end

command :instances do |c|
  c.description = 'List instances that match the search filter' 
  c.syntax = 'marauder instances <filter>'
  c.option '-s', '--short', 'Only return hostnames'
  c.action do |args, options|
    display_results(find_instances(args), options.short, 'instances')
  end
end  

command :hardware do |c|
  c.description = 'List hardware that matches the search filter' 
  c.syntax = 'marauder hardware <filter>'
  c.option '-s', '--short', 'Only return hostnames'
  c.action do |args, options|
    display_results(find_hardware(args), options.short, 'hardware')
  end
end   

command :ssh do |c|
  c.syntax = 'marauder ssh <filter>'
  c.description = 'Execute command on matching hosts' 
  c.option '-u', '--user STRING', String, 'Remote username'
  c.option '-c', '--cmd STRING', String, 'Command to execute (quote this if it contains a space)'
  c.action do |args, options|

    STDERR.puts "#{args}"

    query = args.take_while {|s| s != '--'}
    cmd = options.cmd

    STDERR.puts "Query: #{query}"
    STDERR.puts "Command: #{cmd}"

    matching = find_hosts(query)

    if cmd.nil?
      puts "Please provide a command."
      usage
      exit 1
    else if matching.size > MAX_SSH_HOSTS
      exit 1 unless agree("Do you really want to SSH into #{matching.size} hosts?")
    end

    puts "ssh into #{matching.size} hosts and run `#{cmd}`..."
    puts

    matching.each do |host|
      hostname = host['dnsName']
      user = options.user || user_for_host(hostname) || LOGGED_IN_USER
      Net::SSH.start(hostname, user) do |ssh|
        puts "== #{hostname} as #{user} =="
        puts ssh.exec!(cmd)
        puts
        end
      end
    end
  end
end

default_command :hosts
