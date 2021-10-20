module LNCF::Lib
  extend self

  def map_characters(s : String, a : String, b : String, *, limit : Int32 = 0, reverse : Int32 = 0) : String
    s = s.reverse if reverse != 0
    ret = apply_char_map(s, make_char_map(a, b), limit: limit)
    ret = ret.reverse if reverse != 0
    ret
  end

  def make_char_map(a : String, b : String) : Hash(Char, Char)
    map = Hash(Char, Char).new
    a.each_char_with_index do |char, index|
      map[char] = b[index]
    end
    map
  end

  def apply_char_map(s : String, map : Hash(Char, Char), *, limit = 0) : String
    subs_made = 0
    s.gsub { |c| 
      d = map[c]?
      unless d.nil?
        subs_made += 1
        next d if limit == 0 || subs_made <= limit
      end
      c
    }
  end

  def has_match(s : String, pattern : String) : Int32
    s.matches?(Regex.new(pattern)) ? 1 : 0
  end

  def match_count(s : String, pattern : String) : Int32
    s.scan(Regex.new(pattern)).size
  end

  def replace(s : String, a : String, b : String) : String
    s.gsub a, b
  end

  def op_append(a : String, b : String) : String
    a + b
  end

  def op_char(a : String, index : Int32) : String
    a[index].to_s
  end

  macro auto_lib(method)
    {% m = Lib.methods.find {|i| i.name == method.id} %}
    {% named_args = false %}
    ->(vm : ::LNCF::VM, a : Array(::LNCF::VM::Primitive), n : Hash(String, ::LNCF::VM::Primitive)) {
      puts "(native method Lib.{{ method.id }})"
      ::LNCF::Lib.{{ method.id }}(
        {% for arg, index in m.args %}
          {% if arg.name.empty? %}
            {% named_args = true %}
          {% else %}
            {% if named_args %}
              {{arg.name.id}}: (n[{{arg.name.stringify}}]?.try &.as({{ arg.restriction }}) || {{arg.default_value}}),
            {% else %}
              a[{{index}}].as({{ arg.restriction }}),
            {% end %}
          {% end %}
        {% end %}
      ).as ::LNCF::VM::Primitive
    }
  end      
end