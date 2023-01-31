# Mod Tools

A mod for mindustry v7.
It provides many useful tools for developers.

## showUIList

- 显示 `icon`, `tex`, `styles`, `colors`

## Tester

- 提供 `js` 编辑器 `tester`
- - `ctrl+shift+enter` 立即执行代码
- - `ctrl+shift+up/down` 切换历史记录
- 内置 `unsafe`, `lookup`, `MyReflect`
- 内置 `IntFunc` 类
```java
public class JSFunc {
	public static void showInfo(Object o);
	public static void showInfo(Class o);
	public static Window window(final Cons<Window> cons);
	public static Window testElement(Element element);
	public static Window testElement(String text);
	public static void reviewElement(Element element);
	public static void setDrawPadElem(Element elem);
	public static Object asJS(Object o);
	public static Function<?> getFunction(String name);
	public static NativeJavaClass findClass(String name, boolean isAdapter);
	public static NativeJavaClass findClass(String name);
	public static Class<?> forName(String name);
	public static Object unwrap(Object o);
	public static WatchWindow watch(String info, MyProv<Object> value, float interval);
	public static WatchWindow watch(String info, MyProv<Object> value);
	public static MyReflect Reflect;
}
```
- `IntFunc.showInfo`显示所有`字段``方法``构造器``内部类`
### ShowInfoWinodw
- 支持 `文本编辑` `数字编辑` `布尔编辑` `颜色编辑（双击）`


## UnitSwapn

- 多队伍选择
- 支持定点生成
- 显示`name`和`localizedName`

## Selection

- 选择器
- 支持 `Tile`, `Building`, `Bullet`, `Unit`
- 按住 `alt` 多选


## ReviewElement
显示元素列表，双击复制元素到js变量
`右键(desktop)`/`长按(mobile)`显示更多

## Window
- `ctrl+tab` 切换窗口