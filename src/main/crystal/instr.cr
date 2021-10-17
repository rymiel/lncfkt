class LNCF::VM
  abstract class Instr
    getter mnemonic : String
    getter args : String

    def initialize(@mnemonic : String, @args : String)
    end

    def initialize
      initialize("")
    end

    abstract def run(vm : VM, args : IO::Memory, regs : Array(Primitive)): Bool
  end

  INSTR_MAP = {} of UInt8 => Instr

  macro instr(class_name, mnemonic, opcode, args = "")
    class {{class_name}}Instr < Instr
      def initialize
        super({{mnemonic.stringify}}, {{args}})
      end

      def run(vm : VM, args : IO::Memory, regs : Array(Primitive)): Bool
        {{yield}}
        return true
      end
    end

    INSTR_MAP[{{opcode}}_u8] = {{class_name}}Instr.new
  end


  macro instr_contextual(class_name, *props)
    class {{class_name}}Instr < Instr
      def initialize(mnemonic, args, *, {{ props.map { |p| "@#{p}"}.join(", ").id }})
        super(mnemonic, args)
      end

      def run(vm : VM, args : IO::Memory, regs : Array(Primitive)): Bool
        {{yield}}
        return true
      end
    end
  end

  macro instr_known(class_name, mnemonic, opcode, args, *props)
    INSTR_MAP[{{opcode}}_u8] = {{class_name}}Instr.new({{mnemonic.stringify}}, {{args}}, {{ props.map { |a| "#{a.target}: #{a.value}"}.join(", ").id }})
  end

  instr_contextual LoadReg, index : Int32? = nil do
    index = @index
    index = args.b if index.nil?
    vm.stack << regs[index]
  end
  instr_contextual StoreReg, index : Int32? = nil do
    index = @index
    index = args.b if index.nil?
    regs[index] = vm.stack.pop
  end
  instr_contextual Call, kwargs : Bool = false do
    m_id = vm.constants[args.b].as String
    m_namespace, m_qualifier = m_id.split(':', 2)
    m_args = [] of Primitive
    m_named_args = {} of String => Primitive

    m_arg_count = args.b
    if @kwargs
      m_named_arg_count = args.b

      m_named_arg_count.times do
        val = vm.stack.pop
        key = vm.stack.pop.as String
        m_named_args[key] = val
      end
    end

    m_arg_count.times do
      m_args << vm.stack.pop
    end
    m_args.reverse!

    puts ">> #{m_id} >>"
    # pp! m_args, m_named_args
    vm.depth += 1
    m_retval = vm.methods[m_namespace][m_qualifier].call(vm, m_args, m_named_args)
    vm.depth -= 1
    vm.stack << m_retval
  end
  instr_contextual ImmInteger, i : Int32 do
    vm.stack << @i
  end

  instr Noop, Noop, 0x00
  instr Return, Ret, 0xfc do
    return false
  end

  instr LoadConstant, LdConst, 0x01, "#" do
    vm.stack << vm.constants[args.b]
  end

  instr JumpTemp, Jump, 0x15, "$$" do
    jump_by = args.read_bytes(UInt16, IO::ByteFormat::BigEndian)
    puts "Jumping forward 0x#{jump_by.to_s 16} bytes"
    vm.jump_by jump_by
  end

  instr JumpTempZ, Jz, 0x16, "$$" do
    jump_by = args.read_bytes(UInt16, IO::ByteFormat::BigEndian)
    value = vm.stack.pop
    if (value == 0)
      puts "Jumping forward 0x#{jump_by.to_s 16} bytes"
      vm.jump_by jump_by
    end
  end

  instr JumpTempNZ, Jnz, 0x17, "$$" do
    jump_by = args.read_bytes(UInt16, IO::ByteFormat::BigEndian)
    value = vm.stack.pop
    if (value != 0)
      puts "Jumping forward 0x#{jump_by.to_s 16} bytes"
      vm.jump_by jump_by
    end
  end

  instr TestTempEQ, TestEq, 0x31, "" do
    b = vm.stack.pop
    a = vm.stack.pop
    result = (a == b) ? 1 : 0
    vm.stack << result
  end

  instr TestTempGT, TestGt, 0x32, "" do
    b = vm.stack.pop.as Int
    a = vm.stack.pop.as Int
    result = (a > b) ? 1 : 0
    vm.stack << result
  end

  instr TestTempLT, TestLt, 0x33, "" do
    b = vm.stack.pop.as Int
    a = vm.stack.pop.as Int
    result = (a < b) ? 1 : 0
    vm.stack << result
  end

  instr_known LoadReg, LdReg,  0x02, "%"
  instr_known LoadReg, LdReg0, 0x40, "", index = 0

  instr_known StoreReg, StReg,  0x03, "%"
  instr_known StoreReg, StReg0, 0x60, "", index = 0

  instr_known Call, Call,   0x10, "#$"
  instr_known Call, CallKw, 0x11, "#$$", kwargs = true

  instr_known ImmInteger, Imm0, 0x80, "", i = 0
  instr_known ImmInteger, Imm1, 0x81, "", i = 1
end