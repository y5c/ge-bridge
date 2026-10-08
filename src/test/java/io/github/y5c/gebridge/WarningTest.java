package io.github.y5c.gebridge;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class WarningTest
{
	@Test
	public void chatWarningIsColouredNotEscaped()
	{
		// 0.6.0 printed "<col=ff5c5c>" and "</col>" as text: the tags must reach the chat box as tags
		assertEquals("<col=ff5c5c>GE Bridge: slot 4 differs from the advice: Bagged plant 1: quantity 10 (advised 1,770)</col>",
			GeBridgePlugin.warning(4, "Bagged plant 1: quantity 10 (advised 1,770)"));
	}

	@Test
	public void textInsideIsStillEscaped()
	{
		assertEquals("<col=ff5c5c>GE Bridge: slot 0 differs from the advice: a<lt>b</col>", GeBridgePlugin.warning(0, "a<b"));
	}
}
