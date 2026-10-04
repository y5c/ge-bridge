package io.github.y5c.gebridge;

import java.io.IOException;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;

/**
 * The append-only event log, kept to a bounded size.
 *
 * <p>When events.jsonl reaches the size limit it is renamed to events-&lt;UTC time&gt;.jsonl and a new one is started;
 * only the newest {@link #KEEP} archives are kept. A rename keeps the file's identity, so a reader that was part-way
 * through events.jsonl can find the same file under its archive name and finish it. Called on the io thread only.
 */
final class EventLog
{
	static final String LIVE = "events.jsonl";
	static final int KEEP = 6;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

	private EventLog()
	{
	}

	static void append(Filepath dir, String line, long maxBytes, long now) throws IOException
	{
		final Filepath live = dir.joinSegment(LIVE);
		if (live.exists() && live.size() >= maxBytes)
		{
			live.moveTo(dir.joinSegment(archiveName(now)));
			final List<String> names;
			try (Stream<Filepath> files = dir.walk(1))
			{
				names = files.map(Filepath::getFileName).collect(Collectors.toList());
			}
			for (String old : toDelete(names, KEEP))
			{
				dir.joinSegment(old).deleteIfExists();
			}
		}
		live.write(line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	static String archiveName(long ms)
	{
		return "events-" + STAMP.format(Instant.ofEpochMilli(ms)) + ".jsonl";
	}

	static boolean isArchive(String name)
	{
		return name != null && name.matches("events-\\d{8}T\\d{6}\\.jsonl");
	}

	/** The archives beyond the newest {@code keep}; the names sort by time. */
	static List<String> toDelete(List<String> names, int keep)
	{
		final List<String> archives = new ArrayList<>();
		for (String n : names)
		{
			if (isArchive(n))
			{
				archives.add(n);
			}
		}
		Collections.sort(archives);
		return archives.size() <= keep ? Collections.emptyList() : archives.subList(0, archives.size() - keep);
	}
}
