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
  - `TrackedItem` — one tracked item; holds per-sale lots and the rolling window math.
  - `SalesTracker` — item list, selection, persistence (atomic JSON writes), sale recording.
  - `VenstoneEconomyClient` — entrypoint: keybinding, chat listener, HUD element, config screen.
  - `VenstoneModMenu` — Mod Menu integration.
- `src/main/resources/assets/venstone_economy_assistant/lang/` — translation keys for the
  keybinding (`key.venstone_economy.open`) and its category (`key.category.venstone.general`).

## Conventions and gotchas
- Keep parsing conservative: prefer false negatives over false positives. `ShopTextParser`
  rejects negated confirmations, "revendu"-style prefixes, and trailing text after the price.
- The one-hour window is per lot: each sale expires 60 minutes after its own timestamp.
  `TrackedItem.normalize(now)` also clamps limits/prices and migrates the legacy
  `soldItems`/`windowStartedAt` fields.
- HUD rendering uses `HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, ...)`.
  `HudRenderCallback` is deprecated; do not reintroduce it.
- Saves are written to a `.tmp` file and moved atomically. Persist only when something changed.
- Config lives at `<config>/venstone-economy.json`. Seeding defaults happens only on first run.
- The Venstone shop lore/confirmation wording is not yet validated in-game; update
  `ShopTextParser` patterns once the real text is known.
