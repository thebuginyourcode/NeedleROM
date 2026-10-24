package com.needlerom.engine.script

/**
 * An event script: what happens when the player talks to an NPC or reads a sign.
 * Scripts are structured (if/else blocks) so they can be edited as visual blocks; they
 * are compiled to NeedleScript bytecode for the in-ROM interpreter.
 */
sealed interface Step {
    /** Show a message. Newline = next line, blank line = new text box. */
    data class Say(val text: String) : Step

    /** Ask a yes/no question (shown after [question]). */
    data class Ask(val question: String, val yes: List<Step>, val no: List<Step>) : Step

    data class GiveItem(val item: Int, val quantity: Int = 1, val ifBagFull: List<Step> = listOf(Say("No room for this item."))) : Step

    data class GivePokemon(val species: Int, val level: Int, val ifNoRoom: List<Step> = emptyList()) : Step

    data class TakeItem(val item: Int) : Step

    data class IfHasItem(val item: Int, val then: List<Step>, val otherwise: List<Step>) : Step

    /** Flags are numbered 0..[Flags.COUNT]-1 and saved with the player's game. */
    data class SetFlag(val flag: Int) : Step
    data class ClearFlag(val flag: Int) : Step
    data class IfFlag(val flag: Int, val then: List<Step>, val otherwise: List<Step>) : Step

    /** Starts a wild battle when the text box closes (like Snorlax or the legendary birds). */
    data class WildBattle(val species: Int, val level: Int) : Step

    /** Starts a trainer battle when the text box closes. */
    data class TrainerBattle(
        val trainerClass: Int,
        val team: Int,
        val winText: String = "Wow, you're\ngood!",
        val loseText: String = "Better luck\nnext time!",
    ) : Step

    data object Heal : Step

    data class GiveMoney(val amount: Int) : Step

    data class PlaySound(val sound: Int) : Step
}

data class EventScript(val steps: List<Step>)

/** Script flags live in event flags the original game never uses ($920-$99F). */
object Flags {
    const val FIRST_EVENT = 0x920
    const val COUNT = 128
}

/** Ready-made scripts for common events. */
object Templates {
    fun itemGift(item: Int, itemName: String, flag: Int) = EventScript(listOf(
        Step.IfFlag(flag,
            then = listOf(Step.Say("I hope that\n$itemName helps!")),
            otherwise = listOf(
                Step.Say("Here, take this!"),
                Step.GiveItem(item, 1, ifBagFull = listOf(Step.Say("Your bag is full!"))),
                Step.Say("<PLAYER> got\n$itemName!"),
                Step.SetFlag(flag),
            ),
        ),
    ))

    fun pokemonGift(species: Int, name: String, level: Int, flag: Int) = EventScript(listOf(
        Step.IfFlag(flag,
            then = listOf(Step.Say("Take good care\nof $name!")),
            otherwise = listOf(
                Step.Ask("Would you like\nthis $name?",
                    yes = listOf(
                        Step.GivePokemon(species, level, ifNoRoom = listOf(Step.Say("You have no room!"))),
                        Step.SetFlag(flag),
                    ),
                    no = listOf(Step.Say("Oh, okay then.")),
                ),
            ),
        ),
    ))

    fun healer() = EventScript(listOf(
        Step.Ask("You look tired.\nShall I heal your\nteam?",
            yes = listOf(Step.Heal, Step.Say("There you go!\nAll better!")),
            no = listOf(Step.Say("Take care!")),
        ),
    ))

    fun legendary(species: Int, name: String, level: Int, flag: Int) = EventScript(listOf(
        Step.IfFlag(flag,
            then = listOf(Step.Say("It's quiet here\nnow…")),
            otherwise = listOf(Step.Say("$name: Gyaoo!"), Step.SetFlag(flag), Step.WildBattle(species, level)),
        ),
    ))

    fun trainer(trainerClass: Int, team: Int, flag: Int) = EventScript(listOf(
        Step.IfFlag(flag,
            then = listOf(Step.Say("That was a great\nbattle!")),
            otherwise = listOf(
                Step.Say("Hey! Let's battle!"),
                Step.SetFlag(flag),
                Step.TrainerBattle(trainerClass, team),
            ),
        ),
    ))
}

/** Plain-text serialization, stored in the ROM next to the bytecode so hacks stay editable. */
object ScriptCodec {
    fun encode(s: EventScript): String = buildString { s.steps.forEach { step(it, this) } }

    private fun str(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun block(steps: List<Step>, sb: StringBuilder) { sb.append('{'); steps.forEach { step(it, sb) }; sb.append('}') }

    private fun step(s: Step, sb: StringBuilder) {
        when (s) {
            is Step.Say -> sb.append("say ").append(str(s.text))
            is Step.Ask -> { sb.append("ask ").append(str(s.question)); block(s.yes, sb); block(s.no, sb) }
            is Step.GiveItem -> { sb.append("item ${s.item} ${s.quantity}"); block(s.ifBagFull, sb) }
            is Step.GivePokemon -> { sb.append("mon ${s.species} ${s.level}"); block(s.ifNoRoom, sb) }
            is Step.TakeItem -> sb.append("take ${s.item}")
            is Step.IfHasItem -> { sb.append("hasitem ${s.item}"); block(s.then, sb); block(s.otherwise, sb) }
            is Step.SetFlag -> sb.append("set ${s.flag}")
            is Step.ClearFlag -> sb.append("clear ${s.flag}")
            is Step.IfFlag -> { sb.append("if ${s.flag}"); block(s.then, sb); block(s.otherwise, sb) }
            is Step.WildBattle -> sb.append("wild ${s.species} ${s.level}")
            is Step.TrainerBattle -> sb.append("trainer ${s.trainerClass} ${s.team} ").append(str(s.winText)).append(' ').append(str(s.loseText))
            Step.Heal -> sb.append("heal")
            is Step.GiveMoney -> sb.append("money ${s.amount}")
            is Step.PlaySound -> sb.append("sound ${s.sound}")
        }
        sb.append(';')
    }

    fun decode(src: String): EventScript = Parser(src).run { EventScript(steps(endBrace = false)) }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] == ' ') i++ }
        fun word(): String { ws(); val st = i; while (i < s.length && s[i] !in " ;{}\"") i++; return s.substring(st, i) }
        fun int() = word().toInt()
        fun string(): String {
            ws(); require(s[i] == '"'); i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') { i++; sb.append(if (s[i] == 'n') '\n' else s[i]) } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
        fun block(): List<Step> { ws(); require(s[i] == '{'); i++; return steps(endBrace = true) }
        fun steps(endBrace: Boolean): List<Step> {
            val out = mutableListOf<Step>()
            while (true) {
                ws()
                if (i >= s.length) break
                if (s[i] == '}') { i++; break }
                out += when (val w = word()) {
                    "say" -> Step.Say(string())
                    "ask" -> Step.Ask(string(), block(), block())
                    "item" -> Step.GiveItem(int(), int(), block())
                    "mon" -> Step.GivePokemon(int(), int(), block())
                    "take" -> Step.TakeItem(int())
                    "hasitem" -> Step.IfHasItem(int(), block(), block())
                    "set" -> Step.SetFlag(int())
                    "clear" -> Step.ClearFlag(int())
                    "if" -> Step.IfFlag(int(), block(), block())
                    "wild" -> Step.WildBattle(int(), int())
                    "trainer" -> Step.TrainerBattle(int(), int(), string(), string())
                    "heal" -> Step.Heal
                    "money" -> Step.GiveMoney(int())
                    "sound" -> Step.PlaySound(int())
                    else -> throw IllegalArgumentException("Unknown step '$w'")
                }
                ws(); if (i < s.length && s[i] == ';') i++
            }
            if (!endBrace) require(i >= s.length)
            return out
        }
    }
}
