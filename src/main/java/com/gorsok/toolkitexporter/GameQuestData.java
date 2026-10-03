package com.gorsok.toolkitexporter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.DBTableID;

/**
 * Turns the game's own "Quest" database table into a plain, documented structure.
 *
 * The client already holds this table (net.runelite.api.gameval.DBTableID.Quest): for every quest the levels and
 * quests required, the starting tile and NPC, the experience rewarded, the quest points and a few codes. Nothing
 * here is fetched from anywhere: it is read from the running client, so it always matches the game version.
 *
 * Reading the client is kept out of this class (see {@link Source}) so the decoding can be tested on its own.
 */
final class GameQuestData
{
	static final int FORMAT_VERSION = 1;

	/** What the decoder needs from the game client. */
	interface Source
	{
		/** The row ids of the Quest table, or null when the table is not available. */
		List<Integer> rows();

		/** One part ("tuple") of one cell, or null when it is empty or cannot be read. */
		Object[] field(int row, int column, int tuple);

		/** The name of an NPC, or null when unknown. */
		String npcName(int npcId);

		/** The name of a skill, or null when the id is not a known skill. */
		String skillName(int skillId);
	}

	private GameQuestData()
	{
	}

	static Export decode(Source src, String exportedAt, int clientRevision)
	{
		Export out = new Export();
		out.exported_at = exportedAt;
		out.format_version = FORMAT_VERSION;
		out.client_revision = clientRevision;
		out.source = "game client database table Quest (DBTableID.Quest)";
		out.note = "Read from the game client, not from any website. difficulty_code (0-5) and length_code (0-4) are the "
				+ "game's raw codes. requirement_combat_raw, prerequisite_direct and prerequisite_indirect are raw values "
				+ "whose meaning is not known. release_date_parts are the three raw values of the release date. "
				+ "start.x/y/plane come from the packed start coordinate. required_quests are resolved from row ids. "
				+ "npc_names has null where the game itself names the NPC \"null\". Rows of the table whose name has no "
				+ "letter (placeholders for quests not defined yet) are left out and counted in skipped_placeholder_rows. "
				+ "A skill the client has no name for is written as skill#<id>. "
				+ "xp_rewards[].xp is the game's raw value, in tenths of an experience point: divide by 10 for the "
				+ "experience actually rewarded.";
		out.quests = new ArrayList<>();

		List<Integer> rows = src.rows();
		if (rows == null)
		{
			return out;
		}

		Map<Integer, String> names = new HashMap<>();
		for (int row : rows)
		{
			String name = text(src.field(row, DBTableID.Quest.COL_DISPLAYNAME, 0));
			if (name == null)
			{
				continue;
			}
			if (!hasLetter(name))
			{
				out.skipped_placeholder_rows++; // e.g. a quest called ".": a slot the game has not filled yet
				continue;
			}
			names.put(row, name);
		}

		for (int row : rows)
		{
			String name = names.get(row);
			if (name == null)
			{
				continue; // a row without a name is not a quest we can describe
			}

			Quest q = new Quest();
			q.row_id = row;
			q.quest_id = number(src.field(row, DBTableID.Quest.COL_ID, 0));
			q.name = name;
			q.members = number(src.field(row, DBTableID.Quest.COL_MEMBERS, 0)) != null
					&& number(src.field(row, DBTableID.Quest.COL_MEMBERS, 0)) == 1;
			q.difficulty_code = number(src.field(row, DBTableID.Quest.COL_DIFFICULTY, 0));
			q.length_code = number(src.field(row, DBTableID.Quest.COL_LENGTH, 0));
			q.quest_points = number(src.field(row, DBTableID.Quest.COL_QUESTPOINTS, 0));
			q.series = number(src.field(row, DBTableID.Quest.COL_SERIES, 0));
			q.series_number = number(src.field(row, DBTableID.Quest.COL_SERIESNO, 0));
			q.release_date_parts = new int[]{
				orZero(number(src.field(row, DBTableID.Quest.COL_RELEASEDATE, 0))),
				orZero(number(src.field(row, DBTableID.Quest.COL_RELEASEDATE, 1))),
				orZero(number(src.field(row, DBTableID.Quest.COL_RELEASEDATE, 2)))};

			q.levels = pairs(src, row, DBTableID.Quest.COL_REQUIREMENT_STATS);
			q.recommended_levels = pairs(src, row, DBTableID.Quest.COL_RECOMMENDED_STATS);
			q.required_quest_points = number(src.field(row, DBTableID.Quest.COL_REQUIREMENT_QUESTPOINTS, 0));
			q.requirement_combat_raw = number(src.field(row, DBTableID.Quest.COL_REQUIREMENT_COMBAT, 0));
			q.prerequisite_direct = number(src.field(row, DBTableID.Quest.COL_PREREQUISITE_DIRECT, 0));
			q.prerequisite_indirect = number(src.field(row, DBTableID.Quest.COL_PREREQUISITE_INDIRECT, 0));

			q.required_quest_rows = new ArrayList<>();
			q.required_quests = new ArrayList<>();
			for (int requiredRow : ints(src.field(row, DBTableID.Quest.COL_REQUIREMENT_QUESTS, 0)))
			{
				q.required_quest_rows.add(requiredRow);
				q.required_quests.add(names.get(requiredRow)); // null when the row is unknown: the id is kept above
			}

			q.start = start(src, row);

			q.xp_rewards = new ArrayList<>();
			for (Level l : pairs(src, row, DBTableID.Quest.COL_STAT_XP_AWARDED))
			{
				Xp xp = new Xp();
				xp.skill_id = l.skill_id;
				xp.skill = l.skill;
				xp.xp = l.level;
				q.xp_rewards.add(xp);
			}

			q.unstarted_state = number(src.field(row, DBTableID.Quest.COL_UNSTARTEDSTATE, 0));
			q.end_state = number(src.field(row, DBTableID.Quest.COL_ENDSTATE, 0));
			q.recommendation_reason = text(src.field(row, DBTableID.Quest.COL_CR_RECOMMENDATION_REASON, 0));
			out.quests.add(q);
		}
		out.quest_count = out.quests.size();
		return out;
	}

	// ---------------------------------------------------------------------------------------------- pieces

	/** The start tile and NPCs. The coordinate is packed: x in bits 14-27, y in bits 0-13, plane above bit 28. */
	private static Start start(Source src, int row)
	{
		Integer packed = number(src.field(row, DBTableID.Quest.COL_STARTCOORD, 0));
		int[] npcs = ints(src.field(row, DBTableID.Quest.COL_STARTNPC, 0));
		if (packed == null && npcs.length == 0)
		{
			return null;
		}
		Start s = new Start();
		if (packed != null)
		{
			s.x = (packed >> 14) & 0x3FFF;
			s.y = packed & 0x3FFF;
			s.plane = (packed >>> 28) & 0x3;
		}
		s.npc_ids = new ArrayList<>();
		s.npc_names = new ArrayList<>();
		for (int npc : npcs)
		{
			s.npc_ids.add(npc);
			String npcName = src.npcName(npc);
			if (npcName != null && (npcName.trim().isEmpty() || "null".equalsIgnoreCase(npcName.trim())))
			{
				npcName = null; // the game's cache names some NPCs "null": that is an unknown name, not a name
			}
			s.npc_names.add(npcName);
		}
		return s;
	}

	private static boolean hasLetter(String s)
	{
		for (int i = 0; i < s.length(); i++)
		{
			if (Character.isLetter(s.charAt(i)))
			{
				return true;
			}
		}
		return false;
	}

	/** A column holding two parts: the skill ids, then a number for each (a level, or an amount of experience). */
	private static List<Level> pairs(Source src, int row, int column)
	{
		int[] skills = ints(src.field(row, column, 0));
		int[] values = ints(src.field(row, column, 1));
		List<Level> out = new ArrayList<>();
		for (int i = 0; i < Math.min(skills.length, values.length); i++)
		{
			Level l = new Level();
			l.skill_id = skills[i];
			String skill = src.skillName(skills[i]);
			l.skill = skill != null ? skill : "skill#" + skills[i];
			l.level = values[i];
			out.add(l);
		}
		return out;
	}

	private static int[] ints(Object[] cell)
	{
		if (cell == null)
		{
			return new int[0];
		}
		int n = 0;
		for (Object o : cell)
		{
			if (o instanceof Number)
			{
				n++;
			}
		}
		int[] out = new int[n];
		int i = 0;
		for (Object o : cell)
		{
			if (o instanceof Number)
			{
				out[i++] = ((Number) o).intValue();
			}
		}
		return out;
	}

	private static Integer number(Object[] cell)
	{
		int[] v = ints(cell);
		return v.length == 0 ? null : v[0];
	}

	private static int orZero(Integer value)
	{
		return value == null ? 0 : value;
	}

	private static String text(Object[] cell)
	{
		if (cell == null || cell.length == 0 || !(cell[0] instanceof String))
		{
			return null;
		}
		String s = ((String) cell[0]).trim();
		return s.isEmpty() ? null : s;
	}

	// --------------------------------------------------------------------------------------------- json model

	@SuppressWarnings("unused")
	static class Export
	{
		String exported_at;
		int format_version;
		int client_revision;
		String source;
		String note;
		int quest_count;
		int skipped_placeholder_rows;
		List<Quest> quests;
	}

	@SuppressWarnings("unused")
	static class Quest
	{
		int row_id;
		Integer quest_id;
		String name;
		boolean members;
		Integer difficulty_code;
		Integer length_code;
		Integer quest_points;
		Integer series;
		Integer series_number;
		int[] release_date_parts;
		List<Level> levels;
		List<Level> recommended_levels;
		List<Integer> required_quest_rows;
		List<String> required_quests;
		Integer required_quest_points;
		Integer requirement_combat_raw;
		Integer prerequisite_direct;
		Integer prerequisite_indirect;
		Start start;
		List<Xp> xp_rewards;
		Integer unstarted_state;
		Integer end_state;
		String recommendation_reason;
	}

	@SuppressWarnings("unused")
	static class Level
	{
		int skill_id;
		String skill;
		int level;
	}

	@SuppressWarnings("unused")
	static class Xp
	{
		int skill_id;
		String skill;
		int xp;
	}

	@SuppressWarnings("unused")
	static class Start
	{
		int x;
		int y;
		int plane;
		List<Integer> npc_ids;
		List<String> npc_names;
	}
}
