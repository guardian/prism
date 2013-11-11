# -*- coding: utf-8 -*-
lib = File.expand_path('../lib', __FILE__)
$LOAD_PATH.unshift(lib) unless $LOAD_PATH.include?(lib)
require 'marauder/marauder'

Gem::Specification.new do |s|
  s.name          = 'marauder'
  s.version       = Marauder::VERSION
  s.summary       = "service locator based on riffraff"
  s.description   = "Small tool to find services based on simple queries"
  s.authors       = ["Sébastien Cevey"]
  s.email         = 'seb@cine7.net'
  s.homepage      = 'https://github.com/guardian/tools/tree/master/marauder#readme'
  s.license       = 'GPL'

  s.files         = `git ls-files`.split($/).grep(%r{(bin|lib)/})
  s.executables   = s.files.grep(%r{^bin/}).map{ |f| File.basename(f) }
  s.require_paths = ["lib"]

  s.add_runtime_dependency "commander"
  s.add_runtime_dependency "net-ssh"
  s.add_runtime_dependency "httparty"
end
