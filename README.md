[English](README_en.md)|[中文](README.md)

# Mod Tools

A mod for mindustry v7.
It provides many useful tools for developers.

![icon.png](./assets/icon.png)

## showUIList

- 显示 `icon`, `tex`, `styles`, `colors`, `interps`

## Tester

![](./screenshots/tester.png)

- 提供 `JS` 编辑器 `Tester`
- - `Ctrl`+`Shift`+`Enter` 立即执行代码
- - `Ctrl`+`Shift`+`↑/↓` 切换历史记录
  - `Ctrl`+`Shift`+`D` 查看详细信息
- 内置 `unsafe`, `lookup`
- 内置 `IntFunc` 类 (缩写`$`)
- + `$.xxx` 可以表示基本数据类型 (e.g `$.void` 表示 `Void.TYPE`;`$.J` 表示 long.class)
- `$p` 代表 `Packages
- [JSFunc](src/modtools/utils/JSFunc.java)

- 长按收藏夹里的代码，可以添加到启动项
  ![](./screenshots/startup.png)
-

## UnitSpawn

- 多队伍选择
- 支持定点生成
- 显示`name`和`localizedName`
  ![unitSpawn](./screenshots/unit_spawn.png)

## Selection

- 选择器
- 支持 `Tile`, `Building`, `Bullet`, `Unit`
  ![selection](./screenshots/selection.png)

## ReviewElement

显示元素列表，双击复制元素到js变量
![reviewElement](./screenshots/review_element.png)

## Window

- `Ctrl`+`Tab` 切换窗口
- `Shift`+`F4` 关闭当前窗口

## ShowInfoWindow

- `‘null`表示null字符串
