# Position Exporter

RuneLite plugin that writes two small JSON files on your computer, next to the output of the
[Character Export](https://runelite.net/plugin-hub/show/character-export) plugin:

| File | Updated | Contents |
|---|---|---|
| `position.json` | when your tile changes | world, plane, x/y, region id |
| `ge_offers.json` | when a Grand Exchange slot changes | every slot: item, buy/sell, state, offer price, quantity filled/total, gp spent, real average price, gp still locked in buy offers |

Files are written to `.runelite/character-exporter/<your character>/`.
Nothing is written while logged out, and **nothing is ever sent over the network**.

It is the data source for [OSRS GE Toolkit](https://github.com/Gorsokk/osrs-ge-toolkit)
(live GE dashboard, flip/alch scanner, Windows alerts), but any tool can read the files.

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
