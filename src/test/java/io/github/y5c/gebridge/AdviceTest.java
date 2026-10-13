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
	private static final int BATTLEMAGE = 24623;
	private static final int CHAINBODY = 3140;
	private static final int JAVELIN = 19484;

	private static final String JSON = "{\"schema\":1,\"generatedAt\":1790000000000,"
		+ "\"slots\":[{\"slot\":0,\"itemId\":19484,\"item\":\"Dragon javelin(p++)\",\"side\":\"sell\",\"price\":1331,\"total\":1560,"
		+ "\"action\":\"trim\",\"text\":\"trim to 1,280\"},"
		+ "{\"slot\":4,\"itemId\":24623,\"item\":\"Battlemage potion(4)\",\"side\":\"buy\",\"price\":14000,\"total\":283,"
		+ "\"action\":\"keep\",\"text\":\"keep\"}],"
		+ "\"orders\":[{\"itemId\":19484,\"item\":\"Dragon javelin(p++)\",\"side\":\"sell\",\"price\":1280,\"qty\":1560,\"why\":\"trim\"},"
		+ "{\"itemId\":24623,\"item\":\"Battlemage potion(4)\",\"side\":\"buy\",\"price\":289,\"qty\":283,\"why\":\"pick\"},"
		+ "{\"itemId\":3140,\"item\":\"Dragon chainbody\",\"side\":\"buy\",\"price\":177255,\"why\":\"pick\"}]}";

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
		assertEquals("trim to 1,280", a.forSlot(0, offer(JAVELIN, "Dragon javelin(p++)", "SELLING", 1331, 1560)).text);
		assertNull(a.forSlot(0, offer(JAVELIN, "Dragon javelin(p++)", "SELLING", 1280, 1560)));  // already trimmed
		assertNull(a.forSlot(1, offer(JAVELIN, "Dragon javelin(p++)", "SELLING", 1331, 1560)));  // another slot
		assertNull(a.forSlot(0, offer(0, null, "EMPTY", 0, 0)));
	}

	@Test
	public void ordersLeaveTheListOnceASlotHoldsThem()
	{
		final Advice a = advice();
		assertEquals(3, a.open(slots()).size());
		// the chainbody order has no quantity: any quantity at the advised price counts
		assertEquals(2, a.open(slots(offer(CHAINBODY, "Dragon chainbody", "BUYING", 177255, 37))).size());
		assertEquals(3, a.open(slots(offer(CHAINBODY, "Dragon chainbody", "BUYING", 177000, 40))).size());
	}

	@Test
	public void anOrderSeenPlacedStaysOffTheListOnceCollected()
	{
		// 2026-10-12: a sell to the standing buyers filled and was collected within seconds; with its slot empty again the
		// panel listed it as still to place
		final Advice a = advice();
		final OfferTracker.Offer trim = offer(JAVELIN, "Dragon javelin(p++)", "SELLING", 1280, 1560);
		a.markPlaced(trim);
		assertEquals(2, a.open(slots()).size());
		assertEquals(2, a.toPlace(slots()).size());
		// an offer that matches no advised order marks nothing
		a.markPlaced(offer(JAVELIN, "Dragon javelin(p++)", "SELLING", 1275, 1560));
		a.markPlaced(offer(0, null, "EMPTY", 0, 0));
		assertEquals(2, a.open(slots()).size());
		// a freshly loaded file starts with nothing seen
		assertEquals(3, advice().open(slots()).size());
	}

	@Test
	public void twoEqualOrdersNeedTwoPlacements()
	{
		final String json = "{\"schema\":1,\"generatedAt\":5,\"orders\":["
			+ "{\"itemId\":3140,\"item\":\"Dragon chainbody\",\"side\":\"buy\",\"price\":177255,\"qty\":20,\"why\":\"split\"},"
			+ "{\"itemId\":3140,\"item\":\"Dragon chainbody\",\"side\":\"buy\",\"price\":177255,\"qty\":20,\"why\":\"split\"}]}";
		final Advice a = new Gson().fromJson(json, Advice.class);
		final OfferTracker.Offer half = offer(CHAINBODY, "Dragon chainbody", "BUYING", 177255, 20);
		a.markPlaced(half);
		assertEquals(1, a.open(slots()).size());
		a.markPlaced(half);
		assertEquals(0, a.open(slots()).size());
	}

	@Test
	public void sellingABidThatCarriesAListPriceIsAdvised()
	{
		// 2026-10-13: the nihil dust bid completed and was collected; setting up the sale read "Advice is to buy this item"
		final Advice a = new Gson().fromJson("{\"schema\":1,\"generatedAt\":5,\"orders\":[{\"itemId\":26368,\"item\":\"Nihil dust\","
			+ "\"side\":\"buy\",\"price\":2886,\"qty\":1316,\"why\":\"pick\",\"listLater\":3345}]}", Advice.class);
		final Advice.Check ok = a.check(26368, "sell", 1316, slots());
		assertEquals("ok", ok.level);
		assertTrue(String.join("|", ok.lines).contains("sell 1,316 @ 3,345 (cost 2,886)"));
		assertEquals("ok", a.check(26368, "sell", 1000, slots()).level);      // part of a part-filled bid
		assertEquals("warn", a.check(26368, "sell", 2000, slots()).level);
		// without a list price the old warning stands
		final Advice b = new Gson().fromJson("{\"schema\":1,\"generatedAt\":5,\"orders\":[{\"itemId\":26368,\"item\":\"Nihil dust\","
			+ "\"side\":\"buy\",\"price\":2886,\"qty\":1316,\"why\":\"pick\"}]}", Advice.class);
		assertTrue(String.join("|", b.check(26368, "sell", 1316, slots()).lines).contains("Advice is to buy this item, not sell"));
	}

	@Test
	public void offerScreenCatchesAWrongQuantity()
	{
		// the slip this was built for: 386 typed where 283 was advised
		final Advice.Check c = advice().check(BATTLEMAGE, "buy", 386, slots());
		assertEquals("warn", c.level);
		assertTrue(String.join("|", c.lines).contains("Quantity 386 is not the advised 283"));
		assertEquals("ok", advice().check(BATTLEMAGE, "buy", 283, slots()).level);
		assertEquals("ok", advice().check(CHAINBODY, "buy", 12, slots()).level);    // no quantity advised
	}

	@Test
	public void offerScreenCatchesTheWrongSideAndAnItemWithoutAdvice()
	{
		final Advice.Check c = advice().check(BATTLEMAGE, "sell", 283, slots());
		assertEquals("warn", c.level);
		assertTrue(c.lines.get(0).contains("Advice is to buy this item, not sell"));
		assertEquals("none", advice().check(4151, "buy", 1, slots()).level);
	}

	@Test
	public void offerScreenWarnsBeforeADuplicate()
	{
		final Advice.Check c = advice().check(BATTLEMAGE, "buy", 283, slots(null, null, null, offer(BATTLEMAGE, "Battlemage potion(4)", "BUYING", 289, 283)));
		assertEquals("warn", c.level);
		assertTrue(String.join("|", c.lines).contains("already placed"));
	}

	@Test
	public void placedOrderIsComparedWithTheAdvice()
	{
		final Advice a = advice();
		assertNull(a.placedMismatch(offer(BATTLEMAGE, "Battlemage potion(4)", "BUYING", 289, 283)));
		assertEquals("Battlemage potion(4): price 14,000 (advised 289), qty 386 (advised 283)",
			a.placedMismatch(offer(BATTLEMAGE, "Battlemage potion(4)", "BUYING", 14000, 386)));
		assertNull(a.placedMismatch(offer(4151, "Abyssal whip", "BUYING", 1, 1)));            // no advice: nothing to say
		assertNull(a.placedMismatch(offer(BATTLEMAGE, "Battlemage potion(4)", "SELLING", 1, 1)));  // advice is for the other side
		assertNotNull(a.placedMismatch(offer(JAVELIN, "Dragon javelin(p++)", "SELLING", 1331, 1560)));
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
			+ "\"slots\":[{\"slot\":0,\"itemId\":19484,\"item\":\"Dragon javelin(p++)\",\"side\":\"sell\",\"price\":1331,\"total\":1560,\"action\":\"trim\",\"text\":\"trim to 1,280\"}],"
			+ "\"orders\":[{\"itemId\":19484,\"item\":\"Dragon javelin(p++)\",\"side\":\"sell\",\"price\":1280,\"qty\":1560,\"why\":\"trim\",\"slot\":0},"
			+ "{\"itemId\":3140,\"item\":\"Dragon chainbody\",\"side\":\"buy\",\"price\":177255,\"qty\":40,\"why\":\"pick\"}]}", Advice.class);
		// the old ask is still up: its slot line says "trim to 1,280", so only the new pick is listed
		assertEquals(1, a.toPlace(slots(offer(JAVELIN, "Dragon javelin(p++)", "SELLING", 1331, 1560))).size());
		// cancelled: the slot line is gone and the re-listing becomes something to place
		assertEquals(2, a.toPlace(slots()).size());
		// re-listed at the advised price: placed, nothing left but the pick
		assertEquals(1, a.toPlace(slots(offer(JAVELIN, "Dragon javelin(p++)", "SELLING", 1280, 1560))).size());
	}

	@Test
	public void aCompletedBuySaysWhereToListIt()
	{
		final Advice a = new Gson().fromJson("{\"schema\":1,\"generatedAt\":1,"
			+ "\"slots\":[{\"slot\":0,\"itemId\":8431,\"item\":\"Bagged plant 1\",\"side\":\"buy\",\"price\":2078,\"total\":2038,\"action\":\"keep\",\"text\":\"keep\",\"listAt\":2290}],"
			+ "\"orders\":[{\"itemId\":3140,\"item\":\"Dragon chainbody\",\"side\":\"buy\",\"price\":177255,\"qty\":40,\"why\":\"pick\",\"listLater\":188341}]}", Advice.class);
		final OfferTracker.Offer bought = new OfferTracker.Offer(8431, "Bagged plant 1", "BOUGHT", 2078, 2038, 2038, 0);
		final OfferTracker.Offer pick = new OfferTracker.Offer(CHAINBODY, "Dragon chainbody", "BOUGHT", 177255, 40, 40, 0);
		final OfferTracker.Offer buying = new OfferTracker.Offer(8431, "Bagged plant 1", "BUYING", 2078, 2038, 5, 0);
		assertEquals(Long.valueOf(2290), a.listAt(0, bought));          // from the slot's advice
		assertEquals(Long.valueOf(188341), a.listAt(3, pick));          // a pick placed since the check-in
		final AdviceView v = AdviceView.build(a, slots(bought, null, null, pick), new java.util.HashMap<>(), null, 2, 3_600_000L, true);
		assertEquals("list 2,290", v.lines.get(0).right);
		assertEquals("list 188,341", v.lines.get(1).right);
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
		assertEquals("trim 1,280", AdviceView.compact("trim to 1,280"));
		assertEquals("cancel; list 13 @ 52,208", AdviceView.compact("cancel, list 13 at 52,208"));
		assertEquals("bid 6,248", AdviceView.compact("re-place at 6,248"));
	}
}
