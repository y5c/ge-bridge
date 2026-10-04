package io.github.y5c.gebridge;

import static org.junit.Assert.assertEquals;
import java.util.Map;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

public class AccountDataTest
{
	@Test
	@SuppressWarnings("unchecked")
	public void karamjaNeedsTwoAndTheRestNeedOne()
	{
		// every varbit at 1: Karamja's easy/medium/hard (which the game marks complete at 2) are not complete
		Map<String, Object> d = AccountData.diaries(v -> 1);
		Map<String, Object> karamja = (Map<String, Object>) ((Map<String, Object>) d.get("regions")).get("karamja");
		assertEquals(false, ((Map<String, Object>) karamja.get("easy")).get("complete"));
		assertEquals(true, ((Map<String, Object>) karamja.get("elite")).get("complete"));
		assertEquals(45, d.get("completedTierCount"));
		assertEquals(48, d.get("totalTierCount"));

		d = AccountData.diaries(v -> v == VarbitID.ATJUN_EASY_DONE || v == VarbitID.ATJUN_MED_DONE || v == VarbitID.ATJUN_HARD_DONE ? 2 : 1);
		assertEquals(48, d.get("completedTierCount"));
		assertEquals(100.0, d.get("completionPercent"));

		d = AccountData.diaries(v -> 0);
		assertEquals(0, d.get("completedTierCount"));
		assertEquals(12, ((Map<String, Object>) d.get("regions")).size());
	}
}
