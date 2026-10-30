package net.betterpartydefence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.mock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.betterpartydefence.DefenceTracker.SpecHistoryEntry;
import net.runelite.api.Client;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

public class EncounterTimelineReplayTest
{
	private static SpecHistoryEntry event(String player, SpecialWeapon weapon, int hit, String id)
	{
		return new SpecHistoryEntry(player, weapon, hit, id, "clock", 0);
	}

	private static EncounterTimeline.Replay<Integer> multiplicative()
	{
		return new EncounterTimeline.Replay<Integer>()
		{
			@Override public Integer apply(Integer state, SpecHistoryEntry event)
			{
				return state - state * 30 / 100;
			}

			@Override public Integer conservative(Integer a, Integer b)
			{
				return Math.min(a, b);
			}
		};
	}

	@Test public void eightSameTickEquivalentsNoLongerExhaustTheBudget()
	{
		List<SpecHistoryEntry> events = new ArrayList<>();
		for (int i = 0; i < 8; i++)
		{
			events.add(event("P" + i, SpecialWeapon.DRAGON_WARHAMMER, i + 1, "e" + i));
		}

		// Without a symmetry key all 8! interleavings are distinct to the search and the
		// 100k budget is exhausted, so the old behaviour is the undrained baseline.
		assertEquals(Integer.valueOf(310), EncounterTimeline.replay(events, 310, multiplicative()));

		// The tracker's key treats every positive warhammer as the same state transition.
		Integer reduced = EncounterTimeline.replay(events, 310, multiplicative(),
			e -> "dragon-warhammer:positive");
		assertEquals(Integer.valueOf(19), reduced);
	}

	@Test public void symmetryReductionMatchesFullSearchOnAMixedSet()
	{
		List<SpecHistoryEntry> events = Arrays.asList(
			event("A", SpecialWeapon.DRAGON_WARHAMMER, 5, "a"),
			event("B", SpecialWeapon.DRAGON_WARHAMMER, 5, "b"),
			event("C", SpecialWeapon.BANDOS_GODSWORD, 10, "c"),
			event("D", SpecialWeapon.ARCLIGHT, 7, "d"),
			event("E", SpecialWeapon.SEERCULL, 4, "e"),
			event("F", SpecialWeapon.BANDOS_GODSWORD, 20, "f"));

		EncounterTimeline.Replay<Long> replay = new EncounterTimeline.Replay<Long>()
		{
			@Override public Long apply(Long state, SpecHistoryEntry event)
			{
				return state * 31L + event.getWeapon().ordinal() * 17L + event.getHit();
			}

			@Override public Long conservative(Long a, Long b)
			{
				return Math.max(a, b);
			}
		};

		Long full = EncounterTimeline.replay(events, 0L, replay);
		Long reduced = EncounterTimeline.replay(events, 0L, replay,
			e -> e.getWeapon().name() + ":" + e.getHit());
		assertEquals(full, reduced);
	}

	@Test public void distinctEventsAreNotCollapsed()
	{
		List<SpecHistoryEntry> events = Arrays.asList(
			event("A", SpecialWeapon.BANDOS_GODSWORD, 5, "a"),
			event("B", SpecialWeapon.BANDOS_GODSWORD, 6, "b"),
			event("C", SpecialWeapon.BANDOS_GODSWORD, 7, "c"),
			event("D", SpecialWeapon.DRAGON_WARHAMMER, 5, "d"),
			event("E", SpecialWeapon.ARCLIGHT, 5, "e"));

		EncounterTimeline.Replay<Long> replay = new EncounterTimeline.Replay<Long>()
		{
			@Override public Long apply(Long state, SpecHistoryEntry event)
			{
				return state * 17L + event.getHit();
			}

			@Override public Long conservative(Long a, Long b)
			{
				return Math.max(a, b);
			}
		};

		Long full = EncounterTimeline.replay(events, 0L, replay);
		Long reduced = EncounterTimeline.replay(events, 0L, replay,
			e -> e.getWeapon().name() + ":" + e.getHit());
		assertEquals(full, reduced);
	}

	@Test public void trackerEffectKeyGroupsOnlyFormulaEquivalents()
	{
		DefenceTracker tracker = new DefenceTracker(mock(Client.class),
			mock(BetterPartyDefenceConfig.class));

		assertEquals(tracker.effectKey(event("A", SpecialWeapon.DRAGON_WARHAMMER, 5, "a"), null),
			tracker.effectKey(event("B", SpecialWeapon.DRAGON_WARHAMMER, 40, "b"), null));
		assertNotEquals(tracker.effectKey(event("A", SpecialWeapon.BANDOS_GODSWORD, 5, "a"), null),
			tracker.effectKey(event("B", SpecialWeapon.BANDOS_GODSWORD, 6, "b"), null));
		assertEquals(tracker.effectKey(event("A", SpecialWeapon.ARCLIGHT, 5, "a"), null),
			tracker.effectKey(event("B", SpecialWeapon.ARCLIGHT, 40, "b"), null));
		assertEquals(tracker.effectKey(event("A", SpecialWeapon.ACCURSED_SCEPTRE, 1, "a"), null),
			tracker.effectKey(event("B", SpecialWeapon.ACCURSED_SCEPTRE, 30, "b"), null));
		assertNotEquals(tracker.effectKey(event("A", SpecialWeapon.SEERCULL, 4, "a"), null),
			tracker.effectKey(event("B", SpecialWeapon.SEERCULL, 5, "b"), null));
		assertNotEquals(tracker.effectKey(event("A", SpecialWeapon.TONALZTICS_OF_RALOS, 1, "a"), null),
			tracker.effectKey(event("B", SpecialWeapon.TONALZTICS_OF_RALOS, 2, "b"), null));
	}
}
