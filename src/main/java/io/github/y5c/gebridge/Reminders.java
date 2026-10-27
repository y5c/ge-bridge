package io.github.y5c.gebridge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The advice's own deadlines for the orders it names (0.7.0): an ask's grace before a trim, the end of a held price dip, a
 * bid with no fills for the tool's stall or dead time, and a completed buy left uncollected. {@link #clocks} says, per
 * slot, which deadline applies, when it falls due and what the advice would then do — the panel shows it as a countdown
 * or as due (0.7.1); {@link #due} turns the ones that have passed into reminders, once per order. Only orders the advice
 * names are timed, never one marked personal. Display only: it never acts. Pure: offers, advice and time are passed in.
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

	/** One slot's deadline: its kind, when it falls due, whether it has, the action for the panel, the reminder's text. */
	static final class Clock
	{
		final String kind;
		final long at;
		final boolean due;
		final String action;
		final String notice;
		final String key;

		Clock(String kind, long at, long now, String action, String notice, String key)
		{
			this.kind = kind;
			this.at = at;
			this.due = now >= at;
			this.action = action;
			this.notice = notice;
			this.key = key;
		}
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

	/** Per slot, the deadline that applies now (at most one), whether it has passed or not. */
	static Map<Integer, Clock> clocks(Advice a, OfferTracker.Offer[] offers, Settings s, long now)
	{
		final Map<Integer, Clock> out = new LinkedHashMap<>();
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
			Clock c = null;
			switch (o.state)
			{
				case "SELLING":
				{
					// a held dip has its own clock: its end is the one trim, so the grace does not also run for it
					if (sl != null && sl.holdUntil != null)
					{
						if (s.dip)
						{
							final String to = sl.holdTrimTo != null ? Advice.fmt(sl.holdTrimTo) : null;
							c = new Clock("dip", sl.holdUntil, now, to != null ? "trim " + to : "trim",
								o.item + ": the dip hold is over — trim toward " + (to != null ? to + stale : "the 2 h level (check in)"), id + "dip");
						}
					}
					else if (s.grace && d != null && d.askGraceHours != null && o.done < o.total)
					{
						// due once the grace has run AND the ask has gone 2 h without a sale
						final long at = Math.max(placed + hours(d.askGraceHours), o.lastFillAt != null ? o.lastFillAt + FILLING_MS : 0);
						final String to = sl != null && sl.trimTo != null ? Advice.fmt(sl.trimTo) : null;
						c = new Clock("grace", at, now, to != null ? "trim " + to : "trim (check in)",
							o.item + ": " + (o.done == 0 ? "unsold" : "no sale in 2 h") + " " + trimHours(d.askGraceHours) + " h after listing — "
								+ (to != null ? "trim to " + to + stale : "a check-in would trim it"), id + "grace");
					}
					break;
				}
				case "BUYING":
				{
					final long last = o.lastFillAt != null ? o.lastFillAt : placed;
					final Long dead = s.dead && d != null && d.bidDeadHours != null && o.done == 0 ? placed + hours(d.bidDeadHours) : null;
					final Long stall = s.stall && d != null && d.bidStallHours != null ? last + hours(d.bidStallHours) : null;
					if (dead != null && now >= dead)
					{
						c = new Clock("dead", dead, now, "cancel", o.item + ": no fills in " + trimHours(d.bidDeadHours)
							+ " h — the advice cancels bids this quiet", id + "dead");
					}
					else if (stall != null)
					{
						// what the advice said a stall brings (0.7.3): cancel, or the price to re-place at
						final boolean cancel = sl != null && Boolean.TRUE.equals(sl.stallCancel);
						final String to = !cancel && sl != null && sl.replaceTo != null ? Advice.fmt(sl.replaceTo) : null;
						c = new Clock("stall", stall, now, cancel ? "cancel" : to != null ? "re-place " + to : "re-place (check in)",
							o.item + ": no fills in " + trimHours(d.bidStallHours) + " h — "
								+ (cancel ? "the advice cancels it" : to != null ? "re-place at " + to + stale : "a check-in would re-place or cancel it"),
							id + "stall");
					}
					else if (dead != null)
					{
						c = new Clock("dead", dead, now, "cancel", null, id + "dead");
					}
					break;
				}
				case "BOUGHT":
				{
					if (s.collect)
					{
						final long done = o.lastFillAt != null ? o.lastFillAt : placed;
						final Long at = sl != null && sl.listAt != null ? sl.listAt : order != null ? order.listLater : null;
						c = new Clock("collect", done + s.collectMinutes * 60_000L, now, at != null ? "list " + Advice.fmt(at) : "list",
							o.item + ": bought " + Advice.fmt(o.total) + " — collect" + (at != null ? " and list at " + Advice.fmt(at) + stale : " and list"),
							id + "collect");
					}
					break;
				}
				default:
					break;
			}
			if (c != null)
			{
				out.put(i, c);
			}
		}
		return out;
	}

	/** The reminders now due that have not fired yet; each is then marked fired. */
	List<Due> due(Advice a, OfferTracker.Offer[] offers, Settings s, long now)
	{
		final List<Due> out = new ArrayList<>();
		for (Map.Entry<Integer, Clock> e : clocks(a, offers, s, now).entrySet())
		{
			final Clock c = e.getValue();
			if (c.due && c.notice != null && fired.add(c.key))
			{
				out.add(new Due(e.getKey(), c.kind, c.notice));
			}
		}
		return out;
	}

	void clear()
	{
		fired.clear();
	}

	/** "2h10m", "45m", "<1m": the time left before a deadline, compact enough for one panel line. */
	static String left(long ms)
	{
		final long m = ms / 60_000;
		if (m < 1)
		{
			return "<1m";
		}
		return m < 60 ? m + "m" : (m / 60) + "h" + (m % 60) + "m";
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
