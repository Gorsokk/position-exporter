package com.gorsok.toolkitexporter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

/**
 * PlayerData without the game: plain values in, documented files out. The field names checked here are the ones
 * the OSRS Toolkit app reads (stats.*.real_level, quests[].state, items[].id, ...): keep them stable.
 */
public class PlayerDataTest
{
	private static final String NOW = "2026-10-02T20:00:00Z";
	private static final Gson GSON = new Gson();

	private static JsonObject json(Object o)
	{
		return GSON.toJsonTree(o).getAsJsonObject();
	}

	@Test
	public void characterHasEveryLevelAndTotals()
	{
		PlayerData.CharacterFile c = PlayerData.character(NOW, "Gorsok", 496, Collections.singletonList("MEMBERS"), 70,
			Arrays.asList(new PlayerData.SkillValue("Attack", 54, 56, 157454),
				new PlayerData.SkillValue("Sailing", 1, 1, 0)));
		JsonObject j = json(c);
		assertEquals("Gorsok", j.get("account_name").getAsString());
		assertEquals(496, j.get("world").getAsInt());
		assertEquals("MEMBERS", j.getAsJsonArray("world_types").get(0).getAsString());
		JsonObject attack = j.getAsJsonObject("stats").getAsJsonObject("Attack");
		assertEquals(54, attack.get("real_level").getAsInt());
		assertEquals(56, attack.get("boosted_level").getAsInt());
		assertEquals(157454, attack.get("experience").getAsInt());
		assertTrue(j.getAsJsonObject("stats").has("Sailing"));
		assertEquals(55, j.get("total_level").getAsInt());
		assertEquals(157454, j.get("total_experience").getAsLong());
		assertEquals(PlayerData.FORMAT_VERSION, j.get("format_version").getAsInt());
	}

	@Test
	public void everySkillOfRuneLitesListIsKeptSailingIncluded()
	{
		// Regression: dropping "the last constant" (once Overall) dropped Sailing, the real last skill in RuneLite 1.13.
		ClientQuestSource src = new ClientQuestSource(null);   // skillName does not touch the client
		assertEquals("Attack", src.skillName(0));
		assertEquals("Construction", src.skillName(22));
		assertEquals("Sailing", src.skillName(23));
		assertNull(src.skillName(24));
		assertNull(src.skillName(-1));
		int kept = 0;
		for (net.runelite.api.Skill skill : net.runelite.api.Skill.values())
		{
			if (ClientQuestSource.isSkill(skill))
			{
				kept++;
			}
		}
		assertEquals(24, kept);
	}

	@Test
	public void questStatesFollowTheGameScript()
	{
		assertEquals(PlayerData.FINISHED, PlayerData.questState(2));
		assertEquals(PlayerData.NOT_STARTED, PlayerData.questState(1));
		assertEquals(PlayerData.IN_PROGRESS, PlayerData.questState(0));
		assertEquals(PlayerData.IN_PROGRESS, PlayerData.questState(3));
	}

	@Test
	public void questsSkipPlaceholdersAndUnreadableRowsAndAreSorted()
	{
		Map<Integer, String> names = new HashMap<>();
		names.put(0, "Animal Magnetism");
		names.put(17, "Cook's Assistant");
		names.put(31, "Dragon Slayer I");
		names.put(5192, ".");          // placeholder row of the game's table
		Map<Integer, Integer> status = new HashMap<>();
		status.put(0, 1);
		status.put(17, 2);
		status.put(31, 0);
		PlayerData.Quests q = PlayerData.quests(NOW, Arrays.asList(31, 17, 5192, 0, 999), names::get,
			row -> status.getOrDefault(row, 1), 22);
		JsonObject j = json(q);
		JsonArray list = j.getAsJsonArray("quests");
		assertEquals(3, list.size());                              // "." and the unreadable row 999 are left out
		assertEquals("Animal Magnetism", list.get(0).getAsJsonObject().get("name").getAsString());
		assertEquals(0, list.get(0).getAsJsonObject().get("id").getAsInt());
		assertEquals("NOT_STARTED", list.get(0).getAsJsonObject().get("state").getAsString());
		assertEquals("FINISHED", list.get(1).getAsJsonObject().get("state").getAsString());
		assertEquals("IN_PROGRESS", list.get(2).getAsJsonObject().get("state").getAsString());
		JsonObject summary = j.getAsJsonObject("summary");
		assertEquals(1, summary.get("finished").getAsInt());
		assertEquals(1, summary.get("in_progress").getAsInt());
		assertEquals(1, summary.get("not_started").getAsInt());
		assertEquals(22, j.get("quest_points").getAsInt());
	}

	@Test
	public void questsWaitForTheTable()
	{
		assertNull(PlayerData.quests(NOW, null, row -> "x", row -> 1, 0));
		assertNull(PlayerData.quests(NOW, Collections.emptyList(), row -> "x", row -> 1, 0));
	}

	@Test
	public void diariesCoverTwelveRegionsWithNamedGameVariables()
	{
		assertEquals(12, PlayerData.DIARIES.length);
		Set<Integer> ids = new HashSet<>();
		for (Object[] d : PlayerData.DIARIES)
		{
			for (int id : (int[]) d[1])
			{
				assertTrue("a game variable is used twice: " + id, ids.add(id));
			}
		}
		assertEquals(48, ids.size());

		Map<Integer, Integer> values = new HashMap<>();
		values.put(VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, 1);
		values.put(VarbitID.ATJUN_EASY_DONE, 1);
		PlayerData.Diaries d = PlayerData.diaries(NOW, id -> values.getOrDefault(id, 0));
		JsonObject j = json(d);
		assertTrue(j.getAsJsonObject("diaries").getAsJsonObject("Ardougne").getAsJsonObject("easy")
			.get("complete").getAsBoolean());
		assertFalse(j.getAsJsonObject("diaries").getAsJsonObject("Ardougne").getAsJsonObject("medium")
			.get("complete").getAsBoolean());
		assertTrue(j.getAsJsonObject("diaries").getAsJsonObject("Karamja").getAsJsonObject("easy")
			.get("complete").getAsBoolean());
		assertEquals(2, j.getAsJsonObject("summary").get("tiers_complete").getAsInt());
		assertEquals(48, j.getAsJsonObject("summary").get("tiers_possible").getAsInt());
	}

	@Test
	public void combatAchievementTotalCountsEveryTaskBit()
	{
		Map<Integer, Integer> varps = new HashMap<>();
		varps.put(VarPlayerID.CA_TASK_COMPLETED_0, 0b1011);          // 3 tasks
		varps.put(VarPlayerID.CA_TASK_COMPLETED_20, 0x80000000);     // the sign bit is a task too
		varps.put(VarPlayerID.CA_TASK_COMPLETED_7, -1);              // 32 tasks
		Map<Integer, Integer> varbits = new HashMap<>();
		varbits.put(VarbitID.CA_POINTS, 8);
		varbits.put(VarbitID.CA_TIER_STATUS_EASY, 2);
		PlayerData.CombatAchievements ca = PlayerData.combatAchievements(NOW,
			id -> varps.getOrDefault(id, 0), id -> varbits.getOrDefault(id, 0));
		JsonObject j = json(ca);
		assertEquals(36, j.getAsJsonObject("summary").get("total_tasks_completed").getAsInt());
		assertEquals(8, j.getAsJsonObject("summary").get("points").getAsInt());
		assertFalse(j.getAsJsonObject("summary").get("named_data_available").getAsBoolean());
		assertEquals(2, j.getAsJsonObject("tier_status_raw").get("easy").getAsInt());
		assertEquals(6, j.getAsJsonObject("tier_status_raw").size());
		assertFalse(j.has("tiers"));                               // no per-tier detail is claimed
		assertEquals(21, new HashSet<>(Arrays.asList(Arrays.stream(PlayerData.CA_TASK_VARPS).boxed()
			.toArray(Integer[]::new))).size());
	}

	@Test
	public void itemsSkipEmptySlotsAndPlaceholders()
	{
		PlayerData.Items it = PlayerData.items(NOW, "bank", new int[]{995, -1, 13204, 4151},
			new int[]{1000, 0, 3, 0}, id -> id == 995 ? "Coins" : id == 13204 ? "Platinum token" : "Abyssal whip");
		JsonObject j = json(it);
		assertEquals("bank", j.get("kind").getAsString());
		assertEquals(2, j.get("item_count").getAsInt());
		JsonArray items = j.getAsJsonArray("items");
		assertEquals(995, items.get(0).getAsJsonObject().get("id").getAsInt());
		assertEquals(1000, items.get(0).getAsJsonObject().get("quantity").getAsInt());
		assertEquals("Coins", items.get(0).getAsJsonObject().get("name").getAsString());
		assertEquals(0, items.get(0).getAsJsonObject().get("slot").getAsInt());
		assertEquals(2, items.get(1).getAsJsonObject().get("slot").getAsInt());   // the slot is kept as in game
	}
}
