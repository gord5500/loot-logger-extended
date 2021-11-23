package com.ferocitylootlogger;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class FerocityLoggerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(FerocityLoggerPlugin.class);
		RuneLite.main(args);
	}
}