module LNCF
  class BytecodeFileReader
    MAGIC = "LNCFB"
    alias JavaEndian = IO::ByteFormat::BigEndian
    alias Header = {version: UInt32, meta: Hash(String, String)}

    record BytecodeFile,
      header : Header,
      const_pool : Array(VM::Primitive),
      defined : Hash(String, {Bytes, UInt16}),
      enums : Hash(String, EnumDefinition)

    enum EnumType
      Manual
      Functional
    end

    record EnumDefinition, type : EnumType, members : Array(String)

    def initialize(@io : IO)
    end
    
    private def read_utf : String
      @io.read_string(@io.read_bytes(UInt16, JavaEndian))
    end

    private def read(t : T.class) : T forall T
      @io.read_bytes(t, JavaEndian)
    end

    def read : BytecodeFile
      slice = Bytes.new(MAGIC.size)
      @io.read(slice)
      raise ArgumentError.new("Wrong magic") if slice != MAGIC.to_slice
      header_version = read UInt32
      header_meta_amount = read Int32
      header_meta = Hash(String, String).new
      header_meta_amount.times do
        meta_key = read_utf
        meta_val = read_utf
        header_meta[meta_key] = meta_val
      end
      const_amount = read UInt32
      constants = Array(VM::Primitive).new const_amount
      const_amount.times do
        const_type = @io.read_byte
        constants << case const_type
        when 1 then read_utf
        when 2 then read Int32
        else raise ArgumentError.new("Unknown const type #{const_type}")
        end
      end
      def_amount = read UInt32
      defined = Hash(String, {Bytes, UInt16}).new
      def_amount.times do
        def_name = read_utf
        def_registers = read UInt16
        def_bytecode = Bytes.new read UInt32
        @io.read(def_bytecode)
        defined[def_name] = {def_bytecode, def_registers}
      end
      enum_amount = read UInt32
      enums = Hash(String, EnumDefinition).new
      enum_amount.times do
        enum_type = EnumType.new (read UInt8).to_i32
        enum_name = read_utf
        enum_entries_amount = read UInt16
        enum_entries = Array(String).new
        enum_entries_amount.times do
          enum_entries << read_utf
        end
        enums[enum_name] = EnumDefinition.new enum_type, enum_entries
      end
      BytecodeFile.new(
        header: {
          version: header_version,
          meta: header_meta,
        },
        const_pool: constants,
        defined: defined,
        enums: enums
      )
    end
  end
end