# Better Party Defence

Better Party Defence is a focused RuneLite plugin that displays a supported boss/NPC's estimated live Defence after defence-draining special attacks.

## Hub Party Panel integration

The user-facing party UI is **Hub Party Panel**. Hub Party Panel uses RuneLite's underlying `PartyService`, and Better Party Defence observes that same session. It does not create a second party, use OSParty networking, create a custom websocket/backend, or require RuneLite's built-in Party sidebar.

Local tracking now works even when you are not in a party. Hub Party is only the transport used to combine specs from multiple clients.

## How special attacks reach the tracker

Better Party Defence detects **your own supported defence-draining specs directly**. You do not need to enable RuneLite's separate Special Attack Counter just to make your own tracker work.

For party members:

- If they run Better Party Defence, their supported local drains are published to the existing Hub Party session.
- If they instead have RuneLite's **Special Attack Counter** enabled, its standard `SpecialCounterUpdate` messages are also understood by Better Party Defence.
- A client running only Hub Party Panel cannot contribute spec events, because Hub Party Panel does not transmit target/hit/weapon data for special attacks.

Better Party Defence reuses RuneLite's standard `SpecialCounterUpdate` wire message; it does not introduce a custom networking protocol.

## Quick functionality test

1. Enable Better Party Defence.
2. Leave **Show before first spec** enabled.
3. Attack/interact with a supported boss/NPC. Its starting Defence should appear immediately, even solo.
4. Land a supported defence-draining spec. The value should update.
5. For party testing, join the same party through **Hub Party Panel**. A second client can contribute specs by running either Better Party Defence or RuneLite's Special Attack Counter.

With `--debug`, `client.log` includes messages such as `Tracking supported NPC`, `Local defence spec`, `Party spec`, and `def ... -> ...`.

## Supported spec weapons

- Dragon Warhammer
- Elder Maul
- Bandos Godsword
- Arclight / Darklight
- Emberlight
- Barrelchest Anchor
- Bone Dagger
- Dorgeshuun Crossbow
- Accursed Sceptre
- Tonalztics of Ralos
- Seercull
- Eye of Ayak

## Attribution

The boss stat table and defence-drain calculations were derived from the OSParty code supplied with the original project. The local special-attack detection follows RuneLite's Special Attack Counter event model, reduced to defence-draining weapons only. See `ATTRIBUTION.md` and `LICENSE-OSPARTY.txt`.

## Multi-phase encounter handling

- **Kephri:** Defence drains persist while RuneLite swaps between the shielded and weak NPC actors. When Kephri enters the final enrage/vulnerable phase, the tracker restores Defence to the phase's real starting value (80, with the existing floor of 60) and rebinds the overlay to the new actor. The tracker clears when Kephri reaches her dead actor.
- **Sotetseg:** The tracker/overlay survives the combat ↔ maze NPC swaps instead of treating each NPC index as a new boss. Sotetseg genuinely restores Defence at each maze, so the displayed Defence is restored to 200 when the ToB encounter state enters the maze while the encounter itself remains tracked. The tracker clears only when the Sotetseg encounter state ends.
