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


def usage
  puts "usage: marauder <filter>"
  puts "       marauder list <filter>"
  puts "       marauder hosts <filter>"
  puts "       marauder ssh <filter> -- <command>"
end


if ARGV.empty?
  usage
  exit
end

action, *remaining =
  case ARGV[0]
  when 'ssh', 'list', 'hosts'
    ARGV
  else # default
    ['list', *ARGV]
  end

query, command =
  if remaining.include?('--')
    [remaining.take_while {|s| s != '--'},
     remaining.drop_while {|s| s != '--'}.drop(1).join(' ')]
  else
    [remaining]
  end

query.map!(&:downcase).map! {|s| Regexp.new("^#{s}.*")}

# TODO: allow customising the user, maybe 'ssh --user <foo> ...'
user = DEFAULT_USER


data = HTTParty.get(API, :query => {:key => API_KEY})
response = data["response"]

stale = response["stale"]
update_time = response["updateTime"]

if stale
  STDERR.puts "WARNING: Riff-Raff reports that this deployinfo is stale, it was last updated at #{update_time}"
end

hosts = response["results"]["hosts"]

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

matching = hosts.select do |host|
  query.all? do |name|
    tokens = tokenize(host["app"]) + [host["app"], host["stage"]]
    tokens.any? {|token| name.match(token.downcase)}
  end
end

case action
when 'list'
  matching.each do |host|
    puts "#{host['stage']}\t#{host['app']}\t#{host['hostname']}\t#{host['created_at']}"
  end

when 'hosts'
  matching.each do |host|
    puts host['hostname']
  end

when 'ssh'
  if command.nil?
    puts "Please provide a command."
    usage
    exit 1
  else if matching.size > MAX_SSH_HOSTS
    puts "Do you really want to SSH into #{matching.size} hosts?"
    exit 1 unless confirm
  end

  puts "ssh into #{matching.size} hosts and run `#{command}`..."
  puts

  matching.each do |host|
    Net::SSH.start(host['hostname'], user) do |ssh|
      puts "== #{host['hostname']} =="
      puts ssh.exec!(command)
      puts
      end
    end
  end
end
