package com.gorsok.toolkitexporter;

// OSRS Toolkit Exporter - part of the OSRS Toolkit suite (https://github.com/Gorsokk/osrs-toolkit).
// Each export can be switched on or off in the plugin's settings.
//
// What it does:
//   1) Position: every game tick, if your tile changed, writes
//        <home>/.runelite/plugin-data/position-exporter/<YourCharacterName>/position.json
//   2) Grand Exchange: whenever one of your 8 GE slots changes (new offer,
//      partial fill, completed, cancelled, collected), writes
//        <home>/.runelite/plugin-data/position-exporter/<YourCharacterName>/ge_offers.json
//      with every slot: item, buy/sell, state, offer price, qty filled / total,
//      gp spent, and the real average price paid/received.
//   3) Game quest data (off by default): once per login, writes the game's own Quest table
//      (levels and quests required, start tile and NPC, experience rewarded) to
//        <home>/.runelite/plugin-data/position-exporter/game_quests.json
//      It is read from your client, so it always matches the game version. See GameQuestData.
//   4) Character progress: levels and experience (character.json), the state of every quest of the game's
//      quest table (quests.json), achievement diary tiers (diaries.json) and combat achievement totals
//      (combat_achievements.json), in <home>/.runelite/plugin-data/position-exporter/<YourCharacterName>/.
//      Written at login, then when they change (at most every few seconds). See PlayerData.
//   5) Items: inventory.json and equipment.json when they change, bank.json when the bank is opened.
//
// The files live in RuneLite's data folder for this plugin (file I/O goes through RuneLite's
// Filepath API); the OSRS Toolkit app reads them there, together with Character Export's files.
// Nothing is written while logged out. Nothing is ever sent over the network.
// Data is collected on the client thread; the disk writes happen on a background thread.

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import com.google.inject.Provides;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Filepath;

@Slf4j
@PluginDescriptor(
		name = "OSRS Toolkit Exporter",
		internalName = "position-exporter",
		description = "OSRS Toolkit suite: writes your live position, Grand Exchange offers, levels, quests, diaries, items and (optionally) the game's quest data to local JSON files, each switchable in the settings",
		tags = {"export", "position", "location", "json", "grand exchange", "ge", "quests", "toolkit", "character", "bank"}
)
public class OsrsToolkitExporterPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private OsrsToolkitExporterConfig config;

	@Inject
	private ItemManager itemManager;

	// RuneLite's shared background executor: keeps file I/O off the client thread.
	@Inject
	private ScheduledExecutorService executor;

	// RuneLite requires reusing the client's Gson instance (customized via newBuilder()).
	@Inject
	private Gson clientGson;

	private Gson gson;

	private WorldPoint lastWritten;

	// Set when a GE slot changes; the actual write happens on the next game tick
	// (the local player's name isn't always known yet when offers load at login).
	private boolean geDirty;

	// Set at login and when the quest export is switched on; cleared once the file is written. The table
	// may not be loaded in the very first ticks, so a few tries are made.
	private boolean questsDirty;
	private int questTries;

	// Character progress: levels change often (boosts), game variables change all the time. Changes only mark the
	// data dirty; it is written at most once every few ticks.
	private static final int STATS_EVERY_TICKS = 5;      // ~3 s
	private static final int PROGRESS_EVERY_TICKS = 50;  // ~30 s: the quest status of every quest is asked again
	private boolean statsDirty;
	private boolean progressDirty;
	private int ticksSinceStats;
	private int ticksSinceProgress;
	private boolean inventoryDirty;
	private boolean equipmentDirty;
	private boolean bankDirty;

	@Provides
	OsrsToolkitExporterConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsrsToolkitExporterConfig.class);
	}

	@Override
	protected void startUp()
	{
		gson = clientGson.newBuilder().setPrettyPrinting().disableHtmlEscaping().create();
		lastWritten = null;
		// Export current offers right away if the plugin is enabled while logged in.
		geDirty = true;
		questsDirty = true;
		questTries = 0;
		markAllPlayerData();
	}

	@Override
	protected void shutDown()
	{
		lastWritten = null;
		geDirty = false;
		questsDirty = false;
		statsDirty = false;
		progressDirty = false;
		inventoryDirty = false;
		equipmentDirty = false;
		bankDirty = false;
	}

	private void markAllPlayerData()
	{
		statsDirty = true;
		progressDirty = true;
		ticksSinceStats = STATS_EVERY_TICKS;
		ticksSinceProgress = PROGRESS_EVERY_TICKS;
		inventoryDirty = true;
		equipmentDirty = true;
		bankDirty = true; // only written if the client has the bank (it does once the bank was opened this session)
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			geDirty = true;
			questsDirty = true;
			questTries = 0;
			markAllPlayerData();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!OsrsToolkitExporterConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if ("exportGameQuests".equals(event.getKey()))
		{
			questsDirty = true;
			questTries = 0;
		}
		else if ("exportCharacter".equals(event.getKey()) || "exportItems".equals(event.getKey())
			|| "exportBank".equals(event.getKey()))
		{
			markAllPlayerData();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		statsDirty = true;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		progressDirty = true;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int id = event.getContainerId();
		if (id == InventoryID.INV)
		{
			inventoryDirty = true;
		}
		else if (id == InventoryID.WORN)
		{
			equipmentDirty = true;
		}
		else if (id == InventoryID.BANK)
		{
			bankDirty = true;
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		geDirty = true;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return;
		}

		if (geDirty)
		{
			geDirty = false;
			if (config.exportGeOffers())
			{
				writeGeOffers(local.getName());
			}
		}

		if (questsDirty && config.exportGameQuests())
		{
			exportGameQuests();
		}

		exportPlayerData(local);

		if (!config.exportPosition())
		{
			lastWritten = null;
			return;
		}

		WorldPoint pos = local.getWorldLocation();
		if (pos == null || pos.equals(lastWritten))
		{
			return;
		}

		lastWritten = pos;
		writePosition(local.getName(), pos);
	}

	// ---------------------------------------------------------------- position

	private void writePosition(String playerName, WorldPoint pos)
	{
		PositionSnapshot snapshot = new PositionSnapshot();
		snapshot.exported_at = Instant.now().toString();
		snapshot.player_name = playerName;
		snapshot.world = client.getWorld();
		snapshot.plane = pos.getPlane();
		snapshot.x = pos.getX();
		snapshot.y = pos.getY();
		snapshot.region_id = pos.getRegionID();
		snapshot.region_x = pos.getRegionX();
		snapshot.region_y = pos.getRegionY();

		writeJson(playerName, "position.json", snapshot);
	}

	// ---------------------------------------------------------- grand exchange

	private void writeGeOffers(String playerName)
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return;
		}

		GeSnapshot snapshot = new GeSnapshot();
		snapshot.exported_at = Instant.now().toString();
		snapshot.player_name = playerName;
		snapshot.world = client.getWorld();
		snapshot.note = "price = offer price; avg_price = real average price (spent / quantity_filled). "
				+ "A BOUGHT/SOLD slot stays listed until it is collected.";

		List<GeSlot> slots = new ArrayList<>();
		for (int i = 0; i < offers.length; i++)
		{
			GrandExchangeOffer offer = offers[i];
			GeSlot slot = new GeSlot();
			slot.slot = i + 1; // 1-8, like in game

			GrandExchangeOfferState state = offer == null ? GrandExchangeOfferState.EMPTY : offer.getState();
			slot.state = state.name();

			if (offer == null || state == GrandExchangeOfferState.EMPTY)
			{
				snapshot.empty_slots++;
				slots.add(slot);
				continue;
			}

			slot.type = isBuy(state) ? "BUY" : "SELL";
			slot.item_id = offer.getItemId();
			slot.item_name = itemManager.getItemComposition(offer.getItemId()).getName();
			slot.price = (long) offer.getPrice();
			slot.quantity_filled = offer.getQuantitySold();
			slot.quantity_total = offer.getTotalQuantity();
			slot.spent = (long) offer.getSpent();
			slot.progress_pct = slot.quantity_total > 0
					? Math.round(1000.0 * slot.quantity_filled / slot.quantity_total) / 10.0
					: 0.0;
			if (slot.quantity_filled > 0)
			{
				slot.avg_price = Math.round(10.0 * slot.spent / slot.quantity_filled) / 10.0;
			}
			slot.complete = state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.SOLD;
			slot.cancelled = state == GrandExchangeOfferState.CANCELLED_BUY || state == GrandExchangeOfferState.CANCELLED_SELL;

			if (!slot.complete && !slot.cancelled)
			{
				if ("BUY".equals(slot.type))
				{
					// gp still locked in the offer
					snapshot.gp_locked_in_buys += (slot.quantity_total - slot.quantity_filled) * slot.price;
				}
				snapshot.active_offers++;
			}

			slots.add(slot);
		}

		snapshot.slots = slots;
		writeJson(playerName, "ge_offers.json", snapshot);
	}

	private static boolean isBuy(GrandExchangeOfferState state)
	{
		return state == GrandExchangeOfferState.BUYING
				|| state == GrandExchangeOfferState.BOUGHT
				|| state == GrandExchangeOfferState.CANCELLED_BUY;
	}

	// ---------------------------------------------------------------- game quest data

	private void exportGameQuests()
	{
		GameQuestData.Export data = GameQuestData.decode(
				new ClientQuestSource(client), Instant.now().toString(), client.getRevision());
		if (data.quest_count == 0)
		{
			// the table may not be loaded yet: try again on a later tick, a few times only
			if (++questTries >= 5)
			{
				questsDirty = false;
				log.debug("OSRS Toolkit Exporter: the quest table was not available");
			}
			return;
		}

		questsDirty = false;
		writeGameData("game_quests.json", data);
	}

	// ---------------------------------------------------------------- character progress and items

	private void exportPlayerData(Player local)
	{
		String name = local.getName();
		String now = Instant.now().toString();
		ticksSinceStats++;
		ticksSinceProgress++;

		if (config.exportCharacter())
		{
			if (statsDirty && ticksSinceStats >= STATS_EVERY_TICKS)
			{
				statsDirty = false;
				ticksSinceStats = 0;
				writeJson(name, "character.json", character(local, now));
			}
			if (progressDirty && ticksSinceProgress >= PROGRESS_EVERY_TICKS)
			{
				ticksSinceProgress = 0;
				PlayerData.Quests quests = PlayerData.quests(now, questRows(), this::questName, this::questStatus,
					client.getVarpValue(VarPlayerID.QP));
				// the quest table may not be loaded yet: keep the data dirty and try again later
				progressDirty = quests == null;
				if (quests != null)
				{
					writeJson(name, "quests.json", quests);
				}
				writeJson(name, "diaries.json", PlayerData.diaries(now, client::getVarbitValue));
				writeJson(name, "combat_achievements.json",
					PlayerData.combatAchievements(now, client::getVarpValue, client::getVarbitValue));
			}
		}

		if (config.exportItems())
		{
			if (inventoryDirty)
			{
				inventoryDirty = false;
				writeItems(name, now, "inventory", InventoryID.INV);
			}
			if (equipmentDirty)
			{
				equipmentDirty = false;
				writeItems(name, now, "equipment", InventoryID.WORN);
			}
		}

		if (config.exportBank() && bankDirty)
		{
			bankDirty = false;
			writeItems(name, now, "bank", InventoryID.BANK);
		}
	}

	private PlayerData.CharacterFile character(Player local, String now)
	{
		List<PlayerData.SkillValue> skills = new ArrayList<>();
		for (Skill skill : Skill.values())
		{
			if (!ClientQuestSource.isSkill(skill))
			{
				continue;
			}
			skills.add(new PlayerData.SkillValue(skill.getName(), client.getRealSkillLevel(skill),
				client.getBoostedSkillLevel(skill), client.getSkillExperience(skill)));
		}
		List<String> types = new ArrayList<>();
		for (WorldType type : client.getWorldType())
		{
			types.add(type.name());
		}
		return PlayerData.character(now, local.getName(), client.getWorld(), types, local.getCombatLevel(), skills);
	}

	private List<Integer> questRows()
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

	private String questName(int row)
	{
		try
		{
			Object[] cell = client.getDBTableField(row, DBTableID.Quest.COL_DISPLAYNAME, 0);
			return cell != null && cell.length > 0 && cell[0] instanceof String ? (String) cell[0] : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	// The game's own quest status script takes the quest's row of the Quest table (RuneLite's Quest ids are these rows).
	private int questStatus(int row)
	{
		client.runScript(ScriptID.QUEST_STATUS_GET, row);
		return client.getIntStack()[0];
	}

	private void writeItems(String name, String now, String kind, int containerId)
	{
		ItemContainer container = client.getItemContainer(containerId);
		if (container == null)
		{
			return; // e.g. the bank before it was opened this session: keep the last file
		}
		Item[] items = container.getItems();
		int[] ids = new int[items.length];
		int[] qtys = new int[items.length];
		for (int i = 0; i < items.length; i++)
		{
			ids[i] = items[i].getId();
			qtys[i] = items[i].getQuantity();
		}
		writeJson(name, kind + ".json", PlayerData.items(now, kind, ids, qtys,
			id -> itemManager.getItemComposition(id).getName()));
	}

	// ------------------------------------------------------------------ output

	private void writeJson(String playerName, String fileName, Object data)
	{
		// Serialize now (client thread, consistent snapshot), write later (background thread).
		final String json = gson.toJson(data);
		executor.execute(() -> writeFile(playerName, fileName, json));
	}

	// Game-wide data (not tied to a character) goes in the plugin's own folder, not in a character's.
	private void writeGameData(String fileName, Object data)
	{
		final String json = gson.toJson(data);
		executor.execute(() -> writeFile(null, fileName, json));
	}

	private void writeFile(String playerName, String fileName, String json)
	{
		try
		{
			// RuneLite's sandboxed data folder for this plugin: .runelite/plugin-data/position-exporter/
			Filepath dir = playerName == null ? getPluginDirectory() : getPluginDirectory().joinSegment(playerName);
			dir.createDirectories();
			Filepath outFile = dir.joinSegment(fileName);
			Filepath tmpFile = dir.joinSegment(fileName + ".tmp");

			// Write to a temp file then swap it in, so a reader never sees half a file.
			tmpFile.write(json.getBytes(StandardCharsets.UTF_8));
			try
			{
				tmpFile.moveTo(outFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException atomicFailed)
			{
				tmpFile.moveTo(outFile, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException | IllegalArgumentException e)
		{
			log.warn("OSRS Toolkit Exporter: failed to write {}", fileName, e);
		}
	}

	// ------------------------------------------------------------- json models

	@SuppressWarnings("unused")
	private static class PositionSnapshot
	{
		String exported_at;
		String player_name;
		int world;
		int plane;
		int x;
		int y;
		int region_id;
		int region_x;
		int region_y;
	}

	@SuppressWarnings("unused")
	private static class GeSnapshot
	{
		String exported_at;
		String player_name;
		int world;
		int active_offers;
		int empty_slots;
		long gp_locked_in_buys;
		String note;
		List<GeSlot> slots;
	}

	@SuppressWarnings("unused")
	private static class GeSlot
	{
		int slot;
		String state;
		String type;
		Integer item_id;
		String item_name;
		Long price;
		Integer quantity_filled;
		Integer quantity_total;
		Long spent;
		Double avg_price;
		Double progress_pct;
		Boolean complete;
		Boolean cancelled;
	}
}
