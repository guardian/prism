# -*- coding: utf-8 -*-
lib = File.expand_path('../lib', __FILE__)
$LOAD_PATH.unshift(lib) unless $LOAD_PATH.include?(lib)
require 'marauder/marauder'

Gem::Specification.new do |s|
  s.name          = 'prism-marauder'
  s.version       = Marauder::VERSION
  s.summary       = "service locator based on prism"
  s.description   = "Command line tool to find services in Prism based on simple queries"
  s.authors       = ["Sébastien Cevey", "Simon Hildrew"]
  s.email         = 'seb@cine7.net'
  s.homepage      = 'https://github.com/guardian/prism/tree/main/marauder#readme'
  s.license       = 'GPL'

  s.files         = `git ls-files`.split($/).grep(%r{(bin|lib)/})
  s.executables   = s.files.grep(%r{^bin/}).map{ |f| File.basename(f) }
  s.require_paths = ["lib"]

  s.add_runtime_dependency "commander"
  s.add_runtime_dependency "net-ssh"
  s.add_runtime_dependency "httparty"
end
