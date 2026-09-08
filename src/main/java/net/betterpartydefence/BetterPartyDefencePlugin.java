/*
 * Copyright (c) 2026, Maxwell
 * All rights reserved.
 *
 * Better Party Defence
 *
 * Hub Party Panel integration note:
 * Hub Party Panel creates/joins the same underlying RuneLite PartyService session used by
 * RuneLite's party message bus. This plugin does not use or enable RuneLite's built-in Party
 * sidebar and does not create a second party. It only observes the existing PartyService session.
 *
 * Remote party special-attack events use RuneLite's standard SpecialCounterUpdate party message.
 * This plugin also detects its own supported defence specs locally, so its core tracking does not
 * depend on the separate Special Attack Counter plugin. See ATTRIBUTION.md for attribution.
 */
package net.betterpartydefence;

import com.google.inject.Provides;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.specialcounter.SpecialCounterPlugin;
import net.runelite.client.plugins.specialcounter.SpecialCounterUpdate;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.ImageUtil;

/**
 * Displays a boss's live Defence using special-attack events shared by members of the
 * current Hub Party Panel party.
 *
 * <p>Hub Party Panel is the user-facing party UI. Under the hood it uses RuneLite's
 * {@link PartyService}; therefore this plugin can observe that exact party without depending
 * on Hub Party Panel's implementation classes or enabling RuneLite's built-in Party sidebar.
 *
 * <p>Remote drains arrive as RuneLite's standard {@link SpecialCounterUpdate} messages.
 * Better Party Defence also contains a minimal local detector for supported defence-draining specs.
 * If RuneLite's {@link SpecialCounterPlugin} is active it remains the party-message producer; if it
 * is disabled, Better Party Defence publishes the same standard message for its own supported drains.
 * No second party, custom websocket, or OSParty protocol is created.
 */
@Slf4j
@Singleton
@PluginDescriptor(
	name = "Better Party Defence",
	description = "Tracks boss Defence from special attacks shared through your Hub Party Panel party.",
	tags = {"defence", "defense", "drain", "party", "hub", "boss", "special"}
)
public class BetterPartyDefencePlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private PartyService partyService;

	@Inject
	private WSClient wsClient;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private SkillIconManager skillIconManager;

	@Inject
	private DefenceTracker defenceTracker;

	@Inject
	private LocalDefenceSpecDetector localSpecDetector;

	@Inject
	private BetterPartyDefenceConfig config;

	@Inject
	private InfoBoxManager infoBoxManager;

	private NpcDefenceOverlay defenceOverlay;
	private DefenceInfoBox defenceBox;
	private static final int DUPLICATE_WINDOW_TICKS = 1;
	private final Map<SpecEventKey, Integer> recentSpecEvents = new HashMap<>();
	private boolean wasInParty;

	@Provides
	@Singleton
	BetterPartyDefenceConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BetterPartyDefenceConfig.class);
	}

	@Override
	protected void startUp()
	{
		// Register the core SpecialCounterUpdate message ourselves so remote party drains can
		// still be decoded even when this client's Special Attack Counter UI is disabled.
		// WSClient de-duplicates registrations by message class.
		wsClient.registerMessage(SpecialCounterUpdate.class);

		defenceOverlay = new NpcDefenceOverlay(
			client,
			defenceTracker,
			config,
			ImageUtil.resizeImage(skillIconManager.getSkillImage(Skill.DEFENCE), 16, 16),
			ImageUtil.resizeImage(skillIconManager.getSkillImage(Skill.MAGIC), 16, 16));
		overlayManager.add(defenceOverlay);

		wasInParty = partyService.isInParty();
		log.info("Better Party Defence started; Hub Party session active={}", wasInParty);
		if (!isSpecialCounterActive())
		{
			log.info("Special Attack Counter is disabled; Better Party Defence will detect and broadcast "
				+ "this client's supported defence specs itself.");
		}
	}

	@Override
	protected void shutDown()
	{
		if (defenceOverlay != null)
		{
			overlayManager.remove(defenceOverlay);
			defenceOverlay = null;
		}
		removeInfoBox();
		recentSpecEvents.clear();
		// Do not unregister a message class that the active core Special Attack Counter still needs.
		if (!isSpecialCounterActive())
		{
			wsClient.unregisterMessage(SpecialCounterUpdate.class);
		}
		localSpecDetector.reset();
		defenceTracker.reset("plugin shutdown");
		wasInParty = false;
		log.info("Better Party Defence shut down");
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		boolean inParty = partyService.isInParty();
		if (inParty != wasInParty)
		{
			log.debug("Hub Party session state changed: {}", inParty ? "joined" : "left");
			wasInParty = inParty;
		}

		int tick = client.getTickCount();
		recentSpecEvents.entrySet().removeIf(entry -> tick - entry.getValue() > DUPLICATE_WINDOW_TICKS);

		// Local defence tracking is intentionally independent of party membership. Hub Party is
		// the transport for remote specs, not a prerequisite for drawing our own tracked target.
		localSpecDetector.onGameTick();
		defenceTracker.onGameTick();
		updateDefenceInfoBox();
	}

	@Subscribe
	public void onFakeXpDrop(FakeXpDrop event)
	{
		localSpecDetector.onFakeXpDrop(event);
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		localSpecDetector.onStatChanged(event);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		localSpecDetector.onVarbitChanged(event);
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		localSpecDetector.onHitsplatApplied(event);
	}

	@Subscribe
	public void onPartyChanged(PartyChanged event)
	{
		recentSpecEvents.clear();
		wasInParty = event.getPartyId() != null;
		log.debug("Hub Party session changed: partyId={}", event.getPartyId());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST)
		{
			localSpecDetector.reset();
			defenceTracker.reset("game state " + state);
			removeInfoBox();
			recentSpecEvents.clear();
		}
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		if (!(event.getPlugin() instanceof SpecialCounterPlugin))
		{
			return;
		}

		if (event.isLoaded())
		{
			log.debug("Special Attack Counter enabled: it will own the standard party spec broadcast");
		}
		else
		{
			// Its shutdown unregisters SpecialCounterUpdate. Re-register so BPD can continue
			// decoding messages sent by other party members.
			wsClient.registerMessage(SpecialCounterUpdate.class);
			log.debug("Special Attack Counter disabled: Better Party Defence will broadcast supported local drains itself");
		}
	}

	/**
	 * Receives the party message emitted by RuneLite's Special Attack Counter. The active
	 * PartyService session can have been created/joined entirely through Hub Party Panel;
	 * the built-in RuneLite Party sidebar is not required.
	 */
	@Subscribe
	public void onSpecialCounterUpdate(SpecialCounterUpdate event)
	{
		if (!partyService.isInParty())
		{
			return;
		}

		PartyMember localMember = partyService.getLocalMember();
		if (localMember != null && localMember.getMemberId() == event.getMemberId())
		{
			// Our local detector applies our spec immediately. Ignore the server echo (whether the
			// message was sent by BPD or by RuneLite's Special Attack Counter) to prevent doubles.
			return;
		}

		PartyMember sender = partyService.getMemberById(event.getMemberId());
		SpecialWeapon weapon = event.getWeapon();
		if (!DefenceTracker.isSupportedWeapon(weapon))
		{
			return;
		}

		String senderName = sender == null ? null : sender.getDisplayName();
		// Party websocket messages are posted from the websocket thread. Keep all game-client
		// state reads and tracker mutation on RuneLite's client thread, matching core plugin practice.
		clientThread.invoke(() ->
		{
			if (!partyService.isInParty())
			{
				return;
			}

			SpecEventKey key = new SpecEventKey(
				event.getMemberId(), event.getWorld(), event.getNpcIndex(), event.getPlayerId(), weapon, event.getHit());
			int tick = client.getTickCount();
			Integer lastSeen = recentSpecEvents.get(key);
			if (lastSeen != null && tick - lastSeen <= DUPLICATE_WINDOW_TICKS)
			{
				log.debug("Ignoring duplicate party spec event {}", key);
				return;
			}
			recentSpecEvents.put(key, tick);

			log.debug("Party spec: member={} ({}) weapon={} hit={} npc={} world={}",
				event.getMemberId(), Objects.toString(senderName, "unknown"), weapon,
				event.getHit(), event.getNpcIndex(), event.getWorld());

			defenceTracker.queue(weapon, event.getNpcIndex(), event.getHit(), event.getWorld());
		});
	}

	private boolean isSpecialCounterActive()
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (plugin instanceof SpecialCounterPlugin && pluginManager.isPluginActive(plugin))
			{
				return true;
			}
		}
		return false;
	}

	private void updateDefenceInfoBox()
	{
		boolean show = config.defenceInfoBox() && defenceTracker.state() != null;
		if (show && defenceBox == null)
		{
			defenceBox = new DefenceInfoBox(
				skillIconManager.getSkillImage(Skill.DEFENCE), this, defenceTracker, config);
			infoBoxManager.addInfoBox(defenceBox);
		}
		else if (!show)
		{
			removeInfoBox();
		}
	}

	private void removeInfoBox()
	{
		if (defenceBox != null)
		{
			infoBoxManager.removeInfoBox(defenceBox);
			defenceBox = null;
		}
	}

	@Value
	private static class SpecEventKey
	{
		long memberId;
		int world;
		int npcIndex;
		int playerId;
		SpecialWeapon weapon;
		int hit;
	}
}
