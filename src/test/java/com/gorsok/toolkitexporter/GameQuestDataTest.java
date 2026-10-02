package com.gorsok.toolkitexporter;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.DBTableID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Decoding of the game's Quest table. No client, no network: a fake table holds values copied from a real client
 * (Animal Magnetism and the three quests it requires, read from the running game on 2 Oct 2026).
 */
public class GameQuestDataTest
{
	private static final String[] SKILLS = {"Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Prayer", "Magic",
		"Cooking", "Woodcutting", "Fletching", "Fishing", "Firemaking", "Crafting", "Smithing", "Mining", "Herblore",
		"Agility", "Thieving", "Slayer", "Farming", "Runecraft", "Hunter", "Construction"};

	/** A fake client table: row -> column -> tuples. */
	private static class Fake implements GameQuestData.Source
	{
		final Map<Integer, Map<Integer, Object[][]>> table = new HashMap<>();
		final Map<Integer, String> npcs = new HashMap<>();
		boolean unavailable;

		void put(int row, int column, Object[]... tuples)
		{
			table.computeIfAbsent(row, r -> new HashMap<>()).put(column, tuples);
		}

		@Override
		public List<Integer> rows()
		{
			return unavailable ? null : new ArrayList<>(table.keySet());
		}

		@Override
		public Object[] field(int row, int column, int tuple)
		{
			Map<Integer, Object[][]> r = table.get(row);
			Object[][] t = r == null ? null : r.get(column);
			return t != null && tuple < t.length ? t[tuple] : null;
		}

		@Override
		public String npcName(int npcId)
		{
			return npcs.get(npcId);
		}

		@Override
		public String skillName(int skillId)
		{
			return skillId >= 0 && skillId < SKILLS.length ? SKILLS[skillId] : null;
		}
	}

	private static Fake game()
	{
		Fake f = new Fake();
		// the three quests Animal Magnetism requires (row ids as in the real table)
		f.put(44, DBTableID.Quest.COL_DISPLAYNAME, new Object[]{"Ernest the Chicken"});
		f.put(120, DBTableID.Quest.COL_DISPLAYNAME, new Object[]{"The Restless Ghost"});
		f.put(111, DBTableID.Quest.COL_DISPLAYNAME, new Object[]{"Priest in Peril"});
		// Animal Magnetism, row 0, as the client returned it
		f.put(0, DBTableID.Quest.COL_ID, new Object[]{123});
		f.put(0, DBTableID.Quest.COL_DISPLAYNAME, new Object[]{"Animal Magnetism"});
		f.put(0, DBTableID.Quest.COL_MEMBERS, new Object[]{1});
		f.put(0, DBTableID.Quest.COL_DIFFICULTY, new Object[]{1});
		f.put(0, DBTableID.Quest.COL_LENGTH, new Object[]{2});
		f.put(0, DBTableID.Quest.COL_QUESTPOINTS, new Object[]{1});
		f.put(0, DBTableID.Quest.COL_SERIES, new Object[]{-1});
		f.put(0, DBTableID.Quest.COL_RELEASEDATE, new Object[]{12}, new Object[]{12}, new Object[]{2006});
		f.put(0, DBTableID.Quest.COL_REQUIREMENT_STATS, new Object[]{8, 4, 12, 18}, new Object[]{35, 30, 19, 18});
		f.put(0, DBTableID.Quest.COL_RECOMMENDED_STATS, new Object[]{}, new Object[]{});
		f.put(0, DBTableID.Quest.COL_REQUIREMENT_QUESTS, new Object[]{44, 120, 111});
		f.put(0, DBTableID.Quest.COL_REQUIREMENT_QUESTPOINTS, new Object[]{0});
		f.put(0, DBTableID.Quest.COL_REQUIREMENT_COMBAT, new Object[]{3});
		f.put(0, DBTableID.Quest.COL_STARTCOORD, new Object[]{50695455});
		f.put(0, DBTableID.Quest.COL_STARTNPC, new Object[]{4408});
		f.put(0, DBTableID.Quest.COL_STAT_XP_AWARDED, new Object[]{12, 9, 18, 8}, new Object[]{10000, 10000, 10000, 25000});
		f.put(0, DBTableID.Quest.COL_UNSTARTEDSTATE, new Object[]{0});
		f.put(0, DBTableID.Quest.COL_ENDSTATE, new Object[]{240});
		f.put(0, DBTableID.Quest.COL_CR_RECOMMENDATION_REASON, new Object[]{"Learn how to equip Ava's ranged devices."});
		f.npcs.put(4408, "Ava");
		return f;
	}

	private static GameQuestData.Quest find(GameQuestData.Export e, String name)
	{
		for (GameQuestData.Quest q : e.quests)
		{
			if (name.equals(q.name))
			{
				return q;
			}
		}
		return null;
	}

	private static GameQuestData.Export decode(Fake f)
	{
		return GameQuestData.decode(f, "2026-10-02T12:00:00Z", 237);
	}

	@Test
	public void requirementsAreTheTwoPartsPairedByPosition()
	{
		GameQuestData.Quest q = find(decode(game()), "Animal Magnetism");
		assertNotNull(q);
		assertEquals(4, q.levels.size());
		assertEquals("Woodcutting", q.levels.get(0).skill);
		assertEquals(35, q.levels.get(0).level);
		assertEquals("Ranged", q.levels.get(1).skill);
		assertEquals(30, q.levels.get(1).level);
		assertEquals("Crafting", q.levels.get(2).skill);
		assertEquals(19, q.levels.get(2).level);
		assertEquals("Slayer", q.levels.get(3).skill);
		assertEquals(18, q.levels.get(3).level);
		assertTrue(q.recommended_levels.isEmpty());
	}

	@Test
	public void requiredQuestsAreResolvedFromRowIdsAndTheIdsAreKept()
	{
		GameQuestData.Quest q = find(decode(game()), "Animal Magnetism");
		assertEquals(3, q.required_quests.size());
		assertEquals("Ernest the Chicken", q.required_quests.get(0));
		assertEquals("The Restless Ghost", q.required_quests.get(1));
		assertEquals("Priest in Peril", q.required_quests.get(2));
		assertEquals(Integer.valueOf(44), q.required_quest_rows.get(0));
	}

	@Test
	public void anUnknownRequiredRowKeepsItsIdAndHasNoName()
	{
		Fake f = game();
		f.put(0, DBTableID.Quest.COL_REQUIREMENT_QUESTS, new Object[]{44, 9999});
		GameQuestData.Quest q = find(decode(f), "Animal Magnetism");
		assertEquals(2, q.required_quest_rows.size());
		assertEquals(Integer.valueOf(9999), q.required_quest_rows.get(1));
		assertNull(q.required_quests.get(1));
	}

	@Test
	public void theStartCoordinateIsUnpackedAndNpcsAreNamed()
	{
		GameQuestData.Quest q = find(decode(game()), "Animal Magnetism");
		assertEquals(3094, q.start.x);
		assertEquals(3359, q.start.y);
		assertEquals(0, q.start.plane);
		assertEquals(Integer.valueOf(4408), q.start.npc_ids.get(0));
		assertEquals("Ava", q.start.npc_names.get(0));
	}

	@Test
	public void aStartOnAnUpperFloorKeepsItsPlane()
	{
		Fake f = game();
		f.put(0, DBTableID.Quest.COL_STARTCOORD, new Object[]{(1 << 28) | (3200 << 14) | 3201});
		GameQuestData.Quest q = find(decode(f), "Animal Magnetism");
		assertEquals(3200, q.start.x);
		assertEquals(3201, q.start.y);
		assertEquals(1, q.start.plane);
	}

	@Test
	public void experienceRewardsArePairedToo()
	{
		GameQuestData.Quest q = find(decode(game()), "Animal Magnetism");
		assertEquals(4, q.xp_rewards.size());
		assertEquals("Crafting", q.xp_rewards.get(0).skill);
		assertEquals(10000, q.xp_rewards.get(0).xp);
		assertEquals("Woodcutting", q.xp_rewards.get(3).skill);
		assertEquals(25000, q.xp_rewards.get(3).xp);
	}

	@Test
	public void theOtherColumnsAreCarriedAsTheyAre()
	{
		GameQuestData.Quest q = find(decode(game()), "Animal Magnetism");
		assertTrue(q.members);
		assertEquals(Integer.valueOf(123), q.quest_id);
		assertEquals(Integer.valueOf(1), q.difficulty_code);
		assertEquals(Integer.valueOf(2), q.length_code);
		assertEquals(Integer.valueOf(1), q.quest_points);
		assertEquals(Integer.valueOf(0), q.required_quest_points);
		assertEquals(Integer.valueOf(3), q.requirement_combat_raw);
		assertEquals(Integer.valueOf(240), q.end_state);
		assertEquals("Learn how to equip Ava's ranged devices.", q.recommendation_reason);
		assertEquals(12, q.release_date_parts[0]);
		assertEquals(12, q.release_date_parts[1]);
		assertEquals(2006, q.release_date_parts[2]);
	}

	@Test
	public void aQuestWithNothingRequiredHasEmptyLists()
	{
		GameQuestData.Quest q = find(decode(game()), "Ernest the Chicken");
		assertNotNull(q);
		assertTrue(q.levels.isEmpty());
		assertTrue(q.required_quests.isEmpty());
		assertTrue(q.xp_rewards.isEmpty());
		assertNull(q.start);
		assertFalse(q.members);
	}

	@Test
	public void anUnknownSkillIdIsNamedByItsNumberInsteadOfFailing()
	{
		Fake f = game();
		f.put(0, DBTableID.Quest.COL_REQUIREMENT_STATS, new Object[]{99}, new Object[]{40});
		GameQuestData.Quest q = find(decode(f), "Animal Magnetism");
		assertEquals("skill#99", q.levels.get(0).skill);
	}

	@Test
	public void aTableThatIsNotAvailableGivesAnEmptyExportNotAnError()
	{
		Fake f = game();
		f.unavailable = true;
		GameQuestData.Export e = decode(f);
		assertEquals(0, e.quest_count);
		assertTrue(e.quests.isEmpty());
	}

	@Test
	public void rowsWithoutANameAreSkippedAndTheCountIsHonest()
	{
		Fake f = game();
		f.put(500, DBTableID.Quest.COL_MEMBERS, new Object[]{1}); // no display name
		GameQuestData.Export e = decode(f);
		assertEquals(4, e.quest_count);
		for (GameQuestData.Quest q : e.quests)
		{
			assertTrue(q.row_id != 500);
		}
	}

	@Test
	public void aPlaceholderQuestNamedWithOnlyPunctuationIsLeftOutAndCounted()
	{
		Fake f = game();
		// a slot the game has not filled yet is named "." in the real table (three of them in revision 241)
		f.put(5192, DBTableID.Quest.COL_DISPLAYNAME, new Object[]{"."});
		f.put(5193, DBTableID.Quest.COL_DISPLAYNAME, new Object[]{"."});
		GameQuestData.Export e = decode(f);
		assertEquals(4, e.quest_count);
		assertEquals(2, e.skipped_placeholder_rows);
		for (GameQuestData.Quest q : e.quests)
		{
			assertTrue(q.row_id != 5192 && q.row_id != 5193);
		}
	}

	@Test
	public void aQuestRequiringAPlaceholderKeepsTheIdAndHasNoName()
	{
		Fake f = game();
		f.put(5192, DBTableID.Quest.COL_DISPLAYNAME, new Object[]{"."});
		f.put(0, DBTableID.Quest.COL_REQUIREMENT_QUESTS, new Object[]{44, 5192});
		GameQuestData.Quest q = find(decode(f), "Animal Magnetism");
		assertEquals(Integer.valueOf(5192), q.required_quest_rows.get(1));
		assertNull(q.required_quests.get(1));
	}

	@Test
	public void anNpcTheGameNamesNullHasAnUnknownNameNotTheTextNull()
	{
		Fake f = game();
		f.npcs.put(4408, "null");
		GameQuestData.Quest q = find(decode(f), "Animal Magnetism");
		assertEquals(Integer.valueOf(4408), q.start.npc_ids.get(0));
		assertNull(q.start.npc_names.get(0));
		f.npcs.put(4408, "NULL ");
		assertNull(find(decode(f), "Animal Magnetism").start.npc_names.get(0));
		f.npcs.put(4408, "  ");
		assertNull(find(decode(f), "Animal Magnetism").start.npc_names.get(0));
	}

	@Test
	public void aQuestWithTwoStartNpcsKeepsTheNamedOneAndTheUnknownOne()
	{
		Fake f = game();
		f.put(0, DBTableID.Quest.COL_STARTNPC, new Object[]{2315, 6282}); // Another Slice of H.A.M.: Ur-tag and a variant
		f.npcs.put(2315, "Ur-tag");
		f.npcs.put(6282, "null");
		GameQuestData.Quest q = find(decode(f), "Animal Magnetism");
		assertEquals("Ur-tag", q.start.npc_names.get(0));
		assertNull(q.start.npc_names.get(1));
		assertEquals(2, q.start.npc_ids.size());
	}

	@Test
	public void theJsonCarriesItsProvenanceAndStaysReadable()
	{
		GameQuestData.Export e = decode(game());
		String json = new Gson().toJson(e);
		assertTrue(json.contains("\"client_revision\":237"));
		assertTrue(json.contains("\"exported_at\":\"2026-10-02T12:00:00Z\""));
		assertTrue(json.contains("\"source\":\"game client database table Quest"));
		assertTrue(json.contains("\"required_quests\":[\"Ernest the Chicken\",\"The Restless Ghost\",\"Priest in Peril\"]"));
		assertTrue(json.contains("\"note\":\"Read from the game client, not from any website."));
		assertEquals(4, e.quest_count);
		assertEquals(GameQuestData.FORMAT_VERSION, e.format_version);
	}
}
