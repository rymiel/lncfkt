require "io/hexdump"
require "benchmark"
require "./lib"
require "./bytecode_file_reader"
require "./vm"

br = LNCF::BytecodeFileReader.new(IO::Hexdump.new(File.open("test.lncfb"), output: STDERR, read: true)).read
c = br[:const_pool]
d = br[:defined]
h = br[:header]

puts "Bytecode version: 0x#{h[:version].to_s(16).rjust 4, '0'}"
puts "Bytecode meta:"
pp h[:meta]
puts
pp c.map_with_index { |j, i| {i, j} }.to_h
puts

vm = LNCF::VM.new c, {
  "lib" => {
    "map_characters" => LNCF::Lib.auto_lib(map_characters),
    "has_match" => LNCF::Lib.auto_lib(has_match),
    "match_count" => LNCF::Lib.auto_lib(match_count),
    "replace" => LNCF::Lib.auto_lib(replace)
  },
  "op" => {
    "append" => LNCF::Lib.auto_lib(op_append),
    "char" => LNCF::Lib.auto_lib(op_char)
  },
  "local" => d.transform_values { |i| LNCF::VM.defined_method(*i) }
}

d.each { |k, v|
  puts "#{k}:"
  vm.inspect(v[0])
}

puts
puts "Executing"
puts
vm.execute(d[ARGV[0]][0], ARGV[1..])
pp! vm.stack
