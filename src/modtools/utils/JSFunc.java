package modtools.utils;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.*;
import modtools.ui.IntStyles;
import modtools.ui.IntUI;
import modtools.ui.MyFonts;
import modtools.ui.components.*;
import modtools.ui.content.ElementShow.ElementShowDialog;
import modtools.ui.content.Selection;
import modtools_lib.MyReflect;
import rhino.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.regex.Pattern;

import static modtools.ui.Contents.*;

public class JSFunc {
	public static ClassLoader main;
	public static Scriptable scope;
	public static final ObjectMap<String, NativeJavaClass> classes;
	public static final Class<?> Reflect = MyReflect.class;

	/*public static Object eval(String code) {
		var scripts = new Scripts();
		return scripts.context.evaluateString(scripts.scope, code, "none", 1);
	}*/

	public static Window showInfo(Object o) {
		return showInfo(o, o.getClass());
	}

	public static Window showInfo(Class<?> clazz) {
		return showInfo(null, clazz);
	}

	public static final Color keyword = Color.valueOf("ff657a"),
			type = Color.valueOf("9cd1bb"),
			number = Color.valueOf("bad761");

	public static final LabelStyle
			keywordStyle = new LabelStyle(MyFonts.MSYHMONO, keyword),
			typeStyle = new LabelStyle(MyFonts.MSYHMONO, type),
			lightGrayStyle = new LabelStyle(MyFonts.MSYHMONO, Color.lightGray),
			redStyle = new LabelStyle(MyFonts.MSYHMONO, Color.red);


	public static Window showInfo(Object o, Class<?> clazz) {
		//			if (!clazz.isInstance(o)) return;

		Window[] dialog = {null};
		Table build = new Table();
		// 默认左居中
		build.left().defaults().left();
		boolean[] isBlack = {false};
		TextField textField = new TextField();
		// Runnable[] last = {null};
		Cons<String> rebuild = text -> {
			// build.clearChildren();
			Pattern pattern;
			try {
				pattern = text == null || text.isEmpty() ? null : Pattern.compile(text, Pattern.CASE_INSENSITIVE);
			} catch (Throwable e) {
				pattern = null;
			}
			buildReflect(o, (ShowInfoWindow) dialog[0], build, pattern, isBlack[0]);
		};
		textField.changed(() -> {
			if (textField.isValid()) {
				rebuild.get(textField.getText());
			}
		});
		if (clazz.isArray()) {
			if (o == null) return null;
			Table _cont = new Table();
			_cont.defaults().grow();
			_cont.button(Icon.refresh, Styles.clearNonei, () -> {
				// 使用Time.runTask避免stack overflow
				Time.runTask(0, () -> {
					dialog[0].hide();
					try {
						showInfo(o, clazz).setPosition(Tools.getAbsPos(dialog[0]));
					} catch (Throwable e) {
						IntUI.showException(e).setPosition(Tools.getAbsPos(dialog[0]));
					}
					dialog[0] = null;
				});
			}).size(50).row();
			int length = Array.getLength(o);

			for (int i = 0; i < length; i++) {
				Object item = Array.get(o, i);
				var button = new TextButton("" + item);
				button.clicked(() -> {
					// 使用Time.runTask避免stack overflow
					if (item != null) Time.runTask(0, () -> showInfo(item).setPosition(Tools.getAbsPos(button)));
					else IntUI.showException(new NullPointerException("item is null"));
				});
				_cont.add(button).fillX().minHeight(40).row();
			}

			return dialog[0] = new Window(clazz.getSimpleName(), 200, 200, true) {{
				cont.pane(_cont).grow();
				//				addCloseButton();
			}}.show();
		}
		dialog[0] = new ShowInfoWindow(clazz);

		final Table cont = new Table();
		// 默认左居中
		cont.left().defaults().left();
		cont.table(t -> {
			t.left().defaults().left();
			t.button(Icon.refresh, Styles.clearNonei, () -> {
				// 使用Time.runTask避免stack overflow
				/*Time.runTask(0, () -> {
					dialog[0].hide();
					try {
						showInfo(o, clazz).setPosition(Tools.getAbsPos(dialog[0]));
					} catch (Throwable e) {
						IntUI.showException(e).setPosition(Tools.getAbsPos(dialog[0]));
					}
					dialog[0] = null;
				});*/
				rebuild.get(null);
			}).size(50);
			t.button("存储为js变量", () -> {}).padLeft(10f).height(50).growX().maxWidth(600).with(b -> {
				b.clicked(() -> tester.put(b, o));
			}).row();
		}).growX().row();
		cont.table(t -> {
			t.button(Tex.whiteui, 35, null).size(42).with(img -> {
				img.clicked(() -> {
					isBlack[0] = !isBlack[0];
					img.getStyle().imageUpColor = isBlack[0] ? Color.black : Color.white;
					rebuild.get(textField.getText());
				});
			});
			t.image(Icon.zoom).size(42);
			t.add(textField).growX();
		}).growX().row();
		cont.table(t -> {
			t.left().defaults().left();
			t.add(new MyLabel(clazz.getTypeName(), IntStyles.myLabel));
			t.button(Icon.copy, Styles.cleari, () -> {
				Core.app.setClipboardText(clazz.getTypeName());
			});
		}).fillX().pad(6, 10, 6, 10).row();
		rebuild.get(null);
		// cont.add(build).grow();

		dialog[0].cont.add(cont).row();
		dialog[0].cont.pane(build).grow();
		//		dialog.addCloseButton();
		dialog[0].show();
		assert dialog[0] != null;
		return dialog[0];
	}

	private static <T> T[] filter(Pattern pattern, boolean isBlack, Func<T, String> func, T[] array) {
		// return Seq.select(array, t -> pattern == null || pattern.matcher(func.get(t)).find() == isBlack).toArray();
		if (pattern == null) return array;
		T[] newArr = Arrays.copyOf(array, array.length);
		int j = array.length;
		for (int i = 0; i < array.length; i++) {
			if (pattern.matcher(func.get(array[i])).find() == isBlack) {
				newArr[i] = null;
				j--;
			}
		}
		array = (T[]) Array.newInstance(array.getClass().getComponentType(), j);
		int k = 0;
		for (T t : newArr) {
			if (t != null) {
				array[k++] = t;
			}
		}
		return array;
	}

	/**
	 * @param pattern 用于搜索
	 * @param isBlack 是否为黑名单模式
	 **/
	private static void buildReflect(Object o, ShowInfoWindow window, Table cont, Pattern pattern, boolean isBlack) {
		/*final Seq<Runnable> runnables = new Seq<>();
		final Runnable mainRun;
		IntVars.addResizeListener(mainRun = () -> {
			runnables.each(Runnable::run);
		});*/
		if (cont.getChildren().size > 0) {
			window.fieldsTable.filter(name -> pattern == null || pattern.matcher(name).find() != isBlack);
			window.fieldsTable.labels.each(ValueLabel::setVal);
			window.methodsTable.filter(name -> pattern == null || pattern.matcher(name).find() != isBlack);
			window.methodsTable.labels.each(ValueLabel::clearVal);
			window.classesTable.filter(name -> pattern == null || pattern.matcher(name).find() != isBlack);
			return;
		}
		cont.add("字段").row();
		cont.image().color(Pal.accent).fillX().row();
		var fields = window.fieldsTable = cont.add(new ReflectTable())
				.pad(4, 6, 4, 6)
				.fillX().padTop(8).get();
		fields.left().defaults().left().top();
		cont.row();
		cont.add("函数").row();
		cont.image().color(Pal.accent).fillX().row();
		var methods = window.methodsTable = cont.add(new ReflectTable())
				.pad(4, 6, 4, 6)
				.fillX().padTop(8).get();
		methods.left().defaults().left().top();
		cont.row();
		cont.add("构造器").row();
		cont.image().color(Pal.accent).fillX().row();
		Table constructors = cont.table(t -> t.left().defaults().left().top())
				.pad(4, 6, 4, 6).fill().padTop(8).get();
		cont.row();
		cont.add("类").row();
		cont.image().color(Pal.accent).fillX().row();
		var classes = window.classesTable = cont.add(new ReflectTable())
				.pad(4, 6, 4, 6)
				.fillX().padTop(8).get();
		classes.left().defaults().left().top();

		boolean displayClass = MySettings.settings.getBool("displayClassIfMemberIsNull", "false");
		for (Class<?> cls = window.clazz; cls != null; cls = cls.getSuperclass()) {
			// fieldArray = filter(pattern, isBlack, Field::getName, fieldArray);
			fields.build(cls);
			// Method[] methodArray = filter(pattern, isBlack, Method::getName, window.methodMap.get(cls));
			methods.build(cls);
			/*Constructor<?>[] constructorArray = filter(pattern, isBlack, Constructor::getName, window.consMap.get(cls));
			if (constructorArray.length != 0 || !displayClass) {*/
			constructors.add(cls.getSimpleName(), IntStyles.myLabel).row();
			constructors.image().color(Color.lightGray).fillX().padTop(6).row();
			// }
			classes.build(cls);
			// 字段
			Field[] fields1;
			try {
				fields1 = MyReflect.lookupGetFields(cls);
			} catch (Throwable e) {
				fields1 = new Field[0];
			}
			for (Field f : fields1) {
				fields.bind(f.getName());
				/*try {
					MyReflect.lookupRemoveFinal(f);
				} catch (Throwable ignored) {}*/
				try {
					MyReflect.setOverride(f);
					//					f.setAccessible(true);
				} catch (Throwable t) {
					Log.err(t);
				}

				Class<?> type = f.getType();
				int modifiers = f.getModifiers();
				try {
					// modifiers
					fields.add(Modifier.toString(modifiers) + " ", keywordStyle).growY().touchable(Touchable.disabled);
					// type
					fields.add(new MyLabel(type.getSimpleName(), typeStyle))
							.growY().padRight(16).touchable(Touchable.disabled);
					// name
					fields.add(f.getName(), IntStyles.myLabel).growY().touchable(Touchable.disabled);
					fields.add(" = ", IntStyles.myLabel).growY().touchable(Touchable.disabled);
				} catch (Throwable e) {
					Log.err(e);
				}
				fields.table(t -> {
					// 占位符
					Cell<?> cell = t.add();
					// float[] prefW = {0};
					/*Cell<?> lableCell = */
					ValueLabel l = new ValueLabel("", type);
					fields.labels.add(l);
					l.field = f;
					l.obj = o;
					t.add(l);
					// 太卡了
					// Runnable listener = () -> lableCell.width(Math.min(prefW[0], Core.graphics.getWidth()));
					// IntVars.addResizeListener(listener);

					// if (type.isPrimitive() || type == String.class) {
					try {
						l.setVal(f.get(o));
						if (l.val instanceof Color) {
							cell.setElement(new BorderImage(Core.atlas.white(), 2f)
									.border(((Color) l.val).cpy().inv())).color((Color) l.val).size(42f);
						}
						// prefW[0] = l.getPrefWidth();
						// listener.run();
						// Time.runTask(0, () -> l.setWrap(true));
						if (!type.isPrimitive() && type != String.class) {
							l.addShowInfoListener();
						} else {
							l.setColor(number);
						}
					} catch (Throwable e) {
						//								`Log.info`(e);
						l.setText("<ERROR>");
						l.setColor(Color.red);
					}
					/*} else {
						l.setText("???");
						boolean[] ok = {false};
						l.clicked(() -> {
							if (ok[0]) return;
							ok[0] = true;
							try {
								l.setVal(MyReflect.getValueExact(o, f));
								// prefW[0] = l.getPrefWidth();
								// l.setWrap(true);
								if (l.val instanceof Color) {
									cell.setElement(new Image(IntUI.whiteui.tint((Color) l.val))).size(32).padRight(4).touchable(Touchable.disabled);
								}
								l.addShowInfoListener();
							} catch (Throwable ex) {
								Log.err(ex);
								l.setText("<ERROR>");
								l.setColor(Color.red);
							}
						});
					}*/

					t.button("将字段储存为js变量", () -> {}).padLeft(10f).size(180, 40).with(b -> {
						b.clicked(() -> tester.put(b, f));
					});
					t.button("将值存储为js变量", () -> {}).padLeft(10f).size(180, 40).with(b -> {
						b.clicked(() -> tester.put(b, l.val));
					});
				}).pad(4).row();
			}


			// 函数
			// int c = 0;
			Method[] methods1;
			try {
				methods1 = MyReflect.lookupGetMethods(cls);
			} catch (Throwable e) {
				methods1 = new Method[0];
			}
			for (Method m : methods1) {
				// if (c++ > 10) continue;
				methods.bind(m.getName());
				try {
					MyReflect.setOverride(m);
				} catch (Throwable ignored) {}
				try {
					StringBuilder sb = new StringBuilder();
					int mod = m.getModifiers() & Modifier.methodModifiers();
					if (mod != 0 && !m.isDefault()) {
						sb.append(Modifier.toString(mod));
					} else {
						sb.append(Modifier.toString(mod));
						if (m.isDefault()) {
							sb.append(" default");
						}
					}
					// modifiers
					methods.add(sb, keywordStyle).growY().touchable(Touchable.disabled).padRight(8f);
					// return type
					methods.add(new MyLabel(m.getReturnType().getSimpleName(), typeStyle)).growY().touchable(Touchable.disabled).padRight(8f);
					// method name
					methods.add(m.getName(), IntStyles.myLabel).growY().touchable(Touchable.disabled);
					// method parameters + exceptions + buttons
					methods.table(t -> {
						t.left().defaults().left();
						t.add("(", lightGrayStyle);

						Class<?>[] exceptionTypes = m.getParameterTypes();

						for (int i = 0, exceptionTypesLength = exceptionTypes.length; i < exceptionTypesLength; i++) {
							Class<?> parameterType = exceptionTypes[i];
							t.add(parameterType.getSimpleName(), typeStyle);
							if (i != exceptionTypesLength - 1) t.add(", ");
						}
						t.add(")", lightGrayStyle);

						exceptionTypes = m.getExceptionTypes();
						if (exceptionTypes.length > 0) {
							t.add(" throws ", keywordStyle);
							for (int i = 0, exceptionTypesLength = exceptionTypes.length; i < exceptionTypesLength; i++) {
								Class<?> parameterType = exceptionTypes[i];
								t.add(parameterType.getSimpleName(), typeStyle);
								if (i != exceptionTypesLength - 1) t.add(", ");
							}
						}
						// 占位符
						Cell<?> cell = t.add();
						ifl:
						if (m.getParameterTypes().length == 0) {
							if (o == null && !Modifier.isStatic(m.getModifiers())) {
								t.add();
								break ifl;
							}
							ValueLabel l = new ValueLabel("", m.getReturnType());
							methods.labels.add(l);
							// float[] prefW = {0};
							t.add(l)/*.self(c -> c.update(__ -> c.width(Math.min(prefW[0], Core.graphics.getWidth()))))*/;

							t.button("invoke", () -> {
								try {
									l.setVal(m.invoke(o));
									// l.setWrap(false);
									// prefW[0] = l.getPrefWidth();
									// l.setWrap(true);

									if (l.val instanceof Color) {
										cell.setElement(new Image(IntUI.whiteui.tint((Color) l.val))).size(32).padRight(4).touchable(Touchable.disabled);
									}
									if (l.val != null && !(l.val instanceof String) && !m.getReturnType().isPrimitive()) {
										//											l.setColor(Color.white);
										l.addShowInfoListener();
									} else {
										l.setColor(number);
									}
								} catch (Throwable ex) {
									IntUI.showException("invoke出错", ex).setPosition(Tools.getAbsPos(l));
								}

							}).width(100);
						}

						t.button("将函数存储为js变量", () -> {}).padLeft(10f).size(180, 40).with(b -> {
							b.clicked(() -> tester.put(b, m));
						});
					}).pad(4).row();
				} catch (Throwable err) {
					methods.add("<" + err + ">", redStyle);
				}

			}

			// 构造器
			Constructor<?>[] constructors1;
			try {
				constructors1 = MyReflect.lookupGetConstructors(cls);
			} catch (Throwable e) {
				constructors1 = new Constructor<?>[0];
			}
			for (Constructor<?> cons : constructors1) {
				try {
					MyReflect.setOverride(cons);
				} catch (Throwable ignored) {}

				constructors.table(t -> {
					try {
						int mod = cons.getModifiers() & Modifier.constructorModifiers();
						t.add(new MyLabel(Modifier.toString(mod), keywordStyle));
						t.add(new MyLabel(cons.getDeclaringClass().getSimpleName(), typeStyle)).padLeft(10);
						t.add(new MyLabel("(", lightGrayStyle)).padLeft(4);

						if (cons.getParameterCount() > 0) {
							StringJoiner sj = new StringJoiner(", ");
							// 参数
							Class<?>[] parameterTypes = cons.getParameterTypes();
							for (Class<?> pt : parameterTypes) {
								sj.add(format(pt));
							}
							t.add(new MyLabel(sj.toString(), typeStyle));
						}
						t.add(new MyLabel(")", lightGrayStyle));
						Type[] exceptionTypes = cons.getGenericExceptionTypes();
						// 报错
						if (exceptionTypes.length > 0) {
							t.add(" throws ");
							StringJoiner joiner = new StringJoiner(",");

							for (Type exceptionType : exceptionTypes) {
								joiner.add(exceptionType.getTypeName());
							}

							t.add(new MyLabel(joiner.toString(), typeStyle));
						}

						t.button("将函数存储为js变量", () -> {}).padLeft(10f).size(180, 40).with(b -> {
							b.clicked(() -> tester.put(b, cons));
						});
					} catch (Throwable e) {
						Log.err(e);
					}
				}).pad(4).row();
			}

			for (Class<?> dcls : cls.getDeclaredClasses()) {
				classes.bind(dcls.getName());
				classes.table(t -> {
					try {
						int mod = dcls.getModifiers() & Modifier.classModifiers();
						t.add(Modifier.toString(mod) + " class ", keywordStyle).padRight(8f).touchable(Touchable.disabled);
						Label l = t.add(dcls.getSimpleName(), typeStyle).padRight(8f).get();
						Class<?>[] types = dcls.getInterfaces();
						if (types.length > 0) {
							t.add(" implements ", keywordStyle).padRight(8f).touchable(Touchable.disabled);
							for (Class<?> interf : types) {
								t.add(interf.getName()).style(IntStyles.myLabel).padRight(8f);
							}
						}
						IntUI.longPress(l, 600, b -> {
							if (b) {
								// 使用Time.runTask避免stack overflow
								Time.runTask(0, () -> {
									try {
										showInfo(dcls).setPosition(Tools.getAbsPos(l));
									} catch (Throwable e) {
										IntUI.showException(e).setPosition(Tools.getAbsPos(l));
									}
								});
							}
						});

						t.button("将类存储为js变量", () -> {}).padLeft(10f).size(180, 40).with(b -> {
							b.clicked(() -> tester.put(b, dcls));
						});
					} catch (Throwable e) {
						Log.err(e);
					}
				}).pad(4).row();
			}
		}
		// return mainRun;
	}

	public static CharSequence format(Class<?> cls) {
		StringBuilder base = new StringBuilder();
		base.append(cls.getTypeName());
		if (cls.isArray()) base.append("[\u0001]");
		return base;
	}

	public static Window window(final Cons<Window> cons) {
		return new Window("test") {{
			cons.get(this);
			//			addCloseButton();
			show();
		}};
	}

	public static Window testElement(Element element) {
		return window(d -> {
			Table t = new Table(table -> {
				table.add(element);
			});
			d.cont.pane(t).fillX().fillY();
		});
	}

	public static Window testElement(String text) {
		return testElement(new Label(text));
	}

	public static void showElement(Element element) {
		new ElementShowDialog().show(element);
	}

	public static Selection.Function<?> getFunction(String name) {
		return Selection.all.get(name);
	}

	public static Object unwrap(Object o) {
		if (o instanceof NativeJavaObject) {
			return ((NativeJavaObject) o).unwrap();
		}
		if (o instanceof Undefined) {
			return "undefined";
		}

		return o;
	}

	public static NativeJavaClass findClass(String name, boolean isAdapter) throws ClassNotFoundException {
		if (classes.containsKey(name)) {
			return classes.get(name);
		} else {
			NativeJavaClass clazz = new NativeJavaClass(scope, main.loadClass(name), isAdapter);
			classes.put(name, clazz);
			return clazz;
		}
	}

	public static NativeJavaClass findClass(String name) throws ClassNotFoundException {
		return findClass(name, true);
	}

	public static Class<?> forName(String name) throws ClassNotFoundException {
		return Class.forName(name, false, Vars.mods.mainLoader());
	}

	public static Object asJS(Object o) {
		return Context.javaToJS(o, scope);
	}

	static {
		main = Vars.mods.mainLoader();
		scope = Vars.mods.getScripts().scope;
		classes = new ObjectMap<>();
		// V8.createV8Runtime();
	}

	static class ShowInfoWindow extends Window {

		private final Class<?> clazz;
		private ReflectTable
				fieldsTable,
				methodsTable,
				classesTable;

		public ShowInfoWindow(Class<?> clazz) {
			super(getName(clazz), 200, 200, true);
			this.clazz = clazz;

			hidden(() -> {
				all.remove(this);
				clearChildren();
				/*fieldsTable.clear();
				methodsTable.clear();
				classesTable.clear();
				fieldsTable = null;
				methodsTable = null;
				classesTable = null;*/
			});
		}

		public void build() {
		}

		@Override
		public String toString() {
			return getClass().getSimpleName() + "#" + title.getText();
		}

		public static String getName(Class<?> cls) {
			while (cls != null) {
				String tmp = cls.getName();
				if (!tmp.isEmpty()) return tmp;
				cls = cls.getSuperclass();
			}
			return "unknown";
		}
	}

	static class BindCell {
		static Cell<?> unusedCell = new Cell<>();
		Cell<?> cell;
		private Cell<?> copyCell;
		Element element;
		// Table head;

		public BindCell(Cell<?> cell) {
			this.cell = cell;
			this.element = cell.get();
			// this.head = currentHead;
		}

		public Cell<?> getCopyCell() {
			if (copyCell == null) copyCell = new Cell<>().set(cell);
			return copyCell;
		}
	}

	static class ReflectTable extends Table {
		ObjectMap<String, Seq<BindCell>> map = new ObjectMap<>();
		ObjectMap<Class<?>, Table> heads = new ObjectMap<>();

		public ReflectTable() {
		}

		private Seq<BindCell> current;
		public Seq<ValueLabel> labels = new Seq<>();
		// private Table currentHead;

		public void bind(String name) {
			current = map.get(name, Seq::new);
			// currentHead = head;
		}

		public void unbind() {
			current = null;
			// currentHead = null;
		}

		@Override
		public <T extends Element> Cell<T> add(T element) {
			Cell<T> cell = super.add(element);
			if (current != null) current.add(new BindCell(cell));
			return cell;
		}

		// public Table unuseTable = new Table();

		public void filter(Boolf<String> boolf) {
			map.each((name, seq) -> {
				seq.each(boolf.get(name) ?
						c -> c.cell.set(c.getCopyCell()).setElement(c.element) :
						c -> {
							c.getCopyCell();
							c.cell.set(BindCell.unusedCell).clearElement();
						});
			});
		}

		public void build(Class<?> cls) {
			table(t -> {
				t.left().defaults().left();
				t.add(cls.getSimpleName(), IntStyles.myLabel).row();
				t.image().color(Color.lightGray).fillX().padTop(6).row();
				heads.put(cls, t);
			}).growY().row();

		}
	}

	static class ValueLabel extends MyLabel {
		public Object val;
		private @Nullable Object obj;
		private @Nullable Field field;
		public final Class<?> type;

		public ValueLabel(Object val, Class<?> type) {
			super(String.valueOf(val), IntStyles.myLabel);
			setVal(val);
			this.type = type;
			setAlignment(Align.left, Align.left);
		}

		private boolean hasChange = false;
		private float lastWidth = 0;

		@Override
		public float getPrefWidth() {
			if (hasChange) {
				hasChange = false;
				wrap = false;
				float def = super.getPrefWidth();
				wrap = true;
				lastWidth = Mathf.clamp(def, 40, 800);
			}
			return lastWidth;
		}

		public void setVal(Object val) {
			if (this.val == val) return;
			this.val = val;
			hasChange = true;
			setText(type == String.class ? '"' + (String) val + '"' : String.valueOf(val));
		}

		public void clearVal() {
			val = "";
			lastWidth = 0;
			setText("");
		}

		public void setVal() {
			if (field == null) {
				setVal(null);
			} else {
				try {
					setVal(field.get(obj));
				} catch (IllegalAccessException ignored) {
				}
			}
		}

		public void addShowInfoListener() {
			IntUI.longPress(this, 600, b -> {
				if (!b) return;
				// 使用Time.runTask避免stack overflow
				Time.runTask(0, () -> {
					try {
						if (val != null) {
							showInfo(val).setPosition(Tools.getAbsPos(this));
						} else {
							showInfo(null, type).setPosition(Tools.getAbsPos(this));
						}
					} catch (Throwable e) {
						IntUI.showException(e).setPosition(Tools.getAbsPos(this));
					}
				});
			});
		}
	}
}
