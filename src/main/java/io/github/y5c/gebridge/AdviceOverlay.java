package io.github.y5c.gebridge;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
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
		fill(panelComponent, v);
		return super.render(g);
	}

	/** The panel's contents; also used to render the panel outside the game. */
	static void fill(PanelComponent panel, AdviceView v)
	{
		panel.setPreferredSize(new Dimension(WIDTH, 0));
		panel.getChildren().add(TitleComponent.builder().text(v.header).color(v.headerColor).build());
		for (AdviceView.Line l : v.lines)
		{
			panel.getChildren().add(LineComponent.builder()
				.left(l.left).leftColor(l.color)
				.right(l.right == null ? "" : l.right).rightColor(l.color)
				.build());
		}
	}
}
