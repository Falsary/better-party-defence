# Attribution

Better Party Defence was refactored from a project that included defence-tracking code derived from **OSParty** (`https://github.com/osparty/osparty`). OSParty is distributed under the BSD 2-Clause License; the required upstream notice and disclaimer are preserved in `LICENSE-OSPARTY.txt`.

The retained, defence-only portions include:

- boss/NPC base combat-stat and Defence-floor data used by the tracker;
- supported defence-draining weapon handling;
- Defence and Magic-defence state calculations;
- Chambers of Xeric scaling logic used by the original tracker;
- display/readout behavior associated with the defence tracker.

The refactor removes OSParty's party creation, networking, party UI, invites, ready checks, chat, applicant handling, sounds, and unrelated configuration/enums. No `net.osparty` runtime package remains.

Better Party Defence uses RuneLite's own `SpecialWeapon` / `SpecialCounterUpdate` types for party special events and RuneLite's `PartyService` session for transport. Hub Party Panel is the user-facing party UI.

## RuneLite Special Attack Counter

Version 1.2 adds a minimal local detector for supported defence-draining special attacks. Its event timing and hit association follow RuneLite's core `SpecialCounterPlugin` behavior. RuneLite is distributed under the BSD 2-Clause license; the implementation here is reduced to the data needed by Better Party Defence and keeps no Special Counter UI/notification functionality.
