# LiteCookingMakerBridge

[English](#english) | [简体中文](#简体中文)

LiteCookingMakerBridge is a Paper bridge plugin for LiteCooking and MMOCore.

LiteCookingMakerBridge 是一个面向 LiteCooking 与 MMOCore 的 Paper 桥接插件。

---

## English

LiteCookingMakerBridge is a standalone Bonfire bridge plugin that writes maker attribution onto LiteCooking rewards and can optionally grant MMOCore class experience.

### What It Does

- Tracks LiteCooking workstation sessions through a bridge layer.
- Matches generated reward items back to the original crafter.
- Writes maker UUID, player name, timestamp, and display lore onto the result item.
- Supports optional MMOCore experience rewards with per-recipe control.

### Repository Layout

- `src/`: plugin source code
- `build.ps1`: local build helper
- `build/`: local build output, excluded from release tracking

### Build

```powershell
.\build.ps1
```

### License

This repository currently uses the `Bonfire Non-Commercial Source License 1.0`.
See [LICENSE](LICENSE) for the exact terms.

---

## 简体中文

LiteCookingMakerBridge 是 Bonfire 的独立桥接插件，用来把 LiteCooking 奖励产物与实际制作者重新关联，并可选联动 MMOCore 职业经验。

### 它解决的问题

- 跟踪 LiteCooking 工作台流程中的制作者上下文。
- 将生成的奖励物品准确回写到原始制作玩家。
- 为成品写入制作者 UUID、名称、时间与展示 lore。
- 支持按配方或规则给出可选的 MMOCore 经验奖励。

### 仓库结构

- `src/`：插件源码
- `build.ps1`：本地构建脚本
- `build/`：本地构建输出，默认不纳入发布源码

### 构建方式

```powershell
.\build.ps1
```

### 授权

本仓库当前采用 `Bonfire Non-Commercial Source License 1.0`。
具体条款见 [LICENSE](LICENSE)。
