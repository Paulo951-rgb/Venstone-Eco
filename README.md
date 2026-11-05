# Venstone Economy Assistant — 1.2.0

Client-side Fabric utility for tracking per-item shop sale limits on Venstone.

## Target
- Minecraft Java 1.21.11
- Fabric Loader 0.18.x
- Fabric API 0.141.x (1.21.11)
- Java 21
- Optional Mod Menu 17.x

## Sell-limit reset models
The shop is EconomyShopGUI. Its sell limit does not use a fixed sliding window, so the app offers
three models, selectable from the configuration screen:

- **rolling 60 min** — each sale expires 60 minutes after its own timestamp (per-lot).
- **timer from 0** — the countdown starts only once the limit is fully reached, then the whole
  quota returns at once (EconomyShopGUI `auto-restock-*-sell`).
- **fixed hour** — the quota returns at the next hour boundary after saturation
  (EconomyShopGUI `auto-restock-*-sell-frequency`).

Pick the one that matches what you observe in game.

## Automatic read-only detection
The client reads visible item names and vanilla Lore from the currently open container and parses recognizable price/limit labels. It records sales only from recognizable explicit non-overlay game-chat confirmations. It does not inspect network packets, send commands, click shop buttons, or automate selling.

Detection is deliberately conservative: a confirmation is ignored when it is explicitly negated (for example "vous n'avez pas vendu"), when the verb is part of a different word ("revendu"), or when unexpected trailing text follows the price. Identical confirmations repeated within two seconds are treated as a single sale to avoid double counting.

## Usage
- `O` opens the configuration screen; `H` toggles the HUD. Both keybinds are rebindable in Options > Controls under the "Venstone Economy" category.
- The configuration screen lists tracked items on the left. Selecting a row loads it into the editor fields on the right.
- Editor fields: item id (e.g. `minecraft:cactus`), display name, configured price per unit, and limit in stacks. "Find id" fills the id from the best registry match for the name or id you typed.
- "Apply settings" writes the fields to the selected item; "Add item" creates a new tracked item.
- Manual correction: "+ 1 stack sold" / "+ 1 unit sold" add a sale lot, "- 1 stack (undo)" removes from the most recent lots, "Clear history" empties all lots for the item.
- "Delete item" removes the selected item. "Disable/Enable this item" excludes it from detection and the HUD.
- HUD controls: "Show/Hide HUD" and "HUD corner" (top/bottom, left/right).
- The HUD shows, per enabled item, sold/limit stacks, time until the next lot expires, the maximum revenue estimate, and stacks still available. When an item reaches its limit, a chat notification is shown once.

## Configuration file
The config is written to `<config>/venstone-economy.json` (schema v2). It stores the item list, the selection, and the HUD preferences. Writes are atomic (`.tmp` file then move); if a write fails, the error is shown on the configuration screen. Legacy bare-array configs and the pre-v2 `unitPrice` field migrate automatically on load.

## Reliability status
The Venstone confirmation and tooltip wording have been captured in game and are covered by tests
(`Vous avez vendu avec succès x656 Canne à sucre pour 78.72€.`; `Clic droit: Vendre: 0.12€`;
`Limite de vente: 384`). Custom GUI text not stored in vanilla item Lore is not accessible to this implementation. The build is verified (Gradle build plus 78 automated tests); an in-game test is still required before this project is marked as verified.

## Build
Requires JDK 21. The Gradle wrapper pins Gradle 9.2.1 (Fabric Loom 1.14.x requires Gradle 9.2 or newer).

```
./gradlew clean build
```

On Windows, run `build.bat`. The JAR is written to `build/libs/venstone-economy-assistant-1.2.0.jar`.

## Tests
The parser and the rolling-window tracking logic have automated tests:

```
./gradlew test
```
