package io.github.y5c.gebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

public class RemindersTest
{
	private static final long H = 3_600_000L;
	private static final long NOW = 1_800_000_000_000L;
	private static final Gson GSON = new Gson();

	/** Synthetic advice: slot 0 an ask with its grace trim, slot 1 an ask in a held dip, slot 2 a bid, and a pick. */
	private static Advice advice(long generatedAt)
	{
		return GSON.fromJson("{\"schema\":1,\"generatedAt\":" + generatedAt + ","
			+ "\"deadlines\":{\"askGraceHours\":5,\"bidStallHours\":6,\"bidDeadHours\":12},"
			+ "\"slots\":[{\"slot\":0,\"itemId\":9001,\"item\":\"Alpha rune\",\"side\":\"sell\",\"price\":1000,\"total\":100,\"action\":\"keep\",\"trimTo\":950},"
			+ "{\"slot\":1,\"itemId\":9002,\"item\":\"Beta bar\",\"side\":\"sell\",\"price\":2000,\"total\":50,\"action\":\"keep\",\"holdUntil\":" + (NOW - H) + ",\"holdTrimTo\":1800},"
			+ "{\"slot\":2,\"itemId\":9003,\"item\":\"Gamma seed\",\"side\":\"buy\",\"price\":300,\"total\":400,\"action\":\"keep\",\"listAt\":330}],"
			+ "\"orders\":[{\"itemId\":9004,\"item\":\"Delta hide\",\"side\":\"buy\",\"price\":700,\"qty\":80,\"why\":\"pick\",\"listLater\":760}]}",
			Advice.class);
	}

	private static OfferTracker.Offer offer(int id, String item, String state, long price, int total, int done, long placedAgo, Long fillAgo)
	{
		final OfferTracker.Offer o = new OfferTracker.Offer(id, item, state, price, total, done, 0);
		o.placedAt = NOW - placedAgo;
		o.lastFillAt = fillAgo == null ? null : NOW - fillAgo;
		return o;
	}

	private static List<String> texts(List<Reminders.Due> d)
	{
		return d.stream().map(x -> x.slot + " " + x.kind + ": " + x.text).collect(Collectors.toList());
	}

	@Test
	public void eachDeadlineFiresOnceWithTheAdvicesPrice()
	{
		final OfferTracker.Offer[] o = new OfferTracker.Offer[8];
		o[0] = offer(9001, "Alpha rune", "SELLING", 1000, 100, 0, 6 * H, null);       // past its 5 h grace
		o[1] = offer(9002, "Beta bar", "SELLING", 2000, 50, 0, 30 * H, null);         // dip hold ended an hour ago
		o[2] = offer(9003, "Gamma seed", "BOUGHT", 300, 400, 400, 2 * H, 20 * 60_000L); // completed 20 min ago
		o[3] = offer(9004, "Delta hide", "BUYING", 700, 80, 0, 13 * H, null);         // the pick, quiet 13 h
		final Reminders r = new Reminders();
		final List<String> t = texts(r.due(advice(NOW - H), o, new Reminders.Settings(), NOW));
		assertEquals(4, t.size());
		assertEquals("0 grace: Alpha rune: unsold 5 h after listing — trim to 950", t.get(0));
		assertEquals("1 dip: Beta bar: the dip hold is over — trim toward 1,800", t.get(1));
		assertEquals("2 collect: Gamma seed: bought 400 — collect and list at 330", t.get(2));
		assertEquals("3 dead: Delta hide: no fills in 12 h — the advice cancels bids this quiet", t.get(3));
		assertTrue(r.due(advice(NOW - H), o, new Reminders.Settings(), NOW + 60_000L).isEmpty());     // once only
	}

	@Test
	public void beforeTheirDeadlinesNothingFires()
	{
		final OfferTracker.Offer[] o = new OfferTracker.Offer[8];
		o[0] = offer(9001, "Alpha rune", "SELLING", 1000, 100, 0, 4 * H, null);
		o[0 + 1] = offer(9002, "Beta bar", "SELLING", 2000, 50, 0, 30 * H, null);
		o[2] = offer(9003, "Gamma seed", "BOUGHT", 300, 400, 400, 2 * H, 5 * 60_000L);
		o[3] = offer(9004, "Delta hide", "BUYING", 700, 80, 10, 13 * H, 3 * H);      // a fill 3 h ago: not stalled
		final Advice a = advice(NOW - H);
		a.slots().get(1).holdUntil = NOW + H;                                          // dip still held: no grace either
		assertTrue(texts(new Reminders().due(a, o, new Reminders.Settings(), NOW)).isEmpty());
	}

	@Test
	public void aPartFilledBidStallsAndAnAskStillFillingWaits()
	{
		final OfferTracker.Offer[] o = new OfferTracker.Offer[8];
		o[3] = offer(9004, "Delta hide", "BUYING", 700, 80, 10, 13 * H, 7 * H);
		o[0] = offer(9001, "Alpha rune", "SELLING", 1000, 100, 30, 6 * H, H);          // sold some an hour ago
		final List<String> t = texts(new Reminders().due(advice(NOW - H), o, new Reminders.Settings(), NOW));
		assertEquals(List.of("3 stall: Delta hide: no fills in 6 h — a check-in would re-place or cancel it"), t);
	}

	@Test
	public void personalAndUnadvisedOrdersAreNotTimed()
	{
		final OfferTracker.Offer[] o = new OfferTracker.Offer[8];
		o[0] = offer(9001, "Alpha rune", "SELLING", 1000, 100, 0, 6 * H, null);
		o[0].personal = true;
		o[4] = offer(9100, "Other thing", "BUYING", 5, 1000, 0, 20 * H, null);
		o[5] = offer(9001, "Alpha rune", "SELLING", 990, 100, 0, 6 * H, null);           // not the advised price
		assertTrue(new Reminders().due(advice(NOW - H), o, new Reminders.Settings(), NOW).isEmpty());
	}

	@Test
	public void oldAdviceSaysSoAndSwitchesTurnTriggersOff()
	{
		final OfferTracker.Offer[] o = new OfferTracker.Offer[8];
		o[0] = offer(9001, "Alpha rune", "SELLING", 1000, 100, 0, 6 * H, null);
		o[2] = offer(9003, "Gamma seed", "BOUGHT", 300, 400, 400, 2 * H, 20 * 60_000L);
		final Reminders.Settings s = new Reminders.Settings();
		s.collect = false;
		final List<String> t = texts(new Reminders().due(advice(NOW - 5 * H), o, s, NOW));
		assertEquals(List.of("0 grace: Alpha rune: unsold 5 h after listing — trim to 950 (advice 5h old)"), t);
	}

	@Test
	public void withoutDeadlinesOnlyTheCollectNudgeRuns()
	{
		final Advice a = advice(NOW - H);
		a.deadlines = null;                    // advice from a tool that predates 0.7.0
		final OfferTracker.Offer[] o = new OfferTracker.Offer[8];
		o[0] = offer(9001, "Alpha rune", "SELLING", 1000, 100, 0, 6 * H, null);
		o[2] = offer(9003, "Gamma seed", "BOUGHT", 300, 400, 400, 2 * H, 20 * 60_000L);
		assertEquals(List.of("2 collect: Gamma seed: bought 400 — collect and list at 330"), texts(new Reminders().due(a, o, new Reminders.Settings(), NOW)));
	}

	@Test
	public void clocksCountDownThenTurnDue()
	{
		final OfferTracker.Offer[] o = new OfferTracker.Offer[8];
		o[0] = offer(9001, "Alpha rune", "SELLING", 1000, 100, 0, 3 * H, null);         // 2 h of its 5 h grace left
		o[3] = offer(9004, "Delta hide", "BUYING", 700, 80, 0, 13 * H, null);            // the pick, 13 h without a fill
		final java.util.Map<Integer, Reminders.Clock> c = Reminders.clocks(advice(NOW - H), o, new Reminders.Settings(), NOW);
		assertEquals(false, c.get(0).due);
		assertEquals("trim 950", c.get(0).action);
		assertEquals("2h0m", Reminders.left(c.get(0).at - NOW));
		assertEquals("dead", c.get(3).kind);
		assertEquals(true, c.get(3).due);
		final Reminders.Settings noDead = new Reminders.Settings();
		noDead.dead = false;
		assertEquals("stall", Reminders.clocks(advice(NOW - H), o, noDead, NOW).get(3).kind);
		assertEquals("<1m", Reminders.left(30_000));
		assertEquals("45m", Reminders.left(45 * 60_000L));
	}

	@Test
	public void aStalledBidNamesWhatTheAdviceSaidItBrings()
	{
		// 0.7.3 (expeditious bracelet 2026-10-27): "now: re-place" without a price
		final OfferTracker.Offer[] o = new OfferTracker.Offer[8];
		o[2] = offer(9003, "Gamma seed", "BUYING", 300, 400, 0, 7 * H, null);              // 7 h without a fill
		final Advice a = advice(NOW - H);
		assertEquals("re-place (check in)", Reminders.clocks(a, o, new Reminders.Settings(), NOW).get(2).action);
		a.slots().get(2).replaceTo = 310L;
		assertEquals("re-place 310", Reminders.clocks(a, o, new Reminders.Settings(), NOW).get(2).action);
		assertEquals(List.of("2 stall: Gamma seed: no fills in 6 h — re-place at 310"),
			texts(new Reminders().due(a, o, new Reminders.Settings(), NOW)));
		final Advice old = advice(NOW - 5 * H);
		old.slots().get(2).replaceTo = 310L;
		assertEquals(List.of("2 stall: Gamma seed: no fills in 6 h — re-place at 310 (advice 5h old)"),
			texts(new Reminders().due(old, o, new Reminders.Settings(), NOW)));
		a.slots().get(2).stallCancel = true;                                                 // a cancel day of the re-place A/B
		assertEquals("cancel", Reminders.clocks(a, o, new Reminders.Settings(), NOW).get(2).action);
		assertEquals(List.of("2 stall: Gamma seed: no fills in 6 h — the advice cancels it"),
			texts(new Reminders().due(a, o, new Reminders.Settings(), NOW)));
	}

	@Test
	public void thePanelShowsKeptOrdersDeadlines()
	{
		final Advice a = advice(NOW - H);
		a.slots().get(0).action = "keep";
		final OfferTracker.Offer[] o = new OfferTracker.Offer[8];
		o[0] = offer(9001, "Alpha rune", "SELLING", 1000, 100, 0, 3 * H, null);
		o[3] = offer(9004, "Delta hide", "BUYING", 700, 80, 0, 13 * H, null);
		final AdviceView v = AdviceView.build(a, o, java.util.Collections.emptyMap(), null, NOW, 12 * H, true,
			Reminders.clocks(a, o, new Reminders.Settings(), NOW));
		final java.util.List<String> lines = v.lines.stream().map(l -> l.left + " | " + l.right).collect(Collectors.toList());
		assertTrue(lines.toString(), lines.contains("0 Alpha rune | trim 950 in 2h0m"));
		assertTrue(lines.toString(), lines.contains("3 Delta hide | now: cancel"));
		assertEquals(AdviceView.ACT, v.slots.get(3));
		assertTrue(!v.slots.containsKey(0));                                               // not due yet: no outline
		final AdviceView without = AdviceView.build(a, o, java.util.Collections.emptyMap(), null, NOW, 12 * H, true);
		assertTrue(without.lines.stream().noneMatch(l -> l.left.startsWith("0 ")));        // without clocks: counted as kept
	}
}
