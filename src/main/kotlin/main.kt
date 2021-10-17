package space.rymiel.lncf

import LNCFBaseListener
import LNCFLexer
import LNCFParser
import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.TerminalNode
import java.io.File


const val RESET = "\u001B[0m"
const val RESET_COLOR = "\u001B[39m"
const val ITALIC = "\u001B[3m"
const val RED = "\u001B[0;31m"
const val YELLOW = "\u001B[0;33m"
const val GREEN = "\u001B[0;32m"
const val BLUE = "\u001B[0;34m"
const val PURPLE = "\u001B[0;35m"
const val CYAN = "\u001B[0;36m"
const val RED_BRIGHT = "\u001b[1;91m"
const val GREEN_BRIGHT = "\u001b[1;92m"
const val YELLOW_BRIGHT = "\u001b[1;93m"
const val BLUE_BRIGHT = "\u001b[0;94m"
const val PURPLE_BRIGHT = "\u001b[1;95m"
const val CYAN_BRIGHT = "\u001b[1;96m"
const val WHITE_BRIGHT = "\u001b[1;97m"
const val GRAY = "\u001b[0;38;5;245m"
const val ORANGE = "\u001b[1;38;5;208m"
const val DARK_ORANGE = "\u001b[0;38;5;172m"

class HighlighterListener(val tokens: CommonTokenStream) : LNCFBaseListener() {
  val rewriter = TokenStreamRewriter(tokens)

  override fun visitTerminal(node: TerminalNode) {
    val insertion = when (node.symbol.type) {
      LNCFLexer.INT -> CYAN
      LNCFLexer.STRING -> GREEN
      LNCFLexer.DEFINE_FLOW, LNCFLexer.DEFINE_FN, LNCFLexer.DEFINE_GLOBAL, LNCFLexer.MANUAL_ENUM,
      LNCFLexer.CLASSIFY, LNCFLexer.WHERE, LNCFLexer.FUNCTIONAL_ENUM, LNCFLexer.FUNCTIONAL_SPACE_FOR -> ORANGE
      // LNCFLexer.SUFFIX, LNCFLexer.INCLUDE, LNCFLexer.EXCLUDE -> ITALIC
      LNCFLexer.POS_ARG, LNCFLexer.NAME_ARG, LNCFLexer.TRUE, LNCFLexer.FALSE -> GREEN_BRIGHT
      LNCFLexer.PERMUTE -> ITALIC
      LNCFLexer.BEGIN, LNCFLexer.END -> GRAY
      else -> null
    }
    if (insertion != null) {
      rewriter.insertBefore(node.symbol, insertion)
      rewriter.insertAfter(node.symbol, if (insertion.contains("\u001B[0;")) RESET_COLOR else RESET)
    }
  }

  private fun color(startToken: Token, endToken: Token, startColor: String) {
    rewriter.insertBefore(startToken, startColor)
    rewriter.insertAfter(endToken, if (startColor.contains("\u001B[0;")) RESET_COLOR else RESET)
  }

  private infix fun Pair<Token, Token>.color(code: String) { color(first, second, code) }
  private infix fun ParserRuleContext.color(code: String) { color(start, stop, code) }
  private infix fun Token.color(code: String) { color(this, this, code) }
  private infix fun TerminalNode.color(code: String) { color(symbol, symbol, code) }

  override fun enterEveryRule(ctx: ParserRuleContext) {
    when (ctx) {
      is LNCFParser.Enum_keyContext -> ctx color CYAN_BRIGHT
      is LNCFParser.Global_definitionContext -> ctx.WORD() color PURPLE_BRIGHT
      is LNCFParser.Functional_definitionContext -> ctx.name color YELLOW
      is LNCFParser.FlowCallContext -> ctx.FLOW() color DARK_ORANGE
      is LNCFParser.FnCallContext -> ctx.FN() color DARK_ORANGE
      is LNCFParser.Functional_callContext -> when (val i = ctx.identifier()) {
        is LNCFParser.NamespacedContext -> i.ns to i.COLON().symbol color PURPLE
        is LNCFParser.ImplicitContext -> i.COLON().symbol color PURPLE
      }
      is LNCFParser.If_else_callContext -> {
        ctx.IF() color DARK_ORANGE
        ctx.ELIF().forEach { it color DARK_ORANGE }
        ctx.ELSE()?.color(DARK_ORANGE)
      }
      is LNCFParser.NamedContext -> ctx.WORD().symbol to ctx.EQ().symbol color BLUE
      is LNCFParser.GlobalContext -> ctx.WORD() color WHITE_BRIGHT
      is LNCFParser.Classification_definitionContext -> {
        ctx.EQ() color CYAN
        ctx.v color CYAN_BRIGHT
        ctx.k color YELLOW
        ctx.name color YELLOW
      }
      is LNCFParser.Enum_definitionContext -> ctx.name color YELLOW
    }
  }
}

fun main() {
  val src = getResourceAsText("/solerian.lncf")
  val lexer = LNCFLexer(CharStreams.fromString(src))
  val tokens = CommonTokenStream(lexer)
  val parser = LNCFParser(tokens)
  val topLevel = parser.prog()

  val walker = ParseTreeWalker()
  val listener = HighlighterListener(tokens)
  walker.walk(listener, topLevel)
  println(listener.rewriter.text)

  val defStubs = topLevel.d.map { it.stub() }
  val defs = topLevel.d.map { it.transform(Context(defStubs, it.stub())) }
  val tree = TreeState()
  tree.nest("root") { t ->
    defs.forEach {
      it.tree(t)
    }
  }
  tree.print()
  println()

  val comp = VirtualMachineCompiler()
  comp.compile(defs)
  comp.constants.forEachIndexed { index, primitive ->
    print(index.toString().padStart(3, '0'))
    println(" ${primitive::class.simpleName} ${primitive.value}")
  }
  comp.export(File("test.lncfb"))
}
