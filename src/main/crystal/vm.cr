require "./ext"
require "./instr"

module LNCF
  class VM
    alias Primitive = String | Int32 | Nil
    alias VMMethod = VM, Array(Primitive), Hash(String, Primitive) -> Primitive

    getter stack = Deque(Primitive).new
    getter constants : Array(Primitive)
    getter methods : Hash(String, Hash(String, VMMethod))
    property depth = 0
    @ip = 0
    getter cur_io : IO? = nil

    def self.defined_method(bytecode : Bytes, registers : UInt16) : VMMethod
      ->(vm : VM, a : Array(Primitive), n : Hash(String, Primitive)) {
        vm.execute(bytecode, a)
        vm.stack.pop
      }
    end 

    def initialize(@constants, @methods)
    end

    def execute(bytecode : Bytes, registers : Array(Primitive))
      io = IO::Memory.new bytecode
      @cur_io = io
      depth_prefix = "  " * @depth
      instruction : Instr? = nil
      args = [] of UInt8
      remaining_args = 0
      loop do
        if !instruction.nil? && remaining_args == 0
          {% if flag?(:vm_verbose) %}
          puts "#{depth_prefix}#{@ip.to_s(16).rjust 4}|    R#{registers}  [#{stack.join "|"}|>"
          {% end %}
          puts "#{depth_prefix}#{@ip.to_s(16).rjust 4}| #{instruction.mnemonic.ljust 12, ' '}#{args.empty? ? "" : args}"
          arg_io = IO::Memory.new
          args.each { |i| arg_io.write_byte i }
          arg_io.rewind
          to_continue = instruction.run self, arg_io, registers
          break unless to_continue
          @cur_io = io
          instruction = nil
          args = [] of UInt8
        end
        b = io.read_byte.not_nil!
        if remaining_args > 0
          args << b
          remaining_args -= 1
          next
        end
        instruction = INSTR_MAP[b]
        @ip = io.pos - 1
        remaining_args = instruction.args.size
        next
      end
    end

    def jump_by(i : Int)
      @cur_io.not_nil!.seek(@ip + i)
    end

    def inspect(bytecode : Bytes)
      io = IO::Memory.new bytecode
      ip = 0
      instruction : Instr? = nil
      args = [] of UInt8
      remaining_args = 0
      loop do
        if !instruction.nil? && remaining_args == 0
          arg_type = instruction.args
          smart_args = args.map_with_index { |a, i|
            c = arg_type[i]
            if c == '#'
              @constants[a]
            else
              a
            end
          }
          puts "#{ip.to_s(16).rjust 4}| #{instruction.mnemonic.ljust 12, ' '}#{args.empty? ? "" : smart_args}"
          instruction = nil
          args = [] of UInt8
        end
        b = io.read_byte
        break if b.nil?
        if remaining_args > 0
          args << b
          remaining_args -= 1
          next
        end
        instruction = INSTR_MAP[b]
        ip = io.pos - 1
        remaining_args = instruction.args.size
        next
      end
    end

    def execute(bytecode : Bytes, registers : Array(String))
      execute(bytecode, registers.map &.as(Primitive))
    end
  end
end
