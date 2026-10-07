package io.github.y5c.gebridge;

import java.awt.Color;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** What the two overlays draw, rebuilt on the client thread each tick while the Grand Exchange is open. Immutable. */
final class AdviceView
{
	static final Color OK = new Color(0x7fd27f);
	static final Color WARN = new Color(0xff5c5c);
	static final Color ACT = new Color(0xffd23f);
	static final Color DIM = new Color(0xa0a0a0);
	static final Color TEXT = Color.WHITE;

	static final AdviceView NONE = new AdviceView(null, DIM, Collections.emptyList(), Collections.emptyMap());

	static final class Line
	{
		final String left;
		final String right;
		final Color color;

		Line(String left, String right, Color color)
		{
			this.left = left;
			this.right = right;
			this.color = color;
		}
	}

	final String header;
	final Color headerColor;
	final List<Line> lines;
	// slot -> outline colour
	final Map<Integer, Color> slots;

	AdviceView(String header, Color headerColor, List<Line> lines, Map<Integer, Color> slots)
	{
		this.header = header;
		this.headerColor = headerColor;
		this.lines = lines;
		this.slots = slots;
	}
}
