# Venstone Economy Assistant — repository guide

Client-side Fabric mod (Minecraft 1.21.11) that tracks the Venstone shop's per-item sell limits and
shows a compact, scalable economy HUD. No server component, no packet inspection, no automation of
selling. The mod only reads what the client already displays (open container slots, chat lines).

## Build
- JDK 21 required. Use the wrapper: `./gradlew clean build` (Windows: `gradlew.bat`).
- Gradle is pinned to 9.2.1 in `gradle/wrapper/gradle-wrapper.properties`.
  Fabric Loom 1.14.x requires Gradle >= 9.2; do not downgrade.
- Tests: `./gradlew test` (JUnit 5). Pure-logic classes are unit tested without Minecraft.

## Layout
- `src/main/java/fr/venstone/economy/`
  - `ShopTextParser` — pure text parsing (sale confirmations, sell/buy price, sell/buy limit). No Minecraft deps.
  - `ItemIds` — pure `namespace:path` id helpers. No Minecraft deps.
  - `StackMath` — pure stack/limit/revenue math: the 36-stack rule, real stack sizes, money rounding.
  - `Durations` — pure duration formatting for the HUD. No Minecraft deps.
  - `UiScale` — pure interface-scale steps (60%..160%, 100% default). No Minecraft deps.
  - `ItemEconomics` — pure derived economics for one item (unit/stack/quota prices, margin, remaining).
  - `Clock` / `SystemClock` — monotonic, wall-anchored time source for the window.
  - `TrackedItem` — one tracked item; sale lots, reset models, price history, config migration.
  - `ItemResolver` — registry-backed id/name/stack-size lookup; degrades to pure rules without a registry.
  - `SalesTracker` — item list, selection, preferences, persistence (atomic JSON), sale recording.
  - `ShopCatalog` — local price database read from the shop; never erases a good value with a partial read.
  - `ShopScanner` — reads the open container's slots and feeds the catalogue (settle-on-two-scans).
  - `EconomyHud` — compact HUD panel and shared colour palette (`EconomyHud.Theme`).
  - `EconomyScreen` — tabbed configuration screen (Tracked / Shop prices / Settings).
  - `ItemListWidget` / `ShopListWidget` — scrollable entry lists for the configuration screen.
  - `VenstoneEconomyClient` — entrypoint: keybinds, chat listener, shop scan, HUD element, config screen.
  - `VenstoneModMenu` — Mod Menu integration.
- `src/main/resources/assets/venstone_economy_assistant/lang/` — translation keys (`en_us`, `fr_fr`)
  for the keybinds, the HUD and the configuration screen. Both files must stay key-symmetric.

## Domain rules
- The sell limit is **universal**: 36 stacks per item per cycle (`StackMath.LIMIT_STACKS`). It is
  derived from the item's real stack size (`Item.getMaxCount()`), never configured per item and never
  taken from shop lore. 36 stacks is 2304 items for a 64-stack item, 576 for a 16-stack item.
- Three prices are always kept distinct: unit price (one item), price per stack, quota revenue.
- Margin (sell minus buy) is only shown when a buy price is known; a loss is reported as negative.

## Conventions and gotchas
- Keep parsing conservative: prefer false negatives over false positives. `ShopTextParser`
  rejects negated confirmations, "revendu"-style prefixes, and trailing text after the price.
- The Venstone shop is EconomyShopGUI. The **real** confirmation is
  `Vous avez vendu avec succès x656 Canne à sucre pour 78.72€.` (leading `x`, "avec succès"
  between verb and amount) and the **real** tooltip is
  `Clic gauche: Acheter: 2.00€ / Clic droit: Vendre: 0.12€ / Limite d'achat: 192 / Limite de vente: 384`.
  Sell price and sell limit must win over the buy ones. Keep these strings in the tests.
- `ShopScanner.build(...)` is the pure, unit-testable decision layer (id, navigation filter, prices);
  `describe(ItemStack)` only extracts the raw fields and delegates. Keep logic in `build`.
- Navigation/decoration names are matched as whole words (`NAVIGATION_WORDS`), so an item whose name
  merely contains one ("Backpack") is still kept. Never go back to plain substring matching.
- The shop's own lore limit is never displayed or applied. Every UI surface shows the universal
  `StackMath.LIMIT_STACKS`; showing the lore number invites users to believe it is configurable.
- Look items up by exact id (`SalesTracker.findById`) in scanner-driven paths; the fuzzy
  `findByNameOrId` is only for text a human typed.
- Bulk paths (`trackNewItem`, `addItems`) add items without moving the selection and save once, so a
  shop scan never steals the editor's row or writes the file once per item.
- A failed save is retried only after `SAVE_RETRY_BACKOFF_MILLIS`; `tick()` runs twenty times a
  second, so retrying on every tick would hammer a read-only config directory.
- Never mutate a list's entries from inside that list's own `mouseClicked` handler; that corrupts the
  widget's iteration. Selection changes call `syncFields`; only explicit actions call `reloadList`.
- The reset model is configurable per `SalesTracker.ResetMode`. Venstone's observed rule is
  `WINDOW` (the default): a fixed 60-minute window anchored on the **first** sale, so the whole
  quota returns 60 minutes after that first sale. `ROLLING` keeps the per-lot model, `TIMER` counts
  down once the limit is reached, `CLOCK` resets on the hour. The mode is global and persisted.
- HUD rendering uses `HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, ...)`.
  `HudRenderCallback` is deprecated; do not reintroduce it.
- HUD scaling is a single uniform `Matrix3x2fStack` scale around a design-unit layout; never scale
  X and Y differently, and always `popMatrix()` in a `finally`.
- Configs: `venstone-economy.json` (tracker, schema v3) and `venstone-shop-prices.json` (catalogue,
  schema v1). Both migrate legacy shapes on load and skip malformed entries instead of discarding
  the whole file. Writes are atomic (`.tmp` then move).
- A price observation from a partial tooltip must never overwrite a stored value: only non-null
  readings are applied (`ShopCatalog.record`).
- Saves are written to a `.tmp` file and moved atomically. Persist only when something changed.
  A failed save is surfaced through `SalesTracker.lastError()` and shown on the config screen.
- Seeding defaults happens only on first run (when the file does not exist).
- The shop scan only reads slots that do not belong to the player's own inventory.
- The Venstone confirmation and tooltip wording captured in game is covered by tests. A full
  in-game run is still required before the build is considered verified end to end; update the
  `ShopTextParser` patterns if the server changes its wording.
