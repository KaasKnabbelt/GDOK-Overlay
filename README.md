# GDOK Overlay

Een client-side Fabric mod die metingen en BGT-omtrekken vanuit de [GDOK Viewer](https://gdok.tectabuilds.nl/viewer) visualiseert als in-game overlays in Minecraft.

De mod verbindt met de webviewer via een lokale WebSocket bridge, waardoor wijzigingen die je op de kaart maakt direct real-time in de game verschijnen.

## Vereisten

| | Versie |
|---|---|
| Minecraft | 1.21.11 of 26.1.1 |
| Fabric Loader | >= 0.18.4 |
| Fabric API | Vereist |
| Java | >= 21 (1.21.11) / >= 25 (26.1.1) |

## Installatie

1. Installeer [Fabric Loader](https://fabricmc.net/use/) voor Minecraft 1.21.11 of 26.1.1.
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
- `fabric-26.1.1/build/libs/`

En de overkoepelende mod info vind je in `build/mod-info.json`.

## Licentie

[MIT](LICENSE)
