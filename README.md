# GDOK Overlay

A client-side Fabric mod that visualises measurements and BGT outlines from the [GDOK Viewer](https://gdok.tectabuilds.nl) as in-game overlays in Minecraft.

The mod connects to the web viewer via a local WebSocket bridge, so changes you make on the map appear in real-time inside the game.

## Requirements

| | Version |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | >= 0.18.4 |
| Fabric API | Required |
| Java | >= 21 |

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.11
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and place it in your `mods/` folder
3. Download the latest GDOK Overlay jar from [gdok.tectabuilds.nl](https://gdok.tectabuilds.nl) and place it in your `mods/` folder

## Usage

1. Open the GDOK Viewer in your browser
2. Launch Minecraft with the mod installed
3. The mod automatically connects to the viewer on `localhost:4945`
4. Draw measurements or toggle BGT outlines in the viewer — they appear as block overlays in-game

## Building from source

```bash
./gradlew build
```

The built jar will be in `build/libs/`.

## License

[MIT](LICENSE)
