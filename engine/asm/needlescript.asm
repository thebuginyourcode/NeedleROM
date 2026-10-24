; NeedleScript: a tiny event-script interpreter injected into Pokémon Red/Blue/Yellow.
;
; Layout of the engine bank (any otherwise-empty ROM bank):
;   $4000  "NEEDLE", format version, reserved
;   $4008  NeedleRun (entry point, called through a ROM0 text_asm hook)
;   $4800  script table: entries of (map id, text id, script pointer), $FF-terminated
;   $4C00+ script bytecode, dialogue and battle texts
;
; Built with rgbasm per game version (tools/build_needlescript.py). Addresses of game
; routines and RAM come from version.inc, generated from the disassembly's .sym file.

INCLUDE "version.inc"

DEF SCRIPT_TABLE EQU $4800

; Opcodes (keep in sync with ScriptCompiler.kt)
DEF OP_END          EQU 0
DEF OP_TEXT         EQU 1  ; dw text
DEF OP_YESNO        EQU 2  ; dw jump_if_no
DEF OP_GIVE_ITEM    EQU 3  ; db item, qty ; dw jump_if_bag_full
DEF OP_GIVE_MON     EQU 4  ; db species, level ; dw jump_if_failed
DEF OP_SET_FLAG     EQU 5  ; dw address ; db mask
DEF OP_CLEAR_FLAG   EQU 6  ; dw address ; db mask
DEF OP_IF_FLAG      EQU 7  ; dw address ; db mask ; dw jump_if_set
DEF OP_IF_NOT_FLAG  EQU 8  ; dw address ; db mask ; dw jump_if_clear
DEF OP_JUMP         EQU 9  ; dw target
DEF OP_WILD_BATTLE  EQU 10 ; db species, level
DEF OP_TRAINER      EQU 11 ; db class+200, team ; dw win_text, lose_text
DEF OP_HEAL         EQU 12
DEF OP_GIVE_MONEY   EQU 13 ; 3 bytes BCD (big-endian)
DEF OP_IF_NO_ITEM   EQU 14 ; db item ; dw jump_if_missing
DEF OP_TAKE_ITEM    EQU 15 ; db item
DEF OP_SOUND        EQU 16 ; db sound id
DEF NUM_OPS         EQU 17

SECTION "NeedleHeader", ROMX[$4000], BANK[1]
	db "NEEDLE", 1, 0

SECTION "NeedleRun", ROMX[$4008], BANK[1]

; Entry: called via Bankswitch from the ROM0 hook while a text box is open.
NeedleRun::
; Work out the text id the way DisplayTextID does.
	ldh a, [hTextID]
	ld b, a
	ld a, [wNumSprites]
	cp b
	jp c, .haveTextId ; above the sprite count: a sign / text id
	ld a, b
	dec a
	add a
	inc a
	ld e, a
	ld d, 0
	ld hl, wMapSpriteData
	add hl, de
	ld b, [hl] ; the sprite's text id
.haveTextId
	ld a, [wCurMap]
	ld c, a
	ld hl, SCRIPT_TABLE
.find
	ld a, [hli]
	cp $FF
	ret z
	cp c
	jp nz, .skipMap
	ld a, [hli]
	cp b
	jp nz, .skipText
	ld a, [hli]
	ld h, [hl]
	ld l, a
	jp RunScript
.skipMap
	inc hl
.skipText
	inc hl
	inc hl
	jp .find

; hl = script pointer
RunScript:
	ld a, [hli]
	cp NUM_OPS
	ret nc
	push hl
	ld hl, Handlers
	add a
	ld e, a
	ld d, 0
	add hl, de
	ld a, [hli]
	ld h, [hl]
	ld l, a
	jp hl

Handlers:
	dw Op_End, Op_Text, Op_YesNo, Op_GiveItem, Op_GiveMon, Op_SetFlag, Op_ClearFlag
	dw Op_IfFlag, Op_IfNotFlag, Op_Jump, Op_WildBattle, Op_Trainer, Op_Heal
	dw Op_GiveMoney, Op_IfNoItem, Op_TakeItem, Op_Sound

Op_End:
	pop hl
	ret

; Reads a word at hl into de, hl += 2
ReadWord:
	ld a, [hli]
	ld e, a
	ld a, [hli]
	ld d, a
	ret

; Continue at the address stored at hl
TakeJump:
	ld a, [hli]
	ld h, [hl]
	ld l, a
	jp RunScript

; Skip a jump target
SkipJump:
	inc hl
	inc hl
	jp RunScript

Op_Text:
	pop hl
	call ReadWord
	push hl
	ld h, d
	ld l, e
	call PrintText
	pop hl
	jp RunScript

Op_YesNo:
	pop hl
	push hl
	call YesNoChoice
	pop hl
	ld a, [wCurrentMenuItem]
	and a
	jp nz, TakeJump ; "No"
	jp SkipJump

Op_GiveItem:
	pop hl
	ld a, [hli]
	ld b, a
	ld a, [hli]
	ld c, a
	push hl
	call GiveItem
	pop hl
	jp nc, TakeJump ; bag full
	jp SkipJump

Op_GiveMon:
	pop hl
	ld a, [hli]
	ld b, a
	ld a, [hli]
	ld c, a
	push hl
	call GivePokemon
	pop hl
	jp nc, TakeJump ; party and box full
	jp SkipJump

; hl -> dw address, db mask. Returns de = address, c = mask, hl past them.
ReadFlag:
	call ReadWord
	ld a, [hli]
	ld c, a
	ret

Op_SetFlag:
	pop hl
	call ReadFlag
	ld a, [de]
	or c
	ld [de], a
	jp RunScript

Op_ClearFlag:
	pop hl
	call ReadFlag
	ld a, c
	cpl
	ld c, a
	ld a, [de]
	and c
	ld [de], a
	jp RunScript

Op_IfFlag:
	pop hl
	call ReadFlag
	ld a, [de]
	and c
	jp nz, TakeJump
	jp SkipJump

Op_IfNotFlag:
	pop hl
	call ReadFlag
	ld a, [de]
	and c
	jp z, TakeJump
	jp SkipJump

Op_Jump:
	pop hl
	jp TakeJump

Op_WildBattle:
	pop hl
	ld a, [hli]
	ld [wCurOpponent], a
	ld a, [hli]
	ld [wCurEnemyLevel], a
	jp RunScript

Op_Trainer:
	pop hl
	ld a, [hli]
	ld [wCurOpponent], a
	ld a, [hli]
	ld [wTrainerNo], a
	call ReadWord ; win text
	push de
	call ReadWord ; lose text
	ld b, h
	ld c, l
	pop hl
	push bc
	call SaveEndBattleTextPointers ; hl = win, de = lose, bank = this bank
	pop hl
	jp RunScript

Op_Heal:
	pop hl
	push hl
	ld b, BANK(HealParty)
	ld hl, HealParty
	call Bankswitch
	pop hl
	jp RunScript

Op_GiveMoney:
	pop hl
	ld a, [hli]
	ldh [hMoney], a
	ld a, [hli]
	ldh [hMoney + 1], a
	ld a, [hli]
	ldh [hMoney + 2], a
	push hl
	call AddAmountSoldToMoney
	pop hl
	jp RunScript

Op_IfNoItem:
	pop hl
	ld a, [hli]
	ld b, a
	push hl
	call IsItemInBag
	pop hl
	jp z, TakeJump
	jp SkipJump

Op_TakeItem:
	pop hl
	ld a, [hli]
	ldh [hItemToRemoveID], a
	push hl
	ld b, BANK(RemoveItemByID)
	ld hl, RemoveItemByID
	call Bankswitch
	pop hl
	jp RunScript

Op_Sound:
	pop hl
	ld a, [hli]
	push hl
	call PlaySound
	call WaitForSoundToFinish
	pop hl
	jp RunScript

NeedleRunEnd::
