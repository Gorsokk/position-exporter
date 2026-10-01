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
}
