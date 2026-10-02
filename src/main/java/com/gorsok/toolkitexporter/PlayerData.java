package com.gorsok.toolkitexporter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * The player's own progress, as plain documented structures: levels, quest states, achievement diary tiers,
 * combat achievement totals, and the items of the inventory, equipment and bank.
 *
 * Everything comes from the running game client (skills, the game's quest table and its quest status script,
 * game variables, item containers). Nothing is fetched from anywhere and nothing is sent anywhere.
 *
 * Reading the client is kept out of this class (plain values and functions are passed in) so that it can be
 * tested on its own.
 */
final class PlayerData
{
	static final int FORMAT_VERSION = 1;
	static final String SOURCE = "OSRS Toolkit Exporter, read from the game client";

	private PlayerData()
	{
	}

	// ------------------------------------------------------------------ character

	static CharacterFile character(String exportedAt, String name, int world, List<String> worldTypes, int combatLevel,
		List<SkillValue> skills)
	{
		CharacterFile out = new CharacterFile();
		out.exported_at = exportedAt;
		out.format_version = FORMAT_VERSION;
		out.source = SOURCE;
		out.account_name = name;
		out.world = world;
		out.world_types = new ArrayList<>(worldTypes);
		out.combat_level = combatLevel;
		out.stats = new LinkedHashMap<>();
		for (SkillValue s : skills)
		{
			Stat st = new Stat();
			st.real_level = s.real;
			st.boosted_level = s.boosted;
			st.experience = s.xp;
			out.stats.put(s.name, st);
			out.total_level += s.real;
			out.total_experience += s.xp;
		}
		return out;
	}

	static final class SkillValue
	{
		final String name;
		final int real;
		final int boosted;
		final int xp;

		SkillValue(String name, int real, int boosted, int xp)
		{
			this.name = name;
			this.real = real;
			this.boosted = boosted;
			this.xp = xp;
		}
	}

	// --------------------------------------------------------------------- quests

	static final String FINISHED = "FINISHED";
	static final String IN_PROGRESS = "IN_PROGRESS";
	static final String NOT_STARTED = "NOT_STARTED";

	/** The game's quest status script answers 2 (finished), 1 (not started) or anything else (in progress). */
	static String questState(int status)
	{
		switch (status)
		{
			case 2:
				return FINISHED;
			case 1:
				return NOT_STARTED;
			default:
				return IN_PROGRESS;
		}
	}

	/**
	 * @param rows        the row ids of the game's Quest table (null: not loaded yet)
	 * @param nameOfRow   the quest's name for a row (null when unreadable)
	 * @param statusOfRow the game's quest status for a row (the row id is the quest id the status script takes)
	 * @param questPoints the player's quest points, from the game variable
	 * @return null when the table is not loaded yet
	 */
	static Quests quests(String exportedAt, List<Integer> rows, IntFunction<String> nameOfRow,
		IntUnaryOperator statusOfRow, int questPoints)
	{
		if (rows == null || rows.isEmpty())
		{
			return null;
		}
		Quests out = new Quests();
		out.exported_at = exportedAt;
		out.format_version = FORMAT_VERSION;
		out.source = SOURCE + " (the game's Quest table and its quest status script)";
		out.quest_points = questPoints;
		out.quests = new ArrayList<>();
		out.summary = new QuestSummary();
		for (int row : rows)
		{
			String name = nameOfRow.apply(row);
			if (name == null || !hasLetter(name))
			{
				continue; // unreadable, or a placeholder row (a quest called "."): not a quest yet
			}
			QuestState q = new QuestState();
			q.id = row;
			q.name = name;
			q.state = questState(statusOfRow.applyAsInt(row));
			out.quests.add(q);
			if (FINISHED.equals(q.state))
			{
				out.summary.finished++;
			}
			else if (NOT_STARTED.equals(q.state))
			{
				out.summary.not_started++;
			}
			else
			{
				out.summary.in_progress++;
			}
		}
		out.quests.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
		return out;
	}

	static boolean hasLetter(String s)
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

	// -------------------------------------------------------------------- diaries

	static final String[] TIERS = {"easy", "medium", "hard", "elite"};

	/**
	 * Region name and the game variables (varbits) that say whether each tier (easy, medium, hard, elite) is done.
	 * Named game constants only (net.runelite.api.gameval.VarbitID).
	 */
	static final Object[][] DIARIES = {
		{"Ardougne", new int[]{VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE,
			VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE}},
		{"Desert", new int[]{VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE,
			VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE}},
		{"Falador", new int[]{VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE,
			VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE}},
		{"Fremennik", new int[]{VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE,
			VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE}},
		{"Kandarin", new int[]{VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE,
			VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE}},
		// Karamja's first three tiers use other variables (ATJUN_*_DONE); read the same way, see the file's note.
		{"Karamja", new int[]{VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE,
			VarbitID.ATJUN_HARD_DONE, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE}},
		{"Kourend & Kebos", new int[]{VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE,
			VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE}},
		{"Lumbridge & Draynor", new int[]{VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE,
			VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE}},
		{"Morytania", new int[]{VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE,
			VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE}},
		{"Varrock", new int[]{VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE,
			VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE}},
		{"Western Provinces", new int[]{VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE,
			VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE}},
		{"Wilderness", new int[]{VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE,
			VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE}},
	};

	static Diaries diaries(String exportedAt, IntUnaryOperator varbit)
	{
		Diaries out = new Diaries();
		out.exported_at = exportedAt;
		out.format_version = FORMAT_VERSION;
		out.source = SOURCE + " (game variables)";
		out.note = "complete = the game variable for the tier is not 0; value is that raw variable. Task-by-task progress "
			+ "is not exported. Karamja easy, medium and hard use different variables than the other diaries.";
		out.diaries = new LinkedHashMap<>();
		out.summary = new DiarySummary();
		for (Object[] d : DIARIES)
		{
			int[] ids = (int[]) d[1];
			Map<String, DiaryTier> tiers = new LinkedHashMap<>();
			for (int i = 0; i < TIERS.length; i++)
			{
				DiaryTier t = new DiaryTier();
				t.value = varbit.applyAsInt(ids[i]);
				t.complete = t.value != 0;
				tiers.put(TIERS[i], t);
				out.summary.tiers_possible++;
				if (t.complete)
				{
					out.summary.tiers_complete++;
				}
			}
			out.diaries.put((String) d[0], tiers);
		}
		return out;
	}

	// ------------------------------------------------------- combat achievements

	/** The game variables (varps) whose bits are the combat achievement tasks done (one bit per task). */
	static final int[] CA_TASK_VARPS = {
		VarPlayerID.CA_TASK_COMPLETED_0, VarPlayerID.CA_TASK_COMPLETED_1, VarPlayerID.CA_TASK_COMPLETED_2,
		VarPlayerID.CA_TASK_COMPLETED_3, VarPlayerID.CA_TASK_COMPLETED_4, VarPlayerID.CA_TASK_COMPLETED_5,
		VarPlayerID.CA_TASK_COMPLETED_6, VarPlayerID.CA_TASK_COMPLETED_7, VarPlayerID.CA_TASK_COMPLETED_8,
		VarPlayerID.CA_TASK_COMPLETED_9, VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13, VarPlayerID.CA_TASK_COMPLETED_14,
		VarPlayerID.CA_TASK_COMPLETED_15, VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17,
		VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19, VarPlayerID.CA_TASK_COMPLETED_20,
	};

	static final String[] CA_TIERS = {"easy", "medium", "hard", "elite", "master", "grandmaster"};
	static final int[] CA_TIER_VARBITS = {
		VarbitID.CA_TIER_STATUS_EASY, VarbitID.CA_TIER_STATUS_MEDIUM, VarbitID.CA_TIER_STATUS_HARD,
		VarbitID.CA_TIER_STATUS_ELITE, VarbitID.CA_TIER_STATUS_MASTER, VarbitID.CA_TIER_STATUS_GRANDMASTER,
	};

	static CombatAchievements combatAchievements(String exportedAt, IntUnaryOperator varp, IntUnaryOperator varbit)
	{
		CombatAchievements out = new CombatAchievements();
		out.exported_at = exportedAt;
		out.format_version = FORMAT_VERSION;
		out.source = SOURCE + " (game variables)";
		out.note = "total_tasks_completed counts the task bits the game sets; points is the game's own count. "
			+ "tier_status_raw are the game's raw tier variables (meaning not decoded yet). Task names and per-tier "
			+ "counts are not exported yet.";
		out.summary = new CaSummary();
		for (int id : CA_TASK_VARPS)
		{
			out.summary.total_tasks_completed += Integer.bitCount(varp.applyAsInt(id));
		}
		out.summary.points = varbit.applyAsInt(VarbitID.CA_POINTS);
		out.summary.named_data_available = false;
		out.tier_status_raw = new LinkedHashMap<>();
		for (int i = 0; i < CA_TIERS.length; i++)
		{
			out.tier_status_raw.put(CA_TIERS[i], varbit.applyAsInt(CA_TIER_VARBITS[i]));
		}
		return out;
	}

	// ---------------------------------------------------------------------- items

	/**
	 * @param ids  item id per slot (-1 or less: empty)
	 * @param qtys quantity per slot (0 or less: empty, e.g. a bank placeholder)
	 */
	static Items items(String exportedAt, String kind, int[] ids, int[] qtys, IntFunction<String> nameOf)
	{
		Items out = new Items();
		out.exported_at = exportedAt;
		out.format_version = FORMAT_VERSION;
		out.source = SOURCE;
		out.kind = kind;
		out.items = new ArrayList<>();
		for (int slot = 0; slot < ids.length && slot < qtys.length; slot++)
		{
			if (ids[slot] < 0 || qtys[slot] <= 0)
			{
				continue;
			}
			Item it = new Item();
			it.slot = slot;
			it.id = ids[slot];
			it.quantity = qtys[slot];
			it.name = nameOf.apply(ids[slot]);
			out.items.add(it);
		}
		out.item_count = out.items.size();
		return out;
	}

	// ---------------------------------------------------------------- json models

	@SuppressWarnings("unused")
	static class CharacterFile
	{
		String exported_at;
		int format_version;
		String source;
		String account_name;
		int world;
		List<String> world_types;
		int combat_level;
		int total_level;
		long total_experience;
		Map<String, Stat> stats;
	}

	@SuppressWarnings("unused")
	static class Stat
	{
		int real_level;
		int boosted_level;
		int experience;
	}

	@SuppressWarnings("unused")
	static class Quests
	{
		String exported_at;
		int format_version;
		String source;
		int quest_points;
		QuestSummary summary;
		List<QuestState> quests;
	}

	@SuppressWarnings("unused")
	static class QuestSummary
	{
		int finished;
		int in_progress;
		int not_started;
	}

	@SuppressWarnings("unused")
	static class QuestState
	{
		int id;
		String name;
		String state;
	}

	@SuppressWarnings("unused")
	static class Diaries
	{
		String exported_at;
		int format_version;
		String source;
		String note;
		DiarySummary summary;
		Map<String, Map<String, DiaryTier>> diaries;
	}

	@SuppressWarnings("unused")
	static class DiarySummary
	{
		int tiers_complete;
		int tiers_possible;
	}

	@SuppressWarnings("unused")
	static class DiaryTier
	{
		boolean complete;
		int value;
	}

	@SuppressWarnings("unused")
	static class CombatAchievements
	{
		String exported_at;
		int format_version;
		String source;
		String note;
		CaSummary summary;
		Map<String, Integer> tier_status_raw;
	}

	@SuppressWarnings("unused")
	static class CaSummary
	{
		int total_tasks_completed;
		int points;
		boolean named_data_available;
	}

	@SuppressWarnings("unused")
	static class Items
	{
		String exported_at;
		int format_version;
		String source;
		String kind;
		int item_count;
		List<Item> items;
	}

	@SuppressWarnings("unused")
	static class Item
	{
		int slot;
		int id;
		int quantity;
		String name;
	}
}
