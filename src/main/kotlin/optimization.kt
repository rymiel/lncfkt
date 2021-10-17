package space.rymiel.lncf

private fun posToTwoBytes(i : Int): List<Byte> {
  return listOf(
    ((i shr 8) and 0xFF).toByte(),
    (i and 0xFF).toByte(),
  )
}

data class FlatLineContainer(val lines: MutableList<LineStub>, val unresolved: MutableList<Line>) {
  fun resolve(): ByteArray {
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
}

class Optimizer(val line: Line) {
  fun optimizeBody() {
    println("Begin optimization:")
    while (true) {
      val before = line.toByteArray()

      println("B ${line.toByteArray().contentToString()}")
      optimizeUselessJump()
      optimizeInlineNoopTargets()
      optimizeDoubleReturn()
      optimizeStoreBeforeReturn()
      optimizeInlineStLd()

      val optimized = line.toByteArray()

      if (before.contentEquals(optimized)) {
        println("o ${line.toByteArray().contentToString()}")
        return
      }
    }
  }

  private fun optimizeUselessJump() {
    line.forEachWithPrevious { i, previous ->
      if (i.instr == Instr.JUMP || i.instr == Instr.JZ || i.instr == Instr.JNZ)
        if (i.next === i.branch && previous != null)
          previous.next = i.next
    }
  }

  private fun optimizeInlineNoopTargets() {
    val candidate = line.find { it.instr == Instr.NOOP }
    if (candidate != null) {
      line.skip(candidate)
    }
  }

  private fun optimizeDoubleReturn() {
    line.forEachWithPrevious { i, prev ->
      if (i.instr == Instr.RET && prev?.instr == Instr.RET)
        line.replace(prev, i)
    }
  }

  private fun optimizeStoreBeforeReturn() {
    line.forEachWith2Previous { i, j, k ->
      if (i.instr == Instr.RET && j?.instr == Instr.LD_REG_0 && k?.instr == Instr.ST_REG_0) {
        if (!line.targeted(j) && !line.targeted(k)) {
          line.skip(j)
          line.skip(k)
        }
      }
    }
  }

  private fun optimizeInlineStLd() {
    line.forEachWithPrevious { i, prev ->
      if (i.instr == Instr.LD_REG_0 && prev?.instr == Instr.ST_REG_0) {
        val isUsed = i.find { it !== i && it.instr == Instr.LD_REG_0 }
        if (isUsed == null && !line.targeted(i) && !line.targeted(prev)) {
          line.skip(i)
          line.skip(prev)
        }
      }
    }
  }
}

fun Line.targeted(a : Line): Boolean {
  var found = false
  this.forEach {
    if (it.branch == a) found = true
  }
  return found
}

fun Line.replace(a : Line, b : Line?) {
  this.forEach {
    if (it.branch === a) it.branch = b
    if (it.next === a) it.next = b
  }
}

fun Line.skip(a : Line) {
  this.replace(a, a.next)
}

fun Line.find(predicate: (Line) -> Boolean): Line? {
  var ret: Line? = null
  this.forEach {
    if (predicate(it) && ret == null) ret = it
  }
  return ret
}

fun Line.forEach(action: (Line) -> Unit) {
  var i = this
  while (true) {
    action(i)
    if (i.next == null) break
    i = i.next!!
  }
}

fun Line.forEachWithPrevious(action: (Line, Line?) -> Unit) {
  var previous: Line? = null
  this.forEach {
    action(it, previous)
    previous = it
  }
}

fun Line.forEachWith2Previous(action: (Line, Line?, Line?) -> Unit) {
  var previous1: Line? = null
  var previous2: Line? = null
  this.forEach {
    action(it, previous1, previous2)
    previous2 = previous1
    previous1 = it
  }
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

fun Line.toByteArray(): ByteArray {
  return this.flatten().resolve()
}