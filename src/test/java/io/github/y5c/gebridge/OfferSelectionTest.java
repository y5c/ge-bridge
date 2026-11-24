package io.github.y5c.gebridge;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class OfferSelectionTest
{
    @Test
    public void selectedBuyAndSellCarryTheItemBeforePlacement()
    {
        for (String side : new String[]{"buy", "sell"})
        {
            Map<String, Object> value = GeBridgePlugin.selectionPayload(true, 560, side, 0);
            assertEquals(true, value.get("open"));
            assertEquals(560, value.get("itemId"));
            assertEquals(side, value.get("side"));
            assertEquals(0, value.get("slot"));
        }
    }

    @Test
    public void closedOrInvalidDraftClearsTheOldSelection()
    {
        for (Map<String, Object> value : java.util.Arrays.asList(
            GeBridgePlugin.selectionPayload(false, 560, "buy", 0),
            GeBridgePlugin.selectionPayload(true, -1, "buy", 0),
            GeBridgePlugin.selectionPayload(true, 560, "buy", -1),
            GeBridgePlugin.selectionPayload(true, 560, "buy", 8)))
        {
            assertEquals(false, value.get("open"));
            assertNull(value.get("itemId"));
            assertNull(value.get("side"));
            assertNull(value.get("slot"));
        }
    }
}
