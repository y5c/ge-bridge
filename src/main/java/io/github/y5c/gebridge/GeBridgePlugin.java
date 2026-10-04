package io.github.y5c.gebridge;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Filepath;

/**
 * GE Bridge: writes the account's Grand Exchange activity and state to local files, for the player's own tools.
 *
 * <pre>
 * ~/.runelite/plugin-data/ge-bridge/&lt;account hash&gt;/
 *   events.jsonl   one line per change: placed, fill, completed, cancelled, cleared, login, logout ...
 *                  append-only; archived and restarted at a size limit (see {@link EventLog})
 *   state.json     the current slots and, if enabled, inventory, equipment, bank (as last opened) and skills;
 *                  rewritten atomically on change and at the save interval while logged in; doubles as the
 *                  plugin's slot memory between sessions
 * </pre>
 *
 * Read-only: it never places, changes or collects an offer, and it makes no network requests.
 */
@Slf4j
@PluginDescriptor(
	name = "GE Bridge",
	internalName = "ge-bridge",
	description = "Records Grand Exchange offer changes and account state to local files",
	tags = {"grand exchange", "ge", "export", "flipping"}
)
public class GeBridgePlugin extends Plugin
{
	static final int SCHEMA = 1;
	static final String VERSION = "0.3.0-dev";
	private static final long FLUSH_MIN_MS = 1_000;
	// slots that got no event at login are read from the client this many ticks after it (RuneLite's own GE
	// plugin sees the login burst end within 2 ticks; 10 leaves a wide margin before an EMPTY is believed)
	private static final int RECONCILE_AFTER_TICKS = 10;
	// changes that arrive before the account's memory has loaded wait here; the cap only guards against a runaway.
	// Anything over it is counted in health and reported by an "overflow" event, never dropped silently
	private static final int MAX_PENDING = 4096;
	// Experimental probe: the client keeps item containers per GE slot. Which of these is the collection box is not
	// documented; recording their changes ("container" events) lets that be settled from real play.
	private static final int[] GE_OFFER_INVS = {InventoryID.GE_OFFER_0, InventoryID.GE_OFFER_1, InventoryID.GE_OFFER_2,
		InventoryID.GE_OFFER_3, InventoryID.GE_OFFER_4, InventoryID.GE_OFFER_5, InventoryID.GE_OFFER_6, InventoryID.GE_OFFER_7};
	private static final int[] GE_COLLECT_INVS = {InventoryID.TRADINGPOST_SELL_0, InventoryID.TRADINGPOST_SELL_1,
		InventoryID.TRADINGPOST_SELL_2, InventoryID.TRADINGPOST_SELL_3, InventoryID.TRADINGPOST_SELL_4,
		InventoryID.TRADINGPOST_SELL_5, InventoryID.GE_COLLECT_6, InventoryID.GE_COLLECT_7};
	// a GE change this many ticks after login is still the login burst: it happened while logged out
	private static final int LOGIN_BURST_TICKS = 5;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Gson gson;

	@Inject
	private GeBridgeConfig config;

	private ExecutorService io;

	// client thread only
	private long accountHash = -1;
	private Filepath accountDir;
	private OfferTracker tracker;
	private final List<Runnable> pending = new ArrayList<>();
	private boolean loggedIn;
	private int loginTick = -1;
	private boolean reconciled;
	private long lastWriteMs;
	private boolean dirty;
	private String rsn;
	private String lastLogin;
	private String lastLogout;
	private JsonElement skillsCache;
	private JsonElement inventoryCache;
	private JsonElement equipmentCache;
	private JsonElement bankCache;
	private boolean bankFromCache;
	private long bankSeenMs;
	private Map<String, Object> levelsCache;
	private boolean exiting;
	private int pendingDropped;
	private final Map<Integer, String> containerSeen = new java.util.HashMap<>();

	// health counters since the plugin started (writeFailures is bumped on the io thread)
	private long healthSince;
	private long eventsWritten;
	private long anomalies;
	private long reconcileFixes;
	private long overflowed;
	private final AtomicLong writeFailures = new AtomicLong();

	@Provides
	GeBridgeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GeBridgeConfig.class);
	}

	@Override
	protected void startUp()
	{
		io = Executors.newSingleThreadExecutor(r -> new Thread(r, "ge-bridge-io"));
		healthSince = System.currentTimeMillis();
		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				// enabled mid-session: every slot is a first sight, compared against the remembered state
				loggedIn = true;
				loginTick = client.getTickCount() - RECONCILE_AFTER_TICKS;
				reconciled = false;
				lastLogin = OfferTracker.iso(System.currentTimeMillis());
				whenReady(() -> emit(simple("start")));
			}
		});
	}

	@Override
	protected void shutDown()
	{
		// The final save is made on the client thread, which owns the state. shutdown() rather than shutdownNow():
		// it does not block either, but it lets the already-queued writes (that final save included) finish instead
		// of discarding them, so the slot memory on disk is never older than the session.
		final ExecutorService ex = io;
		clientThread.invoke(() ->
		{
			if (tracker != null && accountDir != null)
			{
				emit(simple("stop"));
				writeState(System.currentTimeMillis());
			}
			ex.shutdown();
			reset();
		});
	}

	private void reset()
	{
		accountHash = -1;
		accountDir = null;
		tracker = null;
		pending.clear();
		loggedIn = false;
		loginTick = -1;
		reconciled = false;
		dirty = false;
		rsn = null;
		exiting = false;
		skillsCache = inventoryCache = equipmentCache = bankCache = null;
		levelsCache = null;
		pendingDropped = 0;
		containerSeen.clear();
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown e)
	{
		// posted off the client thread: hop onto it, and hold the exit until the io thread has written
		final CompletableFuture<Void> written = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			if (tracker != null && accountDir != null)
			{
				final long now = System.currentTimeMillis();
				if (loggedIn)
				{
					// closing the window ends the session like a logout: the slots are current up to now, and
					// state.json must say logged out, so a reader can tell a closed client from a stalled one
					emit(simple("exit"));
					tracker.markObserved(now);
					loggedIn = false;
					lastLogout = OfferTracker.iso(now);
				}
				exiting = true;
				writeState(now);
			}
			io.submit(() -> written.complete(null));
		});
		e.waitFor(written);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		final GameState s = e.getGameState();
		final long now = System.currentTimeMillis();
		if (s == GameState.LOGGED_IN && !loggedIn)
		{
			loggedIn = true;
			loginTick = client.getTickCount();
			reconciled = false;
			lastLogin = OfferTracker.iso(now);
			whenReady(() -> emit(simple("login")));
		}
		else if ((s == GameState.LOGIN_SCREEN || s == GameState.LOGIN_SCREEN_AUTHENTICATOR) && loggedIn)
		{
			loggedIn = false;
			lastLogout = OfferTracker.iso(now);
			if (tracker != null)
			{
				tracker.markObserved(now);
				emit(simple("logout"));
				writeState(now);
				tracker.resetSession();
			}
		}
		if (s == GameState.LOGGING_IN || s == GameState.LOGGED_IN)
		{
			ensureAccount();
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged e)
	{
		final GrandExchangeOffer offer = e.getOffer();
		if (offer.getState() == GrandExchangeOfferState.EMPTY && client.getGameState() != GameState.LOGGED_IN)
		{
			// the client blanks every slot while logging in or hopping; those are not real changes
			return;
		}
		observe(e.getSlot(), offer, System.currentTimeMillis());
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		if (e.getContainerId() == InventoryID.BANK)
		{
			bankCache = container(e.getItemContainer(), true);
			bankFromCache = false;
			bankSeenMs = System.currentTimeMillis();
			dirty = true;
		}
		else if (e.getContainerId() == InventoryID.INV || e.getContainerId() == InventoryID.WORN)
		{
			dirty = true;
		}
		else
		{
			probeGeContainer(e.getContainerId(), e.getItemContainer());
		}
	}

	private void probeGeContainer(int id, ItemContainer c)
	{
		String family = null;
		int slot = -1;
		for (int i = 0; i < OfferTracker.SLOTS; i++)
		{
			if (GE_OFFER_INVS[i] == id)
			{
				family = "offer";
				slot = i;
			}
			else if (GE_COLLECT_INVS[i] == id)
			{
				family = "collect";
				slot = i;
			}
		}
		if (family == null)
		{
			return;
		}
		final List<Map<String, Object>> items = new ArrayList<>();
		final Item[] all = c == null ? new Item[0] : c.getItems();
		for (Item it : all)
		{
			if (it != null && it.getId() > 0 && it.getQuantity() > 0)
			{
				final Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", it.getId());
				m.put("name", itemManager.getItemComposition(it.getId()).getName());
				m.put("quantity", it.getQuantity());
				items.add(m);
			}
		}
		final String key = items.toString();
		if (key.equals(containerSeen.put(id, key)))
		{
			return;
		}
		final long now = System.currentTimeMillis();
		final Map<String, Object> ev = new LinkedHashMap<>();
		ev.put("ts", OfferTracker.iso(now));
		ev.put("t", now);
		ev.put("type", "container");
		ev.put("family", family);
		ev.put("slot", slot);
		ev.put("containerId", id);
		ev.put("items", items);
		final OfferTracker.Offer o = tracker == null ? null : tracker.get(slot);
		if (o != null && !o.isEmpty())
		{
			// the offer in that slot when the container changed, so the two can be lined up
			ev.put("offerItemId", o.itemId);
			ev.put("offerSide", o.side());
			ev.put("offerDone", o.done);
			ev.put("offerTotal", o.total);
		}
		whenReady(() -> emit(ev));
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		dirty = true;
	}

	@Subscribe
	public void onGameTick(GameTick t)
	{
		final long now = System.currentTimeMillis();
		if (tracker == null)
		{
			ensureAccount();
			return;
		}
		if (loggedIn && !reconciled && loginTick >= 0 && client.getTickCount() - loginTick >= RECONCILE_AFTER_TICKS)
		{
			reconciled = true;
			final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
			for (int i = 0; offers != null && i < offers.length && i < OfferTracker.SLOTS; i++)
			{
				if (offers[i] != null && !tracker.seen(i))
				{
					// a slot that got no event at login: whatever this finds is a change the login burst missed
					final List<Map<String, Object>> evs = tracker.observe(i, snapshot(offers[i]), now, true);
					if (!evs.isEmpty())
					{
						reconcileFixes++;
					}
					evs.forEach(this::emit);
				}
			}
		}
		if ((dirty && now - lastWriteMs >= FLUSH_MIN_MS) || now - lastWriteMs >= config.heartbeatSeconds() * 1000L)
		{
			writeState(now);
		}
	}

	private void observe(int slot, GrandExchangeOffer offer, long now)
	{
		final OfferTracker.Offer snap = snapshot(offer);
		final boolean burst = !loggedIn || client.getTickCount() - loginTick <= LOGIN_BURST_TICKS;
		whenReady(() ->
		{
			for (Map<String, Object> ev : tracker.observe(slot, snap, now, burst))
			{
				emit(ev);
			}
		});
	}

	private OfferTracker.Offer snapshot(GrandExchangeOffer o)
	{
		final int id = o.getItemId();
		final String name = id > 0 ? itemManager.getItemComposition(id).getName() : null;
		return new OfferTracker.Offer(id, name, o.getState().name(), o.getPrice(), o.getTotalQuantity(), o.getQuantitySold(), o.getSpent());
	}

	// ------------------------------------------------------------------ account memory

	/** Runs {@code r} now if the account's memory is loaded, otherwise once it is. */
	private void whenReady(Runnable r)
	{
		if (tracker != null)
		{
			r.run();
			return;
		}
		if (pending.size() < MAX_PENDING)
		{
			pending.add(r);
		}
		else
		{
			pendingDropped++;
			overflowed++;
		}
		ensureAccount();
	}

	private void ensureAccount()
	{
		final long h = client.getAccountHash();
		if (h == -1 || h == accountHash)
		{
			return;
		}
		// a different account (or the first): drop the old memory and load this one's off the client thread
		accountHash = h;
		tracker = null;
		accountDir = null;
		bankCache = null;
		io.submit(() ->
		{
			try
			{
				final Filepath dir = getPluginDirectory().joinSegment(Long.toString(h));
				dir.createDirectories();
				final Filepath state = dir.joinSegment("state.json");
				final String text = state.exists() ? new String(readAll(state), StandardCharsets.UTF_8) : null;
				clientThread.invoke(() -> install(h, dir, text));
			}
			catch (IOException ex)
			{
				log.warn("ge-bridge: cannot open the data folder", ex);
			}
		});
	}

	private static byte[] readAll(Filepath f) throws IOException
	{
		try (java.io.InputStream in = f.openInputStream())
		{
			return in.readAllBytes();
		}
	}

	private void install(long h, Filepath dir, String text)
	{
		if (h != accountHash)
		{
			return;
		}
		final OfferTracker t = new OfferTracker();
		if (text != null)
		{
			try
			{
				final JsonObject prev = gson.fromJson(text, JsonObject.class);
				final JsonObject offers = prev.has("grandExchange") ? prev.getAsJsonObject("grandExchange").getAsJsonObject("offers") : null;
				for (int i = 0; offers != null && i < OfferTracker.SLOTS; i++)
				{
					if (offers.has(Integer.toString(i)))
					{
						t.restore(i, offerFromJson(offers.getAsJsonObject(Integer.toString(i))));
					}
				}
				if (prev.has("bank") && prev.get("bank").isJsonObject())
				{
					bankCache = prev.get("bank");
					bankFromCache = true;
					bankSeenMs = prev.has("bankLastSeenTimestamp") && !prev.get("bankLastSeenTimestamp").isJsonNull() ? prev.get("bankLastSeenTimestamp").getAsLong() : 0;
				}
				skillsCache = prev.get("skills");
				inventoryCache = prev.get("inventory");
				equipmentCache = prev.get("equipment");
				if (prev.has("rsn") && !prev.get("rsn").isJsonNull())
				{
					rsn = prev.get("rsn").getAsString();
				}
			}
			catch (RuntimeException ex)
			{
				// an unreadable state.json means no memory: every slot is recorded as a baseline, nothing is booked
				log.warn("ge-bridge: state.json unreadable, starting without slot memory", ex);
			}
		}
		accountDir = dir;
		tracker = t;
		final List<Runnable> run = new ArrayList<>(pending);
		pending.clear();
		run.forEach(Runnable::run);
		if (pendingDropped > 0)
		{
			final Map<String, Object> ev = simple("overflow");
			ev.put("dropped", pendingDropped);
			emit(ev);
			pendingDropped = 0;
		}
		dirty = true;
	}

	private static OfferTracker.Offer offerFromJson(JsonObject m)
	{
		final String state = str(m, "state");
		final OfferTracker.Offer o = new OfferTracker.Offer(num(m, "itemId", 0).intValue(), str(m, "itemName"), state,
			num(m, "listedPrice", 0).longValue(), num(m, "totalQuantity", 0).intValue(),
			num(m, "completedQuantity", 0).intValue(), num(m, "spent", 0).longValue());
		o.placedAt = num(m, "placedAt", null);
		o.placedOffline = m.has("placedOffline") && m.get("placedOffline").getAsBoolean();
		o.lastFillAt = num(m, "lastFillAt", null);
		final Long seen = num(m, "observedAt", null);
		o.observedAt = seen == null ? 0 : seen;
		return o;
	}

	private static String str(JsonObject m, String k)
	{
		return m.has(k) && !m.get(k).isJsonNull() ? m.get(k).getAsString() : null;
	}

	private static Long num(JsonObject m, String k, Integer dflt)
	{
		if (m.has(k) && !m.get(k).isJsonNull())
		{
			return m.get(k).getAsLong();
		}
		return dflt == null ? null : dflt.longValue();
	}

	// ------------------------------------------------------------------ output

	private Map<String, Object> simple(String type)
	{
		final long now = System.currentTimeMillis();
		final Map<String, Object> e = new LinkedHashMap<>();
		e.put("ts", OfferTracker.iso(now));
		e.put("t", now);
		e.put("type", type);
		e.put("world", client.getWorld());
		e.put("version", VERSION);
		return e;
	}

	private void emit(Map<String, Object> ev)
	{
		final Filepath dir = accountDir;
		if (dir == null)
		{
			return;
		}
		final String line = gson.toJson(ev) + "\n";
		eventsWritten++;
		if ("anomaly".equals(ev.get("type")))
		{
			anomalies++;
		}
		final long maxBytes = config.maxLogMb() * 1024L * 1024L;
		final long now = System.currentTimeMillis();
		dirty = true;
		io.submit(() ->
		{
			try
			{
				EventLog.append(dir, line, maxBytes, now);
			}
			catch (IOException ex)
			{
				writeFailures.incrementAndGet();
				log.warn("ge-bridge: cannot append an event", ex);
			}
		});
	}

	private java.util.concurrent.Future<?> writeState(long now)
	{
		lastWriteMs = now;
		dirty = false;
		final Filepath dir = accountDir;
		if (dir == null || tracker == null)
		{
			return CompletableFuture.completedFuture(null);
		}
		if (loggedIn && client.getGameState() == GameState.LOGGED_IN)
		{
			tracker.markObserved(now);
			refreshLive();
		}
		final String text = gson.toJson(buildState(now));
		return io.submit(() ->
		{
			try
			{
				final Filepath tmp = dir.joinSegment("state.json.tmp");
				tmp.write(text);
				tmp.moveTo(dir.joinSegment("state.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException ex)
			{
				writeFailures.incrementAndGet();
				log.warn("ge-bridge: cannot write state.json", ex);
			}
		});
	}

	/** Re-read what the client holds while logged in; logged out, the last copies stand. */
	private void refreshLive()
	{
		final Player p = client.getLocalPlayer();
		if (p != null && p.getName() != null)
		{
			rsn = p.getName();
		}
		final JsonObject skills = new JsonObject();
		for (Skill s : Skill.values())
		{
			if ("OVERALL".equals(s.name()))
			{
				continue;
			}
			final JsonObject v = new JsonObject();
			v.addProperty("level", client.getRealSkillLevel(s));
			v.addProperty("boostedLevel", client.getBoostedSkillLevel(s));
			v.addProperty("xp", client.getSkillExperience(s));
			skills.add(s.getName(), v);
		}
		if (client.getTotalLevel() > 0)
		{
			skillsCache = skills;
			levelsCache = new LinkedHashMap<>();
			levelsCache.put("combatLevel", p == null ? null : p.getCombatLevel());
			levelsCache.put("totalLevel", client.getTotalLevel());
			levelsCache.put("totalXp", client.getOverallExperience());
		}
		// logged in, a container the server never sent is an empty one (an empty inventory at login is not sent)
		inventoryCache = container(client.getItemContainer(InventoryID.INV), false);
		equipmentCache = container(client.getItemContainer(InventoryID.WORN), false);
	}

	private JsonObject container(ItemContainer c, boolean skipPlaceholders)
	{
		final JsonObject items = new JsonObject();
		long value = 0;
		int n = 0;
		final Item[] all = c == null ? new Item[0] : c.getItems();
		for (int slot = 0; slot < all.length; slot++)
		{
			final Item it = all[slot];
			if (it == null || it.getId() <= 0 || it.getQuantity() <= 0)
			{
				continue;
			}
			final ItemComposition comp = itemManager.getItemComposition(it.getId());
			if (skipPlaceholders && comp.getPlaceholderTemplateId() != -1)
			{
				continue;
			}
			final long price = itemManager.getItemPrice(itemManager.canonicalize(it.getId()));
			final JsonObject v = new JsonObject();
			v.addProperty("slot", slot);
			v.addProperty("id", it.getId());
			v.addProperty("name", comp.getName());
			v.addProperty("quantity", it.getQuantity());
			v.addProperty("price", price);
			v.addProperty("value", price * it.getQuantity());
			items.add(Integer.toString(slot), v);
			value += price * it.getQuantity();
			n++;
		}
		final JsonObject out = new JsonObject();
		out.addProperty("loaded", true);
		out.addProperty("value", value);
		out.addProperty("itemCount", n);
		out.add("items", items);
		return out;
	}

	private JsonObject buildState(long now)
	{
		final JsonObject s = new JsonObject();
		s.addProperty("schema", SCHEMA);
		s.addProperty("version", "ge-bridge " + VERSION);
		s.addProperty("timestamp", now);
		s.addProperty("timestampIso", OfferTracker.iso(now));
		s.addProperty("rsn", rsn);
		s.addProperty("accountHash", Long.toString(accountHash));
		s.addProperty("world", client.getWorld());
		s.addProperty("gameState", exiting ? "EXITED" : client.getGameState().name());
		s.addProperty("loggedIn", loggedIn);
		s.addProperty("lastLogin", lastLogin);
		s.addProperty("lastLogout", lastLogout);
		s.addProperty("heartbeatSeconds", config.heartbeatSeconds());
		final JsonObject sections = new JsonObject();
		sections.addProperty("skills", config.writeSkills());
		sections.addProperty("containers", config.writeContainers());
		sections.addProperty("bank", config.writeBank());
		s.add("sections", sections);
		final JsonObject health = new JsonObject();
		health.addProperty("since", OfferTracker.iso(healthSince));
		health.addProperty("eventsWritten", eventsWritten);
		health.addProperty("writeFailures", writeFailures.get());
		health.addProperty("anomalies", anomalies);
		health.addProperty("reconcileFixes", reconcileFixes);
		health.addProperty("overflowed", overflowed);
		health.addProperty("pending", pending.size());
		s.add("health", health);
		if (config.writeSkills())
		{
			if (levelsCache != null)
			{
				levelsCache.forEach((k, v) -> s.add(k, gson.toJsonTree(v)));
			}
			s.add("skills", skillsCache);
		}
		if (config.writeContainers())
		{
			s.addProperty("inventoryLoaded", inventoryCache != null);
			s.add("inventory", inventoryCache);
			s.addProperty("equipmentLoaded", equipmentCache != null);
			s.add("equipment", equipmentCache);
		}
		if (config.writeBank())
		{
			s.addProperty("bankLoaded", bankCache != null);
			s.addProperty("bankFromCache", bankFromCache);
			s.addProperty("bankLastSeenTimestamp", bankSeenMs > 0 ? bankSeenMs : null);
			s.add("bank", bankCache);
		}
		s.add("grandExchange", gson.toJsonTree(tracker.toJson()));
		return s;
	}
}
