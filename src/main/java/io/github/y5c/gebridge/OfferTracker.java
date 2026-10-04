package io.github.y5c.gebridge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the client's Grand Exchange slot states into an event log.
 *
 * <p>Holds the last known offer in each of the eight slots and diffs every new
 * observation against it. The memory survives logouts (it is restored from
 * state.json), so a change first seen at login is an offline change: it is
 * flagged {@code offline} with {@code since} = the last time the slot was
 * observed, because its real time lies somewhere in that window.
 *
 * <p>Pure logic, no client types: every method runs on the client thread.
 */
final class OfferTracker
{
	static final int SLOTS = 8;

	static final class Offer
	{
		int itemId;
		String item;
		String state;
		long price;
		int total;
		int done;
		long spent;
		Long placedAt;
		boolean placedOffline;
		Long lastFillAt;
		long observedAt;
		// taken out of the collection box while this order was in the slot: the order's own item, and coins
		int collectedQty;
		long collectedCoins;

		Offer(int itemId, String item, String state, long price, int total, int done, long spent)
		{
			this.itemId = itemId;
			this.item = item;
			this.state = state;
			this.price = price;
			this.total = total;
			this.done = done;
			this.spent = spent;
		}

		boolean isEmpty()
		{
			return state == null || "EMPTY".equals(state) || itemId <= 0;
		}

		String side()
		{
			return side(state);
		}

		static String side(String state)
		{
			switch (state == null ? "" : state)
			{
				case "BUYING":
				case "BOUGHT":
				case "CANCELLED_BUY":
					return "buy";
				case "SELLING":
				case "SOLD":
				case "CANCELLED_SELL":
					return "sell";
				default:
					return null;
			}
		}

		/** The same order: a slot keeps its item, side, price and quantity until it is collected. */
		boolean sameOrder(Offer o)
		{
			return o != null && !isEmpty() && !o.isEmpty() && itemId == o.itemId && price == o.price
				&& total == o.total && side().equals(o.side());
		}
	}

	/**
	 * A sale to Jagex's GE item sink. The client shows it in a slot whose offer is EMPTY, from the
	 * {@code ge_itemsink_*} varps rather than the offer, so it never reaches {@code GrandExchangeOfferChanged}.
	 * Always one unit; the game labels it "Sold" once the slot's tax varp is below Integer.MAX_VALUE, "Selling" before.
	 */
	static final class Sink
	{
		final int itemId;
		final String item;
		final long price;
		final long tax;

		Sink(int itemId, String item, long price, long tax)
		{
			this.itemId = itemId;
			this.item = item;
			this.price = price;
			this.tax = tax;
		}

		boolean sold()
		{
			return tax < Integer.MAX_VALUE;
		}

		boolean same(Sink o)
		{
			return o != null && itemId == o.itemId && price == o.price && sold() == o.sold();
		}
	}

	private final Offer[] slots = new Offer[SLOTS];
	private final boolean[] seen = new boolean[SLOTS];
	private final Sink[] sinks = new Sink[SLOTS];
	private final boolean[] sinkSeen = new boolean[SLOTS];
	// false until the slot's item-sink state is remembered (a state.json from before sinks were tracked has none)
	private final boolean[] sinkKnown = new boolean[SLOTS];
	// when each slot was last observed before this session began: the start of the window for an offline change
	private final Long[] sessionStart = new Long[SLOTS];

	Offer get(int slot)
	{
		return slots[slot];
	}

	boolean seen(int slot)
	{
		return seen[slot];
	}

	/** Install remembered state (from the last state.json); it counts as not yet seen this session. */
	void restore(int slot, Offer o)
	{
		slots[slot] = o;
		seen[slot] = false;
		sessionStart[slot] = o == null ? null : o.observedAt;
	}

	/** Install the remembered item-sink sale for a slot ({@code null} = none); it counts as not yet seen this session. */
	void restoreSink(int slot, Sink s)
	{
		sinks[slot] = s;
		sinkSeen[slot] = false;
		sinkKnown[slot] = true;
	}

	Sink getSink(int slot)
	{
		return sinks[slot];
	}

	/** A logout: the next observation of every slot is a first sight again. */
	void resetSession()
	{
		for (int i = 0; i < SLOTS; i++)
		{
			seen[i] = false;
			sinkSeen[i] = false;
			sessionStart[i] = slots[i] == null ? null : slots[i].observedAt;
		}
	}

	/** While logged in, a slot seen this session is known to be current up to {@code now}. */
	void markObserved(long now)
	{
		for (int i = 0; i < SLOTS; i++)
		{
			if (seen[i] && slots[i] != null)
			{
				slots[i].observedAt = now;
			}
		}
	}

	List<Map<String, Object>> observe(int slot, Offer snap, long now)
	{
		return observe(slot, snap, now, false);
	}

	/**
	 * {@code loginBurst}: the client is still receiving the slots after a login. It can show a slot unchanged first and
	 * deliver the fills made while logged out a second later, so a change in the burst is offline even when the slot
	 * was already seen this session.
	 */
	List<Map<String, Object>> observe(int slot, Offer snap, long now, boolean loginBurst)
	{
		if (slot < 0 || slot >= SLOTS || snap == null)
		{
			return Collections.emptyList();
		}
		final Offer prev = slots[slot];
		final boolean offline = (!seen[slot] || loginBurst) && prev != null && sessionStart[slot] != null;
		final Long since = offline ? sessionStart[slot] : null;
		seen[slot] = true;
		snap.observedAt = now;
		final List<Map<String, Object>> out = new ArrayList<>();

		if (prev == null)
		{
			// nothing remembered for this slot (first run): record what is there, book nothing
			if (!snap.isEmpty())
			{
				out.add(event("baseline", slot, snap, now, false, null));
			}
			slots[slot] = snap;
			return out;
		}

		if (!snap.sameOrder(prev))
		{
			if (!prev.isEmpty())
			{
				Map<String, Object> e = event("cleared", slot, prev, now, offline, since);
				if (!isTerminal(prev.state))
				{
					// left the slot without us seeing it finish: any fills after `since` are unknown
					e.put("unobserved", true);
				}
				out.add(e);
			}
			if (!snap.isEmpty())
			{
				snap.placedAt = now;
				snap.placedOffline = offline;
				out.add(event("placed", slot, snap, now, offline, since));
				if (snap.done > 0)
				{
					snap.lastFillAt = now;
					out.add(fill(slot, snap, snap.done, snap.spent, now, offline, since));
				}
				addStateChange(out, slot, null, snap, now, offline, since);
			}
			slots[slot] = snap;
			return out;
		}

		snap.placedAt = prev.placedAt;
		snap.placedOffline = prev.placedOffline;
		snap.lastFillAt = prev.lastFillAt;
		snap.collectedQty = prev.collectedQty;
		snap.collectedCoins = prev.collectedCoins;
		final int d = snap.done - prev.done;
		if (d > 0)
		{
			snap.lastFillAt = now;
			out.add(fill(slot, snap, d, snap.spent - prev.spent, now, offline, since));
		}
		else if (d < 0)
		{
			Map<String, Object> e = event("anomaly", slot, snap, now, offline, since);
			e.put("note", "completed quantity went down from " + prev.done);
			out.add(e);
		}
		addStateChange(out, slot, prev.state, snap, now, offline, since);
		slots[slot] = snap;
		return out;
	}

	/**
	 * Items taken out of a slot's collection box. {@code qty} is the order's own item (units bought, or unsold units
	 * returned), {@code coins} the coins (sale proceeds, or a buy's refund). Returns the event, or null when the slot
	 * holds no order to attribute it to.
	 */
	Map<String, Object> collect(int slot, int qty, long coins, List<Map<String, Object>> items, long now)
	{
		final Offer o = slot >= 0 && slot < SLOTS ? slots[slot] : null;
		if (o == null || o.isEmpty() || (qty <= 0 && coins <= 0))
		{
			return null;
		}
		o.collectedQty += Math.max(0, qty);
		o.collectedCoins += Math.max(0, coins);
		final Map<String, Object> e = event("collected", slot, o, now, false, null);
		e.put("qty", qty);
		e.put("coins", coins);
		e.put("items", items);
		e.put("collectedQty", o.collectedQty);
		e.put("collectedCoins", o.collectedCoins);
		return e;
	}

	/**
	 * How much of a fall in the order's own item in its collection box counts as collected from that order. A buy
	 * puts bought units in the box; a sell only gets its item back when it is cancelled. Anything else is the box
	 * of an earlier order in the slot arriving late, and is not this order's.
	 */
	static int ownItemCollected(Offer o, int gone)
	{
		if (o == null || o.isEmpty() || gone <= 0)
		{
			return 0;
		}
		return "buy".equals(o.side()) || o.state.startsWith("CANCELLED") ? gone : 0;
	}

	/**
	 * Diff a slot's item-sink varps ({@code snap}, {@code null} when empty) against memory. Called each tick once the
	 * login settles, so the first observation in a session is the one that can be offline.
	 */
	List<Map<String, Object>> observeSink(int slot, Sink snap, long now)
	{
		if (slot < 0 || slot >= SLOTS)
		{
			return Collections.emptyList();
		}
		final Sink prev = sinks[slot];
		final boolean known = sinkKnown[slot];
		final boolean offline = !sinkSeen[slot] && sessionStart[slot] != null;
		final Long since = offline ? sessionStart[slot] : null;
		sinkSeen[slot] = true;
		sinkKnown[slot] = true;
		sinks[slot] = snap;
		if (snap == null ? prev == null : snap.same(prev))
		{
			return Collections.emptyList();
		}
		final List<Map<String, Object>> out = new ArrayList<>();
		if (snap == null)
		{
			out.add(sinkEvent("cleared", slot, prev, now, offline, since));
		}
		else if (!known)
		{
			// nothing remembered: record what is there, book nothing
			out.add(sinkEvent("baseline", slot, snap, now, false, null));
		}
		else
		{
			final Map<String, Object> e = sinkEvent("sink", slot, snap, now, offline, since);
			if (snap.sold())
			{
				e.put("qty", 1);
				e.put("gp", snap.price);
			}
			out.add(e);
		}
		return out;
	}

	private static Map<String, Object> sinkEvent(String type, int slot, Sink s, long now, boolean offline, Long since)
	{
		final Offer o = new Offer(s.itemId, s.item, s.sold() ? "SOLD" : "SELLING", s.price, 1, s.sold() ? 1 : 0, s.sold() ? s.price : 0);
		final Map<String, Object> e = event(type, slot, o, now, offline, since);
		e.put("itemSink", true);
		if (s.sold())
		{
			e.put("tax", s.tax);
		}
		return e;
	}

	private static boolean isTerminal(String state)
	{
		return state != null && (state.equals("BOUGHT") || state.equals("SOLD") || state.startsWith("CANCELLED"));
	}

	private static void addStateChange(List<Map<String, Object>> out, int slot, String before, Offer snap, long now, boolean offline, Long since)
	{
		if (snap.state.equals(before))
		{
			return;
		}
		if (snap.state.equals("BOUGHT") || snap.state.equals("SOLD"))
		{
			out.add(event("completed", slot, snap, now, offline, since));
		}
		else if (snap.state.startsWith("CANCELLED"))
		{
			out.add(event("cancelled", slot, snap, now, offline, since));
		}
	}

	private static Map<String, Object> fill(int slot, Offer o, int qty, long gp, long now, boolean offline, Long since)
	{
		Map<String, Object> e = event("fill", slot, o, now, offline, since);
		e.put("qty", qty);
		e.put("gp", gp);
		return e;
	}

	static Map<String, Object> event(String type, int slot, Offer o, long now, boolean offline, Long since)
	{
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("ts", iso(now));
		e.put("t", now);
		e.put("type", type);
		e.put("slot", slot);
		e.put("itemId", o.itemId);
		e.put("item", o.item);
		e.put("side", o.side());
		e.put("state", o.state);
		e.put("price", o.price);
		e.put("total", o.total);
		e.put("done", o.done);
		e.put("spent", o.spent);
		if (offline)
		{
			e.put("offline", true);
			e.put("since", since == null ? null : iso(since));
		}
		return e;
	}

	static String iso(long ms)
	{
		return Instant.ofEpochMilli(ms).toString();
	}

	/** The slots for state.json, in the shape the old Local Data Exporter used (plus timing fields). */
	Map<String, Object> toJson()
	{
		Map<String, Object> offers = new LinkedHashMap<>();
		int active = 0;
		boolean any = false;
		for (int i = 0; i < SLOTS; i++)
		{
			Offer o = slots[i];
			if (o == null)
			{
				continue;
			}
			any = true;
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("slot", i);
			m.put("state", o.isEmpty() ? "EMPTY" : o.state);
			m.put("observedAt", o.observedAt);
			m.put("seenThisSession", seen[i]);
			if (sinkKnown[i])
			{
				final Sink s = sinks[i];
				Map<String, Object> sm = null;
				if (s != null)
				{
					sm = new LinkedHashMap<>();
					sm.put("itemId", s.itemId);
					sm.put("itemName", s.item);
					sm.put("price", s.price);
					sm.put("tax", s.tax);
					sm.put("sold", s.sold());
				}
				m.put("itemSink", sm);
			}
			if (!o.isEmpty())
			{
				active++;
				m.put("itemId", o.itemId);
				m.put("itemName", o.item);
				m.put("listedPrice", o.price);
				m.put("totalQuantity", o.total);
				m.put("completedQuantity", o.done);
				m.put("remainingQuantity", o.total - o.done);
				m.put("spent", o.spent);
				m.put("placedAt", o.placedAt);
				m.put("placedOffline", o.placedOffline);
				m.put("lastFillAt", o.lastFillAt);
				m.put("collectedQuantity", o.collectedQty);
				m.put("collectedCoins", o.collectedCoins);
			}
			offers.put(Integer.toString(i), m);
		}
		Map<String, Object> ge = new LinkedHashMap<>();
		ge.put("loaded", any);
		ge.put("activeCount", active);
		ge.put("offers", offers);
		return ge;
	}
}
