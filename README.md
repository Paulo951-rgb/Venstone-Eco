# Venstone Economy Assistant — 1.2.0

Client-side Fabric utility for tracking per-item shop sale limits on Venstone.

## Target
- Minecraft Java 1.21.11
- Fabric Loader 0.18.x
- Fabric API 0.141.x (1.21.11)
- Java 21
- Optional Mod Menu 17.x

## Rolling one-hour limit
Every sale creates a separate lot. Each lot expires 60 minutes after its own sale timestamp; the active quantity is the sum of all non-expired lots.

## Automatic read-only detection
The client reads visible item names and vanilla Lore from the currently open container and parses recognizable price/limit labels. It records sales only from recognizable explicit non-overlay game-chat confirmations. It does not inspect network packets, send commands, click shop buttons, or automate selling.

## Reliability status
The exact Venstone shop lore and confirmation wording still needs in-game validation. Custom GUI text not stored in vanilla item Lore is not accessible to this implementation. Duplicate confirmations are not yet deduplicated. A full Gradle build and in-game test are still required; this project is not yet marked as verified.

## Build
Requires JDK 21 and Gradle 9.x. Run `gradle clean build` from the project directory. The JAR is written to `build/libs/venstone-economy-assistant-1.2.0.jar`.
