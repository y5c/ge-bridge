package io.github.y5c.gebridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * advice.json: what the player's own tool advises for the Grand Exchange, written by that tool into the plugin's data
 * folder. The plugin only displays it and compares it with what is on screen; it never acts on it.
 *
 * <pre>
 * {"schema": 1, "generatedAt": epoch ms,
 *  "slots":  [{"slot", "itemId", "item", "side", "price", "total", "action", "text"}],   advice for the order now in a slot
 *  "orders": [{"itemId", "item", "side", "price", "qty", "why", "slot"}]}               orders the player should place
 * </pre>
 *
 * A slot entry applies only while that same order (item, side, price, quantity) is still in the slot. An order counts
 * as placed once a slot holds it. {@code qty} may be absent, in which case the quantity is not checked. {@code slot}
 * (optional) names the slot whose advice the order carries out; the order is not listed while that advice shows.
 *
 * <p>Pure: no client types. Fields are filled by Gson.
 */
final class Advice
{
	static final int SCHEMA = 1;

	static final class Slot
	{
		int slot = -1;
		int itemId;
		String item;
		String side;
		long price;
		int total;
		String action;
		String text;
	}

	static final class Order
	{
		int itemId;
		String item;
		String side;
		long price;
		Integer qty;
		String why;
		// the slot whose advice this order carries out (a trim, a re-place, a listing), when there is one
		Integer slot;
	}

	/** What the offer screen shows against the advice: {@code level} is ok, warn or none (no advice for the item). */
	static final class Check
	{
		final String level;
		final List<String> lines;

		Check(String level, List<String> lines)
		{
			this.level = level;
			this.lines = lines;
		}
	}

	int schema;
	long generatedAt;
	List<Slot> slots;
	List<Order> orders;

	boolean usable()
	{
		return schema == SCHEMA && generatedAt > 0;
	}

	List<Slot> slots()
	{
		return slots == null ? Collections.emptyList() : slots;
	}

	List<Order> orders()
	{
		return orders == null ? Collections.emptyList() : orders;
	}

	/** The advice for the order in {@code slot}, or null when there is none or the slot now holds a different order. */
	Slot forSlot(int slot, OfferTracker.Offer o)
	{
		if (o == null || o.isEmpty())
		{
			return null;
		}
		for (Slot s : slots())
		{
			if (s.slot == slot && sameItem(s.itemId, s.item, o) && o.side().equals(s.side) && s.price == o.price && s.total == o.total)
			{
				return s;
			}
		}
		return null;
	}

	/** The slot holding this advised order, or -1. */
	static int placedIn(Order a, OfferTracker.Offer[] now)
	{
		for (int i = 0; i < now.length; i++)
		{
			final OfferTracker.Offer o = now[i];
			if (o != null && !o.isEmpty() && matches(a, o))
			{
				return i;
			}
		}
		return -1;
	}

	/** Advised orders no slot holds yet. */
	List<Order> open(OfferTracker.Offer[] now)
	{
		final List<Order> out = new ArrayList<>();
		for (Order a : orders())
		{
			if (placedIn(a, now) < 0)
			{
				out.add(a);
			}
		}
		return out;
	}

	/**
	 * The orders to list under "place": the open ones, less those that carry out the advice of a slot still showing
	 * it. A trim reads "trim to 49,726" on its slot; it is listed as an order only once that slot has changed.
	 */
	List<Order> toPlace(OfferTracker.Offer[] now)
	{
		final List<Order> out = new ArrayList<>();
		for (Order a : open(now))
		{
			if (a.slot != null && a.slot >= 0 && a.slot < now.length && forSlot(a.slot, now[a.slot]) != null)
			{
				continue;
			}
			out.add(a);
		}
		return out;
	}

	/**
	 * The offer screen, before Confirm: the item, side and quantity the player has set. The price typed is not compared
	 * here (the client keeps it in a variable RuneLite has not named); it is checked once the order is in its slot.
	 */
	Check check(int itemId, String side, int qty, OfferTracker.Offer[] now)
	{
		final List<String> lines = new ArrayList<>();
		final List<Order> same = new ArrayList<>();
		Order other = null;
		for (Order a : orders())
		{
			if (a.itemId != itemId)
			{
				continue;
			}
			if (side.equals(a.side))
			{
				same.add(a);
			}
			else
			{
				other = a;
			}
		}
		if (same.isEmpty())
		{
			if (other != null)
			{
				lines.add("Advice is to " + other.side + " this item, not " + side);
				lines.add(describe(other));
				return new Check("warn", lines);
			}
			lines.add("No advice for this item");
			return new Check("none", lines);
		}
		boolean qtyOk = false;
		for (Order a : same)
		{
			if (a.qty == null || a.qty == qty)
			{
				qtyOk = true;
			}
		}
		for (Order a : same)
		{
			final int in = placedIn(a, now);
			lines.add(describe(a) + (in >= 0 ? " (already in slot " + in + ")" : ""));
		}
		boolean warn = false;
		if (!qtyOk)
		{
			lines.add("Quantity " + fmt(qty) + " is not the advised " + fmt(same.get(0).qty));
			warn = true;
		}
		for (Order a : same)
		{
			if (placedIn(a, now) >= 0 && same.size() == 1)
			{
				lines.add("This order is already placed: a second one would double it");
				warn = true;
			}
		}
		return new Check(warn ? "warn" : "ok", lines);
	}

	/**
	 * A new order the player has just placed, against the advice for its item and side. Null when it matches an advised
	 * order or there is no advice for it; otherwise the warning to show.
	 */
	String placedMismatch(OfferTracker.Offer o)
	{
		Order near = null;
		for (Order a : orders())
		{
			if (!sameItem(a.itemId, a.item, o) || !o.side().equals(a.side))
			{
				continue;
			}
			if (matches(a, o))
			{
				return null;
			}
			near = a;
		}
		if (near == null)
		{
			return null;
		}
		final List<String> diff = new ArrayList<>();
		if (near.price != o.price)
		{
			diff.add("price " + fmt(o.price) + " (advised " + fmt(near.price) + ")");
		}
		if (near.qty != null && near.qty != o.total)
		{
			diff.add("quantity " + fmt(o.total) + " (advised " + fmt(near.qty) + ")");
		}
		return o.item + ": " + String.join(", ", diff);
	}

	static boolean matches(Order a, OfferTracker.Offer o)
	{
		return sameItem(a.itemId, a.item, o) && o.side().equals(a.side) && a.price == o.price && (a.qty == null || a.qty == o.total);
	}

	private static boolean sameItem(int itemId, String item, OfferTracker.Offer o)
	{
		if (itemId > 0)
		{
			return itemId == o.itemId;
		}
		return item != null && item.equalsIgnoreCase(o.item);
	}

	static String describe(Order a)
	{
		return a.side + " " + (a.qty == null ? "" : fmt(a.qty) + " ") + a.item + " at " + fmt(a.price);
	}

	static String fmt(Number n)
	{
		return n == null ? "?" : String.format(Locale.US, "%,d", n.longValue());
	}

	/** "25m", "3h", "2d": the advice's age. */
	static String age(long ms)
	{
		final long m = Math.max(0, ms / 60_000);
		if (m < 60)
		{
			return m + "m";
		}
		if (m < 48 * 60)
		{
			return (m / 60) + "h";
		}
		return (m / 1440) + "d";
	}
}
