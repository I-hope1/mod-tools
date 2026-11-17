[简体中文](index.md) | English

# Mod Tools Documentation

> A developer toolbox for **Mindustry v7**. This page is a navigation hub; each tool has its own topic page.

New here? Start with [Installation & quick start](https://github.com/I-hope1/mod-tools/blob/main/README_en.md#installation).

## Tool index

| Tool | In one line | Docs |
|:--|:--|:--|
| **Tester** | In-game JS editor/executor with history, startup scripts and the `$` shortcut API | [01-tester.md](features/01-tester.md) |
| **Executor** | Task-tree executor: run, loop, interrupt, status filters and custom JS tasks | [02-executor.md](features/02-executor.md) |
| **Selection** | Select tiles/buildings/bullets/units and run commands on the selection | [03-selection.md](features/03-selection.md) |
| **UnitSpawn** | Spawn units by team, position and amount; submit as an Executor task | [04-unit-spawn.md](features/04-unit-spawn.md) |
| **ReviewElement** | Inspect the UI tree, edit fields live, run layout checks, copy as JS | [05-review-element.md](features/05-review-element.md) |
| **ShowUIList / ShowInfoWindow** | Browse UI resources and inspect any object | [06-ui-inspector.md](features/06-ui-inspector.md) |
| **Frag / Window / WindowManager** | Window switching, minimizing, snapping and force-show | [07-windows.md](features/07-windows.md) |
| **ContentList / LogDisplay / Profiler / Pause** | Content browser, in-game logs, profiling, experimental pause | [08-misc.md](features/08-misc.md) |
| **Extensions** | Override Scene and HTTP redirect | [09-extensions.md](features/09-extensions.md) |

## Hot reload

Since 1.7, hot reload is provided by [Remold](https://github.com/I-hope1/Remold). On desktop JBR it watches compiled output directories and swaps `.class`/resource changes into the running game in place.

- Enablement, watch paths and settings: [hotswap.md](hotswap.md)
- Full architecture, limits and status: [Remold README](https://github.com/I-hope1/Remold#readme)

## Development

- Repository layout, JDK version, build/run/test commands: [development.md](development.md)
- Build and CI configuration: [build.gradle](https://github.com/I-hope1/mod-tools/blob/main/build.gradle), [.github/workflows](https://github.com/I-hope1/mod-tools/tree/main/.github/workflows)

## Screenshots

| | |
|:--:|:--:|
| ![Tester](screenshots/tester.png) | ![Selection](screenshots/selection.png) |
| ![ReviewElement](screenshots/review_element.png) | ![UnitSpawn](screenshots/unit_spawn.png) |

> Topic pages are currently maintained in Chinese; this page and the README provide the English overview.
