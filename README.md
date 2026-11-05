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

Detection is deliberately conservative: a confirmation is ignored when it is explicitly negated (for example "vous n'avez pas vendu"), when the verb is part of a different word ("revendu"), or when unexpected trailing text follows the price. Identical confirmations repeated within two seconds are treated as a single sale to avoid double counting.

## Reliability status
The exact Venstone shop lore and confirmation wording still needs in-game validation. Custom GUI text not stored in vanilla item Lore is not accessible to this implementation. The build is verified (Gradle build plus 37 automated tests); an in-game test is still required before this project is marked as verified.

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
