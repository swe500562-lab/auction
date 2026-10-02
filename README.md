# Bidding (Fabric, Minecraft 26.2)

Build: Java 25, `gradle wrapper --gradle-version 9.5.1` (einmalig), dann `./gradlew build`.
Jar: `build/libs/bidmod-1.0.0.jar` -> in `mods/` von Server UND Clients (+ Fabric API).

Commands:
- `/bid start <startBid> <seconds>`  Item in der Haupthand wird versteigert (Queue bis 10)
- `/bid <amount>`                    bieten
- `/bid cancel`                      eigene Auktion ohne Gebote abbrechen
- `/bid balance`                     Kontostand

Config: `config/bidmod.json` (Startguthaben, Mindest-Erhoehung, Anti-Snipe, "Worth"-Tabelle je Item-ID).
Guthaben: `config/bidmod-balances.json`.
