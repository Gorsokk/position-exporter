package com.gorsok.toolkitexporter;

import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.NPCComposition;
import net.runelite.api.Skill;
import net.runelite.api.gameval.DBTableID;

/** Reads the Quest table from the running game client. Must be used on the client thread. */
final class ClientQuestSource implements GameQuestData.Source
{
	private final Client client;

	ClientQuestSource(Client client)
	{
		this.client = client;
	}

	@Override
	public List<Integer> rows()
	{
		try
		{
			return client.getDBTableRows(DBTableID.Quest.ID);
		}
		catch (RuntimeException e)
		{
			return null; // the table is not loaded (yet)
		}
	}

	@Override
	public Object[] field(int row, int column, int tuple)
	{
		try
		{
			return client.getDBTableField(row, column, tuple);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	@Override
	public String npcName(int npcId)
	{
		try
		{
			NPCComposition npc = client.getNpcDefinition(npcId);
			return npc == null ? null : npc.getName();
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	@Override
	public String skillName(int skillId)
	{
		// The game's skill ids follow the order of RuneLite's Skill enum (0 Attack ... 23 Sailing).
		Skill[] skills = Skill.values();
		return skillId >= 0 && skillId < skills.length && isSkill(skills[skillId]) ? skills[skillId].getName() : null;
	}

	/**
	 * Older RuneLite versions listed "Overall" as the last Skill constant; it is not a skill. In RuneLite 1.13 it is no
	 * longer in Skill.values() (and Sailing is the last skill), so nothing may be dropped by position.
	 */
	static boolean isSkill(Skill skill)
	{
		return skill != null && !"Overall".equalsIgnoreCase(skill.getName());
	}
}
