# OSRS Toolkit Exporter

Part of the **OSRS Toolkit** suite: the [OSRS Toolkit app](https://github.com/Gorsokk/osrs-toolkit), OSRS Toolkit Exporter (this plugin) and OSRS Toolkit Panel.

Each export can be switched on or off in the plugin's settings: **Export position**, **Export GE offers**,
**Export character progress**, **Export inventory and equipment** and **Export bank** (all on by default), and
**Export game quest data** (off by default).

RuneLite plugin that writes small JSON files on your computer, read by the OSRS Toolkit app:

| File | Updated | Contents |
|---|---|---|
| `position.json` | when your tile changes | world, plane, x/y, region id |
| `ge_offers.json` | when a Grand Exchange slot changes | every slot: item, buy/sell, state, offer price, quantity filled/total, gp spent, real average price, gp still locked in buy offers |
| `character.json` | at login, then when a level or boost changes | levels (real and boosted) and experience of every skill, total level, combat level, world and world types |
| `quests.json` | at login, then at most every 30 seconds when the game state changes | the state of every quest (finished / in progress / not started), asked to the game itself for each quest of its quest table, and your quest points |
| `diaries.json` | same | for each achievement diary region and tier (easy, medium, hard, elite): done or not, with the game's raw value |
| `combat_achievements.json` | same | total combat achievement tasks done (counted from the game's own task flags), points, the game's raw tier values |
| `inventory.json`, `equipment.json` | when they change | every item: slot, item id, quantity, name |
| `bank.json` | when your bank changes (open it once per session) | every item: slot, item id, quantity, name |
| `game_quests.json` | once per login (only if **Export game quest data** is on) | the game's own quest table, read from your client: for every quest the levels and quests required, the starting tile and NPC, the experience rewarded, the quest points and the game's raw codes, with the client revision and the export time |

Files are written to RuneLite's data folder for this plugin: `.runelite/plugin-data/position-exporter/<your character>/`
(`game_quests.json` is not tied to a character, so it sits in `.runelite/plugin-data/position-exporter/`).
Nothing is written while logged out, and **nothing is ever sent over the network**.

`game_quests.json` is read from the running game client (its Quest table), not from any website, so it always matches
the game version you are playing. Codes whose meaning is not known (`difficulty_code`, `length_code`,
`requirement_combat_raw`, `prerequisite_direct`, `prerequisite_indirect`) are written as raw values.

It is the data source for the free [OSRS Toolkit](https://github.com/Gorsokk/osrs-toolkit) desktop app
(live GE dashboard, flip/alch scanner, Windows alerts, Claude connector, stream tools), but any tool can read the files.
On its own the plugin only writes the files: install the app to use them.

## Example `ge_offers.json`

```json
{
  "exported_at": "2026-09-26T15:34:46Z",
  "player_name": "Gorsok",
  "world": 402,
  "active_offers": 1,
  "empty_slots": 7,
  "gp_locked_in_buys": 341544,
  "slots": [
    { "slot": 1, "state": "BUYING", "type": "BUY", "item_id": 19615,
      "item_name": "Draynor manor teleport", "price": 2033,
      "quantity_filled": 0, "quantity_total": 168, "spent": 0,
      "progress_pct": 0.0, "complete": false, "cancelled": false }
  ]
}
```
