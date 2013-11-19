#!/usr/bin/env ruby

# FIXME: use bundler?
require 'marauder/marauder'
require 'httparty'
require 'net/ssh'
require 'commander/import'

program :version, Marauder::VERSION
program :description, 'command-line tool to locate infrastructure'

API = 'http://prism.gutools.co.uk/instances'

# If you're sshing into too many hosts, you might be doing something wrong
MAX_SSH_HOSTS = 4

LOGGED_IN_USER = ENV['USER']

def table(rows)
  lengths = rows.map { |row| row.map { |value| value.size } }
  col_widths = lengths.transpose.map { |column| column.max }
  rows.map { |row| 
    col_widths.each_with_index.map { |width, index| 
      row[index].ljust(width)
    }.join("\t")
  }.join("\n")
end

def tokenize(s)
  separators = ['-', '_', '::']
  separators.inject([s]) do |tokens, sep|
    tokens.map {|t| t.split(sep)}.flatten
  end
end

def find_hosts(filter)
  prism_filters = filter.select{ |f| f =~ /=/ }
  api_query = Hash[prism_filters.map { |f|
    param = f.split('=')
    [param[0], param[1]]
  }]

  data = HTTParty.get(API, :query => {:_expand => true}.merge(api_query))

  if data["stale"]
    update_time = data["lastUpdated"]
    STDERR.puts "WARNING: Prism reports that this deployinfo is stale, it was last updated at #{update_time}"
  end

  hosts = data["data"]["instances"]

  dumb_filters = filter.reject{ |f| f =~ /=/ }
  query = dumb_filters.map(&:downcase).map{|s| Regexp.new("^#{s}.*")}

  hosts.select do |host|
    query.all? do |name|
      tokens = host["mainclasses"].map{|mc| tokenize(mc)}.flatten + host["mainclasses"] + [host["stage"]]
      tokens.any? {|token| name.match(token.downcase)}
    end
  end
end

def user_for_host(hostname)
  Net::SSH.configuration_for(hostname)[:user]
end

###### COMMANDS ######

command :hosts do |c|
  c.description = 'List hosts that match the search filter' 
  c.syntax = 'marauder hosts <filter>'
  c.option '-s', '--short', 'Only return hostnames'
  c.action do |args, options|
    matching = find_hosts(args)
    if matching.empty?
      STDERR.puts "No hosts found"
    else
      if options.short 
        matching.each { |host| puts host['dnsName'] }
      else
        puts table(matching.map { |host| [host['stage'], host['mainclasses'].join(','), host['dnsName'], host['createdAt']] })
      end
    end
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
