package space.rymiel.lncf

import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.Token

data class Context(val definitions: List<DefinitionStub>, val current: DefinitionStub)
data class DefinitionStub(val name: String, val type: DefinitionType) {
  enum class DefinitionType { FN, FLOW, GLOBAL, UNDEFINED }
}

data class SinglePos(val token: Token) {
  override fun toString(): String {
    return "@=${token.line}:${token.charPositionInLine + 1}>${token.stopIndex - token.startIndex + 1}"
  }
}
val NULL_TOKEN = CommonToken(0)
val NULL_SINGLE_POS = SinglePos(NULL_TOKEN)

data class Identifier(val namespace: String, val qualifier: String) {
  companion object {
    operator fun invoke(id: String): Identifier {
      val split = id.split(":")
      if (split.size == 1) return Identifier("local", id)
      return Identifier(split.first(), split.last())
    }
  }
}

sealed class LiteralLike(open val pos: SinglePos)

sealed class Literal(override val pos: SinglePos) : LiteralLike(pos)
data class Constant<T>(val value: T, val token: Token): Literal(SinglePos(token))
data class Global(val name: String, val token: Token): LiteralLike(SinglePos(token))
data class PassedIndexArgument(val index: Int, val token: Token): LiteralLike(SinglePos(token))
data class PassedNamedArgument(val name: String, val token: Token): LiteralLike(SinglePos(token))
data class CallLiteral(val call: Call, val token: Token): LiteralLike(SinglePos(token))
data class ComplexLiteral(val clause: Clause, val token: Token): LiteralLike(SinglePos(token))

data class Arguments(val positional: List<LiteralLike> = listOf(), val named: Map<String, LiteralLike> = mapOf())

sealed class Call(open val pos: SinglePos)
data class FnCall(val name: Identifier, val args: Arguments, val token: Token) : Call(SinglePos(token))
data class IfElseCall(val clauses: List<Conditional>, val elseBody: List<Call>?, val token: Token) : Call(SinglePos(token))
data class MacroCall(val name: Identifier, val body: List<MacroBody>, val token: Token) : Call(SinglePos(token))
data class SetCall(val name: String, val body: Call, val token: Token) : Call(SinglePos(token))
data class ReturnCall(val value: LiteralLike, val token: Token) : Call(SinglePos(token))
@Deprecated("unimplemented")
data class UnimplementedCall(val msg: String = "UNIMPLEMENTED!") : Call(NULL_SINGLE_POS)

sealed class MacroBody
data class ArgumentMacroBody(val argument: LiteralLike, val name: String?) : MacroBody()
data class CallMacroBody(val call: Call) : MacroBody()
data class RecursiveMacroBody(val next: List<MacroBody>) : MacroBody()

sealed class Definition(open val name: String)
data class GlobalDefinition(override val name: String, val value: Literal): Definition(name)
data class FnDefinition(override val name: String, val args: List<String>, val body: List<Call>): Definition(name)
data class EnumDefinition(override val name: String, val type: EnumType, val entries: List<String>): Definition(name)
data class ClassificationDefinition(override val name: String, val key: String, val whereValue: String, val classifications: Map<String, Classifier>): Definition(name)
@Deprecated("unimplemented")
data class UnimplementedDefinition(val msg: String = "UNIMPLEMENTED!") : Definition("?")

enum class EnumType { MANUAL, FUNCTIONAL }

data class Conditional(val clause: Clause, val body: List<Call>)

sealed class Classifier(open val method: String)
data class LiteralClassifier(override val method: String, val value: LiteralLike): Classifier(method)
data class CompoundClassifier(override val method: String, val members: List<Classifier>): Classifier(method)

sealed class Clause
data class LiteralClause(val literal: LiteralLike) : Clause()
data class BinaryClause(val operation: String, val a: Clause, val b: Clause) : Clause()
data class FunctionalCallClause(val function: Identifier, val args: List<LiteralLike>) : Clause()

fun readArgs(a: List<LNCFParser.ArgumentContext>, ctx: Context): Arguments {
  val pos = mutableListOf<LiteralLike>()
  val named = mutableMapOf<String, LiteralLike>()
  a.forEach {
    when (it) {
      is LNCFParser.PositionalContext -> pos.add(it.literal_like().transform(ctx))
      is LNCFParser.NamedContext -> named[it.WORD().text] = it.literal_like().transform(ctx)
    }
  }
  return Arguments(pos, named)
}

fun LNCFParser.Macro_memberContext.transform(ctx: Context): MacroBody {
  return when (this) {
    is LNCFParser.ArgumentMacroMemberContext -> {
      when (val a = this.argument()) {
        is LNCFParser.PositionalContext -> ArgumentMacroBody(a.literal_like().transform(ctx), null)
        is LNCFParser.NamedContext -> ArgumentMacroBody(a.literal_like().transform(ctx), a.WORD().text)
        else -> throw IllegalStateException("Unknown macro member argument $a ${a::class}")
      }
    }
    is LNCFParser.CallMacroMemberContext -> CallMacroBody(this.call().transform(ctx))
    is LNCFParser.RecursiveMacroMemberContext -> RecursiveMacroBody(this.macro_body().d.map { it.transform(ctx) })
    else -> throw IllegalStateException("Unknown macro member $this ${this::class}")
  }
}

fun LNCFParser.ClauseContext.transform(ctx: Context): Clause {
  return when (this) {
    is LNCFParser.LiteralClauseContext -> {
      val l = this.literal_like().transform(ctx)
      var r: Clause = LiteralClause(l)
      if (l is Global) {
        val implicit = ctx.definitions.find { it.name == l.name }
        if (implicit != null && implicit.type == DefinitionStub.DefinitionType.FN && ctx.current.type == DefinitionStub.DefinitionType.FLOW) {
          r = FunctionalCallClause(Identifier(l.name), listOf(PassedNamedArgument("word", l.token)))
        }
      }
      r
    }
    is LNCFParser.BooleanClauseContext -> BinaryClause(this.OR().text, this.clause(0).transform(ctx), this.clause(1).transform(ctx))
    is LNCFParser.ComparisonClauseContext -> {
      val operation = when {
        this.EQ() != null -> this.EQ().text
        this.GT() != null -> this.GT().text
        this.LT() != null -> this.LT().text
        else -> throw IllegalStateException("Couldn't identify operation in clause $text")
      }
      BinaryClause(operation, this.clause(0).transform(ctx), this.clause(1).transform(ctx))
    }
    is LNCFParser.FunctionCallClauseContext -> FunctionalCallClause(this.fn.transform(), this.d.map { it.transform(ctx) })
    is LNCFParser.OperativeClauseContext -> {
      val operation = when {
        this.CONCAT() != null -> this.CONCAT().text
        else -> throw IllegalStateException("Couldn't identify operation in clause $text")
      }
      BinaryClause(operation, this.clause(0).transform(ctx), this.clause(1).transform(ctx))
    }
    else -> throw IllegalStateException("Unknown clause $this ${this::class}")
  }
}

fun LNCFParser.LiteralContext.transform(): Literal {
  return when (this) {
    is LNCFParser.StringContext -> Constant(this.STRING().text.dropLast(1).drop(1), this.STRING().symbol)
    is LNCFParser.IntContext -> Constant(this.INT().text.toInt(), this.INT().symbol)
    is LNCFParser.BooleanContext -> Constant(this.TRUE() != null, (this.TRUE() ?: this.FALSE()).symbol)
    else -> throw IllegalStateException("Unknown literal $this ${this::class}")
  }
}

fun LNCFParser.Literal_likeContext.transform(ctx: Context): LiteralLike {
  return when (this) {
    is LNCFParser.ActualLiteralContext -> this.literal().transform()
    is LNCFParser.GlobalContext -> Global(this.WORD().text, this.WORD().symbol)
    is LNCFParser.PassedArgumentContext -> {
      return when (val a = this.passed_argument()) {
        is LNCFParser.NamedPassedArgumentContext -> PassedNamedArgument(a.NAME_ARG().text.substring(1), a.NAME_ARG().symbol)
        is LNCFParser.PositionalPassedArgumentContext -> PassedIndexArgument(a.POS_ARG().text.substring(1).toInt(), a.POS_ARG().symbol)
        else -> throw IllegalStateException("No known passed argument $this ${this::class}")
      }
    }
    is LNCFParser.CallLiteralContext -> CallLiteral(this.call().transform(ctx), this.call().start)
    is LNCFParser.ComplexLiteralContext -> ComplexLiteral(this.clause().transform(ctx), this.clause().start)
    else -> throw IllegalStateException("Unknown literal-like $this ${this::class}")
  }
}

fun LNCFParser.IdentifierContext.transform(): Identifier {
  return when (this) {
    is LNCFParser.NamespacedContext -> Identifier(ns.text, qualifier.text)
    is LNCFParser.ImplicitContext -> Identifier("op", qualifier.text)
    is LNCFParser.LocalContext -> Identifier("local", qualifier.text)
    else -> throw IllegalStateException("No known identifier $this ${this::class}")
  }
}

fun LNCFParser.CallContext.transform(ctx: Context): Call {
  return when (this) {
    is LNCFParser.FlowCallContext -> {
      val call = functional_call()
      if (ctx.current.type == DefinitionStub.DefinitionType.FLOW) {
        val args = readArgs(call.d, ctx)
        val dummy = call.BEGIN()?.symbol ?: call.getStop()
        return SetCall("word",
          FnCall(
            call.identifier().transform(),
            Arguments(listOf(PassedNamedArgument("word", dummy)) + args.positional, args.named), dummy), dummy)
      }
      return FnCall(call.identifier().transform(), readArgs(call.d, ctx), call.start)
    }
    is LNCFParser.FnCallContext -> {
      val call = functional_call()
      return FnCall(call.identifier().transform(), readArgs(call.d, ctx), call.start)
    }
    is LNCFParser.IfElseCallContext -> {
      val ifElse = if_else_call()
      val clauses = ifElse.if_clauses.map {
        it.transform(ctx)
      }
      val bodies = ifElse.if_bodies.map {
        it.d.map { call -> call.transform(ctx) }
      }
      val conditions = clauses.zip(bodies) { clause, body ->
        Conditional(clause, body)
      }
      val elseBody = ifElse.else_body?.d?.map { it.transform(ctx) }
      return IfElseCall(conditions, elseBody, ifElse.start)
    }
    is LNCFParser.MacroCallContext -> {
      val m = macro_call()
      MacroCall(m.identifier().transform(), m.macro_body().d.map { it.transform(ctx) }, m.start)
    }
    is LNCFParser.SetCallContext -> SetCall(WORD().text, call().transform(ctx), start)
    is LNCFParser.ReturnCallContext -> ReturnCall(literal_like().transform(ctx), start)
    else -> UnimplementedCall("call $this ${this::class}")
  }
}

fun LNCFParser.Enum_definitionContext.transform(type: EnumType): EnumDefinition {
  return EnumDefinition(this.name.text, type, this.d.map { it.text })
}

fun LNCFParser.ClassifierContext.transform(ctx: Context): Classifier {
  return when (this) {
    is LNCFParser.LiteralClassifierContext -> LiteralClassifier(this.WORD().text, this.literal_like().transform(ctx))
    is LNCFParser.CompoundClassifierContext -> CompoundClassifier(this.WORD()?.text ?: "include", this.compound_classifier().d.map { it.transform(ctx) })
    else -> throw IllegalStateException("No known classifier $this ${this::class}")
  }
}

fun LNCFParser.Classification_definitionContext.transform(ctx: Context): ClassificationDefinition {
  val body = mutableMapOf<String, Classifier>()
  classification_body().d.forEach { body[it.k.text] = it.v.transform(ctx) }
  return ClassificationDefinition(name.text, k.text, v.text, body)
}

fun LNCFParser.DefinitionContext.stub(): DefinitionStub {
  return when (this) {
    is LNCFParser.GlobalDefinitionContext -> DefinitionStub(this.global_definition().WORD().text, DefinitionStub.DefinitionType.GLOBAL)
    is LNCFParser.FlowDefinitionContext -> DefinitionStub(this.functional_definition().name.text, DefinitionStub.DefinitionType.FLOW)
    is LNCFParser.FnDefinitionContext -> DefinitionStub(this.functional_definition().name.text, DefinitionStub.DefinitionType.FN)
    else -> DefinitionStub("?", DefinitionStub.DefinitionType.UNDEFINED)
  }
}

fun LNCFParser.DefinitionContext.transform(ctx: Context): Definition {
  return when (this) {
    is LNCFParser.GlobalDefinitionContext -> {
      val global = global_definition()
      GlobalDefinition(global.WORD().text, global.literal().transform())
    }
    is LNCFParser.FlowDefinitionContext -> {
      val flow = functional_definition()

      val args = listOf("word") + flow.args.map { it.text }
      val body = flow.body().d.map { it.transform(ctx) } + ReturnCall(PassedNamedArgument("word", flow.END().symbol), flow.getStop())

      FnDefinition(flow.name.text, args, body)
    }
    is LNCFParser.FnDefinitionContext -> {
      val fn = functional_definition()

      FnDefinition(fn.name.text, fn.args.map { it.text }, fn.body().d.map { it.transform(ctx) })
    }
    is LNCFParser.ManualEnumDefinitionContext -> this.enum_definition().transform(EnumType.MANUAL)
    is LNCFParser.FunctionalEnumDefinitionContext -> this.enum_definition().transform(EnumType.FUNCTIONAL)
    is LNCFParser.ClassificationDefinitionContext -> this.classification_definition().transform(ctx)
    else -> UnimplementedDefinition("definition $this ${this::class}")
  }
}