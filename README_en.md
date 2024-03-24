English|[中文](README.md)

<div align="center"><h1>Mod Tools</h1>
  <a href="https://github.com/i-hope1/mod-tools/releases"><img src="https://img.shields.io/github/v/release/i-hope1/mod-tools?style=flat-square&include_prereleases&label=version" /></a>
  <a href="https://github.com/i-hope1/mod-tools/releases"><img src="https://img.shields.io/github/downloads/i-hope1/mod-tools/total.svg?style=flat-square" /></a>
  <a href="https://github.com/i-hope1/mod-tools/releases"><img src="https://img.shields.io/github/downloads/i-hope1/mod-tools/latest/total?style=flat-square" /></a>
  <a href="https://github.com/i-hope1/mod-tools/issues"><img src="https://img.shields.io/github/issues-raw/i-hope1/mod-tools.svg?style=flat-square&label=issues" /></a>
  <a href="https://github.com/i-hope1/mod-tools/graphs/contributors"><img src="https://img.shields.io/github/contributors/i-hope1/mod-tools?style=flat-square" /></a>
  <a href="https://github.com/i-hope1/mod-tools/blob/master/LICENSE"><img src="https://img.shields.io/github/license/i-hope1/mod-tools?style=flat-square" /></a>
</div>

A mod for mindustry v7.
It provides many useful tools for developers.

## ShowUIList

- Display `icon`, `tex`, `styles`, `colors`, `interps`

## Tester

![](./docs/screenshots/tester.png)

- Provides `JS` code editor `Tester`
  - Press `Ctrl`+`Shift`+`Enter` to execute code immediately
  - Press `Ctrl`+`Shift`+`↑/↓` to switch through history records
  - Press `Ctrl`+`Shift`+`D` to view detailed information
- Built-in `unsafe`, `lookup`
- Built-in `IntFunc` class (abbreviated as `$`)
  - Use `$.xxx` to represent basic data types (e.g., `$.void` represents `Void.TYPE`; `$.J` represents `long.class`)
  - `$p` represents `Packages`
- [JSFunc](src/modtools/utils/JSFunc.java)
- Long press on code in the favorites to add it to the startup items\
![](./docs/screenshots/startup.png)
- Quick switching history\
![截图2024-03-10 14-45-37](https://github.com/I-hope1/mod-tools/assets/78016895/4918af35-19af-4fab-b961-70bdc8679fe8)

## UnitSpawn

- Multiple team selection
- Supports spawning at specific points
- Displays `name` and `localizedName`\
![unitSpawn](./docs/screenshots/unit_spawn.png)

## Selection

- Selector
- Supports `Tile`, `Building`, `Bullet`, `Unit`\
![selection](./docs/screenshots/selection.png)
- `Ctrl`+`Alt` to fixed `Focus Window`

## ReviewElement

- Displays a list of elements, double-click to copy the element to a js variable
- Select untouchable elements.
- + Mobile: Touch screen with another finger to filter the current element.
  + Computer: Press ` F` to filter the current element.
- Press `i` on the element to display the details (Open the `ShowInfoWindow`).\
![reviewElement](./docs/screenshots/review_element.png)

## Window

- Press `Ctrl`+`Tab` to switch windows
- Press `Shift`+`F4` to close the current window

## ShowInfoWindow

- Use `'null` to represent the null string
- `Ctrl`+`F` Focus search field

## Other
### Extending

- Override Scene
> Replace the original scene and capture the rendering error. (Maybe not good)

- Http Redirect
> Redirect some websites, such as github.\
> Config file: b0kkihope/http_redirect.properties
