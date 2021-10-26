require "io/hexdump"
require "benchmark"
require "./lib"
require "./bytecode"
require "./vm"

br = LNCF::Bytecode.new(IO::Hexdump.new(File.open("test.lncfb"), output: STDERR, read: true)).read
c = br.const_pool
d = br.defined
h = br.header

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
pp br.enums
puts
pp br.classifiers
puts

if ARGV[0]? == "run"
  puts
  puts "Executing"
  puts
  vm.execute(d[ARGV[1]][0], ARGV[2..])
  pp! vm.stack
elsif ARGV[0]? == "apply"
  word = ARGV[1]
  puts ">> #{word}"
  manual_enums = Hash(LNCF::Bytecode::EnumDefinition, {String, Int32}).new
  br.enums.select{ |k, v| v.type.manual? }.each do |k, v|
    puts "Manual: #{k}: #{v.members}"
    print "0..#{v.members.size - 1} >> "
    opt = gets.not_nil!.chomp
    idx = v.members.index opt
    idx = opt.to_i if idx.nil?
    manual_enums[v] = {k, idx}
  end
  classified = Hash(LNCF::Bytecode::Classification, String).new
  br.classifiers.each do |k, v|
    manual_enums.each do |ek, ev|
      if ek == v.enum_key && ev[1] == v.ordinal
        puts "will classify #{k} because #{ev[0]} is #{v.enum_val}"
        v.body.each do |ck, cv|
          next unless classified[v]?.nil?
          if cv.operation == "suffix"
            pattern = Regex.new cv.params.as(String)
            classified[v] = ck if word.ends_with? pattern
          end
        end
        puts "  classified as #{classified[v]}"
      end
    end
  end
end
