package com.needlerom.engine.rom

/**
 * The ROM being hacked. [original] is the untouched base (used for patches and to tell
 * original content from additions); [data] is the working copy every editor writes to.
 * Every write is recorded so it can be undone, and writes can be grouped into one step.
 */
class Rom(original: ByteArray) {
    val original: ByteArray = original.copyOf()
    var data: ByteArray = original.copyOf()
        private set

    class Change(val label: String, val offset: Int, val previous: ByteArray, val group: Int, val whole: Boolean)

    private val changes = mutableListOf<Change>()
    private var groupCounter = 0
    private var openGroup: Int? = null
    private var openDepth = 0

    /** Bumped on every modification so UIs can cheaply notice changes. */
    var revision = 0
        private set

    val size: Int get() = data.size
    val canUndo: Boolean get() = changes.isNotEmpty()
    val changeCount: Int get() = changes.size
    val history: List<String> get() = changes.map { it.label }
    val isModified: Boolean get() = !data.contentEquals(original)

    fun u8(o: Int): Int = data[o].toInt() and 0xFF
    fun u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
    fun inBounds(o: Int, len: Int = 1) = o >= 0 && len >= 0 && o.toLong() + len <= data.size
    fun slice(o: Int, len: Int): ByteArray = data.copyOfRange(o, (o + len).coerceAtMost(data.size))

    /** Starts an undo step that spans several calls (e.g. a paint stroke); close with [endGroup]. */
    fun beginGroup() { if (openDepth++ == 0) openGroup = ++groupCounter }

    fun endGroup() { if (openDepth > 0 && --openDepth == 0) openGroup = null }

    /** Runs [block] as a single undo step. */
    fun <T> batch(block: () -> T): T {
        if (openDepth++ == 0) openGroup = ++groupCounter
        try {
            return block()
        } finally {
            if (--openDepth == 0) openGroup = null
        }
    }

    fun write(o: Int, bytes: ByteArray, label: String) {
        require(inBounds(o, bytes.size)) { "Write outside ROM at 0x${o.toString(16)}" }
        var same = true
        for (i in bytes.indices) if (data[o + i] != bytes[i]) { same = false; break }
        if (same) return
        changes += Change(label, o, data.copyOfRange(o, o + bytes.size), openGroup ?: ++groupCounter, whole = false)
        bytes.copyInto(data, o)
        revision++
    }

    fun write8(o: Int, v: Int, label: String) = write(o, byteArrayOf(v.toByte()), label)
    fun write16(o: Int, v: Int, label: String) = write(o, byteArrayOf(v.toByte(), (v shr 8).toByte()), label)

    /** Replaces the whole image (e.g. after applying a patch). */
    fun replaceAll(newData: ByteArray, label: String) {
        changes += Change(label, 0, data, openGroup ?: ++groupCounter, whole = true)
        data = newData.copyOf()
        revision++
    }

    /** Reverts the last undo step and returns its label. */
    fun undo(): String? {
        val last = changes.lastOrNull() ?: return null
        while (changes.isNotEmpty() && changes.last().group == last.group) {
            val c = changes.removeAt(changes.lastIndex)
            if (c.whole) data = c.previous else c.previous.copyInto(data, c.offset)
        }
        revision++
        return last.label
    }

    fun revertAll() {
        data = original.copyOf()
        changes.clear()
        revision++
    }

    /** Loads previously saved working data (e.g. when opening a project). History starts empty. */
    fun restore(working: ByteArray) {
        data = working.copyOf()
        changes.clear()
        revision++
    }

    fun indexOf(pattern: ByteArray, from: Int = 0, until: Int = data.size): Int {
        if (pattern.isEmpty()) return -1
        val last = minOf(until, data.size) - pattern.size
        var i = from.coerceAtLeast(0)
        outer@ while (i <= last) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) { i++; continue@outer }
            return i
        }
        return -1
    }
}

/** Game Boy banked addressing. */
object Gb {
    const val BANK = 0x4000

    fun offset(bank: Int, ptr: Int): Int = if (ptr < 0x4000) ptr else bank * BANK + (ptr - 0x4000)
    fun bankOf(offset: Int): Int = offset / BANK
    fun ptr(offset: Int): Int = if (offset < BANK) offset else 0x4000 + offset % BANK
    fun bankEnd(bank: Int, size: Int): Int = minOf((bank + 1) * BANK, size)
}

/**
 * Finds room for relocated data. Only the run of 0x00/0xFF padding that assemblers leave
 * at the very end of a bank is used, with a margin on both sides, so live data is never
 * overwritten.
 */
object FreeSpace {
    fun find(rom: Rom, bank: Int, size: Int, margin: Int = 8): Int? {
        val d = rom.data
        val end = Gb.bankEnd(bank, d.size)
        val start0 = bank * Gb.BANK
        if (end <= start0) return null
        val fill = d[end - 1]
        if (fill != 0x00.toByte() && fill != 0xFF.toByte()) return null
        var start = end
        while (start > start0 && d[start - 1] == fill) start--
        if (start == start0) return start0 // a completely empty bank: no margin needed
        return if (end - start >= size + margin * 2) start + margin else null
    }

    /**
     * Writes [bytes] into free space in [bank] and returns the offset. A guard byte that
     * differs from the padding is written right after, so data ending in 0x00 can never be
     * mistaken for padding by a later allocation.
     */
    fun alloc(rom: Rom, bank: Int, bytes: ByteArray, label: String): Int? {
        val at = find(rom, bank, bytes.size + 1, margin = 1) ?: return null
        val fill = rom.u8(Gb.bankEnd(bank, rom.size) - 1)
        rom.write(at, bytes + (fill xor 0xFF).toByte(), label)
        return at
    }

    /** Total bytes of end-of-bank padding, for the "space used" meter. */
    fun available(rom: Rom, bank: Int): Int {
        val d = rom.data
        val end = Gb.bankEnd(bank, d.size)
        if (end <= bank * Gb.BANK) return 0
        val fill = d[end - 1]
        if (fill != 0x00.toByte() && fill != 0xFF.toByte()) return 0
        var start = end
        while (start > bank * Gb.BANK && d[start - 1] == fill) start--
        return end - start
    }

    /** A bank with no data at all (Red/Blue have 19 of these), or null. */
    fun emptyBank(rom: Rom, from: Int = 1): Int? {
        val banks = rom.size / Gb.BANK
        for (b in banks - 1 downTo from) if (available(rom, b) == Gb.BANK) return b
        return null
    }
}
