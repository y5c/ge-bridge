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
		if (advice == null)
		{
			return NONE;
		}
		final long age = now - advice.generatedAt;
		final String header = "GE Bridge advice (" + Advice.age(age) + " old)";
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
			lines.add(new Line("Slot " + m.getKey() + ": " + m.getValue(), null, WARN));
			slots.put(m.getKey(), WARN);
		}
		int keep = 0;
		for (int i = 0; i < OfferTracker.SLOTS; i++)
		{
			final Advice.Slot a = advice.forSlot(i, offers[i]);
			if (a == null || offers[i].personal)
			{
				continue;
			}
			if ("keep".equals(a.action))
			{
				keep++;
				continue;
			}
			lines.add(new Line(i + " " + a.item, a.text, ACT));
			slots.putIfAbsent(i, ACT);
		}
		for (Advice.Order o : advice.toPlace(offers))
		{
			lines.add(new Line(o.side + " " + (o.qty == null ? "" : Advice.fmt(o.qty) + " ") + o.item, Advice.fmt(o.price), TEXT));
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
