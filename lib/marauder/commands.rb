#!/usr/bin/env ruby

# FIXME: use bundler?
require 'marauder/marauder'
require 'httparty'
require 'net/ssh'
require 'commander/import'

program :version, Marauder::VERSION
program :description, 'command-line tool to locate infrastructure'

API = 'https://riffraff.gutools.co.uk/api/deployinfo'
API_KEY='8TQMOsrLKeXhWaLmULvtj7wk7AZvtTxv' # 'marauder' key

# If you're sshing into too many hosts, you might be doing something wrong
MAX_SSH_HOSTS = 4
DEFAULT_USER = 'jetty'

# def usage
#   puts "usage: marauder <filter>"
#   puts "       marauder list <filter>"
#   puts "       marauder hosts <filter>"
#   puts "       marauder ssh <filter> -- <command>"
# end

# if ARGV.empty?
#   usage
#   exit
# end


def tokenize(s)
  separators = ['-', '_', '::']
  separators.inject([s]) do |tokens, sep|
    tokens.map {|t| t.split(sep)}.flatten
  end
end

def confirm
  print "Please confirm [yes/NO]: "
  STDIN.gets.chomp == 'yes'
end

def find_hosts(filter)
  query = filter.map(&:downcase).map{|s| Regexp.new("^#{s}.*")}

  data = HTTParty.get(API, :query => {:key => API_KEY})
  response = data["response"]

  stale = response["stale"]
  update_time = response["updateTime"]

  if stale
    STDERR.puts "WARNING: Riff-Raff reports that this deployinfo is stale, it was last updated at #{update_time}"
  end

  hosts = response["results"]["hosts"]

  hosts.select do |host|
    query.all? do |name|
      tokens = tokenize(host["app"]) + [host["app"], host["stage"]]
      tokens.any? {|token| name.match(token.downcase)}
    end
  end
end


command :list do |c|
  c.description = 'Display list of hosts' 
  c.option '--test STRING', String, 'Test a parameter'
  c.action do |args, options|
    matching = find_hosts(args)
    matching.each do |host|
      puts "#{host['stage']}\t#{host['app']}\t#{host['hostname']}\t#{host['created_at']}"
    end
  end
end

command :hosts do |c|
  c.description = 'Display only hostnames' 
  c.action do |args, options|
    matching = find_hosts(args)
    matching.each do |host|
      puts host['hostname']
    end
  end
end

command :ssh do |c|
  c.syntax = 'marauder ssh <filter> -- <command>'
  c.description = 'Execute command on matching hosts' 
  c.option '--user STRING', String, 'Remote username'
  c.option '--cmd STRING', String, 'Command to execute'
  c.action do |args, options|
    options.default :user => DEFAULT_USER

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
      puts "Do you really want to SSH into #{matching.size} hosts?"
      exit 1 unless confirm
    end

    puts "ssh into #{matching.size} hosts and run `#{cmd}`..."
    puts

    matching.each do |host|
      Net::SSH.start(host['hostname'], options.user) do |ssh|
        puts "== #{host['hostname']} =="
        puts ssh.exec!(cmd)
        puts
        end
      end
    end
  end
end

default_command :list