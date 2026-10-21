package io.github.y5c.gebridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the game said when the player last used an item's "Check" option (0.6.8): a charged weapon's charges, a
 * blowpipe's darts and scales, a gem bag's or herb sack's contents. The game sends the answer as chat messages, not as
 * item data, so the messages that arrive within a few ticks of the click are kept as text, keyed by the item. Reading
 * them is left to the reader of state.json, so a new message format needs no plugin release. Pure: ticks and times
 * are passed in, so it is testable without a client.
 */
final class Checks
{
	/** Messages answering a click arrive on the next tick or two; later ones are something else. */
	static final int WINDOW_TICKS = 3;
	static final int MAX_LINES = 20;
	static final int MAX_ITEMS = 50;

	/** item id → {name, lines, timestamp}, in the order last checked. */
	private final Map<String, JsonObject> items = new java.util.LinkedHashMap<>();
	private int pendingId = -1;
	private String pendingName;
	private int pendingTick;
	private List<String> lines;

	static boolean isCheck(String option)
	{
		return option != null && option.trim().toLowerCase(Locale.ROOT).startsWith("check");
	}

	/** The player clicked Check on an item: the next messages, until the window closes, belong to it. */
	void clicked(int itemId, String name, int tick)
	{
		pendingId = itemId;
		pendingName = name;
		pendingTick = tick;
		lines = null;
	}

	/**
	 * A game message. Returns true when it was kept (state.json then needs writing). The first message replaces what
	 * the item's last check said; the rest of the same answer is added to it.
	 */
	boolean message(String text, int tick, long now)
	{
		if (pendingId <= 0 || tick - pendingTick > WINDOW_TICKS || text == null || text.trim().isEmpty())
		{
			return false;
		}
		if (lines == null)
		{
			lines = new ArrayList<>();
		}
		if (lines.size() >= MAX_LINES)
		{
			return false;
		}
		lines.add(text.trim());
		final JsonObject v = new JsonObject();
		v.addProperty("id", pendingId);
		v.addProperty("name", pendingName);
		final JsonArray a = new JsonArray();
		lines.forEach(a::add);
		v.add("lines", a);
		v.addProperty("timestamp", now);
		final String key = Integer.toString(pendingId);
		items.remove(key);
		items.put(key, v);
		while (items.size() > MAX_ITEMS)
		{
			items.remove(items.keySet().iterator().next());
		}
		return true;
	}

	/** The previous state.json's "checks", so a restart keeps the last answers. */
	void restore(JsonElement prev)
	{
		items.clear();
		if (prev == null || !prev.isJsonObject())
		{
			return;
		}
		for (Map.Entry<String, JsonElement> e : prev.getAsJsonObject().entrySet())
		{
			if (e.getValue().isJsonObject())
			{
				items.put(e.getKey(), e.getValue().getAsJsonObject());
			}
		}
	}

	void clear()
	{
		items.clear();
		pendingId = -1;
		lines = null;
	}

	JsonObject toJson()
	{
		final JsonObject out = new JsonObject();
		items.forEach(out::add);
		return out;
	}
}
