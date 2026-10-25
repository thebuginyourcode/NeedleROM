; NeedleScript: a tiny event-script interpreter injected into Pokémon Red/Blue/Yellow.
;
; Layout of the engine bank (any otherwise-empty ROM bank):
;   $4000  "NEEDLE", format version, reserved
;   $4008  jp NeedleRun        (called through a ROM0 text_asm hook)
;   $400B  jp NeedleMapScript  (called every frame through a per-map script hook)
;   $4600  trigger table: 8-byte entries (map, x, y, text id, dw flag address, flag mask,
;          size = (width-1)<<4 | (height-1)), $FF-terminated; x/y of $FF match anywhere
;   $4800  script table: entries of (map id, text id, script pointer), $FF-terminated
;   $4C00+ script bytecode, dialogue and battle texts
;
; An event can pause while an NPC walks (OP_WALK_TO_PLAYER). The pause is kept in RAM
; bytes the game never reads: wNeedleState = $40 | text id while waiting for the walk,
; $80 | text id while resuming; wNeedlePtr = where to resume. wNeedleState is cleared by
; the game on every map change.
;
; Built with rgbasm per game version (tools/build_needlescript.py). Addresses of game
; routines and RAM come from version.inc, generated from the disassembly's .sym file.

INCLUDE "version.inc"

DEF SCRIPT_TABLE EQU $4800
DEF TRIGGERS     EQU $4600

DEF wNeedleState EQU wUnusedMapVariable
DEF wNeedlePtrLo EQU wUnusedPlayerDataByte
DEF wNeedlePtrHi EQU wUnusedObtainedBadges
; Pointer to the visibility table (written by the editor; 0 = none): 7-byte entries
; (map, sprite index, dw flag address, flag mask, invert, toggle index), $FF-terminated.
; The NPC is hidden when (flag set) xor invert.
DEF VIS_PTR EQU $45FE
DEF PAD_RIGHT EQU $10
DEF PAD_LEFT  EQU $20
DEF PAD_UP    EQU $40
DEF PAD_DOWN  EQU $80
DEF NPC_MOVEMENT_DOWN  EQU $00
DEF NPC_MOVEMENT_UP    EQU $40
DEF NPC_MOVEMENT_LEFT  EQU $80
DEF NPC_MOVEMENT_RIGHT EQU $C0

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
DEF OP_WALK_TO_PLAYER EQU 17 ; db sprite index (1-based); pauses the event until the walk ends
DEF OP_CRY          EQU 18 ; db species (internal id)
DEF OP_MUSIC        EQU 19 ; db music id, audio bank
DEF OP_LEAD         EQU 20 ; db sprite index (1-based), x, y; the player follows; pauses the event
DEF OP_HIDE         EQU 21 ; db sprite index
DEF OP_SHOW         EQU 22 ; db sprite index
DEF OP_FACE         EQU 23 ; db sprite index (0 = player), direction (0 down, 4 up, 8 left, 12 right)
DEF OP_MOVE_NPC     EQU 24 ; db sprite index, NPC movement directions..., $FF; pauses the event
DEF OP_MOVE_PLAYER  EQU 25 ; db joypad directions..., 0; pauses the event
DEF OP_WAIT         EQU 26 ; db frames
DEF OP_FADE_OUT     EQU 27
DEF OP_FADE_IN      EQU 28
DEF OP_WARP         EQU 29 ; db map, warp index (0-based); ends the event
DEF OP_IF_LESS_MONEY EQU 30 ; 3 bytes BCD ; dw jump_if_less
DEF OP_TAKE_MONEY   EQU 31 ; 3 bytes BCD (all of it if the player has less)
DEF OP_IF_NO_MON    EQU 32 ; db species ; dw jump_if_not_in_party
DEF OP_IF_RANDOM    EQU 33 ; db chance (0-255) ; dw jump_if_hit
DEF NUM_OPS         EQU 34

SECTION "NeedleHeader", ROMX[$4000], BANK[1]
	db "NEEDLE", 6, 0

SECTION "NeedleRun", ROMX[$4008], BANK[1]
	jp NeedleRun
	jp NeedleMapScript
	jp NeedleLoadToggles

; Entry: called via Bankswitch from the ROM0 hook while a text box is open.
NeedleRun::
	ld a, [wNeedleState]
	bit 7, a
	jp z, .lookup
; Resuming an event after an NPC finished walking.
	xor a
	ld [wNeedleState], a
	ld [wJoyIgnore], a
	ld [wDoNotWaitForButtonPressAfterDisplayingText], a
	ld a, [wNeedlePtrLo]
	ld l, a
	ld a, [wNeedlePtrHi]
	ld h, a
	jp RunScript
.lookup
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
	dw Op_GiveMoney, Op_IfNoItem, Op_TakeItem, Op_Sound, Op_WalkToPlayer, Op_Cry, Op_Music
	dw Op_Lead, Op_Hide, Op_Show, Op_Face, Op_MoveNpc, Op_MovePlayer, Op_Wait, Op_FadeOut
	dw Op_FadeIn, Op_Warp, Op_IfLessMoney, Op_TakeMoney, Op_IfNoMon, Op_IfRandom

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

Op_Cry:
	pop hl
	ld a, [hli]
	push hl
	call PlayCry ; waits for the cry to finish
	pop hl
	jp RunScript

; Plays until the next map change (or another music step).
Op_Music:
	pop hl
	ld a, [hli]
	ld b, a
	ld a, [hli]
	ld c, a
	push hl
	ld a, b
	ld [wLastMusicSoundID], a
	call PlayMusic
	pop hl
	jp RunScript

; Sets the pathfinding distances and directions from the NPC at [hNPCSpriteOffset]
; to the map tile (c, b) = (x, y), from map coordinates (so it works off screen too).
SetPathTarget:
	ld h, HIGH(wSpriteStateData2)
	ldh a, [hNPCSpriteOffset]
	add 4
	ld l, a
	ld e, 0
	ld a, [hli] ; map Y + 4
	sub 4
	ld d, a
	ld a, b
	sub d
	jr nc, .yDone
	cpl
	inc a
	set 0, e ; target is above: walk up
.yDone
	ldh [hNPCPlayerYDistance], a
	ld a, [hl] ; map X + 4
	sub 4
	ld d, a
	ld a, c
	sub d
	jr nc, .xDone
	cpl
	inc a
	set 1, e ; target is to the left: walk left
.xDone
	ldh [hNPCPlayerXDistance], a
	ld a, e
	ldh [hNPCPlayerRelativePosFlags], a
	ret

; Reads the sprite index at hl and selects that NPC. Returns hl past it.
SelectNPC:
	ld a, [hli]
	ldh [hSpriteIndex], a
	swap a
	ldh [hNPCSpriteOffset], a
	ret

; Finds the path set up by SetPathTarget and starts the NPC walking it.
WalkPath:
	ld b, BANK_FindPathToPlayer
	ld hl, FindPathToPlayer
	call Bankswitch
	ld de, wNPCMovementDirections2
	jp MoveSprite

; Ends the current event until the walking is done; NeedleMapScript then resumes it at
; hl. c = the event's text id.
PauseEvent:
	ld a, $FF ; freeze the player's controls
	ld [wJoyIgnore], a
	ld a, 1   ; close this text box without waiting for a button
	ld [wDoNotWaitForButtonPressAfterDisplayingText], a
	ld a, l
	ld [wNeedlePtrLo], a
	ld a, h
	ld [wNeedlePtrHi], a
	ld a, c
	and $3F
	or $40
	ld [wNeedleState], a
	ret

Op_WalkToPlayer:
	ldh a, [hTextID] ; the event's text id, needed to resume
	ld c, a
	pop hl
	call SelectNPC
	push hl
	push bc
	ld a, [wYCoord]
	ld b, a
	ld a, [wXCoord]
	ld c, a
	call SetPathTarget
; Stop next to the player: shorten the longer axis by one step.
	ldh a, [hNPCPlayerYDistance]
	ld b, a
	ldh a, [hNPCPlayerXDistance]
	cp b
	jp c, .shortenY
	and a
	jp z, .path
	dec a
	ldh [hNPCPlayerXDistance], a
	jp .path
.shortenY
	ld a, b
	dec a
	ldh [hNPCPlayerYDistance], a
.path
	call WalkPath
	pop bc
	pop hl
	jp PauseEvent

; The NPC walks to (x, y) and the player follows one step behind.
Op_Lead:
	ldh a, [hTextID]
	ld c, a
	pop hl
	call SelectNPC
	ld a, [hli] ; x
	ld e, a
	ld a, [hli] ; y
	ld d, a
	push hl
	push bc
	push de
; The player first walks to where the NPC starts.
	ld h, HIGH(wSpriteStateData2)
	ldh a, [hNPCSpriteOffset]
	add 4
	ld l, a
	ld a, [hli]
	sub 4
	ld [wNeedlePtrLo], a ; NPC start Y (wNeedlePtr is free until PauseEvent)
	ld a, [hl]
	sub 4
	ld [wNeedlePtrHi], a ; NPC start X
	pop de
	ld b, d
	ld c, e
	call SetPathTarget
	call WalkPath
; The player's steps go in wSimulatedJoypadStatesEnd, which the game plays from the
; end, so they're written backwards from the last slot. First count them: the walk to
; where the NPC started, then the NPC's path except its last step.
	ld hl, wNPCMovementDirections2
	ld b, 0
.count
	ld a, [hli]
	cp $FF
	jr z, .counted
	inc b
	jr .count
.counted
	ld a, b
	and a
	jp z, .noSteps ; the NPC is already there
	dec a
	ld b, a
	ld a, [wNeedlePtrHi] ; start X
	ld c, a
	ld a, [wXCoord]
	sub c
	jr nc, .absX
	cpl
	inc a
.absX
	add b
	ld b, a
	ld a, [wNeedlePtrLo] ; start Y
	ld c, a
	ld a, [wYCoord]
	sub c
	jr nc, .absY
	cpl
	inc a
.absY
	add b
	jp z, .noSteps
	ld [wSimulatedJoypadStatesIndex], a
	ld hl, wSimulatedJoypadStatesEnd - 1
	add l
	ld l, a
	jr nc, .xSteps
	inc h
.xSteps
	ld a, [wNeedlePtrHi]
	ld b, a
	ld a, [wXCoord]
	ld c, PAD_LEFT
	sub b
	jr z, .ySteps
	jr nc, .xCount ; the player is right of the start
	ld c, PAD_RIGHT
	cpl
	inc a
.xCount
	ld b, a
.xStep
	ld a, c
	ld [hld], a
	dec b
	jr nz, .xStep
.ySteps
	ld a, [wNeedlePtrLo]
	ld b, a
	ld a, [wYCoord]
	ld c, PAD_UP
	sub b
	jr z, .follow
	jr nc, .yCount ; the player is below the start
	ld c, PAD_DOWN
	cpl
	inc a
.yCount
	ld b, a
.yStep
	ld a, c
	ld [hld], a
	dec b
	jr nz, .yStep
.follow
	ld de, wNPCMovementDirections2
.copy
	ld a, [de]
	inc de
	ld b, a
	ld a, [de]
	cp $FF
	jr z, .started ; leave out the NPC's last step
	ld a, b
	ld c, PAD_DOWN
	cp NPC_MOVEMENT_DOWN
	jr z, .put
	ld c, PAD_UP
	cp NPC_MOVEMENT_UP
	jr z, .put
	ld c, PAD_LEFT
	cp NPC_MOVEMENT_LEFT
	jr z, .put
	ld c, PAD_RIGHT
.put
	ld a, c
	ld [hld], a
	jr .copy
.started
	call StartSimulatingJoypadStates
.noSteps
	pop bc
	pop hl
	jp PauseEvent

; ---- Showing and hiding NPCs ----------------------------------------------------------------

; Called instead of the game's own toggleable-object setup when a map loads: adds this
; map's event-controlled NPCs to the list, so they're right from the first frame.
NeedleLoadToggles::
	ld b, BANK_MarkTownVisitedAndLoadToggleableObjects
	ld hl, MarkTownVisitedAndLoadToggleableObjects
	call Bankswitch
SyncVisibility:
	ld hl, VIS_PTR
	ld a, [hli]
	ld h, [hl]
	ld l, a
	or h
	ret z
.entry
	ld a, [hl]
	cp $FF
	ret z
	ld b, a
	ld a, [wCurMap]
	cp b
	jr nz, .next
	push hl
	call SyncEntry
	pop hl
.next
	ld de, 7
	add hl, de
	jr .entry

; hl = visibility entry. Lists its NPC as toggleable and sets its hidden bit.
SyncEntry:
	inc hl
	ld a, [hli] ; sprite index
	ld c, a
	ld a, [hli]
	ld e, a
	ld a, [hli]
	ld d, a     ; flag address
	ld a, [de]
	and [hl]
	inc hl
	jr z, .flagClear
	ld a, 1
.flagClear
	xor [hl]    ; invert
	inc hl
	ld b, a     ; b = 1 if hidden
	ld e, [hl]  ; toggle index to use if the NPC isn't listed yet
	push bc
	ld hl, wToggleableObjectList
.find
	ld a, [hli]
	cp $FF
	jr z, .append
	cp c
	jr z, .found
	inc hl
	jr .find
.append
	dec hl
	ld a, c
	ld [hli], a
	ld a, e
	ld [hli], a
	ld [hl], $FF
	jr .haveIndex
.found
	ld a, [hl]
.haveIndex
	pop bc
	ld d, a
	and 7
	inc a
	ld e, a
	ld a, $80
.shift
	rlca
	dec e
	jr nz, .shift
	ld e, a     ; bit mask
	ld a, d
	srl a
	srl a
	srl a
	ld hl, wToggleableObjectFlags
	add l
	ld l, a
	jr nc, .noCarry
	inc h
.noCarry
	ld a, b
	and a
	ld a, e
	jr z, .show
	or [hl]
	ld [hl], a
	ret
.show
	cpl
	and [hl]
	ld [hl], a
	ret

Op_Hide:
	ld b, 1
	jr ShowHide
Op_Show:
	ld b, 0
ShowHide:
	pop hl
	ld a, [hli]
	push hl
	ld c, a
	ld hl, VIS_PTR
	ld a, [hli]
	ld h, [hl]
	ld l, a
	or h
	jr z, .done
.find
	ld a, [hl]
	cp $FF
	jr z, .done
	ld d, a
	ld a, [wCurMap]
	cp d
	jr nz, .next
	inc hl
	ld a, [hld]
	cp c
	jr z, .found
.next
	ld de, 7
	add hl, de
	jr .find
.found
	push hl
	inc hl
	inc hl
	ld a, [hli]
	ld e, a
	ld a, [hli]
	ld d, a     ; flag address
	ld a, [hli]
	ld c, a     ; mask
	ld a, b
	xor [hl]    ; the flag value that gives this visibility
	ld a, [de]
	jr z, .clearFlag
	or c
	jr .setFlag
.clearFlag
	ld b, a
	ld a, c
	cpl
	and b
.setFlag
	ld [de], a
	pop hl
	call SyncEntry
	call UpdateSprites
.done
	pop hl
	jp RunScript

; ---- More steps -----------------------------------------------------------------------------

Op_Face:
	pop hl
	ld a, [hli]
	ld b, a
	ld a, [hli]
	ld c, a
	push hl
	ld a, b
	and a
	jr nz, .npc
	ld a, c
	ld [wSpritePlayerStateData1FacingDirection], a
	jr .done
.npc
	ldh [hSpriteIndex], a
	ld a, c
	ldh [hSpriteFacingDirection], a
	call SetSpriteFacingDirectionAndDelay
.done
	pop hl
	jp RunScript

Op_MoveNpc:
	ldh a, [hTextID]
	ld c, a
	pop hl
	call SelectNPC
	push bc
	ld d, h
	ld e, l
.skip
	ld a, [hli]
	cp $FF
	jr nz, .skip
	push hl
	call MoveSprite ; copies the path at de
	pop hl
	pop bc
	jp PauseEvent

Op_MovePlayer:
	ldh a, [hTextID]
	ld c, a
	pop hl
	push bc
	ld d, h
	ld e, l
	ld b, 0
.count
	ld a, [hli]
	and a
	jr z, .counted
	inc b
	jr .count
.counted
	push hl
	ld a, b
	and a
	jr z, .none
	ld [wSimulatedJoypadStatesIndex], a
	ld hl, wSimulatedJoypadStatesEnd - 1
	add l
	ld l, a
	jr nc, .copy
	inc h
.copy
	ld a, [de]
	inc de
	ld [hld], a
	dec b
	jr nz, .copy
	call StartSimulatingJoypadStates
.none
	pop hl
	pop bc
	jp PauseEvent

Op_Wait:
	pop hl
	ld a, [hli]
	ld c, a
	push hl
	call DelayFrames
	pop hl
	jp RunScript

Op_FadeOut:
	call GBFadeOutToBlack
	pop hl
	jp RunScript

Op_FadeIn:
	call GBFadeInFromBlack
	pop hl
	jp RunScript

; Ends the event; NeedleMapScript then starts the warp from the map's script, the way
; the game's own scripts do (hWarpDestinationMap doesn't survive the text box closing).
Op_Warp:
	pop hl
	ld a, [hli]
	ld [wNeedlePtrLo], a
	ld a, [hli]
	ld [wNeedlePtrHi], a
	ld a, $20
	ld [wNeedleState], a
	ret

; hl -> 3 BCD bytes: copies them to hMoney, hl past them.
ReadMoney:
	ld a, [hli]
	ldh [hMoney], a
	ld a, [hli]
	ldh [hMoney + 1], a
	ld a, [hli]
	ldh [hMoney + 2], a
	ret

Op_IfLessMoney:
	pop hl
	call ReadMoney
	push hl
	call HasEnoughMoney
	pop hl
	jp c, TakeJump
	jp SkipJump

Op_TakeMoney:
	pop hl
	call ReadMoney
	push hl
	call HasEnoughMoney
	jr nc, .enough
	xor a
	ld hl, wPlayerMoney
	ld [hli], a
	ld [hli], a
	ld [hl], a
	jr .done
.enough
	call SubtractAmountPaidFromMoney
.done
	pop hl
	jp RunScript

Op_IfNoMon:
	pop hl
	ld a, [hli]
	ld b, a
	push hl
	ld hl, wPartySpecies
.loop
	ld a, [hli]
	cp $FF
	jr z, .missing
	cp b
	jr nz, .loop
	pop hl
	jp SkipJump
.missing
	pop hl
	jp TakeJump

Op_IfRandom:
	pop hl
	ld a, [hli]
	ld b, a
	push hl
	call Random
	pop hl
	cp b
	jp c, TakeJump
	jp SkipJump

; Called every frame from a map's script hook (before the map's own script).
NeedleMapScript::
	ld a, [wNeedleState]
	bit 5, a
	jr z, .noWarp
	xor a
	ld [wNeedleState], a
	ld a, [wNeedlePtrLo]
	ldh [hWarpDestinationMap], a
	ld a, [wNeedlePtrHi]
	ld [wDestinationWarpID], a
	ld a, [wCurMap]
	ld [wLastMap], a
	ld hl, wStatusFlags3
	set 3, [hl] ; BIT_WARP_FROM_CUR_SCRIPT
	ret
.noWarp
	bit 6, a
	jp z, .triggers
; Waiting for an NPC (and the player following it) to finish walking.
	ld a, [wStatusFlags5]
	and (1 << 0) | (1 << 7) ; BIT_SCRIPTED_NPC_MOVEMENT, BIT_SCRIPTED_MOVEMENT_STATE
	ret nz
	ld a, [wNeedleState]
	and $3F
	ldh [hTextID], a
	or $80
	ld [wNeedleState], a
	jp DisplayTextID
.triggers
	ld a, [wCurMap]
	ld c, a
	ld hl, TRIGGERS
.loop
	ld a, [hl]
	cp $FF
	ret z
	cp c
	jp nz, .next
	push hl
	ld de, 7
	add hl, de
	ld d, [hl]  ; size
	pop hl
	push hl
	inc hl
	ld a, [hli] ; x
	cp $FF
	jp z, .xOk
	ld b, a
	ld a, [wXCoord]
	sub b
	jp c, .no
	ld b, a
	ld a, d
	swap a
	and $0F
	cp b        ; width-1 < dx?
	jp c, .no
.xOk
	ld a, [hli] ; y
	cp $FF
	jp z, .yOk
	ld b, a
	ld a, [wYCoord]
	sub b
	jp c, .no
	ld b, a
	ld a, d
	and $0F
	cp b
	jp c, .no
.yOk
	ld a, [hli] ; text id
	ld b, a
	ld a, [hli]
	ld e, a
	ld a, [hli]
	ld d, a
	ld a, [hl]  ; mask
	ld c, a
	ld a, [de]
	and c
	jp nz, .no  ; already happened
	ld a, [de]
	or c
	ld [de], a
	pop hl
	ld a, b
	ldh [hTextID], a
	jp DisplayTextID
.no
	pop hl
	ld a, [wCurMap]
	ld c, a
.next
	ld de, 8
	add hl, de
	jp .loop

NeedleRunEnd::
	ASSERT NeedleRunEnd <= VIS_PTR, "engine code overlaps the visibility pointer"
