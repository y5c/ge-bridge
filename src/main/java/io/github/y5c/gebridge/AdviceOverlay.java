package io.github.y5c.gebridge;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.Text;

/** A panel listing the advice while the Grand Exchange is open. Display only. */
final class AdviceOverlay extends OverlayPanel
{
	private static final int WIDTH = 240;
	/** The panel widens to fit its longest line up to this (0.7.4); a longer line still wraps. */
	static final int MAX_WIDTH = 480;
	/** Space kept between a line's left and right text. */
	private static final int GAP = 8;

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
		fill(panelComponent, v, g.getFontMetrics());
		return super.render(g);
	}

	/** The panel's contents; also used to render the panel outside the game. */
	static void fill(PanelComponent panel, AdviceView v, FontMetrics fm)
	{
		panel.setPreferredSize(new Dimension(width(v, fm), 0));
		panel.getChildren().add(TitleComponent.builder().text(v.header).color(v.headerColor).build());
		for (AdviceView.Line l : v.lines)
		{
			panel.getChildren().add(LineComponent.builder()
				.left(l.left).leftColor(l.color)
				.right(l.right == null ? "" : l.right).rightColor(l.color)
				.build());
		}
	}

	/** Wide enough for every line on one line (left, a gap, right), never under the old 240 nor over {@link #MAX_WIDTH}. */
	static int width(AdviceView v, FontMetrics fm)
	{
		int need = fm.stringWidth(Text.removeTags(v.header));
		for (AdviceView.Line l : v.lines)
		{
			final int right = l.right == null || l.right.isEmpty() ? 0 : GAP + fm.stringWidth(Text.removeTags(l.right));
			need = Math.max(need, fm.stringWidth(Text.removeTags(l.left)) + right);
		}
		return Math.max(WIDTH, Math.min(MAX_WIDTH, need + 2 * ComponentConstants.STANDARD_BORDER));
	}
}
