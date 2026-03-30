# LiteCookingMakerBridge

Standalone bridge plugin for LiteCooking precise maker attribution.

## What it does
- Tracks LiteCooking workstation sessions via reflection.
- When a session ends, opens a short "claim window" for expected reward drops.
- On matching reward `ItemSpawnEvent`, writes maker identity:
  - `PDC`: `litecookingmakerbridge:lc_maker_uuid`, `litecookingmakerbridge:lc_maker_name`, `litecookingmakerbridge:lc_maker_time`
  - Lore lines from `config.yml` templates (`maker.lore-template`, `maker.time-lore-template`).
- Optional MMOCore class exp bridge on matched rewards:
  - configurable `per-craft` + `per-item` amounts
  - per-recipe overrides
  - command placeholders: `%player%`, `%player_name%`, `%uuid%`, `%recipe%`, `%amount%`, `%amount_int%`

## Build
```powershell
cd E:\Minecraft\12121\purpur第四版\LiteCookingMakerBridge
.\build.ps1
```

This compiles and copies:
- `build/LiteCookingMakerBridge-1.0.0.jar`
- `server/plugins/LiteCookingMakerBridge.jar`
