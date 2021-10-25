import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import space.rymiel.lncf.*

internal class ParserTest {
  private fun parseFromResource(s: String): LNCFParser.ProgContext {
    val content = getResourceAsText(s)
    val lexer = LNCFLexer(CharStreams.fromString(content))
    val tokens = CommonTokenStream(lexer)
    val parser = LNCFParser(tokens)
    return parser.prog()
  }

  @Test
  fun stub() {
    val topLevel = parseFromResource("/fntypes.lncf")

    val defStubs = topLevel.d.map { it.stub() }
    assertIterableEquals(defStubs, listOf(
      DefinitionStub("a_flow", DefinitionStub.DefinitionType.FLOW),
      DefinitionStub("an_fn", DefinitionStub.DefinitionType.FN)
    ))
  }

  @Test
  fun testSolerian() {
    val topLevel = parseFromResource("/solerian.lncf")
    assertDoesNotThrow {
      val defStubs = topLevel.d.map { it.stub() }
      val defs = topLevel.d.map { it.transform(Context(defStubs, it.stub())) }
      val comp = VirtualMachineCompiler()
      comp.compile(defs)
    }
  }
}