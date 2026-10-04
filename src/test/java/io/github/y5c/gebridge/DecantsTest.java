package io.github.y5c.gebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class DecantsTest
{
	private static Decants.Change c(String name, int delta)
	{
		return new Decants.Change(name.hashCode(), name, delta);
	}

	@Test
	public void bobsDecantIsFound()
	{
		// 324 (4)s into 1,296 (1)s, plus the empty vials bought for it
		List<Map<String, Object>> d = Decants.find(Arrays.asList(c("Super combat potion(4)", -324), c("Super combat potion(1)", 1296), c("Vial", -972)));
		assertEquals(1, d.size());
		assertEquals("Super combat potion", d.get(0).get("potion"));
		assertEquals(1296, d.get(0).get("doses"));
	}

	@Test
	public void combiningByHandIsADecantToo()
	{
		List<Map<String, Object>> d = Decants.find(Arrays.asList(c("Prayer potion(3)", -1), c("Prayer potion(1)", -1), c("Prayer potion(4)", 1), c("Vial", 1)));
		assertEquals(1, d.size());
		assertEquals(4, d.get(0).get("doses"));
	}

	@Test
	public void ordinaryInventoryChangesAreNot()
	{
		assertTrue(Decants.find(Arrays.asList(c("Super combat potion(4)", 10))).isEmpty());              // a withdrawal
		assertTrue(Decants.find(Arrays.asList(c("Super combat potion(4)", -1), c("Super combat potion(3)", 1))).isEmpty()); // a drink: a dose gone
		assertTrue(Decants.find(Arrays.asList(c("Prayer potion(4)", -2), c("Super restore(2)", 4))).isEmpty());  // different potions
		assertTrue(Decants.find(Arrays.asList(c("Coins", -5), c("Dragon chainbody", 1))).isEmpty());
	}

	@Test
	public void twoKindsAtOnceAreTwoDecants()
	{
		List<Map<String, Object>> d = Decants.find(Arrays.asList(c("Super combat potion(4)", -1), c("Super combat potion(2)", 2),
			c("Prayer potion(3)", -4), c("Prayer potion(4)", 3)));
		assertEquals(2, d.size());
	}
}
