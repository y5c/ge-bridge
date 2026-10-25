package io.github.y5c.gebridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * The advice-file contract with the OSRS skill that writes advice.json (2026-10-17). Each case in
 * src/test/resources/contract/ is an advice file built by the skill's own code (its tests/contract.py exports them) plus
 * what this plugin must do with it: the offer-screen check, the "differs from the advice" warning, and which orders the
 * panel lists as still to place. The cases are the boundary bugs found in-game in October 2026.
 */
public class ContractTest
{
	private static final Gson GSON = new Gson();

	private static OfferTracker.Offer offer(JsonObject o)
	{
		final OfferTracker.Offer x = new OfferTracker.Offer(o.get("itemId").getAsInt(), o.get("item").getAsString(), o.get("state").getAsString(),
			o.get("price").getAsLong(), o.get("total").getAsInt(), o.has("done") ? o.get("done").getAsInt() : 0, 0);
		x.placedAt = o.has("placedAt") ? o.get("placedAt").getAsLong() : null;
		x.lastFillAt = o.has("lastFillAt") ? o.get("lastFillAt").getAsLong() : null;
		x.personal = o.has("personal") && o.get("personal").getAsBoolean();
		return x;
	}

	private static OfferTracker.Offer[] slots(JsonObject s)
	{
		final OfferTracker.Offer[] out = new OfferTracker.Offer[OfferTracker.SLOTS];
		if (s != null)
		{
			for (Map.Entry<String, JsonElement> e : s.entrySet())
			{
				out[Integer.parseInt(e.getKey())] = offer(e.getValue().getAsJsonObject());
			}
		}
		return out;
	}

	private static String str(JsonObject o, String k)
	{
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
	}

	@Test
	public void everyContractCaseHolds() throws IOException
	{
		final Path dir = Paths.get("src", "test", "resources", "contract");
		final List<Path> files;
		try (Stream<Path> s = Files.list(dir))
		{
			files = s.filter(p -> p.toString().endsWith(".json")).sorted().collect(Collectors.toList());
		}
		assertFalse("no contract cases in " + dir, files.isEmpty());
		final List<String> failures = new ArrayList<>();
		for (Path f : files)
		{
			final JsonObject c = GSON.fromJson(new String(Files.readAllBytes(f), StandardCharsets.UTF_8), JsonObject.class);
			final String name = c.get("name").getAsString();
			if (!GSON.fromJson(c.get("advice"), Advice.class).usable())
			{
				failures.add(name + ": advice not usable");
				continue;
			}
			int i = 0;
			for (JsonElement el : c.getAsJsonArray("checks"))
			{
				final JsonObject k = el.getAsJsonObject();
				final String where = name + " check " + (i++) + " (" + k.get("type").getAsString() + ")";
				// a fresh Advice per check: markPlaced is state, and one check must not leak into the next
				final Advice a = GSON.fromJson(c.get("advice"), Advice.class);
				switch (k.get("type").getAsString())
				{
					case "check":
					{
						final Advice.Check r = a.check(k.get("itemId").getAsInt(), k.get("side").getAsString(), k.get("qty").getAsInt(),
							new OfferTracker.Offer[OfferTracker.SLOTS]);
						final String lines = String.join(" | ", r.lines);
						if (!r.level.equals(k.get("level").getAsString()))
						{
							failures.add(where + ": level " + r.level + ", expected " + k.get("level").getAsString() + " — " + lines);
						}
						final String want = str(k, "contains");
						if (want != null && !lines.contains(want))
						{
							failures.add(where + ": lines lack \"" + want + "\" — " + lines);
						}
						break;
					}
					case "mismatch":
					{
						final String got = a.placedMismatch(offer(k.getAsJsonObject("offer")));
						final String want = str(k, "expect");
						if (want == null ? got != null : got == null || !got.contains(want))
						{
							failures.add(where + ": got " + got + ", expected " + (want == null ? "no warning" : "\"" + want + "\""));
						}
						break;
					}
					case "toPlace":
					{
						if (k.has("markPlaced"))
						{
							for (JsonElement m : k.getAsJsonArray("markPlaced"))
							{
								a.markPlaced(offer(m.getAsJsonObject()));
							}
						}
						final List<String> got = a.toPlace(slots(k.getAsJsonObject("slots"))).stream()
							.map(o -> o.side + ":" + o.itemId + ":" + o.price).sorted().collect(Collectors.toList());
						final List<String> want = new ArrayList<>();
						for (JsonElement w : k.getAsJsonArray("expect"))
						{
							want.add(w.getAsString());
						}
						Collections.sort(want);
						if (!got.equals(want))
						{
							failures.add(where + ": to place " + got + ", expected " + want);
						}
						break;
					}
					case "reminders":
					{
						// each expected line is "SLOT KIND[ text the reminder contains]"; nothing else may fire
						final List<Reminders.Due> got = new Reminders().due(a, slots(k.getAsJsonObject("slots")), new Reminders.Settings(),
							k.get("now").getAsLong());
						final List<String> seen = got.stream().map(d -> d.slot + " " + d.kind + " — " + d.text).collect(Collectors.toList());
						final JsonArray want = k.getAsJsonArray("expect");
						if (got.size() != want.size())
						{
							failures.add(where + ": fired " + seen + ", expected " + want);
							break;
						}
						for (int j = 0; j < want.size(); j++)
						{
							final String[] w = want.get(j).getAsString().split(" ", 3);
							final Reminders.Due d = got.get(j);
							if (d.slot != Integer.parseInt(w[0]) || !d.kind.equals(w[1]) || (w.length > 2 && !d.text.contains(w[2])))
							{
								failures.add(where + ": fired " + seen + ", expected " + want);
								break;
							}
						}
						break;
					}
					default:
						failures.add(where + ": unknown check type");
				}
			}
		}
		assertTrue(String.join("\n", failures), failures.isEmpty());
	}
}
