package modtools.utils;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import com.strobel.decompiler.*;
import com.strobel.decompiler.Decompiler;
import ihope_lib.MyReflect;
import mindustry.Vars;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.*;
import modtools.IntVars;
import modtools.ui.*;
import modtools.ui.components.*;
import modtools.ui.components.Window.DisposableInterface;
import modtools.ui.components.input.MyLabel;
import modtools.ui.components.input.area.*;
import modtools.ui.components.input.highlight.JavaSyntax;
import modtools.ui.components.limit.*;
import modtools.ui.content.SettingsContent;
import modtools.utils.JSFunc.ReflectTable;
import rhino.*;

import java.io.StringWriter;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import static modtools.IntVars.hasDecomplier;
import static modtools.ui.IntStyles.MOMO_Label;
import static modtools.utils.JSFunc.*;
import static modtools.utils.MySettings.*;
import static modtools.utils.Tools.*;

class ShowInfoWindow extends Window implements DisposableInterface {

	final Class<?> clazz;
	Object o;
	ReflectTable
	       fieldsTable,
			methodsTable,
			classesTable;

	public ShowInfoWindow(Object o, Class<?> clazz) {
		super(clazz.getSimpleName(), 200, 200, true);
		this.o = o;
		this.clazz = clazz;
		build();
	}


	public void build() {
		Table build = new LimitTable();
		// 默认左居中
		build.left().top().defaults().left();
		boolean[] isBlack   = {false};
		TextField textField = new TextField();
		// Runnable[] last = {null};
		Cons<String> rebuild = text -> {
			// build.clearChildren();
			Pattern pattern = Tools.complieRegExpCatch(text);
			buildReflect(o, build, pattern, isBlack[0]);
		};
		Runnable rebuild0 = () -> {
			if (textField.isValid()) {
				rebuild.get(textField.getText());
			}
		};
		textField.changed(rebuild0);

		final Table cont = new Table();
		// 默认左居中
		cont.left().defaults().left().fillX();
		cont.table(t -> {
			t.left().defaults().left();
			SettingsContent.bool(t.table().get(), "multi-watch", D_JSFUNC, "@settings.jsfunc.multi.watch");
			if (hasDecomplier) t.button("Decomplie", Styles.flatBordert, () -> {
				StringWriter stringWriter = new StringWriter();
				Decompiler.decompile(
						// clazz.getClassLoader().getResource()
						clazz.getName().replace('.', '/') + ".class",
						new PlainTextOutput(stringWriter)
				);
				var textarea = new TextAreaTable(stringWriter.toString());
				textarea.syntax = new JavaSyntax(textarea);
				JSFunc.window(d -> {
					d.cont.setSize(textarea.getArea().getPrefWidth(), textarea.getArea().getPrefHeight());
					d.cont.add(textarea).grow();
				});
			}).size(100, 32);
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
			t.add(clazz.getTypeName(), MOMO_Label);
			t.button(Icon.copy, Styles.cleari, () -> {
				copyText(clazz.getTypeName(), t);
			});
		}).pad(6, 10, 6, 10).row();
		rebuild.get(null);
		// cont.add(build).grow();

		this.cont.add(cont).row();
		this.cont.add(new ScrollPane(build)).grow().row();
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
	private void buildReflect(Object o, Table cont, Pattern pattern, boolean isBlack) {
		/*final Seq<Runnable> runnables = new Seq<>();
		final Runnable mainRun;
		IntVars.addResizeListener(mainRun = () -> {
			runnables.each(Runnable::run);
		});*/
		if (cont.getChildren().size > 0) {
			fieldsTable.filter(name -> pattern == null || pattern.matcher(name).find() != isBlack);
			fieldsTable.labels.each(ValueLabel::setVal);
			methodsTable.filter(name -> pattern == null || pattern.matcher(name).find() != isBlack);
			methodsTable.labels.each(ValueLabel::clearVal);
			classesTable.filter(name -> pattern == null || pattern.matcher(name).find() != isBlack);
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
		fields = fieldsTable = func.apply("@jsfunc.field", 0);
		methods = methodsTable = func.apply("@jsfunc.method", 1);
		constructors = func.apply("@jsfunc.constructor", 2);
		classes = classesTable = func.apply("@jsfunc.class", 3);

		// boolean displayClass = MySettings.settings.getBool("displayClassIfMemberIsNull", "false");
		for (Class<?> cls = clazz; cls != null; cls = cls.getSuperclass()) {
			// fieldArray = filter(pattern, isBlack, Field::getName, fieldArray);
			fields.build(cls);
			// Method[] methodArray = filter(pattern, isBlack, Method::getName, window.methodMap.get(cls));
			methods.build(cls);
			/*Constructor<?>[] constructorArray = filter(pattern, isBlack, Constructor::getName, window.consMap.get(cls));
			if (constructorArray.length != 0 || !displayClass) {*/
			constructors.add(new MyLabel(cls.getSimpleName(), MOMO_Label)).row();
			constructors.image().color(Color.lightGray).fillX().padTop(6).colspan(6).row();
			// }
			classes.build(cls);
			// 字段
			Field[] fields1;
			try {fields1 = MyReflect.lookupGetFields(cls);} catch (Throwable e) {fields1 = new Field[0];}
			for (Field f : fields1) {
				buildField(o, fields, f);
			}
			if (fields.hasChildren()) fields.getChildren().peek().remove();

			// 函数
			Method[] methods1;
			try {
				methods1 = MyReflect.lookupGetMethods(cls);
			} catch (Throwable e) {
				methods1 = new Method[0];
			}
			for (Method m : methods1) {
				buildMethod(o, methods, m);
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
				buildConstructor(constructors, cons);
			}
			if (constructors.hasChildren()) constructors.getChildren().peek().remove();

			// 类
			for (Class<?> dcls : cls.getDeclaredClasses()) {
				buildClass(classes, dcls);
			}
			if (classes.hasChildren()) classes.getChildren().peek().remove();
		}
		// return mainRun;
	}

	private static void buildField(Object o, ReflectTable fields, Field f) {
		fields.bind(f.getName());
		try {
			MyReflect.setOverride(f);
		} catch (Throwable t) {
			Log.err(t);
		}

		Class<?> type      = f.getType();
		int      modifiers = f.getModifiers();
		try {
			// modifiers
			addModifier(fields, Modifier.toString(modifiers));
			// type
			fields.add(new MyLabel(getGenericString(type), typeStyle))
					.padRight(16).touchable(Touchable.disabled);
			// name
			addDClickCopy(fields.add(new MyLabel(f.getName(), MOMO_Label))
					.get());
			fields.add(new MyLabel(" = ", MOMO_Label))
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
			l.set(f, o);
			fields.labels.add(l);
			Cell<?> labelCell = t.add(l);
			// 太卡了
			IntVars.addResizeListener(() -> labelCell.width(Math.min(prefW[0], Core.graphics.getWidth())));

			// if (type.isPrimitive() || type == String.class) {
			try {
				l.setVal();
				if (l.isStatic() || o != null) buildFieldValue(type, cell, l);

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

			t.table(buttons -> {
				buttons.right().top().defaults().right().top();
				addLabelButton(buttons, () -> l.val, type);
				addStoreButton(buttons, Core.bundle.get("jsfunc.field", "Field"), () -> f);
				addWatchButton(buttons, f.getDeclaringClass().getSimpleName() + ": " + f.getName(), () -> f.get(o));
			}).grow().top().right();
		}).pad(4).growX().left().row();
		fields.image().color(underline).growX().colspan(6).row();
	}
	private static void addModifier(ReflectTable table, CharSequence string) {
		// if (true) return;
		table.add(new MyLabel(string, keywordStyle))
				.touchable(Touchable.disabled).padRight(8);
	}
	private static void buildFieldValue(Class<?> type, Cell<?> cell, ValueLabel l) {
		if (l.val instanceof Color) {
			final Color color = (Color) l.val;
			cell.setElement(new BorderImage(Core.atlas.white(), 2f)
					.border(color.cpy().inv())).color(color).size(42f).with(b -> {
				IntUI.doubleClick(b, () -> {}, () -> {
					Vars.ui.picker.show(color, color::set);
				});
			});
		} else if (D_JSFUNC_EDIT.getBool("boolean", false) && (type == Boolean.TYPE || type == Boolean.class)) {
			var btn = new TextButton("", IntStyles.flatTogglet);
			btn.update(() -> {
				l.setVal();
				btn.setText((boolean) l.val ? "TRUE" : "FALSE");
				btn.setChecked((boolean) l.val);
			});
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
		} else if (D_JSFUNC_EDIT.getBool("number", false) && Number.class.isAssignableFrom(box(type))) {
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
		} else if (D_JSFUNC_EDIT.getBool("string", false) && type == String.class) {
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
	}
	private static void buildMethod(Object o, ReflectTable methods, Method m) {
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
			addModifier(methods, sb);
			// return type
			methods.add(new MyLabel(getGenericString(m.getReturnType()), typeStyle)).touchable(Touchable.disabled).padRight(8f);
			// method name
			addDClickCopy(methods.add(new MyLabel(m.getName(), MOMO_Label))
					.get());
			// method parameters + exceptions + buttons
			methods.add(new LimitLabel(buildArgsAndExceptions(m.getParameterTypes(), m.getExceptionTypes())
					, MOMO_Label)).pad(4).left().touchable(Touchable.disabled);


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
				// methods.add(); // 占位符， 对应上面
				methods.table(buttons -> {
					buttons.right().top().defaults().right().top();
					addStoreButton(buttons, Core.bundle.get("jsfunc.method", "Method"), () -> m);
				}).grow().top().right().colspan(2);
			}
		} catch (Throwable err) {
			methods.add(new MyLabel("<" + err + ">", redStyle));
		}
		methods.row();
		methods.image().color(underline).growX().colspan(7).row();
	}
	private static void buildConstructor(ReflectTable t, Constructor<?> cons) {
		try {
			MyReflect.setOverride(cons);
		} catch (Throwable ignored) {}
		try {
			/* & Modifier.constructorModifiers()*/
			addModifier(t, Modifier.toString(cons.getModifiers()));
			t.add(new MyLabel(cons.getDeclaringClass().getSimpleName(), typeStyle));
			t.add(new LimitLabel(buildArgsAndExceptions(cons.getParameterTypes(), cons.getParameterTypes())));

			t.table(buttons -> {
				addStoreButton(buttons, Core.bundle.get("jsfunc.constructor", "Constructor"), () -> cons);
			}).grow().top().right().row();
		} catch (Throwable e) {
			Log.err(e);
		}
		t.image().color(underline).growX().colspan(6).row();
	}
	private static void buildClass(ReflectTable classes, Class<?> cls) {
		classes.bind(cls.getName());
		classes.table(t -> {
			try {
				int mod = cls.getModifiers() & Modifier.classModifiers();
				t.add(new MyLabel(Modifier.toString(mod) + " class ", keywordStyle)).padRight(8f).touchable(Touchable.disabled);

				Label l = t.add(new MyLabel(getGenericString(cls), typeStyle)).padRight(8f).get();
				addDClickCopy(l);
				Class<?>[] types = cls.getInterfaces();
				if (types.length > 0) {
					t.add(new MyLabel(" implements ", keywordStyle)).padRight(8f).touchable(Touchable.disabled);
					for (Class<?> interf : types) {
						t.add(new MyLabel(getGenericString(interf), MOMO_Label)).padRight(8f);
					}
				}
				IntUI.longPress(l, 600, b -> {
					if (b) {
						var pos = getAbsPos(l);
						// 使用Time.runTask避免stack overflow
						Time.runTask(0, () -> {
							try {
								showInfo(cls).setPosition(pos);
							} catch (Throwable e) {
								IntUI.showException(e).setPosition(pos);
							}
						});
					}
				});

				t.table(buttons -> {
					addStoreButton(buttons, Core.bundle.get("jsfunc.class", "Class"), () -> cls);
				}).grow().top().right();
			} catch (Throwable e) {
				Log.err(e);
			}
		}).pad(4).growX().left().row();
		classes.image().color(underline).growX().colspan(6).row();

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
