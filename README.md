# Mod Tools

A mod for mindustry v7.
It provides many useful tools for developers.

![icon.png](./assets/icon.png)

## showUIList

- 显示 `icon`, `tex`, `styles`, `colors`, `interps`

## Tester

- 提供 `js` 编辑器 `tester`
-
    - `ctrl`+`shift`+`enter` 立即执行代码
-
    - `ctrl`+`shift`+`up/down` 切换历史记录
- 内置 `unsafe`, `lookup`
- 内置 `IntFunc` 类 (缩写`$`)
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
  public static boolean eq(Object a, Object b);
  public static long addressOf(Object o);
  public static void focusWorld(T o);
	public static MyReflect Reflect;
}
```

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
- `ctrl+tab` 切换窗口

## ShowInfoWindow

- `‘null`表示null字符串