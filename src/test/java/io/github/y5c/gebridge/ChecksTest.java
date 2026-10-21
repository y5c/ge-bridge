package io.github.y5c.gebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.gson.JsonObject;
import org.junit.Test;

public class ChecksTest
{
	@Test
	public void checkOptionsAreRecognised()
	{
		assertTrue(Checks.isCheck("Check"));
		assertTrue(Checks.isCheck("Check-charges"));
		assertTrue(Checks.isCheck(" check "));
		assertFalse(Checks.isCheck("Uncharge"));
		assertFalse(Checks.isCheck(null));
	}

	@Test
	public void theAnswerIsKeptWithItsItemAndTime()
	{
		final Checks c = new Checks();
		c.clicked(12926, "Toxic blowpipe", 100);
		assertTrue(c.message("Darts: Dragon dart x 1,234. Scales: 5,678 (35.0%).", 101, 5000L));
		final JsonObject v = c.toJson().getAsJsonObject("12926");
		assertEquals("Toxic blowpipe", v.get("name").getAsString());
		assertEquals(1, v.getAsJsonArray("lines").size());
		assertEquals(5000L, v.get("timestamp").getAsLong());
	}

	@Test
	public void aManyLineAnswerIsJoinedAndANewCheckReplacesIt()
	{
		final Checks c = new Checks();
		c.clicked(13226, "Herb sack", 10);
		c.message("You look in your herb sack and see:", 11, 1L);
		c.message("12 x Grimy ranarr weed", 11, 1L);
		assertEquals(2, c.toJson().getAsJsonObject("13226").getAsJsonArray("lines").size());
		c.clicked(13226, "Herb sack", 50);
		c.message("The herb sack is empty.", 51, 2L);
		assertEquals(1, c.toJson().getAsJsonObject("13226").getAsJsonArray("lines").size());
	}

	@Test
	public void messagesOutsideTheWindowOrWithoutAClickAreIgnored()
	{
		final Checks c = new Checks();
		assertFalse(c.message("You feel a slight tingle.", 5, 1L));      // no click yet
		c.clicked(11905, "Trident of the seas", 10);
		assertFalse(c.message("Your trident has 2,500 charges.", 10 + Checks.WINDOW_TICKS + 1, 1L));
		assertEquals(0, c.toJson().size());
	}

	@Test
	public void aRestartKeepsTheLastAnswersAndTheListIsBounded()
	{
		final Checks a = new Checks();
		a.clicked(1, "A", 0);
		a.message("Your A has 5 charges.", 1, 1L);
		final Checks b = new Checks();
		b.restore(a.toJson());
		assertEquals("A", b.toJson().getAsJsonObject("1").get("name").getAsString());
		for (int i = 2; i < 2 + Checks.MAX_ITEMS; i++)
		{
			b.clicked(i, "X", i);
			b.message("x", i, 1L);
		}
		assertEquals(Checks.MAX_ITEMS, b.toJson().size());
		assertFalse(b.toJson().has("1"));                                // the oldest answer goes first
		b.clear();
		assertEquals(0, b.toJson().size());
	}
}
