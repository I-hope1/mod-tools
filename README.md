# Mod Tools

A mod for mindustry v7.
It provides many useful tools for developers.

## showUIList

- 显示 `icon`, `tex`, `styles`

## Tester

- 提供 `js` 编辑器 `tester`
- - `ctrl+shift+enter` 立即执行代码
- - `ctrl+shift+up/down` 切换历史记录
- 内置 `unsafe`, `lookup`
- 内置 `IntFunc` 类
```java
public class JSFunc {
	public static void showInfo(Object o);
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

## UnitSwapn

- 多队伍选择
- 支持定点生成
- 显示`name`和`localizedName`

## Selection

- 选择器
- 支持 `Tile`, `Building`, `Bullet`, `Unit`


## ReviewElement
显示元素列表，双击复制元素到js变量

## Window
- `ctrl+tab` 切换窗口