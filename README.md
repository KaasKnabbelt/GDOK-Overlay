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

### Beheer-menu (G)

Druk in-game op **G** (of open de mod via ModMenu) voor het overlaybeheer. Per laag: blok-icoon, naam en aantal blokken, **Verberg/Toon**, **Pin** en een kruisje om de laag uit Minecraft te halen. Onder de lagen de instellingen: **Zelfde niveau** (alle lagen op één hoogte, met een niveau-stepper; uit = elke laag een eigen stepper), **Reset hoogtes** (terug naar de AHN-starthoogte van de site), doorzichtigheid, **Weergave** (volledige blokken of dun tapijt) en **Spelerlocatie**. Onderaan **Alles wissen (behalve vastgezet)**. De kop toont het aantal blokken (maximaal 100.000) en of er een GDOK-viewer verbonden is.

Hoe de mod met hoogte, pins en verbergen omgaat:

- De site stuurt bij elke laag een starthoogte uit het AHN; daarna is de **mod eigenaar van de hoogte**. Page Up / Page Down en de steppers in het menu verschuiven hem, ook terwijl je op de site doorverft.
- **Pin** laat een laag staan bij "Wissen" op de site en bij "Alles wissen" in het menu. Een pin geldt voor de laag, niet voor de blokken: opnieuw verven op de site vervangt wel de inhoud. Het kruisje verwijdert altijd, ook een vastgezette laag.
- **Verbergen** gebeurt in Minecraft; de site houdt de laag en blijft hem meesturen, maar de mod tekent hem niet.
- Pins, verborgen lagen en het gedeelde niveau zijn **per sessie**: bij het verlaten van de server is alles weg. De eerste keer dat je op GeoCraft joint, krijg je eenmalig een chatregel die naar de G-toets wijst.

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
- **Client-gametests** (`./gradlew :fabric-26.x:runClientGameTest` of `:fabric-1.21.11:runClientGameTest`): start een echte client met de mod, maakt een vlakke wereld, stuurt een overlay via de echte WebSocket-bridge en controleert screenshots op pixelniveau: volledige blokken, dun tapijt, verbergen, een echt blok op de overlaypositie (slab weg, kader erbij), blok weer weg, hoogte-wissel, 100.000 blokken versus de framerate, frustum-culling en een resource-reload. Tot slot opent de test het beheer-menu (G) met een paar lagen in verschillende staat en maakt daar screenshots van, in beide niveau-standen; die worden niet gemeten maar zijn bedoeld om de layout per MC-versie met eigen ogen te controleren. Bron in `fabric-*/src/gametest/`, screenshots in `fabric-*/build/run/clientGameTest/screenshots/`. Duurt ~45 s per versie en heeft een scherm nodig (de client opent een venster). De testclient luistert op bridge-poort **4946** (system property `geocraft.overlay.port`, gezet in `build.gradle`): op 4945 zou een open GDOK-viewer-tab meeverbinden, zijn eigen overlays insturen en daarmee het gedeelde niveau verleggen, waardoor de testlaag uit beeld verdwijnt.

### Site-kant

De GDOK Viewer verbindt altijd met `127.0.0.1:4945`, ook vanaf een lokale dev-server. Draai dus in de gdok-repo `npm run dev` (plus `php artisan serve`) en open de viewer; de verbindingsstatus linksonder wordt groen zodra de dev-client draait en een wereld geladen is.

## Licentie

[MIT](LICENSE)
