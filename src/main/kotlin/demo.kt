package space.rymiel.lncf

class DemoReader {
  data class AgnosticReturn(val w: String?, val r: Constant<*>?)
  data class LibFlow(val f: (String, Arguments) -> String)
  data class LibFn(val f: (Arguments) -> Constant<*>)
  companion object Util {
    fun translate(s: String, r: Map<Char, Char>, limit: Int = 0): String {
      var c = 0
      return String(s.map { if (r.containsKey(it) && ((limit > 0 && c < limit) || limit == 0)) { r[it]!!.also { c++ } } else it}.toCharArray())
    }

    fun makeTranslate(a: String, b: String): Map<Char, Char> {
      if (a.length != b.length) throw IllegalArgumentException("makeTranslate arguments must be same length")
      val map = mutableMapOf<Char, Char>()
      a.forEachIndexed { index, c -> map[c] = b[index] }
      return map
    }

    fun makeAndTranslate(s: String, a: String, b: String, limit: Int = 0, reverse: Boolean = false): String {
      val r = translate(if (reverse) s.reversed() else s, makeTranslate(a, b), limit=limit)
      return if (reverse) r.reversed() else r
    }

    fun countMatch(base: String, pattern: String): Int {
      return pattern.toRegex().findAll(base).count()
    }

    fun hasMatch(base: String, pattern: String): Boolean {
      return pattern.toRegex().containsMatchIn(base)
    }

    private interface OptionalArgument {
      val name: String
      val arg: LiteralLike?
    }

    private operator fun Arguments.get(index: Int): LiteralLike {
      return this.positional.getOrNull(index) ?: throw IllegalStateException("Argument index $index out of bounds")
    }

    private operator fun Arguments.get(name: String, default: Any): LiteralLike {
      return this.named[name] ?: Constant(default)
    }

    private infix fun Arguments.imm(name: String): OptionalArgument {
      return object : OptionalArgument {
        override val arg = named[name]
        override val name = name
      }
    }

    private inline fun <reified T : Any> immediate(e: LiteralLike): T {
      when (e) {
        is Constant<*> -> {
          if (e.value is T) return e.value
          else throw IllegalStateException("Constant $this is not of expected type ${T::class}")
        }
        else -> throw IllegalStateException("Argument $this cannot be resolved to type ${T::class}")
      }
    }

    private fun Int.boxed(): Constant<Int> {
      return Constant(this)
    }

    private fun Boolean.boxed(): Constant<Boolean> {
      return Constant(this)
    }
  }

  val stack = ArrayDeque<String>()
  val argumentStack = ArrayDeque<Arguments>()
  val constants = mutableMapOf<String, Constant<*>>()
  val flows = mutableMapOf<String, FlowDefinition>()
  val fns = mutableMapOf<String, FnDefinition>()
  val libFlows = mapOf(
    "map_characters" to LibFlow { word, args -> makeAndTranslate(
      word,
      args imm 0,
      args imm 1,
      args imm "limit" default 0,
      args imm "reverse" default false
    ) }
  )
  val libFns = mapOf(
    "match_count" to LibFn { args -> countMatch(
      args imm 0,
      args imm 1
    ).boxed() },
    "has_match" to LibFn { args -> hasMatch(
      args imm 0,
      args imm 1
    ).boxed() }
  )

  fun readDefinitions(defs: List<Definition>) {
    defs.forEach {
      when (it) {
        is GlobalDefinition -> {
          if (it.value is Constant<*>) {
            constants[it.name] = it.value
          }
        }
        is FlowDefinition -> flows[it.name] = it
        is FnDefinition -> fns[it.name] = it
        is UnimplementedDefinition -> {}
      }
    }
  }

  fun traverseBody(body: List<Call>, word: String? = null): AgnosticReturn {
    var w = word
    var r: Constant<*>? = null
    body.forEach {
      when (it) {
        is FlowCall -> w = invokeFlow(it.name.namespace, it.name.qualifier, w!!, it.args)
        is FnCall -> r = invokeFn(it.name.namespace, it.name.qualifier, it.args)
        is IfElseCall -> {
          val ar = takeCorrectBranch(it, w)
          w = ar.w
          r = ar.r
        }
        else -> TODO()
      }
    }
    return AgnosticReturn(w, r)
  }

  fun invokeFlow(namespace: String?, qualifier: String, word: String, args: Arguments): String {
    val args = translateArguments(args)
    val frame = "flow -> ${namespace ?: ""}:$qualifier <w> $word <p> ${args.positional} <n> ${args.named}"
    log("invoking $frame")
    stack.addLast(frame)
    argumentStack.addLast(args)
    if (namespace == null) {
      val f = flows[qualifier]!!
      val w = traverseBody(f.body, word).w!!
      return w.also { stack.removeLast() }
    } else if (namespace == "lib") {
      val f = libFlows[qualifier]!!
      log("found library flow $qualifier")
      return f.f(word, args).also { stack.removeLast(); log("library flow returned $it") }
    }
    TODO()
  }

  fun invokeFn(namespace: String?, qualifier: String, args: Arguments): Constant<*> {
    val args = translateArguments(args)
    val frame = "fn -> ${namespace ?: ""}:$qualifier <p> ${args.positional} <n> ${args.named}"
    log("invoking $frame")
    stack.addLast(frame)
    argumentStack.addLast(args)
    if (namespace == null) {
      val f = fns[qualifier]!!
      val w = traverseBody(f.body).r!!
      return w.also { stack.removeLast(); argumentStack.removeLast() }
    } else if (namespace == "lib") {
      val f = libFns[qualifier]!!
      log("found library fn $qualifier")
      return f.f(args).also { stack.removeLast(); argumentStack.removeLast(); log("library fn returned $it") }
    }
    TODO()
  }

  fun translateArguments(args: Arguments): Arguments {
    var pos = args.positional
    val named = args.named
    log("Translating <p> ${args.positional} <n> ${args.named}")
    pos = pos.map {
      when (it) {
        is PassedIndexArgument -> argumentStack.last()[it.index - 1]
        is PassedNamedArgument -> argumentStack.last().named[it.name]!!
        is Global -> constants[it.name]!!
        else -> it
      }
    }
    return Arguments(pos, named)
  }

  fun takeCorrectBranch(cond: IfElseCall, word: String? = null): AgnosticReturn {
    val frame = "if-else call"
    log("invoking $frame")
    stack.addLast(frame)
    var foundSuccess = false
    var w = word
    var r: Constant<*>? = null
    cond.clauses.forEach {
      val success = resolveClause(it.clause, word)
      if (truthinessOf(success)) {
        foundSuccess = true
        val bodyFrame = "clause body of ${it.clause}"
        log("running $bodyFrame")
        stack.addLast(bodyFrame)
        val bodyRet = traverseBody(it.body, word)
        w = bodyRet.w
        r = bodyRet.r
        stack.removeLast()
        return@forEach
      }
    }
    if (!foundSuccess) {
      log("None of the clauses succeeded!")
      if (cond.elseBody != null) {
        val bodyFrame = "else body"
        log("running $bodyFrame")
        stack.addLast(bodyFrame)
        val bodyRet = traverseBody(cond.elseBody, word)
        w = bodyRet.w
        r = bodyRet.r
        stack.removeLast()
      }
    }
    stack.removeLast()
    log("if-else return <w> $w <r> $r")
    return AgnosticReturn(w, r)
  }

  fun resolveClause(clause: Clause, word: String?): Constant<*> {
    val clauseFrame = "clause $clause"
    log("resolving $clauseFrame")
    stack.addLast(clauseFrame)
    val ret = resolveClause0(clause, word)
    stack.removeLast()
    log("clause returned $ret (<w> $word)")
    return ret
  }

  private fun resolveClause0(clause: Clause, word: String?): Constant<*> {
    when (clause) {
      is LiteralClause -> {
        val literal = clause.literal
        if (literal is Global) {
          if (word != null && fns.containsKey(literal.name)) {
            log("Clause can be implicit fn")
            // This is actually an implicit fn call, not a global
            return invokeFn(
              null, literal.name,
              Arguments(
                listOf(Constant(word)), mapOf()
              )
            )
          }
        } else if (literal is Constant<*>) {
          return literal
        }
      }
      is BinaryClause -> {
        val op = clause.operation
        val a = resolveClause(clause.a, word)
        val b = resolveClause(clause.b, word)
        log("Applying operation `$op` to $a and $b")
        when (op) {
          "=" -> return Constant(a.value == b.value)
          ">" -> if (a.value is Int && b.value is Int) return Constant(a.value > b.value)
          "or" -> if (a.value is Boolean && b.value is Boolean) return Constant(a.value || b.value)
        }
      }
      is UnimplementedClause -> TODO()
    }
    return Constant(false)
  }

  fun truthinessOf(literal: Literal): Boolean {
    if (literal is Constant<*>) {
      if (literal.value is Boolean) {
        return literal.value
      }
    }
    return true
  }

  private inline infix fun <reified T : Any> OptionalArgument.default(d: T): T {
    return if (arg == null) {
      d.also { log("^ named argument $name fallback to default $it") }
    } else {
      immediate<T>(arg!!).also { log("^ positional argument $name coerced to type $it ${T::class}") }
    }
  }
  private inline infix fun <reified T : Any> Arguments.imm(index: Int): T {
    return immediate<T>(this[index]).also { log("^ positional argument $index coerced to type $it ${T::class}") }
  }

  fun log(m: String) {
    println("  ".repeat(stack.size) + m)
  }
}