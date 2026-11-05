# Venstone Economy Assistant — 1.3.0

Client-side Fabric utility that turns the Venstone shop into a real economic tracker: it reads the
prices the shop shows, models the per-item sell limit, and shows a compact, resizable economy HUD.

## Target
- Minecraft Java 1.21.11
- Fabric Loader 0.18.x
- Fabric API 0.141.x (1.21.11)
- Java 21
- Optional Mod Menu 17.x

## What it does
- **Reads the shop.** While a shop container is open, the client reads its slots (item name, vanilla
  Lore, stack size). Nothing is sent to the server and no button is clicked.
- **Keeps a local price database.** Every item's sell price, buy price and limits are stored in
  `venstone-shop-prices.json`. A partial or misread tooltip never erases a good value.
- **Models the sell limit.** The limit is a single rule for the whole server: **36 stacks per item**,
  using each item's real stack size (2304 items for a 64-stack item, 576 for a 16-stack item).
- **Shows a HUD.** A compact panel per tracked item: sold/limit stacks, reset countdown, maximum
  revenue and a progress bar. The panel is scalable and can sit in any corner.
- **Reports the economics.** Unit price, price per stack, quota revenue, and the buy/sell margin
  when a buy price is known.

## Sell-limit reset models
The shop is EconomyShopGUI. Four reset models are offered and selected from the Settings tab; the
mode is global and persisted:

- **60 min from 1st sale** (default, matches Venstone) — a fixed 60-minute window anchored on the
  first sale; the whole quota returns 60 minutes after that first sale.
- **rolling 60 min** — each sale expires 60 minutes after its own timestamp (per-lot).
- **timer from 0** — the countdown starts only once the limit is fully reached, then the whole
  quota returns at once (EconomyShopGUI `auto-restock-*-sell`).
- **fixed hour** — the quota returns at the next hour boundary after saturation
  (EconomyShopGUI `auto-restock-*-sell-frequency`).

## Automatic read-only detection
The client reads visible item names and vanilla Lore from the currently open container and parses
recognizable price/limit labels. It records sales only from recognizable explicit non-overlay
game-chat confirmations. It does not inspect network packets, send commands, click shop buttons, or
automate selling.

Detection is deliberately conservative: a confirmation is ignored when it is explicitly negated
(for example "vous n'avez pas vendu"), when the verb is part of a different word ("revendu"), or when
unexpected trailing text follows the price. Identical confirmations repeated within two seconds are
treated as a single sale. Navigation and decoration slots ("Page suivante", "Fermer", empty fillers)
are never stored as items, and a container is only committed once its contents have been stable for
two consecutive scans.

## Usage
- `O` opens the configuration screen; `H` toggles the HUD. Both keybinds are rebindable in
  Options > Controls under the "Venstone Economy" category.
- The configuration screen has three tabs:
  - **Tracked** — the tracked item list on the left, the editor on the right. Fields: item id
    (e.g. `minecraft:cactus`), display name, unit sell price. "Find id" fills the id from the best
    registry match. "Apply" writes the fields; "Add" creates a new item; "Remove" deletes it;
    "Disable/Enable" excludes it from detection and the HUD.
  - **Shop prices** — every price discovered in the shop, richest first, with the universal 36-stack
    limit and a freshness marker. "Import into tracker" turns all of them into tracked items at once
    (one disk write, and your current selection is preserved).
  - **Settings** — HUD visibility, HUD corner, reset model, auto-add of newly seen items, and the
    interface scale (60% to 160%, click the middle button to return to 100%).
- Manual correction: "+ 1 stack sold" / "+ 1 unit sold" add a sale, "- 1 stack" removes from the most
  recent sales, "Clear history" empties the item's history.
- When an item reaches its limit, a chat notification is shown once per saturation.

## Configuration files
- `<config>/venstone-economy.json` (schema v3) — tracked items, selection, HUD and scale preferences.
- `<config>/venstone-shop-prices.json` (schema v1) — the price database read from the shop.

Both files are written atomically (`.tmp` file then move); if a write fails, the error is shown on
the configuration screen. Legacy bare-array configs and the pre-v2 `unitPrice` field migrate
automatically on load, and a single malformed entry is skipped rather than discarding the file.

## Reliability status
The Venstone confirmation and tooltip wording have been captured in game and are covered by tests
(`Vous avez vendu avec succès x656 Canne à sucre pour 78.72€.`; `Clic droit: Vendre: 0.12€`;
`Limite de vente: 384`). Custom GUI text not stored in vanilla item Lore is not accessible to this
implementation. The build is verified (Gradle build plus 146 automated tests); an in-game test is
still required before this project is marked as verified.

## Build
Requires JDK 21. The Gradle wrapper pins Gradle 9.2.1 (Fabric Loom 1.14.x requires Gradle 9.2 or newer).

```
./gradlew clean build
```

On Windows, run `build.bat`. The JAR is written to `build/libs/venstone-economy-assistant-1.3.0.jar`.

## Install and run

1. Install Fabric Loader 0.18.x for Minecraft 1.21.11.
2. Put the built JAR in the instance `mods` folder:
   `%APPDATA%\.minecraft\mods` (or the PrismLauncher instance's `minecraft\mods`).
3. Add `fabric-api-0.141.3+1.21.11.jar` to `mods` (required). Mod Menu 17.x is optional
   but recommended: it adds a config button in the mod list.
4. Launch the Fabric 1.21.11 profile. The mod is client-side only, so it also works on servers.

Verify the build without launching the game:

```
./gradlew test
```

Launch a development client straight from the project (downloads the assets on first run):

```
./gradlew runClient
```

The mod is loaded when `logs/latest.log` contains `venstone-economy` startup lines.

## Tests
The parser, the stack/limit math, the economics, the UI scale, the price catalogue, the scanner rules
and the tracking logic all have automated tests:

```
./gradlew test
```
