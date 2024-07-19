English|[中文](index.md)

## ShowUIList

- Display `icon`, `tex`, `styles`, `colors`, `interps`\
  ![](./screenshots/UIList.png)

## Tester

![](./screenshots/tester.png)

- Provide `JS` editor `Tester`
- Shortcuts
    - Press `Ctrl`+`Shift`+`Enter` to `execute` code immediately
    - Press `Ctrl`+`Shift`+`↑/↓` to switch `history` records
    - Press `Ctrl`+`Shift`+`D` to `view` detailed information
    - Press `Alt`+`V` to preview `Texture`
- Built-in `unsafe`, `lookup`
- Built-in `IntFunc` class (Alias as `$`)

| Alias                                 | Object/Expression              | Description                                                     |
|---------------------------------------|--------------------------------|-----------------------------------------------------------------|
| IntFunc                               | $                              | -                                                               |
| $p                                    | Packages                       | -                                                               |
| $.J, $.I, ...                         | long.class, int.class          | Encoding of Primitive Type                                      |
| $.long<br/>$.int<br>...               | long.class, int.class          | -                                                               |
| $.duo, $.copper                       | Blocks.duo, Items.copper       | Get a content by name                                           |
| $.items, $.liquids, ...               | Items, Liquids, ...            | -                                                               |
| $.item(name/id), $.unit(name/id), ... | Content which has the name/id  | Get a content by name/id                                        |
| $.forEach(list, func)                 | for (let v of list) { ... }    | Mindustry's RhinoJS is not supported the for-of for java object |
| $.toArray(iterable)                   | [...iterable]                  | Convert a object to array                                       |
| $.range(int), ... (like python)       | a generator (from -> to, step) | Like python                                                     |
| $.dialog(text/drawable/texture)       | _                              | View the text/drawable/texture                                  |

- Code: [JSFunc](https://github.com/i-hope1/mod-tools/src/modtools/utils/JSFunc.java)
- Long press on code in the favorites to add to startup items\
  ![](./screenshots/startup.png)
- Quick switch history\
  ![Screenshot 2024-03-10 14-45-37](https://github.com/I-hope1/mod-tools/assets/78016895/4918af35-19af-4fab-b961-70bdc8679fe8)
- `$.item`, `$.liquid`, `$.unit` and so on.

## UnitSpawn

- Multiple team selection
- Support for fixed point spawning
- Display `name` and `localizedName`\
  ![unitSpawn](./screenshots/unit_spawn.png)
  ![unitSpawnPoint](./screenshots/unitspawnpoint.gif)
- R-click/LongPress the name Label to copy save as JS var.

## Selection

- Selector
- Supports `Tile`, `Building`, `Bullet`, `Unit`\
  ![selection](./screenshots/selection.png)

- Press `Ctrl`+`Alt` to fix Focus Window

## ReviewElement

- Display element list, double-click to copy element to js variable
- `Ctrl`+`Shift`+`C` to Inspect Element
- `Ctrl`+`Alt`+`D` to display bounds of Element
- Select untouchable elements
    - Mobile: Filter current element with two fingers
    - PC: Press `F` to filter current element
- Functions shortcuts for Element
    - `i`: display details (open ShowInfoWindow)\
    - `p`(for Image):  show `DrawablePicker`
    - `del`: (`shift` to ignore confirmation), delete element
    - `<` / `>`: collapse Group
    - `f`: fix floating Info column
    - `r`: invoke element's method

- ![reviewElement](./screenshots/review_element.png)

# Frag

- Double-click the blue part of Frag to minimize/restore Frag
- In the minimized state, click the blue part, it will behave like a floating ball

## Window

- Press `Ctrl`+`Tab` to switch windows
- Press `Shift`+`F4` to close current window
- RClick the close button to move and scl window.

## ShowInfoWindow

- `'null` represents `null` pointer
- Press `Ctrl`+`F` to focus search box
- Press `Ctrl`+`Shift`+`F` to focus search box and clear search box

## Others

### Extensions

- Override Scene

> Replace the original scene, capture rendering errors, may not be good

- Http Redirect

> Redirect some websites, such as: github\
> Configuration file: b0kkihope/http_redirect.properties
