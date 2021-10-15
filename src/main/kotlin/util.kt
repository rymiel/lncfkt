package space.rymiel.lncf

import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.misc.Utils
import org.antlr.v4.runtime.tree.Tree
import org.antlr.v4.runtime.tree.Trees

val singleton = {}

fun getResourceAsText(path: String): String {
  return singleton::class.java.getResource(path)!!.readText()
}

fun Tree.prettyTree(p: Parser): String {
  return TreeUtils.toPrettyTree(this, p.ruleNames.toList())
}

object TreeUtils {
  /** Platform dependent end-of-line marker  */
  val eol: String = System.lineSeparator()

  /** The literal indent char(s) used for pretty-printing  */
  const val indents = "  "
  private var level = 0

  /**
   * Pretty print out a whole tree. [.getNodeText] is used on the node payloads to get the text
   * for the nodes. (Derived from Trees.toStringTree(....))
   */
  fun toPrettyTree(t: Tree, ruleNames: List<String>): String {
    level = 0
    return process(t, ruleNames).replace("(?m)^\\s+$".toRegex(), "").replace("\\r?\\n\\r?\\n".toRegex(), eol)
  }

  private fun process(t: Tree, ruleNames: List<String>): String {
    if (t.childCount == 0) return Utils.escapeWhitespace(Trees.getNodeText(t, ruleNames), false)
    val sb = StringBuilder()
    sb.append(lead(level))
    level++
    val s: String = Utils.escapeWhitespace(Trees.getNodeText(t, ruleNames), false)
    sb.append("$s ")
    for (i in 0 until t.childCount) {
      sb.append(process(t.getChild(i), ruleNames))
    }
    level--
    sb.append(lead(level))
    return sb.toString()
  }

  private fun lead(level: Int): String {
    val sb = StringBuilder()
    if (level > 0) {
      sb.append(eol)
      for (cnt in 0 until level) {
        sb.append(indents)
      }
    }
    return sb.toString()
  }
}