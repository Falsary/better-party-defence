package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.betterpartydefence.DefenceTracker.SyncState;
import net.betterpartydefence.DefenceTracker.SyncTarget;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/**
 * Production overlay regressions for independently tracked NPCs.
 *
 * <p>The test records which NPCs the real {@link NpcDefenceOverlay#render(Graphics2D)} path asks
 * to anchor text on. It does not replace the overlay's target-selection logic with a separate
 * idealized renderer.
 */
public class NpcDefenceOverlayDisplayRegressionTest
{
	private static final int WORLD = 301;
	private static final int BOSS_A_INDEX = 41;
	private static final int BOSS_B_INDEX = 42;
	private static final int BOSS_C_INDEX = 43;

	@Test
	public void singleTeammateSpecRendersWithoutLocalSelection()
	{
		Harness h = new Harness();
		NPC bossA = h.addNpc(BOSS_A_INDEX, "Chaos Elemental");
		h.trackPartySpec(bossA, "Alice");

		h.renderOverlay();

		assertEquals(BOSS_A_INDEX, h.tracker.state().getNpcIndex());
		verify(bossA).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());
	}

	@Test
	public void teammateSpecTargetRendersWhileCurrentTargetRemainsSelected()
	{
		Harness h = new Harness();
		NPC bossA = h.addNpc(BOSS_A_INDEX, "Chaos Elemental");
		NPC bossB = h.addNpc(BOSS_B_INDEX, "Corporeal Beast");
		h.selectAndDrain(bossB, "LocalPlayer");
		h.trackPartySpec(bossA, "Alice");

		h.renderOverlay();

		assertEquals("the active target must remain Boss B", BOSS_B_INDEX,
			h.tracker.state().getNpcIndex());
		assertEquals("Boss A's party evidence must remain recorded", 1,
			h.historySize(BOSS_A_INDEX));
		verify(bossA).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());
		verify(bossB).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());
	}

	@Test
	public void previousTargetSettingDoesNotSuppressOtherTrackedOverheads()
	{
		Harness h = new Harness();
		h.previousTargetDisplay = false;
		NPC bossA = h.addNpc(BOSS_A_INDEX, "Chaos Elemental");
		NPC bossB = h.addNpc(BOSS_B_INDEX, "Corporeal Beast");
		h.selectAndDrain(bossB, "LocalPlayer");
		h.trackPartySpec(bossA, "Alice");

		h.renderOverlay();

		assertEquals(BOSS_B_INDEX, h.tracker.state().getNpcIndex());
		verify(bossA).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());
		verify(bossB).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());
	}

	@Test
	public void threeTrackedBossesRenderIndependently()
	{
		Harness h = new Harness();
		NPC bossA = h.addNpc(BOSS_A_INDEX, "Chaos Elemental");
		NPC bossB = h.addNpc(BOSS_B_INDEX, "Corporeal Beast");
		NPC bossC = h.addNpc(BOSS_C_INDEX, "King Black Dragon");
		h.selectAndDrain(bossB, "LocalPlayer");
		h.trackPartySpec(bossA, "Alice");
		h.trackPartySpec(bossC, "Bob");

		h.renderOverlay();

		assertEquals(3, h.tracker.states().size());
		assertEquals(BOSS_B_INDEX, h.tracker.state().getNpcIndex());
		verify(bossA).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());
		verify(bossB).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());
		verify(bossC).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());
	}

	@Test
	public void previousTargetIsDrawnOnceWithOtherPartyTarget()
	{
		Harness h = new Harness();
		NPC bossA = h.addNpc(BOSS_A_INDEX, "Chaos Elemental");
		NPC bossB = h.addNpc(BOSS_B_INDEX, "Corporeal Beast");
		NPC bossC = h.addNpc(BOSS_C_INDEX, "King Black Dragon");
		h.selectAndDrain(bossB, "LocalPlayer");
		h.selectAndDrain(bossC, "LocalPlayer");
		h.trackPartySpec(bossA, "Alice");

		h.renderOverlay();

		assertEquals(BOSS_C_INDEX, h.tracker.state().getNpcIndex());
		verify(bossA).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());
		verify(bossB).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());
		verify(bossC).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());
	}

	@Test
	public void clickingAnotherBossChangesInfoBoxSelectionOnly()
	{
		Harness h = new Harness();
		NPC bossA = h.addNpc(BOSS_A_INDEX, "Chaos Elemental");
		NPC bossB = h.addNpc(BOSS_B_INDEX, "Corporeal Beast");
		h.selectAndDrain(bossB, "LocalPlayer");
		h.trackPartySpec(bossA, "Alice");
		assertEquals(1, h.historySize(BOSS_A_INDEX));
		DefenceInfoBox infoBox = h.infoBox();
		assertEquals(Long.toString(h.tracker.state().getCurrent()), infoBox.getText());

		h.interacting = bossA;
		h.advanceTick();

		assertEquals(BOSS_A_INDEX, h.tracker.state().getNpcIndex());
		assertEquals(Long.toString(h.tracker.state().getCurrent()), infoBox.getText());
		assertEquals("interaction must not replay combat evidence", 1,
			h.historySize(BOSS_A_INDEX));
	}

	@Test
	public void outOfRenderTargetAppearsWhenItReturnsWithoutInteraction()
	{
		Harness h = new Harness();
		NPC bossA = h.addNpc(BOSS_A_INDEX, "Chaos Elemental");
		NPC bossB = h.addNpc(BOSS_B_INDEX, "Corporeal Beast");
		h.selectAndDrain(bossB, "LocalPlayer");
		h.trackPartySpec(bossA, "Alice");

		h.removeNpc(bossA);
		h.renderOverlay();
		verify(bossA, never()).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());
		verify(bossB).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());

		h.restoreNpc(bossA);
		h.renderOverlay();
		verify(bossA).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());
		assertEquals("restoring rendering must not steal the active target", BOSS_B_INDEX,
			h.tracker.state().getNpcIndex());
	}

	@Test
	public void initialSyncGateStillHidesUndrainedTargetUntilReleased()
	{
		Harness h = new Harness();
		h.alwaysShow = true;
		NPC bossA = h.addNpc(BOSS_A_INDEX, "Chaos Elemental");
		NPC bossB = h.addNpc(BOSS_B_INDEX, "Corporeal Beast");
		h.interacting = bossA;
		h.advanceTick();
		h.initialSyncDisplayGate.arm(BOSS_A_INDEX, BossDefence.CHAOS_ELEMENTAL, h.tick, 4);
		h.selectAndDrain(bossB, "LocalPlayer");

		h.renderOverlay();
		verify(bossA, never()).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());

		h.initialSyncDisplayGate.release(BOSS_A_INDEX, BossDefence.CHAOS_ELEMENTAL);
		h.renderOverlay();
		verify(bossA).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());
	}

	@Test
	public void encounterResetRemovesAllOverheads()
	{
		Harness h = new Harness();
		NPC bossA = h.addNpc(BOSS_A_INDEX, "Chaos Elemental");
		NPC bossB = h.addNpc(BOSS_B_INDEX, "Corporeal Beast");
		h.selectAndDrain(bossB, "LocalPlayer");
		h.trackPartySpec(bossA, "Alice");

		h.tracker.reset("test reset");
		h.renderOverlay();

		assertTrue(h.tracker.states().isEmpty());
		verify(bossA, never()).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());
		verify(bossB, never()).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());
	}

	@Test
	public void disabledTrackerDrawsNoOverheads()
	{
		Harness h = new Harness();
		h.defenceEnabled = false;
		NPC bossA = h.addNpc(BOSS_A_INDEX, "Chaos Elemental");
		h.trackPartySpec(bossA, "Alice");

		h.renderOverlay();

		verify(bossA, never()).getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt());
	}

	@Test
	public void configuredPositionIsAppliedToEveryTrackedTarget()
	{
		Harness h = new Harness();
		h.position = DefenceOverlayPosition.RIGHT_OF_HP_BAR;
		NPC bossA = h.addNpc(BOSS_A_INDEX, "Chaos Elemental");
		NPC bossB = h.addNpc(BOSS_B_INDEX, "Corporeal Beast");
		h.selectAndDrain(bossB, "LocalPlayer");
		h.trackPartySpec(bossA, "Alice");

		h.renderOverlay();

		int expectedZOffset = (int) (100 * h.position.getHeightFactor())
			+ h.position.getHeightOffset();
		verify(bossA).getCanvasTextLocation(h.graphics, "", expectedZOffset);
		verify(bossB).getCanvasTextLocation(h.graphics, "", expectedZOffset);
	}

	private static final class Harness
	{
		private final Map<Integer, NPC> npcsByIndex = new LinkedHashMap<>();
		private final Client client = mock(Client.class);
		private final WorldView worldView = mock(WorldView.class);
		@SuppressWarnings("unchecked")
		private final IndexedObjectSet<NPC> npcs = mock(IndexedObjectSet.class);
		@SuppressWarnings("unchecked")
		private final IndexedObjectSet<Player> players = mock(IndexedObjectSet.class);
		private final Player localPlayer = mock(Player.class);
		private final BetterPartyDefenceConfig config = mock(BetterPartyDefenceConfig.class);
		private final Graphics2D graphics = mock(Graphics2D.class);
		private final FontMetrics fontMetrics = mock(FontMetrics.class);
		private final BufferedImage skillImage =
			new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		private final SkillIconManager skillIconManager = mock(SkillIconManager.class);
		private final InitialSyncDisplayGate initialSyncDisplayGate = new InitialSyncDisplayGate();
		private final DefenceTracker tracker;
		private final PreviousTargetDisplayState previousTargetDisplayState;
		private final NpcDefenceOverlay overlay;

		private boolean previousTargetDisplay = true;
		private boolean alwaysShow;
		private boolean defenceEnabled = true;
		private DefenceOverlayPosition position = DefenceOverlayPosition.ABOVE_HP_BAR;
		private int tick = 100;
		private NPC interacting;

		@SuppressWarnings("unchecked")
		private Harness()
		{
			when(client.getTopLevelWorldView()).thenReturn(worldView);
			when(client.getLocalPlayer()).thenReturn(localPlayer);
			when(client.getTickCount()).thenAnswer(invocation -> tick);
			when(client.getWorld()).thenReturn(WORLD);
			doReturn(npcs).when(worldView).npcs();
			doReturn(players).when(worldView).players();
			when(npcs.byIndex(anyInt())).thenAnswer(
				invocation -> npcsByIndex.get(invocation.getArgument(0)));
			when(npcs.iterator()).thenAnswer(
				invocation -> new ArrayList<>(npcsByIndex.values()).iterator());
			when(players.iterator()).thenReturn(java.util.Collections.emptyIterator());
			when(localPlayer.getInteracting()).thenAnswer(invocation -> interacting);
			when(localPlayer.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
			when(skillIconManager.getSkillImage(any())).thenReturn(skillImage);

			when(config.defenceHpBar()).thenAnswer(invocation -> defenceEnabled);
			when(config.defenceAlwaysShow()).thenAnswer(invocation -> alwaysShow);
			when(config.previousTargetDisplay()).thenAnswer(invocation -> previousTargetDisplay);
			when(config.defenceHpBarPosition()).thenAnswer(invocation -> position);
			when(config.defenceHpBarYOffset()).thenReturn(0);
			when(config.defenceValueFormat()).thenReturn(DefenceValueFormat.CURRENT);
			when(config.defenceDrainFormat()).thenReturn(DefenceDrainFormat.AMOUNT);
			when(config.defenceInfoBoxValue()).thenReturn(DefenceInfoBoxValue.CURRENT);
			when(config.defenceShowFullLevel()).thenReturn(false);
			when(config.defenceShowIcons()).thenReturn(false);
			when(config.defenceUseThemeSkillIcons()).thenReturn(false);
			when(config.defenceTextPlate()).thenReturn(false);
			when(config.defenceLowThresholdUnit()).thenReturn(DefenceThresholdUnit.LEVELS);
			when(config.defenceLowThreshold()).thenReturn(10);
			when(config.defenceHighColor()).thenReturn(Color.WHITE);
			when(config.defenceLowColor()).thenReturn(Color.YELLOW);
			when(config.defenceCappedColor()).thenReturn(Color.GREEN);
			when(config.defenceDrainColor()).thenReturn(Color.RED);
			when(config.magicDefence()).thenReturn(false);
			when(config.defenceFont()).thenReturn(TrackerFont.SANS_SERIF);
			when(config.defenceFontSize()).thenReturn(12);
			when(config.defenceBoldText()).thenReturn(false);

			when(graphics.getFontMetrics()).thenReturn(fontMetrics);
			when(fontMetrics.stringWidth(anyString())).thenReturn(10);
			when(fontMetrics.getHeight()).thenReturn(12);
			when(fontMetrics.getAscent()).thenReturn(9);
			when(fontMetrics.getDescent()).thenReturn(3);

			tracker = new DefenceTracker(client, config);
			previousTargetDisplayState = new PreviousTargetDisplayState(client, tracker, config);
			overlay = new NpcDefenceOverlay(client, tracker, config,
				new SkillIconSource(client, skillIconManager, config),
				new TrackerFontManager(config), previousTargetDisplayState, initialSyncDisplayGate);
		}

		private NPC addNpc(int index, String name)
		{
			NPC npc = mock(NPC.class);
			when(npc.getName()).thenReturn(name);
			when(npc.getIndex()).thenReturn(index);
			when(npc.getId()).thenReturn(0);
			when(npc.getHealthRatio()).thenReturn(1);
			when(npc.getHealthScale()).thenReturn(1);
			when(npc.isDead()).thenReturn(false);
			when(npc.getLogicalHeight()).thenReturn(100);
			when(npc.getWorldLocation()).thenReturn(new WorldPoint(3200 + index, 3200, 0));
			Point anchor = mock(Point.class);
			when(anchor.getX()).thenReturn(100);
			when(anchor.getY()).thenReturn(100);
			when(npc.getCanvasTextLocation(any(Graphics2D.class), anyString(), anyInt()))
				.thenReturn(anchor);
			npcsByIndex.put(index, npc);
			return npc;
		}

		private void removeNpc(NPC npc)
		{
			npcsByIndex.remove(npc.getIndex());
		}

		private void restoreNpc(NPC npc)
		{
			npcsByIndex.put(npc.getIndex(), npc);
		}

		private void selectAndDrain(NPC npc, String playerName)
		{
			interacting = npc;
			tracker.queueLocal(SpecialWeapon.DRAGON_WARHAMMER, npc.getIndex(), 1, WORLD,
				playerName, npc);
			advanceTick();
		}

		private void trackPartySpec(NPC npc, String playerName)
		{
			tracker.queue(SpecialWeapon.DRAGON_WARHAMMER, npc.getIndex(), 1, WORLD,
				playerName, npc);
			advanceTick();
		}

		private void advanceTick()
		{
			tick++;
			tracker.onGameTick();
			previousTargetDisplayState.update();
		}

		private void renderOverlay()
		{
			overlay.render(graphics);
		}

		private DefenceInfoBox infoBox()
		{
			return new DefenceInfoBox(
				new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
				mock(Plugin.class), tracker, config);
		}

		private int historySize(int npcIndex)
		{
			for (SyncTarget target : tracker.syncTargets())
			{
				if (target.getNpcIndex() != npcIndex || target.getState() == null)
				{
					continue;
				}
				SyncState state = target.getState();
				return state.getHistory() == null ? 0 : state.getHistory().size();
			}
			return 0;
		}
	}
}
