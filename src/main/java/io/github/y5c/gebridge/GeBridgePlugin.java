package io.github.y5c.gebridge;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
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
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.ScriptID;
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
import net.runelite.api.gameval.ItemID;
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
	static final String VERSION = "0.4.0";
	private static final long FLUSH_MIN_MS = 1_000;
	// slots that got no event at login are read from the client this many ticks after it (RuneLite's own GE
	// plugin sees the login burst end within 2 ticks; 10 leaves a wide margin before an EMPTY is believed)
	private static final int RECONCILE_AFTER_TICKS = 10;
	// changes that arrive before the account's memory has loaded wait here; the cap only guards against a runaway.
	// Anything over it is counted in health and reported by an "overflow" event, never dropped silently
	private static final int MAX_PENDING = 4096;
	// the collection box of each GE slot (slots 0-5 carry the older trading-post names)
	private static final int[] GE_COLLECT_INVS = {InventoryID.TRADINGPOST_SELL_0, InventoryID.TRADINGPOST_SELL_1,
		InventoryID.TRADINGPOST_SELL_2, InventoryID.TRADINGPOST_SELL_3, InventoryID.TRADINGPOST_SELL_4,
		InventoryID.TRADINGPOST_SELL_5, InventoryID.GE_COLLECT_6, InventoryID.GE_COLLECT_7};
	// a GE change this many ticks after login is still the login burst: it happened while logged out
	private static final int LOGIN_BURST_TICKS = 5;
	// account.json: first written once the login has settled, then refreshed about every ten minutes while logged in
	private static final int ACCOUNT_FIRST_TICKS = RECONCILE_AFTER_TICKS + 5;
	private static final int ACCOUNT_EVERY_TICKS = 1000;

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
	private final Map<Integer, Map<Integer, Integer>> collectBoxes = new HashMap<>();

	// health counters since the plugin started (writeFailures is bumped on the io thread)
	private long healthSince;
	private long eventsWritten;
	private long anomalies;
	private long reconcileFixes;
	private long overflowed;
	private final AtomicLong writeFailures = new AtomicLong();
	private long readFailures;

	private int accountTick = -1;
	private String accountText;
	private boolean potionsStale;
	private JsonElement potionCache;
	private long potionSeenMs;

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
		collectBoxes.clear();
		accountTick = -1;
		accountText = null;
		potionsStale = false;
		potionCache = null;
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
			accountTick = -1;
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
			collectBoxes.clear();
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
			potionsStale = true;      // read on the next tick, outside the container event
			dirty = true;
		}
		else if (e.getContainerId() == InventoryID.INV || e.getContainerId() == InventoryID.WORN)
		{
			dirty = true;
		}
		else
		{
			onCollectionBox(e.getContainerId(), e.getItemContainer());
		}
	}

	/**
	 * When a slot's order leaves or a new one arrives, what was known of its collection box belongs to the old order:
	 * the client can send the emptied box a while later, and it must not be read as a collection from the new order.
	 */
	private void forgetBoxOnNewOrder(int slot, Map<String, Object> ev)
	{
		final Object type = ev.get("type");
		if ("cleared".equals(type) || "placed".equals(type) || "baseline".equals(type))
		{
			collectBoxes.remove(slot);
		}
	}

	/**
	 * Collection boxes: the client keeps one item container per GE slot holding what is waiting to be collected. It
	 * grows as the offer fills or is aborted and shrinks only when the player collects, so a fall in an item's quantity
	 * is a collection: of the order's item (bought units, or unsold units returned) or of coins. Contents are only sent
	 * while a collection interface is open; the first sight of a box in a session is its baseline.
	 */
	private void onCollectionBox(int id, ItemContainer c)
	{
		int slot = -1;
		for (int i = 0; i < OfferTracker.SLOTS; i++)
		{
			if (GE_COLLECT_INVS[i] == id)
			{
				slot = i;
			}
		}
		if (slot < 0)
		{
			return;
		}
		final Map<Integer, Integer> now = new HashMap<>();
		for (Item it : c == null ? new Item[0] : c.getItems())
		{
			if (it != null && it.getId() > 0 && it.getQuantity() > 0)
			{
				now.merge(it.getId(), it.getQuantity(), Integer::sum);
			}
		}
		final Map<Integer, Integer> before = collectBoxes.put(slot, now);
		if (before == null)
		{
			return;
		}
		final OfferTracker.Offer o = tracker == null ? null : tracker.get(slot);
		final int orderItem = o == null ? -1 : o.itemId;
		int qty = 0;
		long coins = 0;
		final List<Map<String, Object>> items = new ArrayList<>();
		for (Map.Entry<Integer, Integer> b : before.entrySet())
		{
			final int gone = b.getValue() - now.getOrDefault(b.getKey(), 0);
			if (gone <= 0)
			{
				continue;
			}
			final int canon = itemManager.canonicalize(b.getKey());
			final Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", b.getKey());
			m.put("name", itemManager.getItemComposition(b.getKey()).getName());
			m.put("quantity", gone);
			items.add(m);
			if (b.getKey() == ItemID.COINS)
			{
				coins += gone;
			}
			else if (canon == orderItem || b.getKey() == orderItem)
			{
				qty += OfferTracker.ownItemCollected(o, gone);
			}
		}
		if (items.isEmpty())
		{
			return;
		}
		final int s = slot;
		final int q = qty;
		final long gp = coins;
		final long t = System.currentTimeMillis();
		whenReady(() ->
		{
			final Map<String, Object> ev = tracker.collect(s, q, gp, items, t);
			if (ev != null)
			{
				emit(ev);
			}
		});
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
					final int slot = i;
					evs.forEach(ev ->
					{
						forgetBoxOnNewOrder(slot, ev);
						emit(ev);
					});
				}
			}
		}
		if (potionsStale && loggedIn)
		{
			potionsStale = false;
			readPotionStore(now);
		}
		final int sinceLogin = client.getTickCount() - loginTick;
		if (loggedIn && config.writeAccount() && sinceLogin >= ACCOUNT_FIRST_TICKS
			&& (accountTick < 0 || client.getTickCount() - accountTick >= ACCOUNT_EVERY_TICKS))
		{
			accountTick = client.getTickCount();
			writeAccount(now);
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
				forgetBoxOnNewOrder(slot, ev);
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
				if (prev.has("potionStore") && prev.get("potionStore").isJsonObject())
				{
					potionCache = prev.get("potionStore");
					potionSeenMs = prev.has("potionStoreLastSeenTimestamp") && !prev.get("potionStoreLastSeenTimestamp").isJsonNull()
						? prev.get("potionStoreLastSeenTimestamp").getAsLong() : 0;
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
		o.collectedQty = num(m, "collectedQuantity", 0).intValue();
		o.collectedCoins = num(m, "collectedCoins", 0);
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

	/**
	 * The bank's potion storage, which is not part of the bank container. Read the way RuneLite's bank tags plugin
	 * reads it: each stored potion's doses come from the game's own potion-store script.
	 */
	private void readPotionStore(long now)
	{
		try
		{
			final JsonObject items = new JsonObject();
			long value = 0;
			for (int listId : new int[]{EnumID.POTIONSTORE_POTIONS, EnumID.POTIONSTORE_UNFINISHED_POTIONS})
			{
				for (int potionEnumId : client.getEnum(listId).getIntVals())
				{
					final EnumComposition potion = client.getEnum(potionEnumId);
					client.runScript(ScriptID.POTIONSTORE_DOSES, potionEnumId);
					final int doses = client.getIntStack()[0];
					client.runScript(ScriptID.POTIONSTORE_WITHDRAW_DOSES, potionEnumId);
					final int withdraw = client.getIntStack()[0];
					if (doses <= 0 || withdraw <= 0)
					{
						continue;
					}
					final int itemId = potion.getIntValue(withdraw);
					final String name = itemManager.getItemComposition(itemId).getName();
					final double perDose = (double) itemManager.getItemPrice(itemManager.canonicalize(itemId)) / withdraw;
					final JsonObject v = new JsonObject();
					v.addProperty("id", itemId);
					v.addProperty("name", name);
					v.addProperty("doses", doses);
					v.addProperty("withdrawDoses", withdraw);
					v.addProperty("pricePerDose", Math.round(perDose));
					v.addProperty("value", Math.round(perDose * doses));
					items.add(name, v);
					value += Math.round(perDose * doses);
				}
			}
			final JsonObject out = new JsonObject();
			out.addProperty("loaded", true);
			out.addProperty("value", value);
			out.addProperty("itemCount", items.size());
			out.add("items", items);
			potionCache = out;
			potionSeenMs = now;
			dirty = true;
		}
		catch (RuntimeException ex)
		{
			// a change to the game's potion-store scripts must never stop the GE log
			readFailures++;
			log.debug("ge-bridge: potion storage unreadable", ex);
		}
	}

	/** account.json: quest states and diary completion, rewritten only when they change. */
	private void writeAccount(long now)
	{
		final Filepath dir = accountDir;
		if (dir == null)
		{
			return;
		}
		final Map<String, Object> quests = new LinkedHashMap<>();
		final Map<String, Object> entries = new LinkedHashMap<>();
		int notStarted = 0;
		int inProgress = 0;
		int finished = 0;
		int unknown = 0;
		try
		{
			for (Quest q : Quest.values())
			{
				QuestState st;
				try
				{
					st = q.getState(client);
				}
				catch (RuntimeException ex)
				{
					st = null;
				}
				final Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", q.getName());
				m.put("state", st == null ? "UNKNOWN" : st.name());
				entries.put(q.name(), m);
				if (st == QuestState.FINISHED)
				{
					finished++;
				}
				else if (st == QuestState.IN_PROGRESS)
				{
					inProgress++;
				}
				else if (st == QuestState.NOT_STARTED)
				{
					notStarted++;
				}
				else
				{
					unknown++;
				}
			}
			quests.put("notStarted", notStarted);
			quests.put("inProgress", inProgress);
			quests.put("finished", finished);
			quests.put("unknown", unknown);
			quests.put("total", entries.size());
			quests.put("entries", entries);
			final Map<String, Object> diaries = AccountData.diaries(client::getVarbitValue);
			final Map<String, Object> body = new LinkedHashMap<>();
			body.put("quests", quests);
			body.put("achievementDiaries", diaries);
			final String key = gson.toJson(body);
			if (key.equals(accountText))
			{
				return;
			}
			accountText = key;
			final Map<String, Object> doc = new LinkedHashMap<>();
			doc.put("schema", SCHEMA);
			doc.put("version", "ge-bridge " + VERSION);
			doc.put("timestamp", now);
			doc.put("timestampIso", OfferTracker.iso(now));
			doc.put("rsn", rsn);
			doc.putAll(body);
			final String text = gson.toJson(doc);
			io.submit(() ->
			{
				try
				{
					final Filepath tmp = dir.joinSegment("account.json.tmp");
					tmp.write(text);
					tmp.moveTo(dir.joinSegment("account.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				}
				catch (IOException ex)
				{
					writeFailures.incrementAndGet();
					log.warn("ge-bridge: cannot write account.json", ex);
				}
			});
		}
		catch (RuntimeException ex)
		{
			readFailures++;
			log.debug("ge-bridge: account data unreadable", ex);
		}
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
		health.addProperty("readFailures", readFailures);
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
			s.add("potionStore", potionCache);
			s.addProperty("potionStoreLastSeenTimestamp", potionSeenMs > 0 ? potionSeenMs : null);
		}
		s.add("grandExchange", gson.toJsonTree(tracker.toJson()));
		return s;
	}
}
