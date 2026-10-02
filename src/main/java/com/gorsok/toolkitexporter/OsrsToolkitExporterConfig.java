package com.gorsok.toolkitexporter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

// Each export can be switched off. The OSRS Toolkit app reads these same settings
// from the RuneLite profile to show which data is being exported.
@ConfigGroup(OsrsToolkitExporterConfig.GROUP)
public interface OsrsToolkitExporterConfig extends Config
{
	String GROUP = "osrstoolkitexporter";

	@ConfigItem(
			keyName = "exportPosition",
			name = "Export position",
			description = "Write your live world position to position.json",
			position = 1
	)
	default boolean exportPosition()
	{
		return true;
	}

	@ConfigItem(
			keyName = "exportGeOffers",
			name = "Export GE offers",
			description = "Write your Grand Exchange offers to ge_offers.json",
			position = 2
	)
	default boolean exportGeOffers()
	{
		return true;
	}

	@ConfigItem(
			keyName = "exportGameQuests",
			name = "Export game quest data",
			description = "Write the game's own quest table (requirements, start locations, experience rewards) to game_quests.json. Read from your client; nothing is sent anywhere",
			position = 3
	)
	default boolean exportGameQuests()
	{
		return false;
	}

	@ConfigItem(
			keyName = "exportCharacter",
			name = "Export character progress",
			description = "Write your levels and experience (character.json), quest states (quests.json), achievement diary tiers (diaries.json) and combat achievement totals (combat_achievements.json)",
			position = 4
	)
	default boolean exportCharacter()
	{
		return true;
	}

	@ConfigItem(
			keyName = "exportItems",
			name = "Export inventory and equipment",
			description = "Write your inventory (inventory.json) and worn equipment (equipment.json)",
			position = 5
	)
	default boolean exportItems()
	{
		return true;
	}

	@ConfigItem(
			keyName = "exportBank",
			name = "Export bank",
			description = "Write your bank (bank.json). It updates when you open your bank",
			position = 6
	)
	default boolean exportBank()
	{
		return true;
	}
}
