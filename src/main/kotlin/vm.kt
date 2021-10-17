package space.rymiel.lncf

import space.rymiel.lncf.Instr.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

fun Any?.inspectHash(): String {
  return "%08X".format(this.hashCode())
}

sealed class Primitive<T> {
  abstract val value: T
}

data class Int32Primitive(override val value: Int): Primitive<Int>() {
  override fun toString(): String {
    return "${value}_i32"
  }
}
data class StringPrimitive(override val value: String): Primitive<String>() {
  override fun toString(): String {
    return "\"$value\""
  }
}

enum class Instr(val opcode: UByte) {
  NOOP     (0x00u),
  RET      (0xfcu),
  LD_CONST (0x01u),
  LD_REG   (0x02u),
  LD_REG_0 (0x40u),
  ST_REG   (0x03u),
  ST_REG_0 (0x60u),
  I8_IMM   (0x20u),
  IMM_0    (0x80u),
  IMM_1    (0x81u),
  CALL     (0x10u),
  CALL_KW  (0x11u),
  JUMP     (0x15u),
  JZ       (0x16u),
  JNZ      (0x17u),
  TEST_EQ  (0x31u),
  TEST_GT  (0x32u),
  TEST_LT  (0x33u),
}

class Line(val instr: Instr, val args: ByteArray = byteArrayOf(), var next: Line? = null, var branch: Line? = null) {
  override fun toString(): String {
    return "{$instr ${args.contentToString()}} ${inspectHash()} ->"
  }
}
data class LineStub(val real: Line, val instr: Instr, val args: ByteArray = byteArrayOf()) {
  override fun toString(): String {
    return ":{$instr ${args.contentToString()}${real.inspectHash()} -> ${real.next.inspectHash()} => ${real.branch.inspectHash()}}"
  }
}
data class FlatLineContainer(val lines: MutableList<LineStub>, val unresolved: MutableList<Line>)

sealed class VirtualMachineMethod(open val parameterMapping: List<Pair<String, Primitive<*>?>>)
data class VirtualMachineFunction(
  val registers: Int,
  val bytecode: ByteArray,
  override val parameterMapping: List<Pair<String, Primitive<*>?>>
): VirtualMachineMethod(parameterMapping)

class VirtualMachineCompiler {
  val constants = mutableListOf<Primitive<*>>()
  val globals = mutableMapOf<String, Int>()
  val declared = mutableMapOf<String, VirtualMachineFunction>()
  var current: Line? = null
  var currentEntrypoint: Line? = null
  var knownDefs = listOf<Definition>()
  private var context: FnDefinition? = null
  private var inControl = false
  private var inFunctional = false

  fun compile(defs: List<Definition>) {
    this.knownDefs = defs
    defs.filterIsInstance<GlobalDefinition>().forEach { compile(it) }
    defs.filter { it !is GlobalDefinition }.forEach { compile(it) }
  }

  fun compile(def: Definition) {
    when (def) {
      is GlobalDefinition -> {
        globals[def.name] = allocateConstant(def.value)
      }
      is FnDefinition -> {
        topLevelGuard(def)
      }
    }
  }

  fun export(file: File) {
    val b = ByteArrayOutputStream()
    val s = DataOutputStream(b)
    s.writeBytes("LNCFB")
    s.writeInt(0x0100)
    val meta = mapOf(
      "com" to (singleton::class.java.getPackage().implementationTitle ?: "LNCFKT-in-IDE"),
      "comver" to (singleton::class.java.getPackage().implementationVersion ?: "Unknown")
    )
    s.writeInt(meta.size)
    meta.forEach { (k, v) ->
      s.writeUTF(k)
      s.writeUTF(v)
    } 
    s.writeInt(constants.size)
    constants.forEach {
      when (it) {
        is StringPrimitive -> {
          s.writeByte(0x01)
          s.writeUTF(it.value)
        }
        is Int32Primitive -> {
          s.writeByte(0x02)
          s.writeInt(it.value)
        }
      }
    }
    s.writeInt(declared.size)
    declared.forEach { (k, v) ->
      s.writeUTF(k)
      s.writeInt(v.registers)
      s.writeInt(v.bytecode.size)
      s.write(v.bytecode)
    }
    file.writeBytes(b.toByteArray())
  }

  fun topLevelGuard(fn: FnDefinition) {
    this.current = null
    this.context = fn
    compileBody(fn.body)
    opcode(RET)
    val res = currentEntrypoint!!
    optimizeBody(res)
    println("===" + fn.name + "===")
    res.flatten().lines.forEach {
      println("${it.instr} ${it.args.contentToString()}")
    }
    println("===" + fn.name + "===")
    declared[fn.name] = VirtualMachineFunction(0, res.toByteArray(), listOf())
  }

  fun compileBody(body: List<Call>) {
    body.forEach {
      when (it) {
        is FnCall -> {
          it.args.positional.forEach { a ->
            autoConst(a)
          }
          it.args.named.forEach { (k, v) ->
            autoConst(k)
            autoConst(v)
          }
          val argSize = it.args.positional.size
          autoCall(it.name.namespace, it.name.qualifier, argSize, it.args.named.size)
        }
        is IfElseCall -> {
          val fallthrough = Line(NOOP)
          val elseMarker = if (it.elseBody == null) fallthrough else Line(NOOP)
          it.clauses.forEach { c ->
            println(c.clause)
            resolveConditional(c, fallthrough, elseMarker)
            println("*")
          }
          if (it.elseBody != null) {
            newLine(elseMarker)
            compileBody(it.elseBody)
            newLine(fallthrough)
          } else {
            newLine(fallthrough)
          }
        }
        is SetCall -> {
          compileBody(listOf(it.body))
          autoStoreReg(context!!.args.indexOf(it.name))
        }
        is ReturnCall -> {
          autoConst(it.value)
          opcode(RET)
        }
//        is MacroCall -> {
//          // TODO: THIS IS VERY TEMPORARY
//          opcode(LD_REG_0)
//        }
        else -> println("Couldn't parse body of type ${it::class} $it")
      }
    }
    return
  }

  fun resolveConditional(c: Conditional, endMarker: Line, elseMarker: Line) {
    val bodyMarker = Line(NOOP)
    val fallthrough = Line(NOOP)
    resolveClause(c.clause, fallthrough, bodyMarker, elseMarker)
    newLine(Line(JZ, byteArrayOf(-1, -1), null, fallthrough))
    newLine(bodyMarker)
    compileBody(c.body)
    newLine(Line(JUMP, byteArrayOf(-1, -1), null, endMarker))
    newLine(fallthrough)
  }

  fun resolveClause(clause: Clause, fallthrough: Line, bodyMarker: Line, elseMarker: Line) {
    when (clause) {
      is LiteralClause -> {
        when (val lit = clause.literal) {
          is Global -> TODO("Unknown implicit call $lit")
          is Constant<*> -> autoConst(lit)
          else -> TODO("Unknown literal in if clause $lit")
        }
      }
      is BinaryClause -> {
        resolveClause(clause.a, fallthrough, bodyMarker, elseMarker)
        when (clause.operation) {
          "or" -> newLine(Line(JNZ, byteArrayOf(-1, -1), null, bodyMarker))
          else -> {}
        }
        resolveClause(clause.b, fallthrough, bodyMarker, elseMarker)
        when (clause.operation) {
          "=" -> opcode(TEST_EQ)
          ">" -> opcode(TEST_GT)
          "<" -> opcode(TEST_LT)
          "or" -> {}
          else -> TODO("Unknown binary operation ${clause.operation} in $clause")
        }
      }
      is FunctionalCallClause -> {
        val previousInControl = inControl
        val previousInFunctional = inFunctional
        inControl = true
        inFunctional = true
        compileBody(listOf(FnCall(clause.function, Arguments(clause.args))))
        inControl = previousInControl
        inFunctional = previousInFunctional
      }
    }
  }

  fun optimizeBody(line: Line): Line {
    println("Begin optimization:")
    while (true) {
      val before = line.toByteArray()

      println("B ${line.toByteArray().contentToString()}")
      optimizeUselessJump(line)
      optimizeInlineNoopTargets(line)

      val optimized = line.toByteArray()

      if (before.contentEquals(optimized)) {
        println("o ${line.toByteArray().contentToString()}")
        return line
      }
    }
  }

  private fun optimizeUselessJump(line: Line) {
    var i = line
    var previous: Line? = null
    while (true) {
      if (i.instr == JUMP || i.instr == JZ || i.instr == JNZ)
        if (i.next === i.branch && previous != null)
          previous.next = i.next
      previous = i
      if (i.next == null) break
      i = i.next!!
    }
  }

  private fun optimizeInlineNoopTargets(line: Line) {
    var i = line
    var candidate: Line? = null
    var replacement: Line? = null
    while (true) {
      if (i.instr == NOOP) {
        candidate = i
        replacement = i.next
        break
      }
      if (i.next == null) break
      i = i.next!!
    }
    if (candidate != null) {
      var j = line
      while (true) {
        if (j.branch === candidate) j.branch = replacement
        if (j.next === candidate) j.next = replacement
        if (j.next == null) break
        j = j.next!!
      }
    }
  }

  fun Line.toByteArray(): ByteArray {
    return this.flatten().resolve()
  }

  fun FlatLineContainer.resolve(): ByteArray {
    val flat = mutableListOf<Byte>()
    val positions = mutableMapOf<Line, Int>()
    val pending = mutableMapOf<Line, MutableList<Int>>()
    // println("Resolving $this")
    this.lines.forEach {
      // println("${it.real.inspectHash()}$it")
      if (it.real in pending) {
        // println("Applying pending")
        pending[it.real]!!.forEach { pendingTo ->
          val newPos = posToTwoBytes(flat.size - pendingTo + 1)
          // println(newPos)
          // println(flat)
          flat[pendingTo] = newPos[0]
          flat[pendingTo+1] = newPos[1]
          // println(flat)
        }
      }
      if (it.real in this.unresolved) {
        // println("Unresolved")
        val b = it.real.branch!!
        if (b in positions) {
          // println("Found previous position")
          flat.add(it.instr.opcode.toByte())
          flat.addAll(posToTwoBytes(flat.size - positions[b]!!))
        } else {
          // println("Added as pending")
          flat.add(it.instr.opcode.toByte())
          pending.computeIfAbsent(b) { mutableListOf() }.add(flat.size)
          // println(pending.mapKeys { x -> x.inspectHash() })
          flat.addAll(it.args.toList())
        }
      } else {
        flat.add(it.instr.opcode.toByte())
        flat.addAll(it.args.toList())
      }
    }
    return flat.toByteArray()
  }

  fun posToTwoBytes(i : Int): List<Byte> {
    return listOf(
      ((i shr 8) and 0xFF).toByte(),
      (i and 0xFF).toByte(),
    )
  }

  fun Line.flatten(f: FlatLineContainer) {
    f.lines.add(LineStub(this, this.instr, this.args))
    if (this.next != null) {
      this.next!!.flatten(f)
    }
    if (this.branch != null) {
      f.unresolved.add(this)
    }
  }

  fun Line.flatten(): FlatLineContainer {
    val flattener = FlatLineContainer(mutableListOf(), mutableListOf())
    this.flatten(flattener)
    return flattener
  }

  fun newLine(line: Line) {
    if (this.current != null) this.current!!.next = line
    else this.currentEntrypoint = line
    this.current = line
  }

  fun opcode(i: Instr, vararg a: Byte) {
    newLine(Line(i, a))
  }

  fun opcode(i: Instr) {
    newLine(Line(i))
  }

  fun opcode(i: Instr, vararg a: Int) {
    newLine(Line(i, a.toList().map { it.toByte() }.toByteArray()))
  }

  fun autoConst(vRaw: Literal) {
    var v = vRaw
    if (v is Constant<*> && v.value is Boolean) {
      v = Constant(if (v.value as Boolean) 1 else 0)
    }
    if (v is Constant<*> && v.value is Int) {
      when (v.value) {
        0 -> opcode(IMM_0)
        1 -> opcode(IMM_1)
        else -> this.opcode(I8_IMM, v.value as Int)
      }
    } else {
      val thisConst = allocateConstant(v)
      this.opcode(LD_CONST, thisConst)
    }
  }

  fun autoConst(v: String) {
    this.autoConst(Constant(v))
  }

  fun autoConst(v: LiteralLike) {
    when (v) {
      is Constant<*> -> this.autoConst(v)
      is Global -> this.opcode(LD_CONST, globals[v.name]!!)
      is PassedIndexArgument -> this.autoLoadReg(v.index - 1 + this.context!!.args.size)
      is PassedNamedArgument -> this.autoLoadReg(this.context!!.args.indexOf(v.name))
      else -> println("Couldn't parse argument of type ${v::class} $v")
    }
  }

  fun autoLoadReg(i: Int) {
    if (i == 0) opcode(LD_REG_0)
    else this.opcode(LD_REG, i)
  }

  fun autoStoreReg(i: Int) {
    if (i == 0) opcode(ST_REG_0)
    else this.opcode(ST_REG, i)
  }

  fun autoCall(namespace: String, qualifier: String, args: Int, kwargs: Int) {
    val identifier = "${namespace}:${qualifier}"
    if (kwargs == 0) {
      this.opcode(
        CALL,
        allocateConstant(identifier),
        args,
      )
    } else {
      this.opcode(
        CALL_KW,
        allocateConstant(identifier),
        args, kwargs
      )
    }
  }

  fun allocateConstant(value: String): Int {
    return allocateConstant(Constant(value))
  }

  fun allocateConstant(value: Literal): Int {
    if (value is Constant<*>) {
      val const = when (value.value) {
        is String -> StringPrimitive(value.value)
        is Int -> Int32Primitive(value.value)
        is Boolean -> Int32Primitive(if (value.value) 1 else 0)
        else -> TODO("${value.value}")
      }
      val existing = constants.indexOf(const)
      return if (existing == -1) {
        constants.add(const)
        constants.size - 1
      } else {
        existing
      }
    } else {
      TODO()
    }
  }
}