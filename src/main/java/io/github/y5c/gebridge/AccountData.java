package io.github.y5c.gebridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.VarbitID;

/**
 * Achievement diary completion, read from the game's completion varbits. Pure: the varbit lookup is passed in.
 *
 * <p>Each tier is complete when its varbit reaches the value the game itself checks for: 1, except Karamja's easy,
 * medium and hard tiers, which the game marks complete at 2.
 */
final class AccountData
{
	static final String[] TIERS = {"easy", "medium", "hard", "elite"};

	/** key, display name, then for each tier: varbit, value meaning complete. */
	static final Object[][] DIARIES = {
		{"ardougne", "Ardougne", VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, 1, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, 1,
			VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, 1, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE, 1},
		{"desert", "Desert", VarbitID.DESERT_DIARY_EASY_COMPLETE, 1, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE, 1,
			VarbitID.DESERT_DIARY_HARD_COMPLETE, 1, VarbitID.DESERT_DIARY_ELITE_COMPLETE, 1},
		{"falador", "Falador", VarbitID.FALADOR_DIARY_EASY_COMPLETE, 1, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE, 1,
			VarbitID.FALADOR_DIARY_HARD_COMPLETE, 1, VarbitID.FALADOR_DIARY_ELITE_COMPLETE, 1},
		{"fremennik", "Fremennik", VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, 1, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE, 1,
			VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, 1, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE, 1},
		{"kandarin", "Kandarin", VarbitID.KANDARIN_DIARY_EASY_COMPLETE, 1, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE, 1,
			VarbitID.KANDARIN_DIARY_HARD_COMPLETE, 1, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE, 1},
		{"karamja", "Karamja", VarbitID.ATJUN_EASY_DONE, 2, VarbitID.ATJUN_MED_DONE, 2,
			VarbitID.ATJUN_HARD_DONE, 2, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE, 1},
		{"kourend_kebos", "Kourend & Kebos", VarbitID.KOUREND_DIARY_EASY_COMPLETE, 1, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, 1,
			VarbitID.KOUREND_DIARY_HARD_COMPLETE, 1, VarbitID.KOUREND_DIARY_ELITE_COMPLETE, 1},
		{"lumbridge_draynor", "Lumbridge & Draynor", VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, 1, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE, 1,
			VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, 1, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE, 1},
		{"morytania", "Morytania", VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, 1, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, 1,
			VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, 1, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE, 1},
		{"varrock", "Varrock", VarbitID.VARROCK_DIARY_EASY_COMPLETE, 1, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, 1,
			VarbitID.VARROCK_DIARY_HARD_COMPLETE, 1, VarbitID.VARROCK_DIARY_ELITE_COMPLETE, 1},
		{"western_provinces", "Western Provinces", VarbitID.WESTERN_DIARY_EASY_COMPLETE, 1, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE, 1,
			VarbitID.WESTERN_DIARY_HARD_COMPLETE, 1, VarbitID.WESTERN_DIARY_ELITE_COMPLETE, 1},
		{"wilderness", "Wilderness", VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, 1, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE, 1,
			VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, 1, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE, 1},
	};

	private AccountData()
	{
	}

	/** The diaries block of account.json, in the field layout the Local Data Exporter used. */
	static Map<String, Object> diaries(IntUnaryOperator varbit)
	{
		final Map<String, Object> regions = new LinkedHashMap<>();
		int done = 0;
		int total = 0;
		for (Object[] d : DIARIES)
		{
			final Map<String, Object> r = new LinkedHashMap<>();
			r.put("name", d[1]);
			int rd = 0;
			for (int t = 0; t < TIERS.length; t++)
			{
				final int value = varbit.applyAsInt((Integer) d[2 + 2 * t]);
				final boolean complete = value >= (Integer) d[3 + 2 * t];
				final Map<String, Object> tier = new LinkedHashMap<>();
				tier.put("complete", complete);
				tier.put("value", value);
				r.put(TIERS[t], tier);
				rd += complete ? 1 : 0;
			}
			r.put("completedTierCount", rd);
			r.put("totalTierCount", TIERS.length);
			r.put("allComplete", rd == TIERS.length);
			regions.put((String) d[0], r);
			done += rd;
			total += TIERS.length;
		}
		final Map<String, Object> out = new LinkedHashMap<>();
		out.put("source", "diary completion varbits");
		out.put("completedTierCount", done);
		out.put("totalTierCount", total);
		out.put("completionPercent", total == 0 ? 0 : Math.round(1000.0 * done / total) / 10.0);
		out.put("regions", regions);
		return out;
	}
}
