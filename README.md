# ClaudeCraft

A client-side Minecraft 1.12.2 mod that opens a local text socket so an AI agent or script can play: look, move, mine, build, craft, trade, fight, drive menus, take screenshots, read performance and sweep every creative tab for broken items.

## About

ClaudeCraft listens on `127.0.0.1:25599`. A controller connects, sends one command per line, and gets one reply line back. Every command runs on the Minecraft client thread, so it acts exactly like a player at the keyboard: angles are set directly (no fighting the mouse cursor), keys are pressed through the real key bindings, and clicks go through the normal player controller packets. Because of that, it works in singleplayer and on multiplayer servers.

It was built for testing and automating the Pride modpack, a Minecraft 1.12.2 pack of around 750 mods — walking an AI through menus, checking builds, reproducing crashes, and sweeping every creative tab for missing textures and tooltip crashes. It has no dependencies and works in any 1.12.2 Forge or Cleanroom pack.

It uses no access transformers and no mixins. The only reflection is on `GuiScreen` (button list, `actionPerformed`, `keyTyped`, `mouseClicked`/`mouseReleased`) and the creative inventory, for driving open screens.

## Safety

- The socket is bound to **127.0.0.1 only**. It is not reachable from other machines.
- There is **no authentication**: any program running on the same computer can connect and control the player. Only install it on a machine you trust, and remove it from packs you hand out to players.
- Any command makes the game stop pausing when the window loses focus (`pauseOnLostFocus = false`), so a remotely driven game keeps running in the background.
- Actions go through normal client packets, so server-side permissions and anti-cheat still apply on multiplayer servers.

## Protocol

```
$ nc 127.0.0.1 25599
ping
pong
state
{"world":true,"x":12.50,"y":64.00,"z":-3.50,"yaw":90.00,"pitch":0.00,"health":20.00,"slot":0,"held":"Stone Pickaxe x1","looking":"minecraft:stone @ 11,63,-3 face=up"}
lookat 11.5 63.5 -3.5
OK lookat yaw=... pitch=...
```

- One command per line, words separated by spaces; one reply line per command.
- Replies start with `OK` on success, `ERR` on failure; information commands return plain text or a small JSON object.
- Connections are served one at a time (later connections wait until the current one closes).
- Long actions (`goto`, `mine`, `fight`, `fish`, …) block until they finish, then reply.
- Key names: the built-in names `forward/w`, `back/s`, `left/a`, `right/d`, `jump/space`, `sneak/shift`, `sprint`, `attack`, `use`, `inventory/e`, `drop/q`; any key binding by its description (e.g. `ir_keys.increase_throttle`); or any raw LWJGL key name (`F3`, `T`, `LCONTROL`, `UP`, `NUMPAD8`, …).
- Item queries match any part of an item's display name or registry id (`all` matches everything).

## Commands

### Core (camera, keys, chat, state)

| Command | Arguments | Description |
|---|---|---|
| `ping` | — | Replies `pong`. |
| `help` | — | Lists the command verbs. |
| `state` | — | JSON: position, yaw/pitch, health, hotbar slot, held item, and the block or entity under the crosshair. |
| `look` | `<yaw> <pitch>` | Set absolute look angles (pitch clamped to ±90). |
| `turn` | `<dyaw> <dpitch>` | Turn relative to the current angles. |
| `lookat` | `<x> <y> <z>` | Aim the crosshair at a world point (e.g. a block centre). |
| `key` | `<name> <down\|up>` | Press or release a key (`on`/`1` also mean down). |
| `tap` | `<name>` | Press for ~120 ms and release; also fires the key-input event so mod keybinds react. |
| `hold` | `<name> <ms>` | Hold a key for a time (max 30 s). |
| `combo` | `<key1> <key2> … [ms]` | Press several keys together as a chord (default 120 ms, max 30 s). |
| `jump` | — | One jump. |
| `slot` / `hotbar` | `<0-8>` | Select a hotbar slot. |
| `place` / `use` / `rclick` | — | Right-click what the crosshair is on (block, entity — interact-at first, then plain — or use the held item). |
| `break` / `attack` / `lclick` | — | Left-click what the crosshair is on (start breaking a block, or attack an entity). |
| `chat` / `cmd` | `<text…>` | Send a chat line or a `/command` to the server. |
| `chatbox` | `<text…>` | Send text exactly as the chat box's Enter would: mod chat hooks, client-side commands, then the server. |
| `log` | `[n]` | Last `n` chat and action-bar lines (default 10, keeps 50). |
| `stop` | — | Release all movement, jump, sprint, sneak, attack and use keys. |

### Key bindings and debug

| Command | Arguments | Description |
|---|---|---|
| `binds` | `[filter]` | List key bindings as `name=key`, optionally filtered. |
| `rebind` | `<bind name> <KEY_NAME>` | Rebind a key binding to an LWJGL key name and save options. |
| `reload` | — | Reload all resources and re-bake models (same as F3+T). |
| `debug` | `<t\|reload\|a\|chunks\|b\|hitboxes\|d\|clearchat>` | F3+T reload resources, F3+A reload chunks, F3+B toggle hitboxes, F3+D clear chat. |
| `perf` | — | One line: FPS, integrated-server tick ms and TPS, chunk stats, entity count, memory used/max. |
| `shot` | — | Save a screenshot to `screenshots/claudecraft.png` in the game folder. |

### Screens and menus

| Command | Arguments | Description |
|---|---|---|
| `gui` | — | JSON for the open screen: class, size, container slots with items, and buttons (`id:label`, `(off)` if disabled). |
| `button` | `<id>` | Press a button on the open screen (ids from `gui`). |
| `click` | `<slot#> [left\|right\|shift]` | Click a slot in the open container screen. |
| `mclick` | `<x> <y> [left\|right]` | Click the open screen at GUI coordinates (moves the pointer there first, renders a hover pass, then clicks). |
| `mhover` | `<x> <y>` | Move the pointer over a spot without clicking, so tooltips render. |
| `tip` | `[slot#\|hand]` | Full tooltip text of an item (container slot, inventory slot, or the held item; default `hand`). |
| `type` | `<text…>` | Type text into the open screen (text fields, chat). |
| `enter` | — | Press Enter in the open screen. |
| `esc` | — | Send an Esc key press to the open screen's own key handler. |
| `close` | — | Close the open screen. |
| `pause` | — | Open the pause menu, as Esc does in a world. |

### Worlds

| Command | Arguments | Description |
|---|---|---|
| `worlds` | — | List saved singleplayer worlds as `folder = display name`. |
| `world` | `<folder>` | Load a saved world from the title screen (folder names may contain spaces). |
| `newworld` | `<folder> [flat\|default] [creative\|survival]` | Create and enter a new world with cheats on (default: flat, creative). |
| `quit` | — | Save and quit to the title screen. |

### Information

| Command | Arguments | Description |
|---|---|---|
| `status` | — | JSON: health, max health, food, saturation, air, XP level, armour, game mode, dimension, biome, time, rain/thunder, light, riding, sleeping, offhand, effects, position. |
| `players` | — | Players in the tab list with ping. |
| `inv` | — | Player inventory as `slot:item xN`. |
| `entities` | `[radius]` | Nearby entities (default 32): id, name, type, position, distance, `RIDING` marker. |
| `blocks` | `<x1> <y1> <z1> <x2> <y2> <z2>` | Non-air blocks in a box (max 4096 blocks) as `x,y,z=id`, to check a build. |
| `find` | `<text> [radius]` | Nearest blocks whose id contains the text (default radius 24, max 48; top 10). |
| `recipes` | `<text>` | Up to 15 crafting recipes whose output matches, marked `(2x2)` or `(table)`. |

### Movement

| Command | Arguments | Description |
|---|---|---|
| `goto` | `<x> <y> <z> [sprint]` | Walk there with A* pathfinding (walk, step up 1, drop up to 3; avoids lava). Reports arrival or the closest reachable spot, or `STUCK`. |
| `follow` | `<player> [seconds]` | Keep walking to a player for a time (default 30 s); sprints when more than 8 blocks away. |
| `come` | `<player>` | Walk to a player once. |
| `dismount` | — | Get off whatever you're riding. |

### Blocks

| Command | Arguments | Description |
|---|---|---|
| `mine` | `<x> <y> <z>` | Walk close if needed, pick the best hotbar tool, and break the block (gives up after 30 s). |
| `minearea` | `<x1> <y1> <z1> <x2> <y2> <z2>` | Mine every block in a box, top layer first (max 4096). |
| `placeat` | `<x> <y> <z> [item]` | Optionally select an item, walk close, and place it against a solid neighbour. |
| `open` | `<x> <y> <z>` | Right-click a block (chest, furnace, machine, crafting table, door, lever…). |
| `sleep` | `<x> <y> <z>` | Right-click a bed. |
| `wake` | — | Leave the bed. |

### Items and containers

| Command | Arguments | Description |
|---|---|---|
| `select` | `<item>` | Put the first matching item in hand (swaps it into the hotbar from the main inventory if needed). |
| `equip` | — | Put on any armour from the inventory for empty armour slots. |
| `eat` | `[item]` | Eat the named food, or any food; reports the hunger change. |
| `craft` | `<item> [times]` | Craft through the recipe book (the server pulls ingredients), then shift-click the result. 3×3 recipes need an open crafting table. |
| `take` | `[item\|all]` | Shift-click matching stacks out of the open container. |
| `put` | `[item\|all]` | Shift-click matching stacks from your inventory into the open container. |
| `move` | `<from> <to> [count]` | Move a stack, or `count` single items, between slots of the open screen. |
| `toss` | `<item>` | Drop every matching stack. |
| `swap` | — | Swap main hand and offhand. |
| `enchant` | `<0-2>` | Pick an enchanting-table option in the open enchanting screen. |

### Villagers

| Command | Arguments | Description |
|---|---|---|
| `useent` | `<id>` | Right-click an entity by id without aiming (open a villager, board a cart or train). |
| `trades` | — | List the open villager's trades with index, costs, result and sold-out state. |
| `trade` | `<index> [times]` | Select a trade and take the result `times` times. |

### Combat and fishing

| Command | Arguments | Description |
|---|---|---|
| `hit` | `<id>` | Aim at an entity by id and attack it once. |
| `lookent` | `<id>` | Aim at an entity by id. |
| `fight` | `[radius] [seconds] [all]` | Fight the nearest hostile mobs (or any non-player living entity with `all`) for a time (defaults 8 blocks, 20 s): walks closer, aims gradually with slight wobble, swings only at full attack strength. |
| `fish` | `[seconds]` | Cast, wait for the bobber to dip, reel in, repeat (default 60 s). |
| `respawn` | — | Respawn after death. |

### Creative sweep

| Command | Arguments | Description |
|---|---|---|
| `sweep` | — | Open the creative inventory and go through every tab of every mod, scrolling every page so each item is actually drawn. Needs creative mode. |
| `sweepstatus` | — | Progress: current tab, items checked, pink, invisible, tooltip/model crashes, seconds. |

For every item the sweep reads the display name and the advanced tooltip, and checks the item model. Problems are caught and written down instead of closing the game:

- `TOOLTIP CRASH` — the tooltip threw an exception (with the cause).
- `MODEL CRASH` — getting the model threw.
- `PINK (no model)` / `PINK (texture)` — the missing model, or a quad using the missing-texture sprite.
- `INVISIBLE` — a model with no quads (items drawn by code are skipped).
- Tabs that can't be opened, and tab names that aren't translated, are noted too.

The report is written to `logs/claudecraft-sweep.txt`. A crash while *drawing* an item still crashes the game, but its crash report then names the item.

## Config

None. The port (`25599`) and bind address (`127.0.0.1`) are fixed. ClaudeCraft adds no in-game commands and no key bindings.

## Requirements

- Minecraft 1.12.2
- Forge 14.23.5.2860 or newer, or Cleanroom
- Client side only (`clientSideOnly = true`); servers don't need it

## Install

1. Drop `ClaudeCraft-1.12.2-0.1.0.jar` into the client's `mods` folder.
2. Start the game. The log shows `[ClaudeCraft] command server listening on 127.0.0.1:25599`.
3. Connect with any TCP client (`nc 127.0.0.1 25599`, a Python socket, an agent tool) and send commands.

Minimal Python controller:

```python
import socket
s = socket.create_connection(("127.0.0.1", 25599))
f = s.makefile("rw", encoding="utf-8")
def cc(cmd):
    f.write(cmd + "\n"); f.flush()
    return f.readline().strip()
print(cc("state"))
print(cc("goto 100 64 -20"))
```

## Building

```
./gradlew build
```

The jar is written to `build/libs/`. The project targets Java 8 (ForgeGradle 3, MCP snapshot `20171003-1.12`).

## License

MIT License — © 2026 crunkazcanbe

## Credits

Made by crunkazcanbe, with Claude.
