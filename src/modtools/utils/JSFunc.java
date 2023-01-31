package modtools.utils;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.math.Mathf;
import arc.math.geom.*;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import hope_android.FieldUtils;
import ihope_lib.MyReflect;
import mindustry.Vars;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.*;
import modtools.IntVars;
import modtools.ui.*;
import modtools.ui.components.*;
import modtools.ui.components.Window.DisposableWindow;
import modtools.ui.components.input.MyLabel;
import modtools.ui.components.input.area.AutoTextField;
import modtools.ui.components.input.highlight.*;
import modtools.ui.components.limit.*;
import modtools.ui.content.ui.ReviewElement.ReviewElementWindow;
import modtools.ui.content.world.Selection;
import rhino.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import static ihope_lib.MyReflect.unsafe;
import static modtools.IntVars.topGroup;
import static modtools.ui.Contents.*;
import static modtools.utils.Tools.*;

public class JSFunc {
	public static       ClassLoader                   main;
	public static       Scriptable                    scope;
	public static final ObjectMap<String, Scriptable> classes;
	public static final Font                          FONT    = MyFonts.MSYHMONO;
	public static final Class<?>                      Reflect = MyReflect.class;

	/*public static Object eval(String code) {
		var scripts = new Scripts();
		return scripts.context.evaluateString(scripts.scope, code, "none", 1);
	}*/

	public static Window showInfo(Object o) {
		if (o == null) return null;
		return showInfo(o, o.getClass());
	}

	public static Window showInfo(Class<?> clazz) {
		return showInfo(null, clazz);
	}

	public static final Color
			keyword      = Color.valueOf("ff657a"),
			type         = Color.valueOf("9cd1bb"),
			NUMBER_COLOR = Color.valueOf("bad761"),
			underline    = Color.gray.cpy().a(0.7f);
	public static final String
			keywordMark = "[#" + keyword + "]",
			typeMark    = "[#" + type + "]";

	public static final LabelStyle
			keywordStyle = new LabelStyle(FONT, keyword),
			typeStyle    = new LabelStyle(FONT, type),
	// lightGrayStyle = new LabelStyle(FONT, Color.lightGray),
	redStyle = new LabelStyle(FONT, Color.red);


	public static Window showInfo(Object o, Class<?> clazz) {
		//			if (!clazz.isInstance(o)) return;

		Window[] dialog = {null};
		if (clazz.isArray()) {
			if (o == null) return new DisposableWindow("none");
			Table _cont = new LimitTable();
			_cont.defaults().grow();
			_cont.button(Icon.refresh, IntStyles.clearNonei, () -> {
				// 使用Time.runTask避免stack overflow
				Time.runTask(0, () -> {
					dialog[0].hide();
					var pos = getAbsPos(dialog[0]);
					try {
						showInfo(o, clazz).setPosition(pos);
					} catch (Throwable e) {
						IntUI.showException(e).setPosition(pos);
					}
					dialog[0] = null;
				});
			}).size(50).row();
			int length = Array.getLength(o);

			for (int i = 0; i < length; i++) {
				Object item   = Array.get(o, i);
				var    button = new LimitTextButton("" + item, Styles.grayt);
				int    j      = i;
				addWatchButton(button, o + "#" + i, () -> Array.get(o, j));
				button.clicked(() -> {
					// 使用Time.runTask避免stack overflow
					if (item != null) Time.runTask(0, () -> showInfo(item).setPosition(getAbsPos(button)));
					else IntUI.showException(new NullPointerException("item is null"));
				});
				_cont.add(button).fillX().minHeight(40).row();
				_cont.image().color(underline).growX().row();
			}

			dialog[0] = new DisposableWindow(clazz.getSimpleName(), 200, 200, true);
			dialog[0].cont.pane(_cont).grow();
			dialog[0].show();
			return dialog[0];
		}

		Table build = new LimitTable();
		// 默认左居中
		build.left().top().defaults().left();
		boolean[] isBlack   = {false};
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
		Runnable rebuild0 = () -> {
			if (textField.isValid()) {
				rebuild.get(textField.getText());
			}
		};
		textField.changed(rebuild0);
		dialog[0] = new ShowInfoWindow(clazz);

		final Table cont = new Table();
		// 默认左居中
		cont.left().defaults().left().fillX();
		cont.table(t -> {
			t.left().defaults().left();
			t.button(Icon.refresh, IntStyles.clearNonei, rebuild0).size(50);
			addStoreButton(t, "", () -> o);
		}).row();
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
		}).row();
		cont.table(t -> {
			t.left().defaults().left();
			t.add(clazz.getTypeName(), IntStyles.myLabel);
			t.button(Icon.copy, Styles.cleari, () -> {
				copyText(clazz.getTypeName(), t);
			});
		}).pad(6, 10, 6, 10).row();
		rebuild.get(null);
		// cont.add(build).grow();

		dialog[0].cont.add(cont).row();
		dialog[0].cont.add(new ScrollPane(build)).grow();
		//		dialog.addCloseButton();
		dialog[0].show();
		assert dialog[0] != null;
		return dialog[0];
	}

	private static <T> T[] filter(Pattern pattern, boolean isBlack, Func<T, String> func, T[] array) {
		// return Seq.select(array, t -> pattern == null || pattern.matcher(func.get(t)).find() == isBlack).toArray();
		if (pattern == null) return array;
		T[] newArr = Arrays.copyOf(array, array.length);
		int j      = array.length;
		for (int i = 0; i < array.length; i++) {
			if (pattern.matcher(func.get(array[i])).find() == isBlack) {
				newArr[i] = null;
				j--;
			}
		}
		array = as(Array.newInstance(array.getClass().getComponentType(), j));
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
		ReflectTable fields, methods, constructors, classes;
		boolean[]    c = new boolean[4];
		Arrays.fill(c, true);

		// cont.add("@jsfunc.field").row();
		BiFunction<String, Integer, ReflectTable> func = (text, index) -> {
			cont.button(text, Styles.logicTogglet, () -> c[index] ^= true).growX().height(42)
					.checked(c[index])
					.with(b -> b.getLabelCell().padLeft(10).growX().labelAlign(Align.left))
					.row();
			cont.image().color(Pal.accent).growX().height(2).row();
			var table = new ReflectTable();
			cont.collapser(table, true, () -> c[index])
					.pad(4, 6, 4, 6)
					.fillX().padTop(8).get().setDuration(0.1f);
			cont.row();
			// 占位符
			cont.add().grow().row();
			return table;
		};
		fields = window.fieldsTable = func.apply("@jsfunc.field", 0);
		methods = window.methodsTable = func.apply("@jsfunc.method", 1);
		constructors = func.apply("@jsfunc.constructor", 2);
		classes = window.classesTable = func.apply("@jsfunc.class", 3);

		// boolean displayClass = MySettings.settings.getBool("displayClassIfMemberIsNull", "false");
		for (Class<?> cls = window.clazz; cls != null; cls = cls.getSuperclass()) {
			// fieldArray = filter(pattern, isBlack, Field::getName, fieldArray);
			fields.build(cls);
			// Method[] methodArray = filter(pattern, isBlack, Method::getName, window.methodMap.get(cls));
			methods.build(cls);
			/*Constructor<?>[] constructorArray = filter(pattern, isBlack, Constructor::getName, window.consMap.get(cls));
			if (constructorArray.length != 0 || !displayClass) {*/
			constructors.add(new MyLabel(cls.getSimpleName(), IntStyles.myLabel)).row();
			constructors.image().color(Color.lightGray).fillX().padTop(6).colspan(6).row();
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

				Class<?> type      = f.getType();
				int      modifiers = f.getModifiers();
				try {
					// modifiers
					fields.add(new MyLabel(Modifier.toString(modifiers) + " ", keywordStyle))
							.touchable(Touchable.disabled);
					// type
					fields.add(new MyLabel(getGenericString(type), typeStyle))
							.padRight(16).touchable(Touchable.disabled);
					// name
					addDClickCopy(fields.add(new MyLabel(f.getName(), IntStyles.myLabel))
							              .get());
					fields.add(new MyLabel(" = ", IntStyles.myLabel))
							.touchable(Touchable.disabled);
				} catch (Throwable e) {
					Log.err(e);
				}
				fields.table(t -> {
					// 占位符
					Cell<?> cell  = t.add();
					float[] prefW = {0};
					/*Cell<?> lableCell = */
					ValueLabel l = new ValueLabel("", type);
					fields.labels.add(l);
					l.field = f;
					l.obj = o;
					Cell<?> labelCell = t.add(l);
					// 太卡了
					Runnable listener = () -> labelCell.width(Math.min(prefW[0], Core.graphics.getWidth()));
					IntVars.addResizeListener(listener);

					// if (type.isPrimitive() || type == String.class) {
					try {
						l.setVal();
						if (l.val instanceof Color) {
							final Color color = (Color) l.val;
							cell.setElement(new BorderImage(Core.atlas.white(), 2f)
									                .border(color.cpy().inv())).color(color).size(42f).with(b -> {
								IntUI.doubleClick(b, () -> {}, () -> {
									Vars.ui.picker.show(color, color::set);
								});
							});
						} else if (settingsUI.jsfuncEdit.getBool("boolean", false) && (type == Boolean.TYPE || type == Boolean.class)) {
							var btn = new TextButton((boolean) l.val ? "TRUE" : "FALSE", IntStyles.flatTogglet);
							btn.update(() -> btn.setChecked((boolean) l.val));
							btn.clicked(() -> {
								boolean b = !(boolean) l.val;
								btn.setText(b ? "TRUE" : "FALSE");
								try {
									l.setFieldValue(b);
								} catch (Throwable e) {
									IntUI.showException(e)
											.setPosition(Core.input.mouse());
								}
								l.setVal(b);
							});
							cell.setElement(btn);
							cell.size(96, 42);
							l.remove();
						} else if (settingsUI.jsfuncEdit.getBool("number", false) && Number.class.isAssignableFrom(box(type))) {
							var field = new AutoTextField();
							field.update(() -> {
								if (Core.scene.getKeyboardFocus() != field) {
									l.setVal();
									field.setText(String.valueOf(l.val));
								}
							});
							field.setValidator(Tools::isNum);
							field.changed(() -> {
								if (!field.isValid()) return;
								l.setFieldValue(Context.jsToJava(
										ScriptRuntime.toNumber(field.getText()),
										type));
							});
							cell.setElement(field);
							cell.height(42);
						} else if (settingsUI.jsfuncEdit.getBool("string", false) && type == String.class) {
							var field = new AutoTextField();
							field.update(() -> {
								if (Core.scene.getKeyboardFocus() != field) {
									l.setVal();
									field.setText(String.valueOf(l.val));
								}
							});
							field.changed(() -> {
								l.setFieldValue(field.getText());
							});
							cell.setElement(field);
							cell.height(42);
						}

						// prefW[0] = l.getPrefWidth();
						// listener.run();
						// Time.runTask(0, () -> l.setWrap(true));
						if (!unbox(type).isPrimitive() && type != String.class) {
							l.addShowInfoListener();
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

					t.table(buttons -> {
						buttons.right().top().defaults().right().top();
						addLabelButton(buttons, () -> l.val, type);
						addStoreButton(buttons, Core.bundle.get("jsfunc.field", "Field"), () -> f);
						addWatchButton(buttons, f.getDeclaringClass().getSimpleName() + ": " + f.getName(), () -> f.get(o));
					}).grow().top().right();
				}).pad(4).growX().left().row();
				fields.image().color(underline).growX().colspan(6).row();
			}
			if (fields.hasChildren()) fields.getChildren().peek().remove();


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
					StringBuilder sb  = new StringBuilder();
					int           mod = m.getModifiers() & Modifier.methodModifiers();
					if (mod != 0 && !m.isDefault()) {
						sb.append(Modifier.toString(mod));
					} else {
						sb.append(Modifier.toString(mod));
						if (m.isDefault()) {
							sb.append(" default");
						}
					}
					// modifiers
					methods.add(new MyLabel(sb, keywordStyle)).touchable(Touchable.disabled).padRight(8f);
					// return type
					methods.add(new MyLabel(getGenericString(m.getReturnType()), typeStyle)).touchable(Touchable.disabled).padRight(8f);
					// method name
					addDClickCopy(methods.add(new MyLabel(m.getName(), IntStyles.myLabel))
							              .get());
					// method parameters + exceptions + buttons
					methods.add(new LimitLabel(buildArgsAndExceptions(m.getParameterTypes(), m.getExceptionTypes())))
							.pad(4).left().touchable(Touchable.disabled);


					// 占位符
					Cell<?> cell = methods.add();
					ifl:
					if (m.getParameterTypes().length == 0) {
						if (o == null && !Modifier.isStatic(m.getModifiers())) {
							methods.add();
							break ifl;
						}
						ValueLabel l = new ValueLabel("", m.getReturnType());
						methods.labels.add(l);
						// float[] prefW = {0};
						methods.add(l)/*.self(c -> c.update(__ -> c.width(Math.min(prefW[0], Core.graphics.getWidth()))))*/;

						methods.table(buttons -> {
							buttons.right().top().defaults().right().top();
							buttons.button("Invoke", IntStyles.flatBordert, () -> {
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
									}
								} catch (Throwable ex) {
									IntUI.showException("invoke出错", ex).setPosition(getAbsPos(l));
								}
							}).size(96, 45);
							addLabelButton(buttons, () -> l.val, l.type);
							addStoreButton(buttons, Core.bundle.get("jsfunc.method", "Method"), () -> m);
						}).grow().top().right();
					} else {
						methods.add(); // 占位符， 对应上面
						methods.table(buttons -> {
							buttons.right().top().defaults().right().top();
							addStoreButton(buttons, Core.bundle.get("jsfunc.method", "Method"), () -> m);
						}).grow().top().right();
					}
				} catch (Throwable err) {
					methods.add(new MyLabel("<" + err + ">", redStyle));
				}
				methods.row();
				methods.image().color(underline).growX().colspan(7).row();
			}
			if (methods.hasChildren()) methods.getChildren().peek().remove();

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

				final Table t = constructors;
				try {
					int mod = cons.getModifiers()/* & Modifier.constructorModifiers()*/;
					t.add(new MyLabel(Modifier.toString(mod), keywordStyle));
					t.add(new MyLabel(cons.getDeclaringClass().getSimpleName(), typeStyle)).padLeft(10);
					t.add(new LimitLabel(buildArgsAndExceptions(cons.getParameterTypes(), cons.getParameterTypes())));

					t.table(buttons -> {
						addStoreButton(buttons, Core.bundle.get("jsfunc.constructor", "Constructor"), () -> cons);
					}).grow().top().right().row();
				} catch (Throwable e) {
					Log.err(e);
				}
				t.image().color(underline).growX().colspan(6).row();
			}
			if (constructors.hasChildren()) constructors.getChildren().peek().remove();

			for (Class<?> dcls : cls.getDeclaredClasses()) {
				classes.bind(dcls.getName());
				classes.table(t -> {
					try {
						int mod = dcls.getModifiers() & Modifier.classModifiers();
						t.add(new MyLabel(Modifier.toString(mod) + " class ", keywordStyle)).padRight(8f).touchable(Touchable.disabled);

						Label l = t.add(new MyLabel(getGenericString(dcls), typeStyle)).padRight(8f).get();
						addDClickCopy(l);
						Class<?>[] types = dcls.getInterfaces();
						if (types.length > 0) {
							t.add(new MyLabel(" implements ", keywordStyle)).padRight(8f).touchable(Touchable.disabled);
							for (Class<?> interf : types) {
								t.add(new MyLabel(getGenericString(interf), IntStyles.myLabel)).padRight(8f);
							}
						}
						IntUI.longPress(l, 600, b -> {
							if (b) {
								var pos = getAbsPos(l);
								// 使用Time.runTask避免stack overflow
								Time.runTask(0, () -> {
									try {
										showInfo(dcls).setPosition(pos);
									} catch (Throwable e) {
										IntUI.showException(e).setPosition(pos);
									}
								});
							}
						});

						t.table(buttons -> {
							addStoreButton(buttons, Core.bundle.get("jsfunc.class", "Class"), () -> dcls);
						}).grow().top().right();
					} catch (Throwable e) {
						Log.err(e);
					}
				}).pad(4).growX().left().row();
				classes.image().color(underline).growX().colspan(6).row();
			}
			if (classes.hasChildren()) classes.getChildren().peek().remove();
		}
		// return mainRun;
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
			Table t = new LimitTable(table -> {
				table.add(element);
			});
			d.cont.pane(t).fillX().fillY();
		});
	}

	public static Window testElement(String text) {
		return testElement(new Label(text));
	}

	public static Window testElement(Cons<Table> cons) {
		return testElement(new Table(cons));
	}


	public static void reviewElement(Element element) {
		new ReviewElementWindow().show(element);
	}

	public static Selection.Function<?> getFunction(String name) {
		return Selection.allFunctions.get(name);
	}

	public static Object unwrap(Object o) {
		if (o instanceof Wrapper) {
			return ((Wrapper) o).unwrap();
		}
		if (o instanceof Undefined) {
			return "undefined";
		}

		return o;
	}

	public static Scriptable findClass(String name, boolean isAdapter) throws ClassNotFoundException {
		if (classes.containsKey(name)) {
			return classes.get(name);
		} else {
			Scriptable clazz = tester.cx.getWrapFactory().wrapJavaClass(tester.cx, scope, main.loadClass(name));
			classes.put(name, clazz);
			return clazz;
		}
	}

	public static Scriptable findClass(String name) throws ClassNotFoundException {
		return findClass(name, true);
	}

	public static Class<?> forName(String name) throws ClassNotFoundException {
		return Class.forName(name, false, Vars.mods.mainLoader());
	}

	public static void setDrawPadElem(Element elem) {
		topGroup.drawPadElem = elem;
	}

	public static Object asJS(Object o) {
		return Context.javaToJS(o, scope);
	}

	// public static Window frameLabel(String text) {
	// 	return testElement(new FrameLabel(text));
	// }

	static {
		main = Vars.mods.mainLoader();
		scope = Vars.mods.getScripts().scope;
		classes = new ObjectMap<>();
		// V8.createV8Runtime();
	}

	public static void addDClickCopy(Label label) {
		IntUI.doubleClick(label, () -> {}, () -> {
			copyText(String.valueOf(label.getText()), label);
		});
	}

	public static void copyText(String text, Element element) {
		copyText(text, Tools.getAbsPos(element));
	}

	public static void copyText(String text) {
		copyText(text, Core.input.mouse());
	}


	public static void copyText(String text, Vec2 vec2) {
		Core.app.setClipboardText(text);
		IntUI.showInfoFade(Core.bundle.format("IntUI.copied", text))
				.setPosition(vec2);
	}

	public static class BindCell {
		public static Cell<?> unusedCell = new Cell<>();
		public        Cell<?> cell;
		private       Cell<?> copyCell;
		public        Element element;
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

	static class ReflectTable extends LimitTable {
		ObjectMap<String, Seq<BindCell>> map = new ObjectMap<>();
		// ObjectMap<Class<?>, Table> heads = new ObjectMap<>();

		public ReflectTable() {
			left().defaults().left().top();
		}

		private Seq<BindCell>   current;
		public  Seq<ValueLabel> labels = new Seq<>();
		// private Table currentHead;

		public void bind(String name) {
			current = map.get(name, Seq::new);
			// currentHead = head;
		}

		@Override
		protected void drawChildren() {
			super.drawChildren();
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
			add(new MyLabel(cls.getSimpleName(), IntStyles.myLabel)).labelAlign(Align.left).row();
			image().color(Color.lightGray).fillX().padTop(6).colspan(6).row();
		}
	}

	static Seq<Class<?>> NUMBER_SEQ = Seq.with(int.class, byte.class, short.class,
	                                           long.class, float.class, double.class);

	static class ValueLabel extends MyLabel {
		public            Object   val;
		private @Nullable Object   obj;
		private @Nullable Field    field;
		public final      Class<?> type;

		public ValueLabel(Object val, Class<?> type) {
			super(String.valueOf(val), IntStyles.myLabel);
			this.type = type;
			setVal(val);
			setAlignment(Align.left, Align.left);

			update(() -> {
				if (settingsUI.jsfunc.getBool("auto_refresh", false)) {
					setVal();
				}
			});
		}

		private boolean hasChange = false;
		private float   lastWidth = 0;

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
			String text = type == String.class && val != null ? '"' + (String) val + '"' : String.valueOf(val);
			setColor(val == null ? Syntax.objectsC
					         : type == String.class ? Syntax.stringC
					: NUMBER_SEQ.contains(Tools.unbox(type)) ? NUMBER_COLOR : Color.white);
			if (text.length() > 1000) {
				text = text.substring(0, 1000) + "  ...";
			}
			setText(text);
		}

		public void setFieldValue(Object val) {
			boolean isStatic = Modifier.isStatic(field.getModifiers());

			// Tools.setFieldValue(field, obj, val);
			if (OS.isAndroid) {
				try {
					field.set(obj, val);
				} catch (IllegalAccessException e) {
					throw new RuntimeException(e);
				}
			} else {
				unsafe.putObject(
						isStatic ? field.getDeclaringClass() : obj,
						OS.isAndroid ? FieldUtils.getFieldOffset(field) :
								isStatic ? unsafe.staticFieldOffset(field) : unsafe.objectFieldOffset(field),
						val);
			}

			setVal(val);
		}

		public void clearVal() {
			val = "";
			lastWidth = 0;
			setText("");
		}

		public void setVal() {
			if (field == null || (obj == null && !Modifier.isStatic(field.getModifiers()))) {
				setVal(null);
			} else {
				try {
					setVal(field.get(obj));
				} catch (IllegalAccessException ignored) {
				}
			}
		}

		public static final boolean disabled = true;

		public void addShowInfoListener() {
			// disabled
			if (disabled) return;
			IntUI.longPress(this, 600, b -> {
				if (!b) return;
				// 使用Time.runTask避免stack overflow
				Time.runTask(0, () -> {
					var pos = getAbsPos(this);
					try {
						if (val != null) {
							showInfo(val).setPosition(pos);
						} else {
							showInfo(null, type).setPosition(pos);
						}
					} catch (Throwable e) {
						IntUI.showException(e).setPosition(pos);
					}
				});
			});
		}
	}

	private static StringBuilder
	buildArgsAndExceptions(Class<?>[] args,
	                       Class<?>[] exceptions) {
		StringBuilder sb = new StringBuilder();
		sb.append("[lightgray](");

		for (int i = 0, length = args.length; i < length; i++) {
			Class<?> parameterType = args[i];
			sb.append(typeMark);
			sb.append(parameterType.getSimpleName());
			if (i != length - 1) {
				sb.append(", ");
			}
		}
		sb.append("[lightgray])");

		if (exceptions.length > 0) {
			sb.append(' ').append(keywordMark).append("throws ");
			for (int i = 0, length = exceptions.length; i < length; i++) {
				Class<?> parameterType = exceptions[i];
				sb.append(typeMark);
				sb.append(parameterType.getSimpleName());
				if (i != length - 1) {
					sb.append(", ");
				}
			}
		}
		return sb;
	}

	public static CharSequence getGenericString(Class<?> cls) {
		if (settingsUI.jsfunc.getBool("displayGeneric")) return cls.getSimpleName();
		StringBuilder sb         = new StringBuilder();
		String        simpleName;
		int           arrayDepth = 0;
		while (cls.isArray()) {
			arrayDepth++;
			cls = cls.getComponentType();
		}
		simpleName = cls.getName();
		simpleName = simpleName.substring(simpleName.lastIndexOf('.') + 1); // strip the package name
		sb.append(simpleName);
		TypeVariable<?>[] typeparms = cls.getTypeParameters();
		if (typeparms.length > 0) {
			StringJoiner sj = new StringJoiner(",", "<", ">");
			for (TypeVariable<?> typeparm : typeparms) {
				sj.add(typeparm.getTypeName());
			}
			sb.append(sj);
		}

		sb.append("[]".repeat(Math.max(0, arrayDepth)));

		return sb;
	}


	public static class WatchWindow extends DisposableWindow {
		Table pane;

		public WatchWindow() {
			super("Watch");
			pane = new Table();
			cont.add(new ScrollPane(pane) {
				@Override
				public float getPrefWidth() {
					return 220;
				}
			}).grow();
		}

		public WatchWindow watch(String info, MyProv<Object> value) {
			return watch(info, value, 0);
		}

		public WatchWindow watch(String info, MyProv<Object> value, float interval) {
			pane.add(info).color(Pal.accent).growX().left().row();
			pane.image().color(Pal.accent).growX().row();
			var label = new MyLabel(() -> {
				try {
					return String.valueOf(value.get());
				} catch (Throwable e) {
					StringWriter sw = new StringWriter();
					PrintWriter  pw = new PrintWriter(sw);
					e.printStackTrace(pw);
					return sw.toString();
				}
			});
			label.interval = interval;
			pane.add(label).style(IntStyles.myLabel).growX().left().padLeft(6f).row();
			pane.image().color(underline).growX().row();
			pack();
			return this;
		}

		public String toString() {
			return "Watch@" + hashCode();
		}
	}

	public static void addLabelButton(Table table, Prov<?> prov, Class<?> clazz) {
		table.button("@details", IntStyles.flatBordert, () -> {
			Object o = prov.get();
			showInfo(o, o != null ? o.getClass() : clazz);
		}).size(96, 45);
		addStoreButton(table, Core.bundle.get("jsfunc.value", "value"), prov);
	}

	public static void addStoreButton(Table table, String key, Prov<?> prov) {
		table.button(key.isEmpty() ? Core.bundle.get("jsfunc.store_as_js_var2")
				             : Core.bundle.format("jsfunc.store_as_js_var", key),
		             IntStyles.flatBordert, () -> {}).padLeft(10f).size(180, 40)
				.with(b -> {
					b.clicked(() -> {
						tester.put(b, prov.get());
					});
				});
	}

	public static WatchWindow watch(String info, MyProv<Object> value) {
		return watch(info, value, 0);
	}

	public static WatchWindow watch(String info, MyProv<Object> value, float interval) {
		return new WatchWindow() {{
			watch(info, value, interval);
			show();
		}};
	}

	public static void addWatchButton(Table buttons, String info, MyProv<Object> value) {
		buttons.button(Icon.eyeSmall, Styles.squarei, () -> {}).with(b -> b.clicked(() -> {
			watch(info, value).setPosition(b.localToStageCoordinates(Tmp.v1.set(0, 0)));
		})).size(45);
	}

	public interface MyProv<T> {
		T get() throws Exception;
	}
}
