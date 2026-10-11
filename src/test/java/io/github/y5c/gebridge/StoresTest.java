package io.github.y5c.gebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

public class StoresTest
{
	@Test
	public void pouchSlotsInOrderEmptyAndUnknownSkipped()
	{
		final Map<Integer, Integer> vb = new HashMap<>();
		vb.put(VarbitID.RUNE_POUCH_TYPE_1, 6);    // a rune type the enum maps
		vb.put(VarbitID.RUNE_POUCH_QUANTITY_1, 16000);
		vb.put(VarbitID.RUNE_POUCH_TYPE_2, 9);
		vb.put(VarbitID.RUNE_POUCH_QUANTITY_2, 0);   // type set, quantity 0: empty
		vb.put(VarbitID.RUNE_POUCH_TYPE_4, 99);      // a type the enum does not map: skipped
		vb.put(VarbitID.RUNE_POUCH_QUANTITY_4, 5);
		vb.put(VarbitID.RUNE_POUCH_TYPE_6, 7);       // the sixth slot (divine rune pouch) is read too
		vb.put(VarbitID.RUNE_POUCH_QUANTITY_6, 250);
		final JsonObject p = Stores.runePouch(v -> vb.getOrDefault(v, 0),
			t -> t == 6 ? 560 : t == 7 ? 565 : -1,
			id -> id == 560 ? "Death rune" : "Blood rune",
			id -> id == 560 ? 200L : 350L);
		assertTrue(p.get("loaded").getAsBoolean());
		assertEquals(2, p.get("itemCount").getAsInt());
		final JsonObject items = p.getAsJsonObject("items");
		assertEquals("Death rune", items.getAsJsonObject("0").get("name").getAsString());
		assertEquals(16000, items.getAsJsonObject("0").get("quantity").getAsInt());
		assertEquals(565, items.getAsJsonObject("5").get("id").getAsInt());
		assertEquals(16000L * 200 + 250L * 350, p.get("value").getAsLong());
		assertFalse(items.has("1"));
		assertFalse(items.has("3"));
	}

	@Test
	public void anEmptyPouchIsLoadedAndEmpty()
	{
		final JsonObject p = Stores.runePouch(v -> 0, t -> 560, id -> "x", id -> 1L);
		assertTrue(p.get("loaded").getAsBoolean());
		assertEquals(0, p.get("itemCount").getAsInt());
	}

	@Test
	public void pouchVarbitsAreRecognised()
	{
		assertTrue(Stores.isPouchVarbit(VarbitID.RUNE_POUCH_TYPE_1));
		assertTrue(Stores.isPouchVarbit(VarbitID.RUNE_POUCH_QUANTITY_6));
		assertFalse(Stores.isPouchVarbit(VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE));
	}
}
