package com.gorsok.positionexporter;

// What it does:
//   1) Position: every game tick, if your tile changed, writes
//        <home>/.runelite/character-exporter/<YourCharacterName>/position.json
//   2) Grand Exchange: whenever one of your 8 GE slots changes (new offer,
//      partial fill, completed, cancelled, collected), writes
//        <home>/.runelite/character-exporter/<YourCharacterName>/ge_offers.json
//      with every slot: item, buy/sell, state, offer price, qty filled / total,
//      gp spent, and the real average price paid/received.
//
// Both files live next to the output of the "Character Export" plugin (Plugin Hub),
// so tools like OSRS GE Toolkit can read everything from one folder.
// Nothing is written while logged out. Nothing is ever sent over the network.
// Data is collected on the client thread; the disk writes happen on a background thread.

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
		name = "Position Exporter",
		description = "Writes your live world position and Grand Exchange offers to local JSON files, next to character-exporter's output",
		tags = {"export", "position", "location", "json", "grand exchange", "ge"}
)
public class PositionExporterPlugin extends Plugin
{
	@Inject
	private Client client;

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

	@Override
	protected void startUp()
	{
		gson = clientGson.newBuilder().setPrettyPrinting().disableHtmlEscaping().create();
		lastWritten = null;
		// Export current offers right away if the plugin is enabled while logged in.
		geDirty = true;
	}

	@Override
	protected void shutDown()
	{
		lastWritten = null;
		geDirty = false;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			geDirty = true;
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
			writeGeOffers(local.getName());
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

	// ------------------------------------------------------------------ output

	private void writeJson(String playerName, String fileName, Object data)
	{
		// Serialize now (client thread, consistent snapshot), write later (background thread).
		final String json = gson.toJson(data);
		executor.execute(() -> writeFile(playerName, fileName, json));
	}

	private static void writeFile(String playerName, String fileName, String json)
	{
		try
		{
			Path baseDir = RuneLite.RUNELITE_DIR.toPath()
					.resolve("character-exporter")
					.resolve(playerName);
			Files.createDirectories(baseDir);
			Path outFile = baseDir.resolve(fileName);
			Path tmpFile = baseDir.resolve(fileName + ".tmp");

			// Write to a temp file then swap it in, so a reader never sees half a file.
			try (Writer writer = Files.newBufferedWriter(tmpFile, StandardCharsets.UTF_8))
			{
				writer.write(json);
			}
			try
			{
				Files.move(tmpFile, outFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException atomicFailed)
			{
				Files.move(tmpFile, outFile, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e)
		{
			log.warn("Position Exporter: failed to write {}", fileName, e);
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
