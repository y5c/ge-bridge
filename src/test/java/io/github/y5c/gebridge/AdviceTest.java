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
		assertEquals("Battlemage potion(4): price 14,000 (advised 289), quantity 386 (advised 283)",
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
}
