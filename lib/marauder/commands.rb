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

def table(rows)
  lengths = rows.map { |row| row.map { |value| value.size } }
  col_widths = lengths.transpose.map { |column| column.max }
  rows.map { |row| 
    col_widths.each_with_index.map { |width, index| 
      row[index].ljust(width)
    }.join("  ")
  }.join("\n")
end

def tokenize(s)
  separators = ['-', '_', '::']
  separators.inject([s]) do |tokens, sep|
    tokens.map {|t| t.split(sep)}.flatten
  end
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

###### COMMANDS ######

command :hosts do |c|
  c.description = 'Display only hostnames' 
  c.option '-s', '--short', 'Only return hostnames'
  c.action do |args, options|
    matching = find_hosts(args)
    if options.short 
      matching.each { |host| puts host['hostname'] }
    else
      puts table(matching.map { |host| [host['stage'], host['app'], host['hostname'], host['created_at']] })
    end
  end
end

command :ssh do |c|
  c.syntax = 'marauder ssh <filter>'
  c.description = 'Execute command on matching hosts' 
  c.option '-u', '--user STRING', String, 'Remote username'
  c.option '-c', '--cmd STRING', String, 'Command to execute (quote this if it contains a space)'
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
      exit 1 unless agree("Do you really want to SSH into #{matching.size} hosts?")
    end

    puts "ssh into #{matching.size} hosts as #{options.user} and run `#{cmd}`..."
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