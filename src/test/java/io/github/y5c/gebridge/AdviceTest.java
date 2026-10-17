package io.github.y5c.gebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import org.junit.Test;

public class AdviceTest
{
	private static final int BETA = 9002;
	private static final int GAMMA = 9003;
	private static final int ALPHA = 9001;

	private static final String JSON = "{\"schema\":1,\"generatedAt\":1790000000000,"
		+ "\"slots\":[{\"slot\":0,\"itemId\":9001,\"item\":\"Alpha rune\",\"side\":\"sell\",\"price\":1000,\"total\":1000,"
		+ "\"action\":\"trim\",\"text\":\"trim to 950\"},"
		+ "{\"slot\":4,\"itemId\":9002,\"item\":\"Beta bar\",\"side\":\"buy\",\"price\":14000,\"total\":200,"
		+ "\"action\":\"keep\",\"text\":\"keep\"}],"
		+ "\"orders\":[{\"itemId\":9001,\"item\":\"Alpha rune\",\"side\":\"sell\",\"price\":950,\"qty\":1000,\"why\":\"trim\"},"
		+ "{\"itemId\":9002,\"item\":\"Beta bar\",\"side\":\"buy\",\"price\":300,\"qty\":200,\"why\":\"pick\"},"
		+ "{\"itemId\":9003,\"item\":\"Gamma seed\",\"side\":\"buy\",\"price\":50000,\"why\":\"pick\"}]}";

	private static Advice advice()
	{
		return new Gson().fromJson(JSON, Advice.class);
	}

	private static OfferTracker.Offer offer(int id, String item, String state, long price, int total)
	{
		return new OfferTracker.Offer(id, item, state, price, total, 0, 0);
	}

	private static OfferTracker.Offer[] slots(OfferTracker.Offer... o)
	{
		final OfferTracker.Offer[] out = new OfferTracker.Offer[OfferTracker.SLOTS];
		System.arraycopy(o, 0, out, 0, o.length);
		return out;
	}

	@Test
	public void parsesAndRejectsUnknownSchemas()
	{
		assertTrue(advice().usable());
		assertEquals(3, advice().orders().size());
		assertFalse(new Gson().fromJson("{\"schema\":2,\"generatedAt\":1}", Advice.class).usable());
		assertFalse(new Gson().fromJson("{}", Advice.class).usable());
		assertTrue(new Gson().fromJson("{\"schema\":1,\"generatedAt\":5}", Advice.class).slots().isEmpty());
	}

	@Test
	public void slotAdviceAppliesOnlyToTheSameOrder()
	{
		final Advice a = advice();
		assertEquals("trim to 950", a.forSlot(0, offer(ALPHA, "Alpha rune", "SELLING", 1000, 1000)).text);
		assertNull(a.forSlot(0, offer(ALPHA, "Alpha rune", "SELLING", 950, 1000)));  // already trimmed
		assertNull(a.forSlot(1, offer(ALPHA, "Alpha rune", "SELLING", 1000, 1000)));  // another slot
		assertNull(a.forSlot(0, offer(0, null, "EMPTY", 0, 0)));
	}

	@Test
	public void ordersLeaveTheListOnceASlotHoldsThem()
	{
		final Advice a = advice();
		assertEquals(3, a.open(slots()).size());
		// the Gamma seed order has no quantity: any quantity at the advised price counts
		assertEquals(2, a.open(slots(offer(GAMMA, "Gamma seed", "BUYING", 50000, 37))).size());
		assertEquals(3, a.open(slots(offer(GAMMA, "Gamma seed", "BUYING", 49900, 40))).size());
	}

	@Test
	public void anOrderSeenPlacedStaysOffTheListOnceCollected()
	{
		// 2026-10-12: a sell to the standing buyers filled and was collected within seconds; with its slot empty again the
		// panel listed it as still to place
		final Advice a = advice();
		final OfferTracker.Offer trim = offer(ALPHA, "Alpha rune", "SELLING", 950, 1000);
		a.markPlaced(trim);
		assertEquals(2, a.open(slots()).size());
		assertEquals(2, a.toPlace(slots()).size());
		// an offer that matches no advised order marks nothing
		a.markPlaced(offer(ALPHA, "Alpha rune", "SELLING", 945, 1000));
		a.markPlaced(offer(0, null, "EMPTY", 0, 0));
		assertEquals(2, a.open(slots()).size());
		// a freshly loaded file starts with nothing seen
		assertEquals(3, advice().open(slots()).size());
	}

	@Test
	public void twoEqualOrdersNeedTwoPlacements()
	{
		final String json = "{\"schema\":1,\"generatedAt\":5,\"orders\":["
			+ "{\"itemId\":9003,\"item\":\"Gamma seed\",\"side\":\"buy\",\"price\":50000,\"qty\":20,\"why\":\"split\"},"
			+ "{\"itemId\":9003,\"item\":\"Gamma seed\",\"side\":\"buy\",\"price\":50000,\"qty\":20,\"why\":\"split\"}]}";
		final Advice a = new Gson().fromJson(json, Advice.class);
		final OfferTracker.Offer half = offer(GAMMA, "Gamma seed", "BUYING", 50000, 20);
		a.markPlaced(half);
		assertEquals(1, a.open(slots()).size());
		a.markPlaced(half);
		assertEquals(0, a.open(slots()).size());
	}

	@Test
	public void sellingABidThatCarriesAListPriceIsAdvised()
	{
		// 2026-10-13: a pick's bid completed and was collected; setting up the sale read "Advice is to buy this item"
		final Advice a = new Gson().fromJson("{\"schema\":1,\"generatedAt\":5,\"orders\":[{\"itemId\":9004,\"item\":\"Delta hide\","
			+ "\"side\":\"buy\",\"price\":3000,\"qty\":1200,\"why\":\"pick\",\"listLater\":3300}]}", Advice.class);
		final Advice.Check ok = a.check(9004, "sell", 1200, slots());
		assertEquals("ok", ok.level);
		assertTrue(String.join("|", ok.lines).contains("sell 1,200 @ 3,300 (cost 3,000)"));
		assertEquals("ok", a.check(9004, "sell", 1000, slots()).level);      // part of a part-filled bid
		assertEquals("warn", a.check(9004, "sell", 2000, slots()).level);
		// without a list price the old warning stands
		final Advice b = new Gson().fromJson("{\"schema\":1,\"generatedAt\":5,\"orders\":[{\"itemId\":9004,\"item\":\"Delta hide\","
			+ "\"side\":\"buy\",\"price\":3000,\"qty\":1200,\"why\":\"pick\"}]}", Advice.class);
		assertTrue(String.join("|", b.check(9004, "sell", 1200, slots()).lines).contains("Advice is to buy this item, not sell"));
	}

	@Test
	public void aBidsListPriceIsAdvisedEvenBesideAnotherAsk()
	{
		// 2026-10-15: slot 4's 1,000 units were advised at 2,100 and the new bid's 1,500 carried listLater 2,060;
		// the sale at 2,060 was flagged as differing and the player re-listed at the buy price
		final Advice a = new Gson().fromJson("{\"schema\":1,\"generatedAt\":5,\"orders\":["
			+ "{\"itemId\":9005,\"item\":\"Epsilon ore\",\"side\":\"sell\",\"price\":2100,\"qty\":1000,\"why\":\"list\",\"slot\":4},"
			+ "{\"itemId\":9005,\"item\":\"Epsilon ore\",\"side\":\"buy\",\"price\":2000,\"qty\":1500,\"why\":\"pick\",\"listLater\":2060}]}",
			Advice.class);
		assertNull(a.placedMismatch(offer(9005, "Epsilon ore", "SELLING", 2060, 1500)));
		assertNull(a.placedMismatch(offer(9005, "Epsilon ore", "SELLING", 2100, 1000)));
		assertNotNull(a.placedMismatch(offer(9005, "Epsilon ore", "SELLING", 2000, 1500)));   // the slip still warns
		final Advice.Check c = a.check(9005, "sell", 1500, slots());
		assertEquals("ok", c.level);
		assertTrue(String.join("|", c.lines).contains("or sell 1,500 @ 2,060 (cost 2,000)"));
		assertEquals("warn", a.check(9005, "sell", 3000, slots()).level);
	}

	@Test
	public void offerScreenCatchesAWrongQuantity()
	{
		// the slip this was built for: 250 typed where 200 was advised
		final Advice.Check c = advice().check(BETA, "buy", 250, slots());
		assertEquals("warn", c.level);
		assertTrue(String.join("|", c.lines).contains("Quantity 250 is not the advised 200"));
		assertEquals("ok", advice().check(BETA, "buy", 200, slots()).level);
		assertEquals("ok", advice().check(GAMMA, "buy", 12, slots()).level);    // no quantity advised
	}

	@Test
	public void offerScreenCatchesTheWrongSideAndAnItemWithoutAdvice()
	{
		final Advice.Check c = advice().check(BETA, "sell", 200, slots());
		assertEquals("warn", c.level);
		assertTrue(c.lines.get(0).contains("Advice is to buy this item, not sell"));
		assertEquals("none", advice().check(4151, "buy", 1, slots()).level);
	}

	@Test
	public void offerScreenWarnsBeforeADuplicate()
	{
		final Advice.Check c = advice().check(BETA, "buy", 200, slots(null, null, null, offer(BETA, "Beta bar", "BUYING", 300, 200)));
		assertEquals("warn", c.level);
		assertTrue(String.join("|", c.lines).contains("already placed"));
	}

	@Test
	public void placedOrderIsComparedWithTheAdvice()
	{
		final Advice a = advice();
		assertNull(a.placedMismatch(offer(BETA, "Beta bar", "BUYING", 300, 200)));
		assertEquals("Beta bar: price 14,000 (advised 300), qty 250 (advised 200)",
			a.placedMismatch(offer(BETA, "Beta bar", "BUYING", 14000, 250)));
		assertNull(a.placedMismatch(offer(4151, "Abyssal whip", "BUYING", 1, 1)));            // no advice: nothing to say
		assertNull(a.placedMismatch(offer(BETA, "Beta bar", "SELLING", 1, 1)));  // advice is for the other side
		assertNotNull(a.placedMismatch(offer(ALPHA, "Alpha rune", "SELLING", 1000, 1000)));
	}

	@Test
	public void ageIsShort()
	{
		assertEquals("0m", Advice.age(30_000));
		assertEquals("25m", Advice.age(25 * 60_000));
		assertEquals("3h", Advice.age(3 * 3_600_000 + 59 * 60_000));
		assertEquals("3d", Advice.age(3 * 86_400_000L));
	}

	@Test
	public void anOrderThatCarriesOutASlotsAdviceIsListedOnceThatSlotChanges()
	{
		final Advice a = new Gson().fromJson("{\"schema\":1,\"generatedAt\":1,"
			+ "\"slots\":[{\"slot\":0,\"itemId\":9001,\"item\":\"Alpha rune\",\"side\":\"sell\",\"price\":1000,\"total\":1000,\"action\":\"trim\",\"text\":\"trim to 950\"}],"
			+ "\"orders\":[{\"itemId\":9001,\"item\":\"Alpha rune\",\"side\":\"sell\",\"price\":950,\"qty\":1000,\"why\":\"trim\",\"slot\":0},"
			+ "{\"itemId\":9003,\"item\":\"Gamma seed\",\"side\":\"buy\",\"price\":50000,\"qty\":40,\"why\":\"pick\"}]}", Advice.class);
		// the old ask is still up: its slot line says "trim to 950", so only the new pick is listed
		assertEquals(1, a.toPlace(slots(offer(ALPHA, "Alpha rune", "SELLING", 1000, 1000))).size());
		// cancelled: the slot line is gone and the re-listing becomes something to place
		assertEquals(2, a.toPlace(slots()).size());
		// re-listed at the advised price: placed, nothing left but the pick
		assertEquals(1, a.toPlace(slots(offer(ALPHA, "Alpha rune", "SELLING", 950, 1000))).size());
	}

	@Test
	public void aCompletedBuySaysWhereToListIt()
	{
		final Advice a = new Gson().fromJson("{\"schema\":1,\"generatedAt\":1,"
			+ "\"slots\":[{\"slot\":0,\"itemId\":9005,\"item\":\"Epsilon ore\",\"side\":\"buy\",\"price\":1900,\"total\":2000,\"action\":\"keep\",\"text\":\"keep\",\"listAt\":2200}],"
			+ "\"orders\":[{\"itemId\":9003,\"item\":\"Gamma seed\",\"side\":\"buy\",\"price\":50000,\"qty\":40,\"why\":\"pick\",\"listLater\":55000}]}", Advice.class);
		final OfferTracker.Offer bought = new OfferTracker.Offer(9005, "Epsilon ore", "BOUGHT", 1900, 2000, 2000, 0);
		final OfferTracker.Offer pick = new OfferTracker.Offer(GAMMA, "Gamma seed", "BOUGHT", 50000, 40, 40, 0);
		final OfferTracker.Offer buying = new OfferTracker.Offer(9005, "Epsilon ore", "BUYING", 1900, 2000, 5, 0);
		assertEquals(Long.valueOf(2200), a.listAt(0, bought));          // from the slot's advice
		assertEquals(Long.valueOf(55000), a.listAt(3, pick));          // a pick placed since the check-in
		final AdviceView v = AdviceView.build(a, slots(bought, null, null, pick), new java.util.HashMap<>(), null, 2, 3_600_000L, true);
		assertEquals("list 2,200", v.lines.get(0).right);
		assertEquals("list 55,000", v.lines.get(1).right);
		assertEquals(2, v.slots.size());
		// still buying: the usual keep count, no list line yet
		final AdviceView w = AdviceView.build(a, slots(buying), new java.util.HashMap<>(), null, 2, 3_600_000L, true);
		assertEquals("1 slot: keep", w.lines.get(w.lines.size() - 1).left);
	}

	@Test
	public void compactNamesKeepTheirLastThreeCharacters()
	{
		assertEquals("Divine battlemag…(4)", AdviceView.shorten("Divine battlemage potion(4)", AdviceView.SLOT_NAME));
		assertEquals("Divine super s…(4)", AdviceView.shorten("Divine super strength potion(4)", AdviceView.ORDER_NAME));
		assertEquals("Bagged plant 1", AdviceView.shorten("Bagged plant 1", AdviceView.SLOT_NAME));
		assertEquals(20, AdviceView.shorten("Super combat potion(2) and more", 20).length());
		assertEquals("trim 950", AdviceView.compact("trim to 950"));
		assertEquals("cancel; list 10 @ 50,000", AdviceView.compact("cancel, list 10 at 50,000"));
		assertEquals("bid 6,000", AdviceView.compact("re-place at 6,000"));
	}
}
