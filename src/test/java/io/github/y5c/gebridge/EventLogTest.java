package io.github.y5c.gebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;
import org.junit.Test;

public class EventLogTest
{
	private static final long T0 = 1_790_000_000_000L;

	@Test
	public void archiveNamesSortByTime()
	{
		assertEquals("events-20260921T140000.jsonl", EventLog.archiveName(1789999200000L));
		assertTrue(EventLog.archiveName(T0).compareTo(EventLog.archiveName(T0 + 1000)) < 0);
		assertTrue(EventLog.isArchive(EventLog.archiveName(T0)));
		assertFalse(EventLog.isArchive("events.jsonl"));
		assertFalse(EventLog.isArchive("state.json"));
	}

	@Test
	public void onlyTheOldestArchivesBeyondTheLimitAreDeleted()
	{
		List<String> names = Arrays.asList("state.json", "events.jsonl", "events-20260101T000000.jsonl", "events-20260301T000000.jsonl",
			"events-20260201T000000.jsonl");
		assertEquals(Arrays.asList("events-20260101T000000.jsonl"), EventLog.toDelete(names, 2));
		assertTrue(EventLog.toDelete(names, 3).isEmpty());
	}

	@Test
	public void appendRotatesAtTheLimitAndKeepsTheNewestArchives() throws Exception
	{
		final Path tmp = Files.createTempDirectory("gebridge");
		final Filepath dir = Filepath.Unchecked.getRooted(tmp);
		final String line = "{\"t\":1}\n";                       // 8 bytes
		for (int i = 0; i < 40; i++)
		{
			EventLog.append(dir, line, 24, T0 + i * 1000L);        // a rotation every 3 lines
		}
		List<String> names;
		try (Stream<Path> s = Files.list(tmp))
		{
			names = s.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
		}
		long archives = names.stream().filter(EventLog::isArchive).count();
		assertEquals(EventLog.KEEP, archives);
		assertTrue(names.contains(EventLog.LIVE));
		assertTrue(Files.size(tmp.resolve(EventLog.LIVE)) <= 24);
		for (String n : names)
		{
			if (EventLog.isArchive(n))
			{
				assertEquals(24, Files.size(tmp.resolve(n)));       // every archive holds whole lines only
			}
		}
	}
}
