# LiteCookingMakerBridge

![License](https://img.shields.io/badge/license-BNSL--1.0-red)
![Commercial Use](https://img.shields.io/badge/commercial-use%20by%20written%20permission%20only-critical)
![Platform](https://img.shields.io/badge/platform-Paper%201.21.x-brightgreen)
![Dependencies](https://img.shields.io/badge/dependencies-LiteCooking%20%2B%20MMOCore-blueviolet)
![Status](https://img.shields.io/badge/status-active-success)

LiteCookingMakerBridge is a standalone bridge plugin that writes maker attribution onto LiteCooking rewards and can optionally grant MMOCore class experience.

## Highlights

- Tracks LiteCooking workstation sessions through reflection.
- Opens a short claim window and matches spawned reward items back to the crafter.
- Writes maker UUID, name, time, and lore onto the resulting item.
- Supports optional MMOCore experience rewards with per-recipe overrides.

## Build

```powershell
.\build.ps1
```

## Repository Scope

- Source and config only.
- Build outputs and copied server jars are excluded from Git.

## License

Bonfire Non-Commercial Source License 1.0

Commercial use is prohibited unless you first obtain written permission from `mingxi7707@qq.com`.
