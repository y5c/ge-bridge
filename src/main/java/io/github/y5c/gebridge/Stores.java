package io.github.y5c.gebridge;

import com.google.gson.JsonObject;
import java.util.function.IntToLongFunction;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.VarbitID;

/**
 * Item stores outside the bank container that state.json reports (0.6.4): the rune pouch, read from the game's own
 * pouch varbits the way RuneLite's rune pouch overlay reads them. Pure: the varbit read, the rune lookup and the item
 * name and price lookups are passed in, so the layout is testable without a client.
 */
final class Stores
{
	/** One varbit pair per pouch slot: the rune's type (an index into the game's rune enum) and how many. */
	static final int[] POUCH_TYPE = {VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6};
	static final int[] POUCH_QUANTITY = {VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2,
		VarbitID.RUNE_POUCH_QUANTITY_3, VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5,
		VarbitID.RUNE_POUCH_QUANTITY_6};

	private Stores()
	{
	}

	static boolean isPouchVarbit(int varbit)
	{
		for (int i = 0; i < POUCH_TYPE.length; i++)
		{
			if (POUCH_TYPE[i] == varbit || POUCH_QUANTITY[i] == varbit)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The rune pouch in the bank-container layout ({loaded, value, itemCount, items}), keyed by pouch slot. An empty
	 * slot (type 0 or quantity 0), or a type the rune enum does not map to an item, is left out.
	 *
	 * @param varbit    the game's varbit value for an id
	 * @param runeItem  the rune enum: pouch rune type → item id (0 or less: unknown)
	 * @param name      item id → name
	 * @param price     item id → guide price per rune
	 */
	static JsonObject runePouch(IntUnaryOperator varbit, IntUnaryOperator runeItem, IntFunction<String> name, IntToLongFunction price)
	{
		final JsonObject items = new JsonObject();
		long value = 0;
		for (int slot = 0; slot < POUCH_TYPE.length; slot++)
		{
			final int type = varbit.applyAsInt(POUCH_TYPE[slot]);
			final int qty = varbit.applyAsInt(POUCH_QUANTITY[slot]);
			if (type <= 0 || qty <= 0)
			{
				continue;
			}
			final int id = runeItem.applyAsInt(type);
			if (id <= 0)
			{
				continue;
			}
			final long each = price.applyAsLong(id);
			final JsonObject v = new JsonObject();
			v.addProperty("slot", slot);
			v.addProperty("id", id);
			v.addProperty("name", name.apply(id));
			v.addProperty("quantity", qty);
			v.addProperty("price", each);
			v.addProperty("value", each * qty);
			items.add(Integer.toString(slot), v);
			value += each * qty;
		}
		final JsonObject out = new JsonObject();
		out.addProperty("loaded", true);
		out.addProperty("value", value);
		out.addProperty("itemCount", items.size());
		out.add("items", items);
		return out;
	}
}
