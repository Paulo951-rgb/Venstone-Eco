# Venstone Economy Assistant — repository guide

Client-side Fabric mod (Minecraft 1.21.11) that tracks rolling one-hour shop sale
limits. No server component, no packet inspection, no automation of selling.

## Build
- JDK 21 required. Use the wrapper: `./gradlew clean build` (Windows: `gradlew.bat`).
- Gradle is pinned to 9.2.1 in `gradle/wrapper/gradle-wrapper.properties`.
  Fabric Loom 1.14.x requires Gradle >= 9.2; do not downgrade.
- Tests: `./gradlew test` (JUnit 5). Pure-logic classes are unit tested without Minecraft.

## Layout
- `src/main/java/fr/venstone/economy/`
  - `ShopTextParser` — pure text parsing (sale confirmations, price, limit). No Minecraft deps.
  - `ItemIds` — pure `namespace:path` id helpers. No Minecraft deps.
  - `Durations` — pure duration formatting for the HUD. No Minecraft deps.
  - `Clock` / `SystemClock` — monotonic, wall-anchored time source for the rolling window.
  - `TrackedItem` — one tracked item; holds per-sale lots and the rolling window math.
  - `ItemResolver` — registry-backed id/name lookup; degrades to the pure rules when no registry.
  - `SalesTracker` — item list, selection, persistence (atomic JSON writes), sale recording.
  - `ItemListWidget` — scrollable tracked-item list for the config screen.
  - `VenstoneEconomyClient` — entrypoint: keybinds, chat listener, HUD element, config screen.
  - `VenstoneModMenu` — Mod Menu integration.
- `src/main/resources/assets/venstone_economy_assistant/lang/` — translation keys (`en_us`, `fr_fr`)
  for the keybinds, the HUD and the config screen.

## Conventions and gotchas
- Keep parsing conservative: prefer false negatives over false positives. `ShopTextParser`
  rejects negated confirmations, "revendu"-style prefixes, and trailing text after the price.
- The Venstone shop is EconomyShopGUI. The **real** confirmation is
  `Vous avez vendu avec succès x656 Canne à sucre pour 78.72€.` (leading `x`, "avec succès"
  between verb and amount) and the **real** tooltip is
  `Clic gauche: Acheter: 2.00€ / Clic droit: Vendre: 0.12€ / Limite d'achat: 192 / Limite de vente: 384`.
  Sell price and sell limit must win over the buy ones. Keep these strings in the tests.
- The rolling window is configurable per `SalesTracker.ResetMode`: `ROLLING` (original per-lot
  window), `TIMER` (countdown started when the limit is reached) and `CLOCK` (fixed hour).
  EconomyShopGUI has no fixed sliding window, so the default may need to be switched in-game.
- HUD rendering uses `HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, ...)`.
  `HudRenderCallback` is deprecated; do not reintroduce it.
- Saves are written to a `.tmp` file and moved atomically. Persist only when something changed.
  A failed save is surfaced through `SalesTracker.lastError()` and shown on the config screen.
- Config lives at `<config>/venstone-economy.json`, schema v2. Older bare-array configs and
  pre-v2 `unitPrice` fields migrate on load. A single malformed entry is skipped, not fatal.
- Seeding defaults happens only on first run (when the file does not exist).
- The shop scan only reads slots that do not belong to the player's own inventory.
- The Venstone shop lore/confirmation wording is not yet validated in-game; update
  `ShopTextParser` patterns once the real text is known.
