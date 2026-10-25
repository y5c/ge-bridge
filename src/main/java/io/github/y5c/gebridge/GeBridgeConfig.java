package io.github.y5c.gebridge;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Notification;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(GeBridgeConfig.GROUP)
public interface GeBridgeConfig extends Config
{
	String GROUP = "gebridge";

	/** No settings: its header shows the running version at the top of the panel, to compare with the Hub's. */
	@ConfigSection(
		name = "Version " + GeBridgePlugin.VERSION,
		description = "The GE Bridge version running in this client; state.json and every event carry it too",
		position = 0
	)
	String versionSection = "version";

	@ConfigItem(
		keyName = "writeBank",
		name = "Write bank",
		description = "Include the bank and its potion storage (as of the last time the bank was opened), the rune pouch, and the seed vault (as of the last time it was opened) in state.json",
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
		description = "Include the inventory and worn equipment in state.json, and what the game said at your last Check of an item (charges, contents)",
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

	@ConfigSection(
		name = "Advice display",
		description = "Shows advice.json, written into this plugin's data folder by your own tool, while the Grand Exchange is open",
		position = 10
	)
	String adviceSection = "advice";

	@ConfigItem(
		keyName = "showAdvice",
		name = "Show advice",
		description = "While the Grand Exchange is open, show the advice from advice.json and check new offers against it",
		position = 11,
		section = adviceSection
	)
	default boolean showAdvice()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightSlots",
		name = "Highlight slots",
		description = "Outline the slots the advice says to act on, and slots whose new order differs from the advice",
		position = 12,
		section = adviceSection
	)
	default boolean highlightSlots()
	{
		return true;
	}

	@Range(min = 1, max = 72)
	@ConfigItem(
		keyName = "adviceMaxAgeHours",
		name = "Advice expiry (hours)",
		description = "Older advice is not shown or checked: prices move",
		position = 13,
		section = adviceSection
	)
	default int adviceMaxAgeHours()
	{
		return 12;
	}

	@ConfigItem(
		keyName = "personalMenu",
		name = "Personal tag option",
		description = "Add 'Mark personal' to a Grand Exchange slot's right-click menu; it writes a tag event to events.jsonl",
		position = 14,
		section = adviceSection
	)
	default boolean personalMenu()
	{
		return true;
	}

	@ConfigSection(
		name = "Reminders",
		description = "Reminders when a deadline in your tool's advice passes while you are logged in. Display only: nothing is placed or changed",
		position = 20
	)
	String reminderSection = "reminders";

	@ConfigItem(
		keyName = "remindNotification",
		name = "Notification",
		description = "How reminders notify (desktop notification, flash, sound — follows RuneLite's notification settings)",
		position = 21,
		section = reminderSection
	)
	default Notification remindNotification()
	{
		return Notification.ON;
	}

	@ConfigItem(
		keyName = "remindGrace",
		name = "Ask unsold after its grace",
		description = "An advised ask still unsold when the advice's grace before a trim runs out",
		position = 22,
		section = reminderSection
	)
	default boolean remindGrace()
	{
		return true;
	}

	@ConfigItem(
		keyName = "remindDip",
		name = "Dip hold over",
		description = "A held price dip reaches the end of its hold",
		position = 23,
		section = reminderSection
	)
	default boolean remindDip()
	{
		return true;
	}

	@ConfigItem(
		keyName = "remindStall",
		name = "Bid stalled",
		description = "An advised bid has had no fills for the advice's stall time",
		position = 24,
		section = reminderSection
	)
	default boolean remindStall()
	{
		return true;
	}

	@ConfigItem(
		keyName = "remindDead",
		name = "Bid dead",
		description = "An advised bid with no fills at all for the advice's dead-bid time",
		position = 25,
		section = reminderSection
	)
	default boolean remindDead()
	{
		return true;
	}

	@ConfigItem(
		keyName = "remindCollect",
		name = "Buy left uncollected",
		description = "An advised buy completed and is still in its slot",
		position = 26,
		section = reminderSection
	)
	default boolean remindCollect()
	{
		return true;
	}

	@Range(min = 1, max = 120)
	@ConfigItem(
		keyName = "remindCollectMinutes",
		name = "Uncollected after (minutes)",
		description = "How long a completed advised buy waits in its slot before the reminder",
		position = 27,
		section = reminderSection
	)
	default int remindCollectMinutes()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "remindChat",
		name = "Chat line",
		description = "Also write each reminder in the chat box",
		position = 28,
		section = reminderSection
	)
	default boolean remindChat()
	{
		return true;
	}
}
