package io.github.y5c.gebridge;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/** A panel listing the advice while the Grand Exchange is open. Display only. */
final class AdviceOverlay extends OverlayPanel
{
	private static final int WIDTH = 240;

	private final GeBridgePlugin plugin;

	@Inject
	AdviceOverlay(GeBridgePlugin plugin)
	{
		super(plugin);
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		final AdviceView v = plugin.adviceView();
		if (v.header == null)
		{
			return null;
		}
		panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
		panelComponent.getChildren().add(TitleComponent.builder().text(v.header).color(v.headerColor).build());
		for (AdviceView.Line l : v.lines)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(l.left).leftColor(l.color)
				.right(l.right == null ? "" : l.right).rightColor(l.color)
				.build());
		}
		return super.render(g);
	}
}
