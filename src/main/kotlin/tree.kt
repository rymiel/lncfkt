package space.rymiel.lncf

import space.rymiel.lncf.Position.*

enum class Position { FIRST, LAST, OTHER }
data class TreeNode(val key: String, val children: List<TreeNode>) {
  fun tree(pos: Position, inherit: String) {
    val c = when (pos) {
      FIRST -> ""
      LAST -> "└"
      OTHER -> "├"
    }
    // val single = false
    val single = (key.length < 8 && children.size == 1 && children[0].children.isEmpty())

    print("$YELLOW_BRIGHT$inherit$c$PURPLE$key$RESET")
    if (single) {
      print(" ".repeat(8 - key.length))
      print("─")
      children[0].tree(FIRST, "")
      return
    }
    println()
    children.forEachIndexed { index, it ->
      val lastChild = (index == children.size - 1)
      val pipe = when (pos) {
        FIRST -> ""
        LAST -> " "
        OTHER -> "│"
      }
      it.tree(if (lastChild) LAST else OTHER, inherit + pipe)
    }
  }
}

data class TreeState(val body: MutableList<TreeNode> = mutableListOf()) {
  fun emit(s: String, child: List<TreeNode> = listOf()) {
    body.add(TreeNode(s, child))
  }

  fun emit(k: String, v: String, child: List<TreeNode> = listOf()) {
    body.add(TreeNode("$k $RESET$ITALIC$v", child))
  }

  fun emit(k: String, v: String, color: String, child: List<TreeNode> = listOf()) {
    body.add(TreeNode("$k $color$v", child))
  }

  fun print() {
    body.forEach {
      it.tree(FIRST, "")
    }
  }

  fun nestToPrevious(nest: (TreeState) -> Unit) {
    val child = TreeState()
    nest.invoke(child)
    val previous = body.removeLast()
    val previousChildren = previous.children.toMutableList()
    previousChildren.addAll(child.body)
    body.add(TreeNode(previous.key, previousChildren))
  }

  fun nest(implicit: String, nest: (TreeState) -> Unit) {
    val child = TreeState()
    nest.invoke(child)
    emit(implicit, child = child.body)
  }

  fun nest(k: String, v: String, nest: (TreeState) -> Unit) {
    val child = TreeState()
    nest.invoke(child)
    emit(k, v, child = child.body)
  }
}

fun Definition.tree(t: TreeState) {
  when (this) {
    is GlobalDefinition -> this.tree(t)
    is FlowDefinition -> this.tree(t)
    is FnDefinition -> this.tree(t)
    is UnimplementedDefinition -> this.tree(t)
  }
}

fun GlobalDefinition.tree(t: TreeState) {
  t.nest("define global", name) {
    value.tree(it)
  }
}

fun FlowDefinition.tree(t: TreeState) {
  t.nest("define flow", name) { tc ->
    body.forEach {
      it.tree(tc)
    }
  }
}

fun FnDefinition.tree(t: TreeState) {
  t.nest("define fn", name) { tc ->
    body.forEach {
      it.tree(tc)
    }
  }
}

fun Clause.tree(t: TreeState) {
  when (this) {
    is LiteralClause -> this.literal.tree(t)
    is BinaryClause -> this.tree(t)
    is UnimplementedClause -> this.tree(t)
  }
}

fun Call.tree(t: TreeState) {
  when (this) {
    is FlowCall -> this.tree(t)
    is FnCall -> this.tree(t)
    is IfElseCall -> this.tree(t)
    is UnimplementedCall -> this.tree(t)
  }
}

fun Literal.tree(t: TreeState) {
  when (this) {
    is Constant<*> -> this.tree(t)
    is UnimplementedLiteral -> this.tree(t)
  }
}
fun LiteralLike.tree(t: TreeState) {
  when (this) {
    is Literal -> this.tree(t)
    is Global -> this.tree(t)
    is PassedIndexArgument -> this.tree(t)
    is PassedNamedArgument -> this.tree(t)
  }
}

fun FlowCall.tree(t: TreeState) {
  val identifier = if (name.namespace == null) name.qualifier else "${name.namespace}:${name.qualifier}"
  t.nest("call flow", identifier) { tc ->
    args.positional.forEachIndexed { i, it ->
      tc.nest(i.toString()) { ttc ->
        it.tree(ttc)
      }
    }
    args.named.forEach { (k, it) ->
      tc.nest(k) { ttc ->
        it.tree(ttc)
      }
    }
  }
}

fun FnCall.tree(t: TreeState) {
  val identifier = if (name.namespace == null) name.qualifier else "${name.namespace}:${name.qualifier}"
  t.nest("call fn", identifier) { tc ->
    args.positional.forEachIndexed { i, it ->
      tc.nest(i.toString()) { ttc ->
        it.tree(ttc)
      }
    }
    args.named.forEach { (k, it) ->
      tc.nest(k) { ttc ->
        it.tree(ttc)
      }
    }
  }
}
fun IfElseCall.tree(t: TreeState) {
  t.nest("if") { tc ->
    clauses.forEach {
      tc.nest("clause") { ttc ->
        it.clause.tree(ttc)
        ttc.nest("then") { tttc ->
          it.body.forEach { call ->
            call.tree(tttc)
          }
        }
      }
    }
    if (elseBody != null) {
      tc.nest("else") { ttc ->
        elseBody.forEach {
          it.tree(ttc)
        }
      }
    }
  }
}
fun BinaryClause.tree(t: TreeState) {
  t.nest("binary", operation) {
    a.tree(it)
    b.tree(it)
  }
}
fun PassedIndexArgument.tree(t: TreeState) {
  t.emit("passed argument", index.toString())
}
fun PassedNamedArgument.tree(t: TreeState) {
  t.emit("passed argument", name)
}

fun UnimplementedLiteral.tree(t: TreeState) {
  t.emit("literal$RED unimplemented $msg")
}
fun UnimplementedCall.tree(t: TreeState) {
  t.emit("call$RED unimplemented $msg")
}
fun UnimplementedDefinition.tree(t: TreeState) {
  t.emit("define$RED unimplemented $msg")
}
fun UnimplementedClause.tree(t: TreeState) {
  t.emit("clause$RED unimplemented $msg")
}

fun <T> Constant<T>.tree(t: TreeState) {
  val str = when (value) {
    is String -> "\"$value\""
    else -> value.toString()
  }
  t.emit("const", str, GREEN)
}

fun Global.tree(t: TreeState) {
  t.emit("global", name)
}
