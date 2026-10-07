# Changelog

## 0.2.0 - 2026-10-07

### Added
- `bigshot [w h] [hud] [fov=N] [name.png]`: renders an off-screen, high-resolution screenshot (default 1920x1080, FOV 50, HUD hidden unless `hud` is given) without resizing the game window. Saved to `screenshots/` (default `claudecraft_big.png`).
- `mclick` now selects rows in scroll lists (vanilla `GuiSlot`, Forge `GuiScrollingList` and copies such as OTG's world-type list), which ignore synthetic mouse clicks. Add `double` to double-click a row.

### Fixed
- Commands now run on the next client tick instead of inside the scheduled-task lock. Pressing buttons that start a world (for example **Create World**) and the `world` command no longer deadlock the game.

