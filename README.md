# GDOK Overlay

Een client-side Fabric mod die metingen en BGT-omtrekken vanuit de [GDOK Viewer](https://gdok.tectabuilds.nl/viewer) visualiseert als in-game overlays in Minecraft.

De mod verbindt met de webviewer via een lokale WebSocket bridge, waardoor wijzigingen die je op de kaart maakt direct real-time in de game verschijnen.

## Vereisten

| | Versie |
|---|---|
| Minecraft | 1.21.11 of 26.2 |
| Fabric Loader | >= 0.18.4 (1.21.11) / >= 0.19.3 (26.2) |
| Fabric API | Vereist |
| Java | >= 21 (1.21.11) / >= 25 (26.2) |

## Installatie

1. Installeer [Fabric Loader](https://fabricmc.net/use/) voor Minecraft 1.21.11 of 26.2.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) en plaats deze in je `mods/` map.
3. Download de juiste GDOK Overlay jar voor jouw Minecraft-versie vanaf [gdok.tectabuilds.nl](https://gdok.tectabuilds.nl/viewer) en zet hem in je `mods/` map.

## Gebruik

1. Open de GDOK Viewer in je browser.
2. Start Minecraft met de mod geïnstalleerd.
3. De mod verbindt automatisch met de viewer via `localhost:4945`.
4. Teken metingen of schakel BGT-lijnen in via de viewer — ze verschijnen direct in-game als overlay.

## Vanuit broncode compileren

Om tegelijkertijd de jars voor beide ondersteunde versies te compileren, en een overkoepelende `mod-info.json` te genereren, gebruik je het volgende commando:

```bash
./gradlew build generateModInfo
```

De resulterende jars zijn vervolgens voor beide versies terug te vinden in:
- `fabric-1.21.11/build/libs/`
- `fabric-26.x/build/libs/`

En de overkoepelende mod info vind je in `build/mod-info.json`.

## Development

### Dev-client starten

Elke module heeft een Loom-runconfiguratie die Minecraft met de mod start vanuit de broncode:

```bash
./gradlew :fabric-1.21.11:runClient
./gradlew :fabric-26.x:runClient
```

In zo'n dev-client staat de **server-gate open** (`ServerGate.devBypass`, gezet zodra `FabricLoader.isDevelopmentEnvironment()` waar is), dus een singleplayer- of LAN-wereld volstaat om te testen; je hoeft niet op de GeoCraft-server te zitten. De log toont dan een waarschuwing `DEV-BYPASS ACTIEF`. In een gewone jar is die bypass nooit actief.

De dev-client draait in `fabric-<versie>/run/` (gitignored). Log in op een gewone offline-wereld; de mod start de bridge op `127.0.0.1:4945`.

### Hotswap in plaats van herstarten

Een volledige Minecraft-herstart kost een minuut; voor render- en GUI-tweaks hoeft dat niet:

1. Genereer de IDE-runconfiguraties (`./gradlew genSources` is handig voor leesbare Minecraft-broncode) en open het project in IntelliJ. Loom maakt voor elke module een runconfiguratie "Client 1.21.11" / "Client 26.x" aan.
2. Start die configuratie **onder de debugger**.
3. Pas code aan en kies *Run > Debugging Actions > Reload Changed Classes*. Wijzigingen in bestaande methoden (renderer, schermlayout, constanten zoals `SLAB_HEIGHT`) zijn direct zichtbaar in het draaiende spel.
4. Aanrader: draai de dev-client op de **JetBrains Runtime** met `-XX:+AllowEnhancedClassRedefinition` (DCEVM). Dan overleven ook structurele wijzigingen (nieuwe methoden/velden) een reload.

Een volledige herstart is alleen nodig bij event-registraties, entrypoints, `fabric.mod.json` en Gradle-wijzigingen.

### Tests

- **Unittests** (`./gradlew :common:test`): de geometrie-kern in `common/` (mesh-builder, face-culling, bucketing, cache, occupancy-scanner, frustum) wordt vergeleken met een letterlijke port van de oude per-frame-renderer, zodat de weergave quad-voor-quad gelijk blijft.
- **Client-gametests** (`./gradlew :fabric-26.x:runClientGameTest` of `:fabric-1.21.11:runClientGameTest`): start een echte client met de mod, maakt een vlakke wereld, stuurt een overlay via de echte WebSocket-bridge en controleert screenshots op pixelniveau: volledige blokken, dun tapijt, verbergen, een echt blok op de overlaypositie (slab weg, kader erbij), blok weer weg, hoogte-wissel, 100.000 blokken versus de framerate, frustum-culling en een resource-reload. Bron in `fabric-*/src/gametest/`, screenshots in `fabric-*/build/run/clientGameTest/screenshots/`. Duurt ~45 s per versie en heeft een scherm nodig (de client opent een venster).

### Site-kant

De GDOK Viewer verbindt altijd met `127.0.0.1:4945`, ook vanaf een lokale dev-server. Draai dus in de gdok-repo `npm run dev` (plus `php artisan serve`) en open de viewer; de verbindingsstatus linksonder wordt groen zodra de dev-client draait en een wereld geladen is.

## Licentie

[MIT](LICENSE)
