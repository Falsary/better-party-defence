package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.specialcounter.SpecialCounterUpdate;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression coverage for the observed TFA Archive Guardian Ralos rejection. The test models a
 * fresh sender heartbeat whose scope differs from the receiver's while both players and one
 * unambiguous opponent are present in the local world view.
 */
public class ArchiveGuardianScopeRegressionTest
{
	private static final int WORLD = 474;
	private static final int NPC_INDEX = 55828;
	private static final int SENDER_PLAYER_ID = 1733;
	private static final long SENDER_MEMBER_ID = 7L;

	private BetterPartyDefencePlugin plugin;
	private DefenceTracker tracker;
	private Client client;
	private WorldView worldView;
	private IndexedObjectSet<NPC> npcs;
	private IndexedObjectSet<Player> players;
	private NPC guardian;
	private Player sender;
	private PartyService party;
	private PartyMember senderMember;

	@Before
	@SuppressWarnings("unchecked")
	public void setUp() throws Exception
	{
		client = mock(Client.class);
		worldView = mock(WorldView.class);
		npcs = mock(IndexedObjectSet.class);
		players = mock(IndexedObjectSet.class);
		guardian = mock(NPC.class);
		sender = mock(Player.class);
		party = mock(PartyService.class);
		senderMember = mock(PartyMember.class);

		when(client.getWorld()).thenReturn(WORLD);
		when(client.getTickCount()).thenReturn(100);
		when(client.getTopLevelWorldView()).thenReturn(worldView);
		when(worldView.isInstance()).thenReturn(true);
		doReturn(npcs).when(worldView).npcs();
		doReturn(players).when(worldView).players();

		when(guardian.getName()).thenReturn("Archive Guardian");
		when(guardian.getIndex()).thenReturn(NPC_INDEX);
		when(guardian.getId()).thenReturn(0);
		when(guardian.getHealthRatio()).thenReturn(1);
		when(guardian.getHealthScale()).thenReturn(1);
		when(guardian.getWorldLocation()).thenReturn(new WorldPoint(0, 0, 0));
		when(npcs.byIndex(NPC_INDEX)).thenReturn(guardian);
		when(npcs.iterator()).thenAnswer(invocation -> Collections.singletonList(guardian).iterator());

		when(sender.getId()).thenReturn(SENDER_PLAYER_ID);
		when(players.iterator()).thenAnswer(invocation -> Collections.singletonList(sender).iterator());

		when(party.isInParty()).thenReturn(true);
		when(party.getMemberById(SENDER_MEMBER_ID)).thenReturn(senderMember);
		when(senderMember.getDisplayName()).thenReturn("RalosPlayer");

		BetterPartyDefenceConfig config = mock(BetterPartyDefenceConfig.class);
		tracker = new DefenceTracker(client, config);

		ClientThread clientThread = mock(ClientThread.class);
		doAnswer(invocation -> {
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invoke(any(Runnable.class));

		plugin = new BetterPartyDefencePlugin();
		setPluginField("client", client);
		setPluginField("config", config);
		setPluginField("defenceTracker", tracker);
		setPluginField("partyService", party);
		setPluginField("clientThread", clientThread);
	}

	@Test
	public void archiveGuardianRalosAcceptedDespiteDifferentScopeWhenTargetEvidenceMatches() throws Exception
	{
		rememberSenderScope(instanceFingerprint() + 1);

		deliverRalos();
		tracker.onGameTick();

		assertEquals("the current encounter target must not be discarded solely on scope mismatch",
			1, tracker.specHistory().size());
		assertEquals(SpecialWeapon.TONALZTICS_OF_RALOS,
			tracker.specHistory().get(0).getWeapon());
		assertEquals(BossDefence.TFA_ARCHIVE_GUARDIAN, tracker.trackedBossType());
	}

	@Test
	public void scopeMismatchAcceptedWhenSenderIsNotRenderedButTargetIsUnique() throws Exception
	{
		when(players.iterator()).thenAnswer(invocation -> Collections.emptyIterator());
		rememberSenderScope(instanceFingerprint() + 1);

		deliverRalos();
		tracker.onGameTick();

		assertEquals("sender rendering is not evidence about the combat target",
			1, tracker.specHistory().size());
		assertEquals(BossDefence.TFA_ARCHIVE_GUARDIAN, tracker.trackedBossType());
	}

	@Test
	public void scopeMismatchStillRejectedWhenTargetIsAmbiguous() throws Exception
	{
		NPC secondGuardian = mock(NPC.class);
		when(secondGuardian.getName()).thenReturn("Archive Guardian");
		when(secondGuardian.getIndex()).thenReturn(NPC_INDEX + 1);
		when(secondGuardian.getHealthRatio()).thenReturn(1);
		when(npcs.iterator()).thenAnswer(invocation ->
			Arrays.asList(guardian, secondGuardian).iterator());
		rememberSenderScope(instanceFingerprint() + 1);

		deliverRalos();
		tracker.onGameTick();

		assertTrue("two live same-type targets cannot be resolved by the sender hint",
			tracker.specHistory().isEmpty());
		assertNull(tracker.state());
	}

	@Test
	public void scopeMismatchStillRejectedWhenEventTargetIsUnidentified() throws Exception
	{
		when(npcs.byIndex(NPC_INDEX)).thenReturn(null);
		rememberSenderScope(instanceFingerprint() + 1);

		deliverRalos();
		tracker.onGameTick();

		assertTrue("an unknown local NPC index is not sufficient target evidence",
			tracker.specHistory().isEmpty());
		assertNull(tracker.state());
	}

	private void deliverRalos()
	{
		SpecialCounterUpdate packet = new SpecialCounterUpdate(
			NPC_INDEX, SpecialWeapon.TONALZTICS_OF_RALOS, 2, WORLD, SENDER_PLAYER_ID);
		packet.setMemberId(SENDER_MEMBER_ID);
		plugin.onSpecialCounterUpdate(packet);
	}

	private void rememberSenderScope(int scopeId) throws Exception
	{
		Method remember = BetterPartyDefencePlugin.class.getDeclaredMethod(
			"rememberSenderEncounterPresence",
			long.class, int.class, int.class, int.class, long.class);
		remember.setAccessible(true);
		remember.invoke(plugin, SENDER_MEMBER_ID, WORLD,
			BpdDefenceSync.SCOPE_INSTANCE, scopeId, System.currentTimeMillis());
	}

	private int instanceFingerprint() throws Exception
	{
		Method method = BetterPartyDefencePlugin.class.getDeclaredMethod(
			"instanceFingerprint", WorldView.class);
		method.setAccessible(true);
		return (int) method.invoke(null, worldView);
	}

	private void setPluginField(String name, Object value) throws Exception
	{
		Field field = BetterPartyDefencePlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(plugin, value);
	}
}
