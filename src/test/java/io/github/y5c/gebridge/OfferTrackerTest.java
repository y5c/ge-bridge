package io.github.y5c.gebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Test;

public class OfferTrackerTest
{
	private static final long T0 = 1_790_000_000_000L;

	private static OfferTracker.Offer buy(int done, long spent)
	{
		return new OfferTracker.Offer(3140, "Dragon chainbody", done == 24 ? "BOUGHT" : "BUYING", 189_334, 24, done, spent);
	}

	private static OfferTracker.Offer empty()
	{
		return new OfferTracker.Offer(0, null, "EMPTY", 0, 0, 0, 0);
	}

	private static List<String> types(List<Map<String, Object>> evs)
	{
		return evs.stream().map(e -> (String) e.get("type")).collect(Collectors.toList());
	}

	private static OfferTracker remembering(OfferTracker.Offer o, long observedAt)
	{
		OfferTracker t = new OfferTracker();
		o.observedAt = observedAt;
		t.restore(0, o);
		return t;
	}

	@Test
	public void firstRunRecordsABaselineAndBooksNothing()
	{
		OfferTracker t = new OfferTracker();
		List<Map<String, Object>> evs = t.observe(0, buy(10, 1_893_340), T0);
		assertEquals(List.of("baseline"), types(evs));
		assertTrue(t.observe(1, empty(), T0).isEmpty());
	}

	@Test
	public void liveFillIsTimedAndNotOffline()
	{
		OfferTracker t = remembering(buy(10, 1_893_340), T0 - 60_000);
		assertTrue(t.observe(0, buy(10, 1_893_340), T0).isEmpty());
		List<Map<String, Object>> evs = t.observe(0, buy(15, 2_840_010), T0 + 5_000);
		assertEquals(List.of("fill"), types(evs));
		assertEquals(5, evs.get(0).get("qty"));
		assertEquals(946_670L, evs.get(0).get("gp"));
		assertNull(evs.get(0).get("offline"));
		assertEquals(T0 + 5_000, (long) t.get(0).lastFillAt);
	}

	@Test
	public void changeFirstSeenAtLoginIsOfflineWithItsWindow()
	{
		OfferTracker t = remembering(buy(10, 1_893_340), T0 - 3_600_000);
		List<Map<String, Object>> evs = t.observe(0, buy(24, 4_544_016), T0);
		assertEquals(List.of("fill", "completed"), types(evs));
		assertEquals(14, evs.get(0).get("qty"));
		assertEquals(true, evs.get(0).get("offline"));
		assertEquals(OfferTracker.iso(T0 - 3_600_000), evs.get(0).get("since"));
		assertEquals(true, evs.get(1).get("offline"));
	}

	@Test
	public void fullLifecycleInOneSession()
	{
		OfferTracker t = remembering(empty(), T0 - 1_000);
		assertTrue(t.observe(0, empty(), T0).isEmpty());
		assertEquals(List.of("placed"), types(t.observe(0, buy(0, 0), T0 + 1)));
		assertEquals(T0 + 1, (long) t.get(0).placedAt);
		assertEquals(List.of("fill"), types(t.observe(0, buy(20, 3_786_680), T0 + 2)));
		assertEquals(List.of("fill", "completed"), types(t.observe(0, buy(24, 4_544_016), T0 + 3)));
		List<Map<String, Object>> evs = t.observe(0, empty(), T0 + 4);
		assertEquals(List.of("cleared"), types(evs));
		assertNull(evs.get(0).get("unobserved"));
	}

	@Test
	public void instantFillOnPlacementBooksTheUnits()
	{
		OfferTracker t = remembering(empty(), T0 - 1_000);
		t.observe(0, empty(), T0);
		List<Map<String, Object>> evs = t.observe(0, buy(5, 946_670), T0 + 1);
		assertEquals(List.of("placed", "fill"), types(evs));
		assertEquals(5, evs.get(1).get("qty"));
	}

	@Test
	public void cancelThenCollect()
	{
		OfferTracker t = remembering(buy(10, 1_893_340), T0 - 1_000);
		t.observe(0, buy(10, 1_893_340), T0);
		OfferTracker.Offer c = buy(10, 1_893_340);
		c.state = "CANCELLED_BUY";
		assertEquals(List.of("cancelled"), types(t.observe(0, c, T0 + 1)));
		List<Map<String, Object>> evs = t.observe(0, empty(), T0 + 2);
		assertEquals(List.of("cleared"), types(evs));
		assertNull(evs.get(0).get("unobserved"));
	}

	@Test
	public void slotEmptiedWhileAwayIsFlaggedUnobserved()
	{
		OfferTracker t = remembering(buy(10, 1_893_340), T0 - 3_600_000);
		List<Map<String, Object>> evs = t.observe(0, empty(), T0);
		assertEquals(List.of("cleared"), types(evs));
		assertEquals(true, evs.get(0).get("unobserved"));
		assertEquals(true, evs.get(0).get("offline"));
	}

	@Test
	public void orderReplacedWhileAwayClearsTheOldAndPlacesTheNew()
	{
		OfferTracker t = remembering(buy(10, 1_893_340), T0 - 3_600_000);
		OfferTracker.Offer sell = new OfferTracker.Offer(3140, "Dragon chainbody", "SELLING", 197_452, 24, 3, 580_000);
		List<Map<String, Object>> evs = t.observe(0, sell, T0);
		assertEquals(List.of("cleared", "placed", "fill"), types(evs));
		assertEquals(true, evs.get(0).get("unobserved"));
		assertEquals("sell", evs.get(2).get("side"));
		assertEquals(true, t.get(0).placedOffline);
	}

	@Test
	public void logoutMakesTheNextSightOfflineAgain()
	{
		OfferTracker t = remembering(buy(10, 1_893_340), T0 - 1_000);
		t.observe(0, buy(10, 1_893_340), T0);
		t.markObserved(T0 + 10_000);
		t.resetSession();
		List<Map<String, Object>> evs = t.observe(0, buy(12, 2_271_888), T0 + 20_000);
		assertEquals(true, evs.get(0).get("offline"));
		assertEquals(OfferTracker.iso(T0 + 10_000), evs.get(0).get("since"));
	}

	@Test
	public void stateJsonKeepsTheOldExporterFieldNames()
	{
		OfferTracker t = remembering(buy(10, 1_893_340), T0);
		@SuppressWarnings("unchecked")
		Map<String, Object> slot = (Map<String, Object>) ((Map<String, Object>) t.toJson().get("offers")).get("0");
		assertEquals("BUYING", slot.get("state"));
		assertEquals(189_334L, slot.get("listedPrice"));
		assertEquals(14, slot.get("remainingQuantity"));
		assertEquals("Dragon chainbody", slot.get("itemName"));
	}
	@Test
	public void fillArrivingInTheLoginBurstAfterTheSlotWasSeenIsOffline()
	{
		// the slot comes back unchanged at login, then fills made while logged out arrive a second later
		OfferTracker t = remembering(buy(10, 1_893_340), T0 - 1_000);
		t.observe(0, buy(10, 1_893_340), T0);
		t.markObserved(T0 + 10_000);
		t.resetSession();
		assertTrue(t.observe(0, buy(10, 1_893_340), T0 + 60_000, true).isEmpty());
		List<Map<String, Object>> evs = t.observe(0, buy(14, 2_650_676), T0 + 61_000, true);
		assertEquals(true, evs.get(0).get("offline"));
		assertEquals(OfferTracker.iso(T0 + 10_000), evs.get(0).get("since"));
		// after the burst, a change is live again
		assertNull(t.observe(0, buy(15, 2_840_010), T0 + 120_000, false).get(0).get("offline"));
	}
}
