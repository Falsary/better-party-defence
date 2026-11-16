package net.betterpartydefence;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.party.PartyService;

// Resolves the transport scope for the current world or raid encounter.
final class EncounterScopeResolver {
	private final Client client;
	private final PartyService partyService;

	EncounterScopeResolver(Client client, PartyService partyService) {
		this.client = client;
		this.partyService = partyService;
	}

	SyncScope current() {
		int coxController = raidController(BpdDefenceSync.SCOPE_COX);
		if (coxController > 0) {
			return new SyncScope(BpdDefenceSync.SCOPE_COX, coxController);
		}

		int tobController = raidController(BpdDefenceSync.SCOPE_TOB);
		if (tobController > 0) {
			return new SyncScope(BpdDefenceSync.SCOPE_TOB, tobController);
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null) {
			return null;
		}
		if (worldView.isInstance()) {
			return instanceScope(worldView);
		}
		return new SyncScope(BpdDefenceSync.SCOPE_WORLD, 0);
	}

	SyncScope forBoss(BossDefence boss, NPC npc) {
		int raidScope = EncounterScopes.raidScopeType(boss);
		if (raidScope != -1) {
			int controller = raidController(raidScope);
			if (controller > 0) {
				return new SyncScope(raidScope, controller);
			}

			// CoX can clear its public controller after starting.
			// Use the live instance only to match party packets.
			WorldView raidWorldView = client.getTopLevelWorldView();
			if (raidWorldView != null && raidWorldView.isInstance()) {
				return instanceScope(raidWorldView);
			}
			return null;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null) {
			return null;
		}
		if (worldView.isInstance()) {
			return instanceScope(worldView);
		}
		return npc == null ? null : new SyncScope(BpdDefenceSync.SCOPE_WORLD, 0);
	}

	SyncScope currentRaidScopeForBoss(BossDefence boss) {
		int raidScope = EncounterScopes.raidScopeType(boss);
		if (raidScope != -1) {
			int controller = raidController(raidScope);
			return controller > 0 ? new SyncScope(raidScope, controller) : null;
		}

		if (!EncounterScopes.isToaBoss(boss) && !EncounterScopes.isTfaBoss(boss)) {
			return null;
		}

		WorldView worldView = client.getTopLevelWorldView();
		return worldView != null && worldView.isInstance()
				? instanceScope(worldView)
				: null;
	}

	String raidBegunScopeKey() {
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView != null && worldView.isInstance()) {
			return BpdDefenceSync.SCOPE_INSTANCE + ":" + EncounterScopes.instanceFingerprint(worldView);
		}
		SyncScope scope = current();
		return scope == null ? null : scope.getType() + ":" + scope.getId();
	}

	boolean incomingScopeMatches(BpdDefenceSync event, BossDefence boss, NPC localBoss) {
		if (!scopeMatches(event.getScopeType(), event.getScopeId(), boss)) {
			return false;
		}
		if (event.getScopeType() != BpdDefenceSync.SCOPE_WORLD) {
			return true;
		}
		if (localBoss == null) {
			return false;
		}

		// Open-world HP may be unavailable before combat.
		WorldPoint point = localBoss.getWorldLocation();
		WorldPoint remotePoint = event.getBossLocation();
		if (point == null || remotePoint == null
				|| point.distanceTo(remotePoint) > EncounterScopes.POSITION_TOLERANCE) {
			return false;
		}
		return healthMatches(localBoss, event.getHealthPercent());
	}

	boolean scopeMatches(int scopeType, int scopeId, BossDefence boss) {
		int expectedRaidScope = EncounterScopes.raidScopeType(boss);
		if (expectedRaidScope != -1) {
			// A peer can still use the instance id after the controller disappears.
			if (scopeType == BpdDefenceSync.SCOPE_INSTANCE) {
				WorldView raidWorldView = client.getTopLevelWorldView();
				return matchesInstance(raidWorldView, scopeId);
			}
			int controller = raidController(expectedRaidScope);
			return controller > 0 && scopeType == expectedRaidScope && scopeId == controller;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null) {
			return false;
		}
		if (scopeType == BpdDefenceSync.SCOPE_INSTANCE) {
			return matchesInstance(worldView, scopeId);
		}
		return scopeType == BpdDefenceSync.SCOPE_WORLD && !worldView.isInstance();
	}

	boolean syncScopeStillValid(int scopeType, int scopeId) {
		if (client.getGameState() != GameState.LOGGED_IN || !partyService.isInParty()) {
			return false;
		}
		switch (scopeType) {
			case BpdDefenceSync.SCOPE_COX:
			case BpdDefenceSync.SCOPE_TOB:
				return scopeId > 0 && raidController(scopeType) == scopeId;
			case BpdDefenceSync.SCOPE_INSTANCE:
				WorldView worldView = client.getTopLevelWorldView();
				return matchesInstance(worldView, scopeId);
			case BpdDefenceSync.SCOPE_WORLD:
			default:
				// Leaving render does not end an open-world encounter.
				return true;
		}
	}

	private static SyncScope instanceScope(WorldView worldView) {
		return new SyncScope(BpdDefenceSync.SCOPE_INSTANCE, EncounterScopes.instanceFingerprint(worldView));
	}

	private static boolean matchesInstance(WorldView worldView, int scopeId) {
		return worldView != null && worldView.isInstance()
				&& EncounterScopes.instanceFingerprint(worldView) == scopeId;
	}

	int raidController(int scopeType) {
		switch (scopeType) {
			case BpdDefenceSync.SCOPE_COX:
				return client.getVarpValue(VarPlayerID.RAIDS_PARTY_GROUPHOLDER);
			case BpdDefenceSync.SCOPE_TOB:
				return client.getVarpValue(VarPlayerID.TOB_MYCONTROLLER);
			default:
				return -1;
		}
	}

	static boolean healthMatches(NPC npc, int remoteHp) {
		int localHp = healthPercent(npc);
		return localHp < 0 || remoteHp < 0
				|| Math.abs(localHp - remoteHp) <= EncounterScopes.HEALTH_PERCENT_TOLERANCE;
	}

	static int healthPercent(NPC npc) {
		if (npc == null || npc.getHealthRatio() < 0 || npc.getHealthScale() <= 0) {
			return -1;
		}
		return Math.max(0, Math.min(100,
				(int) Math.round(npc.getHealthRatio() * 100.0 / npc.getHealthScale())));
	}
}
