[English](index_en.md)|中文

## ShowUIList

- 显示 `icon`, `tex`, `styles`, `colors`, `interps`\
  ![](./screenshots/UIList.png)

## Tester

![](./screenshots/tester.png)

- 提供 `JS` 编辑器 `Tester`
- 快捷键
  - `Ctrl`+`Shift`+`Enter` 立即`执行`代码
  - `Ctrl`+`Shift`+`↑/↓` 切换`历史`记录
  - `Ctrl`+`Shift`+`D` `查看`详细信息
  - `Alt`+`V` `展示`图集和元素？
- 内置 `unsafe`, `lookup`
- 内置 `IntFunc` 类 (缩写`$`)
  - `$.xxx` 可以表示基本数据类型 (e.g `$.void` 表示 `Void.TYPE`;`$.J` 表示 long.class)
  - `$p` 代表 `Packages`
- Code: [JSFunc](https://github.com/i-hope1/mod-tools/src/modtools/utils/JSFunc.java)
- 长按收藏夹里的代码，可以添加到启动项\
  ![](./screenshots/startup.png)
- 快捷切换历史\
  ![截图2024-03-10 14-45-37](https://github.com/I-hope1/mod-tools/assets/78016895/4918af35-19af-4fab-b961-70bdc8679fe8)
- `$.item`, `$.liquid`, `$.unit`等

## UnitSpawn

- 多队伍选择
- 支持定点生成
- 显示`name`和`localizedName`\
  ![unitSpawn](./screenshots/unit_spawn.png)
  ![unitSpawnPoint](./screenshots/unitspawnpoint.gif)

## Selection

- 选择器
- 支持 `Tile`, `Building`, `Bullet`, `Unit`\
  ![selection](./screenshots/selection.png)

- `Ctrl`+`Alt`固定Focus Window

## ReviewElement

- 显示元素列表，双击复制元素到js变量
- `Ctrl`+`Shift`+`C`来审查元素
- `Ctrl`+`Alt`+`D`来显示元素边界
- 选择不可触摸的元素
    - 移动端：双指过滤当前元素
    - 电脑端：按`F`过滤当前元素
- 元素快捷键
    - `i`: 显示详情（打开ShowInfoWindow)
    - `p`(for Image): 打开`DrawablePicker`
    - `del`:（`shift`不显示确认）删除元素
    - `<` / `>`: 折叠Group
    - `f`: 固定悬浮Info栏 
    - `r`: 运行函数
- ![reviewElement](./screenshots/review_element.png)

## Frag

- 双击Frag的蓝色部分，缩小/恢复 Frag
- 在缩小状态下，点击蓝色部分，类似悬浮球

## Window

- `Ctrl`+`Tab` 切换窗口
- `Shift`+`F4` 关闭当前窗口
- `右键` 关闭按钮，移动和缩放窗口

## ShowInfoWindow

- `‘null` 表示null字符串
- `Ctrl`+`F` 聚焦搜索框
- `Ctrl`+`Shift`+`F` 聚焦搜索框并清空

## 其他

### 扩展

- Override Scene

> 替换原始的scene，捕获渲染报错，可能不好

- Http Redirect

> 重定向一些网站，例如：github\
> 配置文件: b0kkihope/http_redirect.properties
