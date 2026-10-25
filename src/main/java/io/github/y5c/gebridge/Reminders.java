package io.github.y5c.gebridge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reminders when one of the advice's own deadlines passes (0.7.0): an ask's grace before a trim, the end of a held price
 * dip, a bid with no fills for the tool's stall or dead time, and a completed buy left uncollected. Only orders the advice
 * names are watched, never one marked personal; each reminder fires once per order. Display only: it says what the
 * advice would do next and never acts. Pure: the offers, the advice and the time are passed in.
 */
final class Reminders
{
	/** An ask counts as unsold only without a fill in this long (the tool's own "still filling" window). */
	static final long FILLING_MS = 2 * 3_600_000L;
	/** Prices older than this carry the advice's age, so a stale price reads as one. */
	static final long STALE_MS = 3 * 3_600_000L;

	static final class Settings
	{
		boolean grace = true;
		boolean dip = true;
		boolean stall = true;
		boolean dead = true;
		boolean collect = true;
		int collectMinutes = 10;
	}

	static final class Due
	{
		final int slot;
		final String kind;
		final String text;

		Due(int slot, String kind, String text)
		{
			this.slot = slot;
			this.kind = kind;
			this.text = text;
		}
	}

	private final Set<String> fired = new HashSet<>();

	/** The reminders now due that have not fired yet; each is then marked fired. */
	List<Due> due(Advice a, OfferTracker.Offer[] offers, Settings s, long now)
	{
		final List<Due> out = new ArrayList<>();
		if (a == null)
		{
			return out;
		}
		final String stale = now - a.generatedAt > STALE_MS ? " (advice " + Advice.age(now - a.generatedAt) + " old)" : "";
		final Advice.Deadlines d = a.deadlines;
		for (int i = 0; i < offers.length; i++)
		{
			final OfferTracker.Offer o = offers[i];
			if (o == null || o.isEmpty() || o.personal || o.placedAt == null)
			{
				continue;
			}
			final Advice.Slot sl = a.forSlot(i, o);
			final Advice.Order order = a.orderFor(o);
			if (sl == null && order == null)
			{
				continue;      // an order the advice never named: not the tool's to time
			}
			final long placed = o.placedAt;
			final String id = i + "|" + o.itemId + "|" + o.side() + "|" + o.price + "|" + placed + "|";
			switch (o.state)
			{
				case "SELLING":
				{
					// a held dip has its own clock: its end is the one trim, so the grace does not also fire for it
					final boolean held = sl != null && sl.holdUntil != null;
					if (s.dip && held && now >= sl.holdUntil)
					{
						add(out, id + "dip", i, "dip", o.item + ": the dip hold is over — trim toward "
							+ (sl.holdTrimTo != null ? Advice.fmt(sl.holdTrimTo) + stale : "the 2 h level (check in)"));
					}
					final boolean quiet = o.lastFillAt == null || now - o.lastFillAt >= FILLING_MS;
					if (s.grace && !held && d != null && d.askGraceHours != null && quiet && o.done < o.total
						&& now - placed >= hours(d.askGraceHours))
					{
						add(out, id + "grace", i, "grace", o.item + ": " + (o.done == 0 ? "unsold" : "no sale in 2 h") + " "
							+ trimHours(d.askGraceHours) + " h after listing — "
							+ (sl != null && sl.trimTo != null ? "trim to " + Advice.fmt(sl.trimTo) + stale : "a check-in would trim it"));
					}
					break;
				}
				case "BUYING":
				{
					final long quietFor = now - (o.lastFillAt != null ? o.lastFillAt : placed);
					if (s.dead && d != null && d.bidDeadHours != null && o.done == 0 && quietFor >= hours(d.bidDeadHours))
					{
						add(out, id + "dead", i, "dead", o.item + ": no fills in " + trimHours(d.bidDeadHours)
							+ " h — the advice cancels bids this quiet");
					}
					else if (s.stall && d != null && d.bidStallHours != null && quietFor >= hours(d.bidStallHours))
					{
						add(out, id + "stall", i, "stall", o.item + ": no fills in " + trimHours(d.bidStallHours)
							+ " h — a check-in would re-place or cancel it");
					}
					break;
				}
				case "BOUGHT":
				{
					final long done = o.lastFillAt != null ? o.lastFillAt : placed;
					if (s.collect && now - done >= s.collectMinutes * 60_000L)
					{
						final Long at = sl != null && sl.listAt != null ? sl.listAt : order != null ? order.listLater : null;
						add(out, id + "collect", i, "collect", o.item + ": bought " + Advice.fmt(o.total) + " — collect"
							+ (at != null ? " and list at " + Advice.fmt(at) + stale : " and list"));
					}
					break;
				}
				default:
					break;
			}
		}
		return out;
	}

	private void add(List<Due> out, String key, int slot, String kind, String text)
	{
		if (fired.add(key))
		{
			out.add(new Due(slot, kind, text));
		}
	}

	void clear()
	{
		fired.clear();
	}

	private static long hours(double h)
	{
		return (long) (h * 3_600_000L);
	}

	private static String trimHours(double h)
	{
		return h == Math.rint(h) ? Long.toString((long) h) : Double.toString(h);
	}
}
