[English](index_en.md)|中文

## ShowUIList

- 显示 `icon`, `tex`, `styles`, `colors`, `interps`

## Tester
![image](https://github.com/I-hope1/mod-tools/assets/78016895/08dd4a6f-025e-429c-9567-1f133fa82ad9)

- 提供 `JS` 编辑器 `Tester`
- - `Ctrl`+`Shift`+`Enter` 立即执行代码
- - `Ctrl`+`Shift`+`↑/↓` 切换历史记录
  - `Ctrl`+`Shift`+`D` 查看详细信息
- 内置 `unsafe`, `lookup`
- 内置 `IntFunc` 类 (缩写`$`)
- + `$.xxx` 可以表示基本数据类型 (e.g `$.void` 表示 `Void.TYPE`;`$.J` 表示 long.class)
- `$p` 代表 `Packages`
- [JSFunc](src/modtools/utils/JSFunc.java)
- 长按收藏夹里的代码，可以添加到启动项<br>![image](https://github.com/I-hope1/mod-tools/assets/78016895/34da9eea-309b-489c-bb26-ac431921b52f)
- 快捷切换历史<br>![截图2024-03-10 14-45-37](https://github.com/I-hope1/mod-tools/assets/78016895/4918af35-19af-4fab-b961-70bdc8679fe8)

## UnitSpawn

- 多队伍选择
- 支持定点生成
- 显示`name`和`localizedName`<br>![unitSpawn](https://github.com/I-hope1/mod-tools/assets/78016895/212698e6-c0ca-40ff-ba9f-a41a2c617466)


## Selection
- 选择器
- 支持 `Tile`, `Building`, `Bullet`, `Unit`<br>![selection](https://github.com/I-hope1/mod-tools/assets/78016895/eaca8bf2-ebc3-4114-bacf-c3015f47738d)

- `Ctrl`+`Alt`固定Focus Window

## ReviewElement

- 显示元素列表，双击复制元素到js变量
- 选择不可触摸的元素
- + 移动端：双指过滤当前元素
  + 电脑端：按`F`过滤当前元素
- 对着元素按`i`，显示详情（打开ShowInfoWindow)<br>![reviewElement](https://github.com/I-hope1/mod-tools/assets/78016895/f5cc85a0-4850-471d-8e12-17bf5f95c842)

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
