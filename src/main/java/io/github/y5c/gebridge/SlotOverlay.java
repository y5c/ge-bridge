package io.github.y5c.gebridge;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Outlines the Grand Exchange slots the advice says to act on. Draws only; the slots' click areas are untouched. */
final class SlotOverlay extends Overlay
{
	static final int[] SLOT_WIDGETS = {InterfaceID.GeOffers.INDEX_0, InterfaceID.GeOffers.INDEX_1, InterfaceID.GeOffers.INDEX_2,
		InterfaceID.GeOffers.INDEX_3, InterfaceID.GeOffers.INDEX_4, InterfaceID.GeOffers.INDEX_5, InterfaceID.GeOffers.INDEX_6,
		InterfaceID.GeOffers.INDEX_7};

	private final Client client;
	private final GeBridgePlugin plugin;

	@Inject
	SlotOverlay(Client client, GeBridgePlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		final Map<Integer, Color> slots = plugin.adviceView().slots;
		if (slots.isEmpty())
		{
			return null;
		}
		g.setStroke(new BasicStroke(2));
		for (Map.Entry<Integer, Color> e : slots.entrySet())
		{
			final Widget w = client.getWidget(SLOT_WIDGETS[e.getKey()]);
			if (w == null || w.isHidden())
			{
				continue;
			}
			final Rectangle r = w.getBounds();
			g.setColor(e.getValue());
			g.drawRect(r.x + 1, r.y + 1, r.width - 2, r.height - 2);
		}
		return null;
	}
}
