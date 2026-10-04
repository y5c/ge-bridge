package io.github.y5c.gebridge;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(GeBridgeConfig.GROUP)
public interface GeBridgeConfig extends Config
{
	String GROUP = "gebridge";

	@ConfigItem(
		keyName = "writeBank",
		name = "Write bank",
		description = "Include the bank and its potion storage (as of the last time the bank was opened) in state.json",
		position = 1
	)
	default boolean writeBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "writeSkills",
		name = "Write skills",
		description = "Include skill levels and experience in state.json",
		position = 2
	)
	default boolean writeSkills()
	{
		return true;
	}

	@ConfigItem(
		keyName = "writeContainers",
		name = "Write inventory and equipment",
		description = "Include the inventory and worn equipment in state.json",
		position = 3
	)
	default boolean writeContainers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "writeAccount",
		name = "Write quests and diaries",
		description = "Write account.json with quest states and achievement diary completion (refreshed every few minutes while logged in)",
		position = 4
	)
	default boolean writeAccount()
	{
		return true;
	}

	@Range(min = 10, max = 300)
	@Units(Units.SECONDS)
	@ConfigItem(
		keyName = "heartbeatSeconds",
		name = "Save interval",
		description = "While logged in, state.json is rewritten at least this often, and at once when something changes",
		position = 5
	)
	default int heartbeatSeconds()
	{
		return 30;
	}

	@Range(min = 1, max = 50)
	@ConfigItem(
		keyName = "maxLogMb",
		name = "Event log size (MB)",
		description = "When events.jsonl reaches this size it is archived and a new one is started; the newest six archives are kept",
		position = 6
	)
	default int maxLogMb()
	{
		return 5;
	}
}
