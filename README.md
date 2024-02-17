[English](README_en.md)|中文

<div align="center"><h1>Mod Tools</h1>
  <a href="https://github.com/i-hope1/mod-tools/releases"><img src="https://img.shields.io/github/v/release/i-hope1/mod-tools?style=flat-square&include_prereleases&label=version" /></a>
  <a href="https://github.com/i-hope1/mod-tools/releases"><img src="https://img.shields.io/github/downloads/i-hope1/mod-tools/total.svg?style=flat-square" /></a>
  <a href="https://github.com/i-hope1/mod-tools/releases"><img src="https://img.shields.io/github/downloads/i-hope1/mod-tools/latest/total?style=flat-square" /></a>
  <a href="https://github.com/i-hope1/mod-tools/issues"><img src="https://img.shields.io/github/issues-raw/i-hope1/mod-tools.svg?style=flat-square&label=issues" /></a>
  <a href="https://github.com/i-hope1/mod-tools/graphs/contributors"><img src="https://img.shields.io/github/contributors/i-hope1/mod-tools?style=flat-square" /></a>
  <a href="https://github.com/i-hope1/mod-tools/blob/master/LICENSE"><img src="https://img.shields.io/github/license/i-hope1/mod-tools?style=flat-square" /></a>
</div>

一个mindustry v7的mod.
它为开发人员提供了许多有用的工具。

<div align="center"><img src="./assets/icon.png" width=50%></div>

## ShowUIList

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
- `$p` 代表 `Packages`
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
- `Ctrl`+`Alt`固定Focus Window

## ReviewElement

- 显示元素列表，双击复制元素到js变量
- 选择不可触摸的元素
- + 移动端：双指过滤当前元素
  + 电脑端：按`F`过滤当前元素
- 对着元素按`i`，显示详情（打开ShowInfoWindow)
  ![reviewElement](./screenshots/review_element.png)

## Window

- `Ctrl`+`Tab` 切换窗口
- `Shift`+`F4` 关闭当前窗口

## ShowInfoWindow

- `‘null` 表示null字符串
- `Ctrl`+`F` 聚焦搜索框


## 其他
### 扩展

- Override Scene
> 替换原始的scene，捕获渲染报错，可能不好

- Http Redirect
> 重定向一些网站，例如：github\
> 配置文件: b0kkihope/http_redirect.properties