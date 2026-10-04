package io.github.y5c.gebridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises decanting in an inventory change: potions of one kind leave and potions of the same kind but other dose
 * sizes arrive, with the total number of doses unchanged (Bob Barter's decanting, or combining potions by hand).
 * Pure: works on names and quantities.
 */
final class Decants
{
	private static final Pattern DOSE = Pattern.compile("^(.*)\\((\\d)\\)$");

	private Decants()
	{
	}

	/** One change in quantity of one item: positive arrived, negative left. */
	static final class Change
	{
		final int id;
		final String name;
		final int delta;

		Change(int id, String name, int delta)
		{
			this.id = id;
			this.name = name;
			this.delta = delta;
		}
	}

	/** The decants in one inventory change, one map per potion kind; empty when there are none. */
	static List<Map<String, Object>> find(List<Change> changes)
	{
		final Map<String, List<Change>> byKind = new TreeMap<>();
		for (Change c : changes)
		{
			final Matcher m = c.name == null ? null : DOSE.matcher(c.name);
			if (m != null && m.matches() && c.delta != 0)
			{
				byKind.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(c);
			}
		}
		final List<Map<String, Object>> out = new ArrayList<>();
		for (Map.Entry<String, List<Change>> k : byKind.entrySet())
		{
			int dosesOut = 0;
			int dosesIn = 0;
			final List<Map<String, Object>> from = new ArrayList<>();
			final List<Map<String, Object>> to = new ArrayList<>();
			for (Change c : k.getValue())
			{
				final int dose = doses(c.name);
				final Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", c.id);
				m.put("name", c.name);
				m.put("quantity", Math.abs(c.delta));
				if (c.delta < 0)
				{
					dosesOut += dose * -c.delta;
					from.add(m);
				}
				else
				{
					dosesIn += dose * c.delta;
					to.add(m);
				}
			}
			if (!from.isEmpty() && !to.isEmpty() && dosesOut == dosesIn)
			{
				final Map<String, Object> d = new LinkedHashMap<>();
				d.put("potion", k.getKey());
				d.put("doses", dosesOut);
				d.put("from", from);
				d.put("to", to);
				out.add(d);
			}
		}
		return out;
	}

	static int doses(String name)
	{
		final Matcher m = DOSE.matcher(name == null ? "" : name);
		return m.matches() ? Integer.parseInt(m.group(2)) : 0;
	}
}
