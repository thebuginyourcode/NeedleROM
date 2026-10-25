package com.needlerom.engine.script

import com.needlerom.engine.pokered.PokeRed
import com.needlerom.engine.pokered.Text
import java.io.ByteArrayOutputStream

/** Compiles an [EventScript] into NeedleScript bytecode plus its texts, for a given address. */
internal class ScriptCompiler(private val wEventFlags: Int) {
    private object Op {
        const val END = 0; const val TEXT = 1; const val YESNO = 2; const val GIVE_ITEM = 3; const val GIVE_MON = 4
        const val SET_FLAG = 5; const val CLEAR_FLAG = 6; const val IF_FLAG = 7; const val IF_NOT_FLAG = 8; const val JUMP = 9
        const val WILD = 10; const val TRAINER = 11; const val HEAL = 12; const val MONEY = 13; const val IF_NO_ITEM = 14
        const val TAKE_ITEM = 15; const val SOUND = 16; const val WALK = 17
    }

    private val code = ByteArrayOutputStream()
    private val fixups = mutableListOf<Pair<Int, () -> Int>>() // code offset -> resolved CPU address
    private val texts = mutableListOf<Pair<Int, ByteArray>>()   // code offset of the pointer, text bytes
    private var base = 0

    private fun b(v: Int) = code.write(v and 0xFF)
    private fun w(v: Int) { b(v); b(v shr 8) }
    private fun here() = code.size()
    private fun placeholder(): Int { val at = here(); w(0); return at }

    private class Label { var at = -1 }
    private fun mark(l: Label) { l.at = here() }
    private fun ref(l: Label) { val at = placeholder(); fixups += at to { base + l.at } }

    private fun textRef(text: String, last: Boolean) {
        // Final messages end with "done" (the game then waits once); others "prompt".
        val bytes = byteArrayOf(0) + Text.encodeDialogue(text) + (if (last) Text.DONE else Text.PROMPT).toByte() + Text.END.toByte()
        texts += placeholder() to bytes
    }

    private fun flag(n: Int) {
        require(n in 0 until Flags.COUNT) { "Flag $n out of range" }
        val e = Flags.FIRST_EVENT + n
        w(wEventFlags + e / 8); b(1 shl (e % 8))
    }

    private fun steps(list: List<Step>, isTail: Boolean) {
        list.forEachIndexed { i, s -> step(s, isTail && i == list.lastIndex) }
    }

    private fun step(s: Step, last: Boolean) {
        when (s) {
            is Step.Say -> { b(Op.TEXT); textRef(s.text, last) }
            is Step.Ask -> {
                b(Op.TEXT); textRef(s.question, false)
                val no = Label(); val end = Label()
                b(Op.YESNO); ref(no)
                steps(s.yes, last); b(Op.JUMP); ref(end)
                mark(no); steps(s.no, last)
                mark(end)
            }
            is Step.GiveItem -> {
                val full = Label(); val end = Label()
                b(Op.GIVE_ITEM); b(s.item); b(s.quantity.coerceIn(1, 99)); ref(full)
                b(Op.JUMP); ref(end)
                mark(full); steps(s.ifBagFull, false); b(Op.END)
                mark(end)
            }
            is Step.GivePokemon -> {
                val fail = Label(); val end = Label()
                b(Op.GIVE_MON); b(s.species); b(s.level.coerceIn(1, 100)); ref(fail)
                b(Op.JUMP); ref(end)
                mark(fail); steps(s.ifNoRoom, false); b(Op.END)
                mark(end)
            }
            is Step.TakeItem -> { b(Op.TAKE_ITEM); b(s.item) }
            is Step.IfHasItem -> {
                val missing = Label(); val end = Label()
                b(Op.IF_NO_ITEM); b(s.item); ref(missing)
                steps(s.then, last); b(Op.JUMP); ref(end)
                mark(missing); steps(s.otherwise, last)
                mark(end)
            }
            is Step.SetFlag -> { b(Op.SET_FLAG); flag(s.flag) }
            is Step.ClearFlag -> { b(Op.CLEAR_FLAG); flag(s.flag) }
            is Step.IfFlag -> {
                val notSet = Label(); val end = Label()
                b(Op.IF_NOT_FLAG); flag(s.flag); ref(notSet)
                steps(s.then, last); b(Op.JUMP); ref(end)
                mark(notSet); steps(s.otherwise, last)
                mark(end)
            }
            is Step.WildBattle -> { b(Op.WILD); b(s.species); b(s.level.coerceIn(1, 100)) }
            is Step.TrainerBattle -> {
                b(Op.TRAINER); b(s.trainerClass + PokeRed.OPP_ID_OFFSET); b(s.team)
                texts += placeholder() to (byteArrayOf(0) + Text.encodeDialogue(s.winText) + Text.PROMPT.toByte() + Text.END.toByte())
                texts += placeholder() to (byteArrayOf(0) + Text.encodeDialogue(s.loseText) + Text.PROMPT.toByte() + Text.END.toByte())
            }
            Step.Heal -> b(Op.HEAL)
            is Step.GiveMoney -> {
                b(Op.MONEY)
                val d = s.amount.coerceIn(0, 999999).toString().padStart(6, '0')
                for (k in 0 until 3) b(((d[k * 2] - '0') shl 4) or (d[k * 2 + 1] - '0'))
            }
            is Step.PlaySound -> { b(Op.SOUND); b(s.sound) }
            is Step.WalkToPlayer -> { b(Op.WALK); b(s.npc + 1) }
        }
    }

    /** Size of the compiled block (bytecode + texts), independent of where it goes. */
    fun compile(script: EventScript): Int {
        steps(script.steps, isTail = true)
        b(Op.END)
        return here() + texts.sumOf { it.second.size }
    }

    /** Produces the final bytes for a block placed at CPU address [cpuBase]. */
    fun link(cpuBase: Int): ByteArray {
        base = cpuBase
        val out = code.toByteArray()
        val all = ByteArrayOutputStream().apply { write(out) }
        val patched = out.copyOf()
        var textAt = out.size
        for ((at, bytes) in texts) {
            val addr = cpuBase + textAt
            patched[at] = addr.toByte(); patched[at + 1] = (addr shr 8).toByte()
            all.write(bytes)
            textAt += bytes.size
        }
        for ((at, resolve) in fixups) {
            val addr = resolve()
            patched[at] = addr.toByte(); patched[at + 1] = (addr shr 8).toByte()
        }
        val result = all.toByteArray()
        patched.copyInto(result)
        return result
    }
}
