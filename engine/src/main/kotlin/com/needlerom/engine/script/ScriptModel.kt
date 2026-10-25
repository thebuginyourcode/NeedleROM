package com.needlerom.engine.script

import com.needlerom.engine.pokered.PartyMon

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

    /**
     * Starts a trainer battle when the text box closes. With [customTeam], the team is
     * added to the trainer class as a new team when the event is saved (and [team] then
     * holds its number).
     */
    data class TrainerBattle(
        val trainerClass: Int,
        val team: Int,
        val winText: String = "Wow, you're\ngood!",
        val loseText: String = "Better luck\nnext time!",
        val customTeam: List<PartyMon>? = null,
    ) : Step

    /**
     * An NPC on this map walks up to the player (stopping next to them). The event pauses
     * while they walk.
     */
    data class WalkToPlayer(val npc: Int) : Step

    /** NPC [npc] walks to tile ([x], [y]) and the player follows one step behind. */
    data class Lead(val npc: Int, val x: Int, val y: Int) : Step

    data object Heal : Step

    data class GiveMoney(val amount: Int) : Step

    data class PlaySound(val sound: Int) : Step

    /** Plays a Pokémon's cry ([species] is the internal id). */
    data class PlayCry(val species: Int) : Step

    /** Switches the music until the next map change. */
    data class PlayMusic(val music: Int, val bank: Int) : Step
}

data class EventScript(val steps: List<Step>) {
    /** True if any step makes someone walk (the event then pauses and resumes). */
    fun walks(): Boolean = walks(steps)

    private fun walks(list: List<Step>): Boolean = list.any { s ->
        when (s) {
            is Step.WalkToPlayer, is Step.Lead -> true
            is Step.Ask -> walks(s.yes) || walks(s.no)
            is Step.IfFlag -> walks(s.then) || walks(s.otherwise)
            is Step.IfHasItem -> walks(s.then) || walks(s.otherwise)
            is Step.GiveItem -> walks(s.ifBagFull)
            is Step.GivePokemon -> walks(s.ifNoRoom)
            else -> false
        }
    }
}

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

    /** A trainer who spots the player, walks over and battles (for trigger tiles). */
    fun ambush(npc: Int, trainerClass: Int, team: List<PartyMon>) = EventScript(listOf(
        Step.Say("Hey, you there!"),
        Step.WalkToPlayer(npc),
        Step.Say("You look strong.\nLet's battle!"),
        Step.TrainerBattle(trainerClass, 0, customTeam = team),
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
            is Step.TrainerBattle -> {
                sb.append("trainer ${s.trainerClass} ${s.team} ").append(str(s.winText)).append(' ').append(str(s.loseText))
                s.customTeam?.let { t -> sb.append(" team"); t.forEach { sb.append(" ${it.level} ${it.species}") } }
            }
            is Step.WalkToPlayer -> sb.append("walk ${s.npc}")
            is Step.Lead -> sb.append("lead ${s.npc} ${s.x} ${s.y}")
            Step.Heal -> sb.append("heal")
            is Step.GiveMoney -> sb.append("money ${s.amount}")
            is Step.PlaySound -> sb.append("sound ${s.sound}")
            is Step.PlayCry -> sb.append("cry ${s.species}")
            is Step.PlayMusic -> sb.append("music ${s.music} ${s.bank}")
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
                    "trainer" -> {
                        val t = Step.TrainerBattle(int(), int(), string(), string())
                        ws()
                        if (s.startsWith("team", i)) {
                            i += 4
                            val mons = mutableListOf<PartyMon>()
                            while (true) { ws(); if (i >= s.length || s[i] == ';' || s[i] == '}') break; mons += PartyMon(int(), int()) }
                            t.copy(customTeam = mons)
                        } else t
                    }
                    "walk" -> Step.WalkToPlayer(int())
                    "lead" -> Step.Lead(int(), int(), int())
                    "heal" -> Step.Heal
                    "money" -> Step.GiveMoney(int())
                    "sound" -> Step.PlaySound(int())
                    "cry" -> Step.PlayCry(int())
                    "music" -> Step.PlayMusic(int(), int())
                    else -> throw IllegalArgumentException("Unknown step '$w'")
                }
                ws(); if (i < s.length && s[i] == ';') i++
            }
            if (!endBrace) require(i >= s.length)
            return out
        }
    }
}
