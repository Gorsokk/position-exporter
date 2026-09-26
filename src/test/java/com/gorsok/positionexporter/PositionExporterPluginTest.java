package com.gorsok.positionexporter;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PositionExporterPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PositionExporterPlugin.class);
		RuneLite.main(args);
	}
}