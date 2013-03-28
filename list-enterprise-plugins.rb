#!/usr/bin/env ruby
#
# The MIT License
#
# Copyright (c) 2013, CloudBees, Inc., Stephen Connolly.
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in
# all copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
# THE SOFTWARE.
#

require 'rubygems'
require 'nokogiri'
require 'open-uri'

if !ARGV[0]
  puts "Please provide the path to the Jenkins Enterprise WAR pom.xml as the first and only argument"
  exit 1
end

Nokogiri::XML(File.open(ARGV[0])).remove_namespaces!.xpath("/project/dependencies/dependency").each do |dep|
  scope = dep.xpath("scope").first()
  type = dep.xpath("type").first()
  artifactId = dep.xpath("artifactId").first().content()
  version = dep.xpath("version").first().content()
  if scope && scope.content() == "provided" && type && type.content() == "hpi"
    puts "require(\"" + artifactId + "\",\"" + version + "\"),"
  end
end

