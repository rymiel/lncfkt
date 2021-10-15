module LNCF
  class BytecodeFileReader
    MAGIC = "LNCFB"
    alias JavaEndian = IO::ByteFormat::BigEndian
    alias Header = {version: UInt32, meta: Hash(String, String)}
    alias BytecodeFile = {
      header: Header,
      const_pool: Array(VM::Primitive),
      defined: Hash(String, {Bytes, UInt32})
    }

    def initialize(@io : IO)
    end

    def read : BytecodeFile
      slice = Bytes.new(MAGIC.size)
      @io.read(slice)
      raise ArgumentError.new("Wrong magic") if slice != MAGIC.to_slice
      header_version = @io.read_bytes(UInt32, JavaEndian)
      header_meta_amount = @io.read_bytes(Int32, JavaEndian)
      header_meta = Hash(String, String).new
      header_meta_amount.times do
        meta_key = @io.read_string(@io.read_bytes(UInt16, JavaEndian))
        meta_val = @io.read_string(@io.read_bytes(UInt16, JavaEndian))
        header_meta[meta_key] = meta_val
      end
      const_amount = @io.read_bytes(UInt32, JavaEndian)
      constants = Array(VM::Primitive).new const_amount
      const_amount.times do
        const_type = @io.read_byte
        constants << case const_type
        when 1 then @io.read_string(@io.read_bytes(UInt16, JavaEndian))
        when 2 then @io.read_bytes(Int32, JavaEndian)
        else raise ArgumentError.new("Unknown const type #{const_type}")
        end
      end
      def_amount = @io.read_bytes(UInt32, JavaEndian)
      defined = Hash(String, {Bytes, UInt32}).new
      def_amount.times do
        def_name = @io.read_string(@io.read_bytes(UInt16, JavaEndian))
        def_registers = @io.read_bytes(UInt32, JavaEndian)
        def_bytecode = Bytes.new @io.read_bytes(UInt32, JavaEndian)
        @io.read(def_bytecode)
        defined[def_name] = {def_bytecode, def_registers}
      end
      {
        header: {
          version: header_version,
          meta: header_meta
        },
        const_pool: constants,
        defined: defined
      }
    end
  end
end