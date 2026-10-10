package io.github.y5c.gebridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.components.PanelComponent;

/**
 * Renders the advice panel to a PNG outside the game, from an advice.json and a state.json, so the panel's layout can
 * be inspected without logging in. Developer tool only; not part of the plugin.
 *
 * <pre>
 * ./gradlew preview -Pargs="ADVICE_JSON|STATE_JSON|OUT_PNG [|--setup|ITEM_ID|buy or sell|QTY] [|--mismatch|SLOT|TEXT] [|--age-min|N]"
 * </pre>
 * Arguments are separated by "|". STATE_JSON "-" rebuilds the slots from the advice itself (the moment it was written).
 * <pre>
 * </pre>
 */
public class AdvicePreview
{
	public static void main(String[] args) throws Exception
	{
		final Gson gson = new Gson();
		final Advice advice = gson.fromJson(read(Paths.get(args[0])), Advice.class);
		final Path out = Paths.get(args[2]);
		final OfferTracker.Offer[] offers = new OfferTracker.Offer[OfferTracker.SLOTS];
		if ("-".equals(args[1]))
		{
			// the slots as they were when the advice was written: the orders its slot entries name
			for (Advice.Slot a : advice.slots())
			{
				offers[a.slot] = new OfferTracker.Offer(a.itemId, a.item, "buy".equals(a.side) ? "BUYING" : "SELLING", a.price, a.total, 0, 0);
			}
		}
		else
		{
			final JsonObject state = gson.fromJson(read(Paths.get(args[1])), JsonObject.class);
			final JsonObject slots = state.getAsJsonObject("grandExchange").getAsJsonObject("offers");
			for (int i = 0; i < OfferTracker.SLOTS; i++)
			{
				if (slots.has(Integer.toString(i)))
				{
					offers[i] = GeBridgePlugin.offerFromJson(slots.getAsJsonObject(Integer.toString(i)));
				}
			}
		}
		long now = advice.generatedAt + 60_000;
		Advice.Check check = null;
		final Map<Integer, String> mismatches = new HashMap<>();
		for (int a = 3; a < args.length; a++)
		{
			switch (args[a])
			{
				case "--setup":
					check = advice.check(Integer.parseInt(args[a + 1]), args[a + 2], Integer.parseInt(args[a + 3]), offers);
					a += 3;
					break;
				case "--mismatch":
					mismatches.put(Integer.parseInt(args[a + 1]), args[a + 2]);
					a += 2;
					break;
				case "--age-min":
					now = advice.generatedAt + Long.parseLong(args[a + 1]) * 60_000;
					a += 1;
					break;
				default:
					throw new IllegalArgumentException(args[a]);
			}
		}
		final AdviceView v = AdviceView.build(advice, offers, mismatches, check, now, 12 * 3_600_000L, true);
		if (v.header == null)
		{
			System.out.println("nothing to show (no usable advice)");
			return;
		}
		// PanelComponent sizes itself from its previous render (as it does frame to frame in the client): warm it up
		final PanelComponent panel = new PanelComponent();
		Dimension d = null;
		for (int pass = 0; pass < 2; pass++)
		{
			AdviceOverlay.fill(panel, v);
			d = panel.render(prepare(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()));
			panel.getChildren().clear();
		}
		AdviceOverlay.fill(panel, v);
		final int pad = 12;
		final BufferedImage img = new BufferedImage(d.width + 2 * pad, d.height + 2 * pad, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = prepare(img.createGraphics());
		g.setColor(new Color(0x5a5245));        // roughly the GE window's stone, so the panel's translucency reads true
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		g.translate(pad, pad);
		panel.render(g);
		g.dispose();
		try (OutputStream os = Files.newOutputStream(out))
		{
			ImageIO.write(img, "png", os);
		}
		System.out.println("wrote " + out + " (" + img.getWidth() + "x" + img.getHeight() + ", " + v.lines.size() + " lines, "
			+ v.slots.size() + " outlined slots)");
	}

	private static Graphics2D prepare(Graphics2D g)
	{
		g.setFont(FontManager.getRunescapeFont());
		return g;
	}

	private static String read(Path p) throws Exception
	{
		return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
	}
}
