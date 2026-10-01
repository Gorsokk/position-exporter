package com.gorsok.toolkitexporter;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class OsrsToolkitExporterPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(OsrsToolkitExporterPlugin.class);
		RuneLite.main(args);
	}
}