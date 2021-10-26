package space.rymiel.lncf

import org.antlr.v4.runtime.CommonToken
import space.rymiel.lncf.Instr.*
import java.awt.SystemColor.text
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
  Noop    (0x00u),
  Ret     (0xfcu),
  LdConst (0x01u),
  LdReg   (0x02u),
  LdReg0  (0x40u),
  StReg   (0x03u),
  StReg0  (0x60u),
  I8      (0x20u),
  Imm0    (0x80u),
  Imm1    (0x81u),
  Call    (0x10u),
  CallKw  (0x11u),
  Jump    (0x15u),
  Jz      (0x16u),
  Jnz     (0x17u),
  TestEq  (0x31u),
  TestGt  (0x32u),
  TestLt  (0x33u),
}

open class Line(val instr: Instr, val args: ByteArray = byteArrayOf(), var next: Line? = null, var branch: Line? = null) {
  override fun toString(): String {
    val branchHash = if (branch != null) " => ${branch.inspectHash()}" else ""
    return "${inspectHash()} ${instr.toString().padEnd(6)} ${args.contentToString()}$branchHash"
  }
}
class PositionedLine(val pos: SinglePos, instr: Instr, args: ByteArray = byteArrayOf(), next: Line? = null, branch: Line? = null) : Line(instr, args, next, branch)
class LineStub(val real: Line, val instr: Instr, val args: ByteArray = byteArrayOf()) {
  override fun toString(): String {
    return ":{$instr ${args.contentToString()}${real.inspectHash()} -> ${real.next.inspectHash()} => ${real.branch.inspectHash()}}"
  }
}

class VirtualMachineFunction(
  val registers: Int,
  val bytecode: ByteArray,
  val parameterMapping: List<Pair<String, Primitive<*>?>>
)

class VirtualMachineCompiler {
  val constants = mutableListOf<Primitive<*>>()
  val globals = mutableMapOf<String, Int>()
  val declared = mutableMapOf<String, VirtualMachineFunction>()
  val enums = mutableMapOf<String, EnumDefinition>()
  val classifiers = mutableMapOf<String, Classifier>()
  var current: Line? = null
  var currentEntrypoint: Line? = null
  var knownDefs = listOf<Definition>()
  private var context: FnDefinition? = null
  private var inControl = false
  private var inFunctional = false

  fun compile(defs: List<Definition>) {
    this.knownDefs = defs
    defs.filterIsInstance<GlobalDefinition>().forEach { compile(it) }
    defs.filterIsInstance<EnumDefinition>().forEach { compile(it) }
    defs.filter { it !is GlobalDefinition && it !is EnumDefinition }.forEach { compile(it) }
  }

  fun compile(def: Definition) {
    when (def) {
      is GlobalDefinition -> {
        globals[def.name] = allocateConstant(def.value)
      }
      is FnDefinition -> {
        topLevelGuard(def)
      }
      is EnumDefinition -> enums[def.name] = def
      is ClassificationDefinition -> println(def)
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
    s.writeShort(declared.size)
    declared.forEach { (k, v) ->
      s.writeUTF(k)
      s.writeInt(v.registers)
      s.writeShort(v.bytecode.size)
      s.write(v.bytecode)
    }
    s.writeShort(enums.size)
    enums.forEach { (k, v) ->
      s.writeByte(v.type.ordinal)
      s.writeUTF(k)
      s.writeShort(v.entries.size)
      v.entries.forEach(s::writeUTF)
    }
    file.writeBytes(b.toByteArray())
  }

  fun topLevelGuard(fn: FnDefinition) {
    this.current = null
    this.context = fn
    compileBody(fn.body)
    opcode(Ret)
    val res = currentEntrypoint!!
    // res.prettyPrint(fn)
    Optimizer(res).optimizeBody()
    res.prettyPrint(fn)
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
          autoCall(it.name.namespace, it.name.qualifier, argSize, it.args.named.size, it.pos)
        }
        is IfElseCall -> {
          val fallthrough = Line(Noop)
          val elseMarker = if (it.elseBody == null) fallthrough else Line(Noop)
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
          autoStoreReg(context!!.args.indexOf(it.name), pos = it.pos)
        }
        is ReturnCall -> {
          autoConst(it.value)
          opcode(Ret, pos = it.pos)
        }
        else -> println("Couldn't parse body of type ${it::class} $it")
      }
    }
    return
  }

  fun resolveConditional(c: Conditional, endMarker: Line, elseMarker: Line) {
    val bodyMarker = Line(Noop)
    val fallthrough = Line(Noop)
    resolveClause(c.clause, fallthrough, bodyMarker, elseMarker)
    newLine(Line(Jz, ByteArray(2), null, fallthrough))
    newLine(bodyMarker)
    compileBody(c.body)
    newLine(Line(Jump, ByteArray(2), null, endMarker))
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
          "or" -> newLine(Line(Jnz, byteArrayOf(-1, -1), null, bodyMarker))
          else -> {}
        }
        resolveClause(clause.b, fallthrough, bodyMarker, elseMarker)
        when (clause.operation) {
          "=" -> opcode(TestEq)
          ">" -> opcode(TestGt)
          "<" -> opcode(TestLt)
          "or" -> {}
          else -> TODO("Unknown binary operation ${clause.operation} in $clause")
        }
      }
      is FunctionalCallClause -> {
        val previousInControl = inControl
        val previousInFunctional = inFunctional
        inControl = true
        inFunctional = true
        compileBody(listOf(FnCall(clause.function, Arguments(clause.args), NULL_TOKEN)))
        inControl = previousInControl
        inFunctional = previousInFunctional
      }
    }
  }

  fun newLine(line: Line) {
    if (this.current != null) this.current!!.next = line
    else this.currentEntrypoint = line
    this.current = line
  }

  fun opcode(i: Instr) {
    newLine(Line(i))
  }

  fun opcode(i: Instr, pos: SinglePos) {
    if (pos === NULL_SINGLE_POS || pos.token === NULL_TOKEN) return opcode(i)
    newLine(PositionedLine(pos, i))
  }

  fun opcode(i: Instr, vararg a: Int) {
    newLine(Line(i, a.toList().map { it.toByte() }.toByteArray()))
  }

  fun opcode(i: Instr, vararg a: Int, pos: SinglePos) {
    if (pos === NULL_SINGLE_POS || pos.token === NULL_TOKEN) return opcode(i, *a)
    newLine(PositionedLine(pos, i, a.toList().map { it.toByte() }.toByteArray()))
  }

  fun autoConst(vRaw: Literal) {
    var v = vRaw
    if (v is Constant<*> && v.value is Boolean) {
      v = Constant(if (v.value as Boolean) 1 else 0, v.token)
    }
    if (v is Constant<*> && v.value is Int) {
      when (v.value) {
        0 -> opcode(Imm0, pos = v.pos)
        1 -> opcode(Imm1, pos = v.pos)
        else -> {
          val value = v.value as Int
          if (value > 127 || value < 0) {
            val thisConst = allocateConstant(v)
            this.opcode(LdConst, thisConst, pos = v.pos)
          } else {
            this.opcode(I8, value, pos = v.pos)
          }
        }
      }
    } else {
      val thisConst = allocateConstant(v)
      this.opcode(LdConst, thisConst, pos = v.pos)
    }
  }

  fun autoConst(v: String) {
    this.autoConst(Constant(v, NULL_TOKEN))
  }

  fun autoConst(v: LiteralLike) {
    when (v) {
      is Constant<*> -> this.autoConst(v)
      is Global -> this.opcode(LdConst, globals[v.name]!!, pos = v.pos)
      is PassedIndexArgument -> this.autoLoadReg(v.index - 1 + this.context!!.args.size, pos = v.pos)
      is PassedNamedArgument -> this.autoLoadReg(this.context!!.args.indexOf(v.name), pos = v.pos)
      is CallLiteral -> {
        val previousInControl = inControl
        val previousInFunctional = inFunctional
        inControl = true
        inFunctional = true
        compileBody(listOf(v.call))
        inControl = previousInControl
        inFunctional = previousInFunctional
      }
    }
  }

  fun autoLoadReg(i: Int, pos: SinglePos) {
    if (i == 0) opcode(LdReg0, pos = pos)
    else this.opcode(LdReg, i, pos = pos)
  }

  fun autoStoreReg(i: Int, pos: SinglePos) {
    if (i == 0) opcode(StReg0, pos = pos)
    else this.opcode(StReg, i, pos = pos)
  }

  fun autoCall(namespace: String, qualifier: String, args: Int, kwargs: Int, pos: SinglePos) {
    val identifier = "${namespace}:${qualifier}"
    if (kwargs == 0) {
      this.opcode(
        Call,
        allocateConstant(identifier),
        args,
        pos = pos
      )
    } else {
      this.opcode(
        CallKw,
        allocateConstant(identifier),
        args, kwargs,
        pos = pos
      )
    }
  }

  fun allocateConstant(value: String): Int {
    return allocateConstant(Constant(value, CommonToken(0)))
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

  fun Literal.constexpr(): Constant<*> {
    return when (this) {
      is Constant<*> -> this
    }
  }

  fun Clause.constexpr(): Constant<*> {
    return when (this) {
      is LiteralClause -> {
        this.literal.constexpr()
      }
      is BinaryClause -> {
        when (this.operation) {
          ".." -> {
            val a = this.a.constexpr().value as String
            val b = this.b.constexpr().value as String
            Constant(a + b, NULL_TOKEN)
          }
          else -> throw IllegalStateException("Couldn't identify operation in clause $text")
        }
      }
      else -> throw IllegalStateException("Non-constant clause $this ${this::class}")
    }
  }

  fun LiteralLike.constexpr(): Constant<*> {
    return when (this) {
      is Literal -> this.constexpr()
      is ComplexLiteral -> this.clause.constexpr()
      is Global -> (knownDefs.find { it.name == this.name } as GlobalDefinition).value as Constant<*>
      else -> throw IllegalStateException("Non-constant literal-like $this ${this::class}")
    }
  }
}

private fun Line.prettyPrint(fn: FnDefinition) {
  println("===" + fn.name + "===")
  val lines = this.flatten().lines
  val labels = mutableSetOf<Int>()
  lines.forEach {
    if (it.real.branch != null) {
      labels.add(it.real.branch.hashCode())
    }
  }
  lines.forEach {
    val hash = it.real.hashCode()
    val branchHash = it.real.branch?.hashCode()
    val matchingLabel = if (hash in labels) "L" + labels.indexOf(hash).toString() + ":" else ""
    val matchingJump = if (branchHash in labels) "#L" + labels.indexOf(branchHash).toString() else ""
    val relevantArgs = if (branchHash != null) it.args.drop(2) else it.args.toList()
    val args = relevantArgs.joinToString(", ")
    val underlying = if (it.real is PositionedLine) {
      "| " + GRAY + ITALIC + it.real.pos.toString().padEnd(12) + RESET
    } else {
      "| " + " ".repeat(12) // it.real.toString().padEnd(50)
    }
    println("$underlying| ${matchingLabel.padEnd(6)}${it.instr.toString().padEnd(8)} $args${if (args.isNotEmpty()) "\t" else ""}$matchingJump")
  }
  println("===" + fn.name + "===")
}