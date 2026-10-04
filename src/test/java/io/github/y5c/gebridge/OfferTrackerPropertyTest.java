package io.github.y5c.gebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

/**
 * Random order lifecycles in one slot, observed with gaps (missed updates, logouts, login bursts). Whatever the
 * client happens to show, the fills logged for an order must add up to the most it was seen filled, never go
 * negative, and every order seen must be placed (or baselined) exactly once.
 */
public class OfferTrackerPropertyTest
{
	@Test
	public void fillsAlwaysAddUpToWhatWasSeen()
	{
		for (long seed = 1; seed <= 500; seed++)
		{
			run(new Random(seed), seed);
		}
	}

	private static void run(Random rnd, long seed)
	{
		final OfferTracker t = new OfferTracker();
		long now = 1_790_000_000_000L;
		final Map<String, Integer> filled = new HashMap<>();   // order key → sum of logged fill qty
		final Map<String, Integer> maxSeen = new HashMap<>();  // order key → most seen filled
		final Map<String, Integer> openings = new HashMap<>(); // order key → placed/baseline count
		boolean first = true;
		for (int order = 0; order < 6; order++)
		{
			final boolean buy = rnd.nextBoolean();
			final int total = 1 + rnd.nextInt(500);
			final long price = 100 + order;                     // distinct per order, so each has its own key
			final String key = order + "";
			int done = 0;
			while (true)
			{
				done = Math.min(total, done + (rnd.nextInt(3) == 0 ? 0 : 1 + rnd.nextInt(Math.max(1, total / 3))));
				final String state = done == total ? (buy ? "BOUGHT" : "SOLD") : (buy ? "BUYING" : "SELLING");
				final boolean shown = rnd.nextInt(4) != 0;          // a quarter of updates are never seen
				if (rnd.nextInt(8) == 0)
				{
					t.markObserved(now);
					t.resetSession();                               // a logout
				}
				if (shown || done == total)
				{
					final boolean burst = rnd.nextInt(5) == 0;
					tally(t.observe(0, new OfferTracker.Offer(7, "x", state, price, total, done, done * price), now += 1000, burst),
						filled, openings, first);
					first = false;
					maxSeen.merge(key, done, Math::max);
				}
				if (done == total)
				{
					break;
				}
			}
			if (rnd.nextBoolean())
			{
				tally(t.observe(0, new OfferTracker.Offer(0, null, "EMPTY", 0, 0, 0, 0), now += 1000, false), filled, openings, false);
			}
		}
		for (Map.Entry<String, Integer> e : maxSeen.entrySet())
		{
			final int logged = filled.getOrDefault(e.getKey(), 0);
			final boolean baselined = e.getKey().equals("0");
			// a baseline books nothing, so the first order's fills start from its first sighting
			assertTrue("seed " + seed + ": order " + e.getKey() + " logged " + logged + " > seen " + e.getValue(), logged <= e.getValue());
			if (!baselined)
			{
				assertEquals("seed " + seed + ": order " + e.getKey(), (int) e.getValue(), logged);
			}
			assertEquals("seed " + seed + ": order " + e.getKey() + " opened", 1, (int) openings.getOrDefault(e.getKey(), 0));
		}
	}

	private static void tally(List<Map<String, Object>> evs, Map<String, Integer> filled, Map<String, Integer> openings, boolean first)
	{
		for (Map<String, Object> e : evs)
		{
			final String key = (((Long) e.get("price")) - 100) + "";
			final String type = (String) e.get("type");
			if ("fill".equals(type))
			{
				assertTrue("fill qty must be positive", (Integer) e.get("qty") > 0);
				filled.merge(key, (Integer) e.get("qty"), Integer::sum);
			}
			else if ("placed".equals(type) || "baseline".equals(type))
			{
				openings.merge(key, 1, Integer::sum);
			}
			else if ("anomaly".equals(type))
			{
				throw new AssertionError("anomaly on a monotonic sequence: " + e);
			}
		}
	}
}
