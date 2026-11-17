English | [简体中文](README.md) · [Docs site](https://i-hope1.github.io/mod-tools/) · [Docs source](docs/index_en.md) · [Hot reload (Remold)](https://github.com/I-hope1/Remold) · [Releases](https://github.com/i-hope1/mod-tools/releases)

<div align="center"><h1>Mod Tools</h1>
  <a href="https://github.com/i-hope1/mod-tools/releases"><img src="https://img.shields.io/github/v/release/i-hope1/mod-tools?style=flat-square&include_prereleases&label=version" /></a>
  <a href="https://github.com/i-hope1/mod-tools/releases"><img src="https://img.shields.io/github/downloads/i-hope1/mod-tools/total.svg?style=flat-square" /></a>
  <a href="https://github.com/i-hope1/mod-tools/releases"><img src="https://img.shields.io/github/downloads/i-hope1/mod-tools/latest/total?style=flat-square" /></a>
  <a href="https://github.com/i-hope1/mod-tools/issues"><img src="https://img.shields.io/github/issues-raw/i-hope1/mod-tools.svg?style=flat-square&label=issues" /></a>
  <a href="https://github.com/i-hope1/mod-tools/graphs/contributors"><img src="https://img.shields.io/github/contributors/i-hope1/mod-tools?style=flat-square" /></a>
  <a href="https://github.com/i-hope1/mod-tools/blob/master/LICENSE"><img src="https://img.shields.io/github/license/i-hope1/mod-tools?style=flat-square" /></a>
</div>

**Mod Tools** is a developer toolbox for **Mindustry v7**: an in-game JavaScript editor, UI inspector, content browser, unit spawner, task executor and hot-reload workflow. It is built for people who tweak UI, try Mindustry APIs and write startup scripts without restarting the game every time.

<div align="center"><img src="./assets/icon.png" width="240" alt="Mod Tools"></div>

## What's inside

| Module | What it does | Docs |
|:--|:--|:--|
| **Tester** | In-game Rhino JS editor/executor: history, startup scripts, `$` shortcuts, completion and syntax highlighting | [Tester](docs/features/01-tester.md) |
| **Executor** | Task-tree executor: run once, loop, interrupt, edit; custom JS tasks; status filters; startup-script and UnitSpawn integration | [Executor](docs/features/02-executor.md) |
| **Selection** | Select `Tile` / `Building` / `Bullet` / `Unit` and run built-in commands on the selection | [Selection](docs/features/03-selection.md) |
| **UnitSpawn** | Spawn units by team, position and amount; submit the spawn setup as an Executor task | [UnitSpawn](docs/features/04-unit-spawn.md) |
| **ReviewElement** | Inspect the UI element tree, edit fields live, copy elements as JS variables, run layout checks and inspect Cells | [ReviewElement](docs/features/05-review-element.md) |
| **ShowUIList / ShowInfoWindow** | Browse icons, textures, styles, colors, interps and actions; inspect any object and custom viewers | [UI tools](docs/features/06-ui-inspector.md) |
| **Windows** | Frag, Window and WindowManager: switch, minimize, snap and force-show windows | [Windows](docs/features/07-windows.md) |
| **ContentList / LogDisplay / Profiler / Pause** | Content browser, in-game logs, method probes/flame graph/deadlock checks and an experimental pause tool | [Other tools](docs/features/08-misc.md) |
| **Extensions** | Override Scene and HTTP redirect | [Extensions](docs/features/09-extensions.md) |
| **Hot reload** | Swap compiled classes into a running game on desktop JBR, powered by [Remold](https://github.com/I-hope1/Remold) | [Hot reload](docs/hotswap.md) |

## Installation

1. Download the latest `mod-tools-*.jar` from [Releases](https://github.com/i-hope1/mod-tools/releases).
2. Put it into Mindustry's `mods/` directory (the in-game mod page can open that folder).
3. Restart the game, enable **mod-tools** and open the tools from the mod-tools menu.

Requires **Mindustry v7** (`minGameVersion` 159.2). Desktop is fully supported; Android also works, but hot reload and a few desktop-only tools are unavailable. Hot reload additionally requires **JetBrains Runtime (JBR 17/21/25+)**, see [Hot reload](docs/hotswap.md).

## Quick start

Open **Tester** and run:

```js
Log.info("Hello from mod-tools!");
```

Press `Ctrl` + `Shift` + `Enter` to execute. To see how a button or label is built, press `Ctrl` + `Shift` + `C` to open **ReviewElement**, then `Ctrl` + `Alt` + `D` to show element bounds.

The full documentation hub is [here](docs/index_en.md).

## Documentation

| Document | Contents |
|:--|:--|
| [Documentation index](docs/index_en.md) | Entry point for all topic pages |
| [Tester](docs/features/01-tester.md) | JS editor, shortcuts, `$` API, startup scripts |
| [Executor](docs/features/02-executor.md) | Task states, run/loop/interrupt, status filters, custom tasks |
| [Selection](docs/features/03-selection.md) / [UnitSpawn](docs/features/04-unit-spawn.md) | Selector and unit spawning |
| [ReviewElement](docs/features/05-review-element.md) | UI inspection, live field editing, layout checks |
| [UI tools](docs/features/06-ui-inspector.md) / [Windows](docs/features/07-windows.md) | ShowUIList, ShowInfoWindow, Frag/Window |
| [Other tools](docs/features/08-misc.md) / [Extensions](docs/features/09-extensions.md) | Content browser, logs, Profiler, Pause, Override Scene, etc. |
| [Hot reload](docs/hotswap.md) | Remold setup and settings |
| [Development](docs/development.md) | Repository layout, build, run and tests |

> Topic pages are currently maintained in Chinese (matching the upstream docs); this English page and the English index cover the overview.

## Building

Requires **JDK 25**. Build the desktop jar:

```bash
./gradlew jar
```

Release package (also includes Android `classes.dex`; requires `ANDROID_HOME`):

```bash
./gradlew ZCompileAll
```

For local development, point `MdtDataDir` in `gradle.properties` at your Mindustry install/data directory, then:

```bash
./gradlew playGame          # build, copy to mods and launch the game
./gradlew test              # JUnit
./gradlew playGameDirect    # launch with the already deployed jar
```

Some code depends on JavaDoc (annotation processors read comments), so **do not remove JavaDoc**. More details in [Development](docs/development.md).

## Hot reload

Since 1.7, hot reload is provided by [Remold](https://github.com/I-hope1/Remold): `remold-agent.jar` is attached to the system classloader at startup and `remold-plugin-arc` hooks Arc UI hot-swap, texture/asset refresh and InitFix. On desktop, enable `hot_swap` and configure the watch paths; see [Hot reload](docs/hotswap.md).

## License

[GNU Affero General Public License v3.0](LICENSE). The Remold hot-reload dependency is distributed under its own per-module licenses.
