package io.github.y5c.gebridge;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the two overlays draw, rebuilt on the client thread each tick while the Grand Exchange is open. Immutable. */
final class AdviceView
{
	static final Color OK = new Color(0x7fd27f);
	static final Color WARN = new Color(0xff5c5c);
	static final Color ACT = new Color(0xffd23f);
	static final Color DIM = new Color(0xa0a0a0);
	static final Color TEXT = Color.WHITE;

	static final AdviceView NONE = new AdviceView(null, DIM, Collections.emptyList(), Collections.emptyMap());

	static final class Line
	{
		final String left;
		final String right;
		final Color color;

		Line(String left, String right, Color color)
		{
			this.left = left;
			this.right = right;
			this.color = color;
		}
	}

	final String header;
	final Color headerColor;
	final List<Line> lines;
	// slot -> outline colour
	final Map<Integer, Color> slots;

	AdviceView(String header, Color headerColor, List<Line> lines, Map<Integer, Color> slots)
	{
		this.header = header;
		this.headerColor = headerColor;
		this.lines = lines;
		this.slots = slots;
	}

	// the compact layout (user's pick, 2026-10-11): actions in a word or two. Names were cut to 20/18 to fit a fixed
	// 240 px panel; the panel now widens to its longest line (0.7.4, user 2026-10-28), so only very long names are cut
	static final int SLOT_NAME = 32;
	static final int ORDER_NAME = 32;

	/**
	 * A name cut to at most {@code n} characters, keeping its last three: "Divine battlemag…(4)". The ending is what tells
	 * an item's doses, charges or tiers apart, so it is never the part dropped.
	 */
	static String shorten(String s, int n)
	{
		if (s == null || s.length() <= n || n < 6)
		{
			return s;
		}
		return s.substring(0, n - 4).trim() + "…" + s.substring(s.length() - 3);
	}

	/** The advice's action in a word or two: "trim to 1,280" -> "trim 1,280". */
	static String compact(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text.replace("collect, list at ", "list ").replace("trim to ", "trim ").replace("re-place at ", "bid ")
			.replace("sell at ", "sell ").replace("cancel, list ", "cancel; list ").replace(" at ", " @ ");
	}

	private static final java.util.regex.Pattern LIST_UNITS = java.util.regex.Pattern.compile("list [0-9][0-9,]*");

	/**
	 * A bid advised to be cancelled or re-placed, with the units it has bought listed: the count as the slot has it now,
	 * not as it was at the check-in — a bid keeps filling until it is cancelled (0.7.2).
	 */
	static String liveUnits(Advice.Slot a, OfferTracker.Offer o)
	{
		if (a.text == null || o == null || !"buy".equals(o.side()) || o.done <= 0)
		{
			return a.text;
		}
		return LIST_UNITS.matcher(a.text).replaceFirst("list " + Advice.fmt(o.done));
	}

	/**
	 * What to draw. Pure, so the panel can be rendered and checked outside the game.
	 *
	 * @param offers     the eight slots as the plugin last saw them
	 * @param mismatches slot -> how the order just placed there differs from the advice
	 * @param check      the offer screen's check, or null when no new offer is being set up
	 * @param maxAgeMs   older advice is shown as out of date and not used
	 */
	static AdviceView build(Advice advice, OfferTracker.Offer[] offers, Map<Integer, String> mismatches, Advice.Check check,
		long now, long maxAgeMs, boolean highlight)
	{
		return build(advice, offers, mismatches, check, now, maxAgeMs, highlight, Collections.emptyMap());
	}

	/**
	 * As above, with the reminders' clocks (0.7.1): a kept order with a deadline gets its own line — the action and the time
	 * left ("trim 3,362 in 2h10m", dim), or, once it has passed, "now: trim 3,362" in the action colour with its slot
	 * outlined; so does an advised order placed after the check-in. Kept orders without a deadline are still only counted.
	 */
	static AdviceView build(Advice advice, OfferTracker.Offer[] offers, Map<Integer, String> mismatches, Advice.Check check,
		long now, long maxAgeMs, boolean highlight, Map<Integer, Reminders.Clock> clocks)
	{
		if (advice == null)
		{
			return NONE;
		}
		final long age = now - advice.generatedAt;
		final String header = "Advice (" + Advice.age(age) + " old)";
		final List<Line> lines = new ArrayList<>();
		final Map<Integer, Color> slots = new LinkedHashMap<>();
		if (age > maxAgeMs)
		{
			lines.add(new Line("Out of date: run your tool again", null, DIM));
			return new AdviceView(header, DIM, lines, slots);
		}
		if (check != null)
		{
			final Color color = "ok".equals(check.level) ? OK : "warn".equals(check.level) ? WARN : DIM;
			for (String l : check.lines)
			{
				lines.add(new Line(l, null, color));
			}
			return new AdviceView(header, TEXT, lines, slots);
		}
		for (Map.Entry<Integer, String> m : mismatches.entrySet())
		{
			lines.add(new Line(m.getKey() + " " + m.getValue(), null, WARN));
			slots.put(m.getKey(), WARN);
		}
		int keep = 0;
		for (int i = 0; i < OfferTracker.SLOTS; i++)
		{
			final OfferTracker.Offer o = offers[i];
			if (o != null && !o.personal && "BOUGHT".equals(o.state))
			{
				// a buy that finished since the check-in: say where to list it now, not at the next check-in (0.6.3)
				final Long at = advice.listAt(i, o);
				if (at != null)
				{
					lines.add(new Line(i + " " + shorten(o.item, SLOT_NAME), "list " + Advice.fmt(at), ACT));
					slots.putIfAbsent(i, ACT);
					continue;
				}
			}
			final Advice.Slot a = advice.forSlot(i, o);
			final Reminders.Clock c = clocks.get(i);
			if (c != null && (a == null || "keep".equals(a.action)) && o != null && !o.personal)
			{
				lines.add(new Line(i + " " + shorten(o.item, SLOT_NAME), c.due ? "now: " + c.action : c.action + " in " + Reminders.left(c.at - now),
					c.due ? ACT : DIM));
				if (c.due)
				{
					slots.putIfAbsent(i, ACT);
				}
				continue;
			}
			if (a == null || o.personal)
			{
				continue;
			}
			if ("keep".equals(a.action))
			{
				keep++;
				continue;
			}
			lines.add(new Line(i + " " + shorten(a.item, SLOT_NAME), compact(liveUnits(a, o)), ACT));
			slots.putIfAbsent(i, ACT);
		}
		for (Advice.Order o : advice.toPlace(offers))
		{
			lines.add(new Line(("buy".equals(o.side) ? "+ " : "- ") + shorten(o.item, ORDER_NAME),
				(o.qty() == null ? "" : Advice.fmt(o.qty()) + " ") + "@ " + Advice.fmt(o.price), TEXT));
		}
		if (keep > 0)
		{
			lines.add(new Line(keep + (keep == 1 ? " slot" : " slots") + ": keep", null, DIM));
		}
		if (lines.isEmpty())
		{
			lines.add(new Line("Nothing to do", null, DIM));
		}
		return new AdviceView(header, TEXT, lines, highlight ? slots : new LinkedHashMap<>());
	}
}
