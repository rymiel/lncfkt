class IO::Memory
  def b : UInt8
    read_byte.not_nil!
  end
end