package com.needlerom.core

/** A single labelled edit, kept so the UI can show what the hack changes. */
data class Change(val label: String, val offset: Int, val length: Int)

/**
 * An in-memory ROM. [original] is never modified, so patches (IPS/BPS) can always be
 * generated against it; [data] is the working copy every tool edits.
 */
class Rom(val fileName: String, bytes: ByteArray) {
    /** Set when the file was converted on load (e.g. a byte-swapped N64 dump). */
    val loadNote: String?

    val original: ByteArray
    var data: ByteArray
        private set

    private val _changes = mutableListOf<Change>()
    val changes: List<Change> get() = _changes

    init {
        val normalized = N64.normalizeByteOrder(bytes)
        original = normalized.first
        loadNote = normalized.second
        data = original.copyOf()
    }

    val size: Int get() = data.size
    val isModified: Boolean get() = !data.contentEquals(original)

    fun u8(offset: Int): Int = data[offset].toInt() and 0xFF
    fun u16le(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
    fun u16be(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)
    fun u32be(offset: Int): Int =
        (u8(offset) shl 24) or (u8(offset + 1) shl 16) or (u8(offset + 2) shl 8) or u8(offset + 3)

    fun inBounds(offset: Int, length: Int = 1): Boolean =
        offset >= 0 && length >= 0 && offset.toLong() + length <= data.size

    fun slice(offset: Int, length: Int): ByteArray {
        val end = (offset + length).coerceAtMost(data.size)
        return if (offset >= end) ByteArray(0) else data.copyOfRange(offset, end)
    }

    fun write(offset: Int, bytes: ByteArray, label: String) {
        require(inBounds(offset, bytes.size)) { "Write outside ROM: 0x${offset.toString(16)}" }
        bytes.copyInto(data, offset)
        _changes += Change(label, offset, bytes.size)
    }

    fun write8(offset: Int, value: Int, label: String) =
        write(offset, byteArrayOf(value.toByte()), label)

    /** Replaces the whole working image, e.g. after applying an external patch. */
    fun replaceAll(newData: ByteArray, label: String) {
        data = newData.copyOf()
        _changes += Change(label, 0, newData.size)
    }

    fun revertAll() {
        data = original.copyOf()
        _changes.clear()
    }

    fun indexOf(pattern: ByteArray, from: Int = 0): Int = Bytes.indexOf(data, pattern, from)

    fun findAll(pattern: ByteArray, limit: Int = 1000): List<Int> {
        val hits = mutableListOf<Int>()
        var at = indexOf(pattern, 0)
        while (at >= 0 && hits.size < limit) {
            hits += at
            at = indexOf(pattern, at + 1)
        }
        return hits
    }
}

object Bytes {
    fun of(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    fun indexOf(data: ByteArray, pattern: ByteArray, from: Int = 0): Int {
        if (pattern.isEmpty()) return -1
        val first = pattern[0]
        val last = data.size - pattern.size
        var i = from.coerceAtLeast(0)
        outer@ while (i <= last) {
            if (data[i] != first) { i++; continue }
            for (j in 1 until pattern.size) {
                if (data[i + j] != pattern[j]) { i++; continue@outer }
            }
            return i
        }
        return -1
    }

    fun hex(value: Int, digits: Int = 2): String =
        (value.toLong() and 0xFFFFFFFFL).toString(16).uppercase().padStart(digits, '0')

    fun hexString(bytes: ByteArray): String =
        bytes.joinToString(" ") { hex(it.toInt() and 0xFF) }

    /** Parses "A9 02 8D" / "a9028d" style input. Returns null on malformed input. */
    fun parseHex(text: String): ByteArray? {
        val clean = text.filterNot { it.isWhitespace() || it == ',' }.removePrefix("0x")
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun parseOffset(text: String): Int? {
        val t = text.trim().removePrefix("0x").removePrefix("0X").removePrefix("$")
        return t.toIntOrNull(16)
    }
}
