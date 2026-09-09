## Better Party Defence

Better Party Defence is a focused RuneLite plugin that displays a supported boss/NPC's estimated live Defence after defence-draining special attacks.

## What makes this plugin different?

Better Party Defence tracks the target's current estimated Defence instead of only counting how many special attacks have landed.

It keeps track of the reductions that have happened during the encounter and can share that state with other Better Party Defence users through RuneLite's existing party system.

## Some of the main differences are:

Tracks the target's current estimated Defence.
Keeps a history of successful Defence-reducing specs.
Syncs Defence state between Better Party Defence users in the same encounter.
Keeps different raid instances separate even when those players are all in the same RuneLite party.
Lets a player entering the area later pick up the Defence state that the rest of the party already established.
Keeps separate Defence states for multiple targets in the same encounter.
Keeps synced state while the encounter is still active without showing the InfoBox to players who are not near the target.
Tracks Magic reductions separately from normal Defence reductions.
Includes separate Defence and Magic Defence InfoBoxes.
Supports InfoBoxes, NPC overlays, detached displays, custom fonts, icons, and other display options.

## !Party sync uses RuneLite's existing party system. Better Party Defence does not require a separate server or account.!

## Hub Party Panel integration

The user-facing party UI is Hub Party Panel. Hub Party Panel uses RuneLite's underlying PartyService, and Better Party Defence observes that same session.

Better Party Defence does not create a second party, use OSParty networking, create a custom websocket/backend, or require RuneLite's built-in Party sidebar.

Local tracking works even when you are not in a party. Hub Party is only used to combine supported spec and Defence state information between clients.

Better Party Defence also keeps synced encounters separated. Players can be in the same Hub Party while participating in different raid instances without sharing Defence state between those raids.

## How special attacks reach the tracker

Better Party Defence detects your own supported defence-draining specs directly. You do not need to enable RuneLite's separate Special Attack Counter for your own tracker to work.

For party members:

If they run Better Party Defence, their supported local drains are published to the existing Hub Party session.
Better Party Defence users also share the current tracked Defence state, allowing another client to catch up if they enter the area after specs have already landed.
Synced state is scoped to the encounter the player is actually in. Better Party Defence users in another raid instance are ignored even if they are members of the same Hub Party.
If a party member instead has RuneLite's Special Attack Counter enabled, its standard SpecialCounterUpdate messages are also understood by Better Party Defence.
A client running only Hub Party Panel cannot contribute spec events because Hub Party Panel does not transmit target, hit, or weapon information for special attacks.

Better Party Defence uses RuneLite's existing party infrastructure and standard party spec messages where applicable. It does not require an external networking service.



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

Magic reductions are tracked separately where applicable. Seercull and Eye of Ayak affect the Magic Defence display without causing the normal Defence InfoBox to appear. Accursed Sceptre affects both tracked values where applicable.

## Multi-phase encounter handling
Sotetseg: The tracker and overlay survive the combat ↔ maze NPC swaps instead of treating each NPC index as a new boss. Sotetseg restores Defence at each maze, so the displayed Defence is restored to 200 when the ToB encounter state enters the maze while the encounter itself remains tracked. The tracker clears only when the Sotetseg encounter state ends.

## Attribution

The boss stat table and defence-drain calculations were derived from the OSParty code supplied with the original project.

The local special-attack detection follows RuneLite's Special Attack Counter event model, reduced to defence-draining weapons only.

See ATTRIBUTION.md and LICENSE-OSPARTY.txt.

Disclaimer

Better Party Defence is an unofficial third-party RuneLite plugin and is not affiliated with or endorsed by Jagex Ltd.

RuneScape, Old School RuneScape, and related trademarks and game content are the property of Jagex Ltd. Better Party Defence does not claim ownership of any RuneScape or Old School RuneScape assets, names, or intellectual property.