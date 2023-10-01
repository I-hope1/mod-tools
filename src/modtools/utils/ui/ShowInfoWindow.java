package modtools.utils.ui;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.*;
import ihope_lib.MyReflect;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.events.*;
import modtools.ui.*;
import modtools.ui.IntUI.MenuList;
import modtools.ui.components.*;
import modtools.ui.components.Window.DisposableInterface;
import modtools.ui.components.input.*;
import modtools.ui.components.input.area.*;
import modtools.ui.components.input.highlight.JavaSyntax;
import modtools.ui.components.limit.LimitTable;
import modtools.ui.components.utils.ValueLabel;
import modtools.utils.*;
import modtools.utils.reflect.*;
import modtools.utils.ui.search.*;
import rhino.*;

import java.io.StringWriter;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import static ihope_lib.MyReflect.lookup;
import static modtools.IntVars.hasDecompiler;
import static modtools.ui.HopeStyles.*;
import static modtools.ui.content.SettingsUI.addSettingsTable;
import static modtools.utils.JSFunc.*;
import static modtools.utils.MySettings.*;
import static modtools.utils.Tools.*;
import static modtools.utils.ui.MethodTools.*;
import static modtools.utils.ui.ReflectTools.*;

@SuppressWarnings("CodeBlock2Expr")
public class ShowInfoWindow extends Window implements DisposableInterface {
	/* non-null */
	private final Class<?> clazz;
	private final Object   o;
	private final MyEvents events = new MyEvents();

	private ReflectTable
	 fieldsTable,
	 methodsTable,
	 consTable,
	 classesTable;

	public ShowInfoWindow(Object o, Class<?> clazz) {
		super(getName(clazz), 200, 200, true);
		this.o = o;
		this.clazz = clazz;
		if (clazz.isPrimitive()) {
			cont.add("<PRIMITIVE>").color(Color.gray);
			return;
		}
		MyEvents.current = events;
		build();
		MyEvents.current = null;
	}


	Cons<String> rebuild;
	TextField    textField;

	public int modifiers = -1;
	public void rebuildReflect() {
		buildReflect(o, build, pattern, isBlack);
	}
	public boolean isBlack;
	public Table   build;
	public Pattern pattern;
	public void build() {
		build = new LimitTable();
		// 默认左居中
		build.left().top().defaults().left();
		textField = new TextField();
		// Runnable[] last = {null};
		rebuild = text -> {
			// build.clearChildren();
			pattern = PatternUtils.compileRegExpCatch(text);
			rebuildReflect();
		};
		Runnable rebuild0 = () -> {
			if (textField.isValid()) {
				rebuild.get(textField.getText());
			}
		};
		textField.changed(rebuild0);

		final Table cont = new Table();
		// 默认左居中
		cont.left().defaults().left().growX();
		cont.pane(t -> {
			t.left().defaults().left();
			t.button(Icon.settingsSmall, Styles.clearNonei, () -> {
				IntUI.showSelectTableRB(Core.input.mouse().cpy(), (p, hide, ___) -> {
					addSettingsTable(p, "", n -> "jsfunc." + n, D_JSFUNC, E_JSFunc.values());
					addSettingsTable(p, "Display", n -> "jsfunc.display." + n, D_JSFUNC_DISPLAY, E_JSFuncDisplay.values());
					addSettingsTable(p, "Edit", n -> "jsfunc.edit." + n, D_JSFUNC_EDIT, E_JSFuncEdit.values());
				}, false);
			}).size(42);
			if (OS.isWindows && hasDecompiler) buildDeCompiler(t);
			t.button(Icon.refreshSmall, HopeStyles.clearNonei, rebuild0).size(42);
			if (o != null) {
				addStoreButton(t, "", () -> o);
				t.label(() -> "" + addressOf(o)).padLeft(8f);
			}
		}).height(42).row();
		cont.table(t -> {
			ElementUtils.addCodedBtn(t, "modifiers", 4,
			 i -> modifiers = i, () -> modifiers,
			 ModifierR.values());
			t.button(Tex.whiteui, 32, null).size(42).with(img -> {
				img.clicked(() -> {
					isBlack = !isBlack;
					img.getStyle().imageUpColor = isBlack ? Color.black : Color.white;
					rebuild.get(textField.getText());
				});
			});
			t.image(Icon.zoom).size(42);
			t.add(textField).growX();
		}).row();
		cont.table(t -> {
			t.left().defaults().left();
			t.pane(t0 -> t0.add(clazz.getTypeName(), MOMO_LabelStyle))
			 .with(p -> p.setScrollingDisabledY(true)).grow().maxWidth(390);
			t.button(Icon.copySmall, Styles.cleari, () -> {
				copyText(clazz.getTypeName(), t);
			}).size(32);
			if (o == null) t.add("NULL", MOMO_LabelStyle).color(Color.red).padLeft(8f);
		}).pad(6, 10, 6, 10).row();
		rebuild.get(null);
		// cont.add(build).grow();

		this.cont.add(cont).row();
		this.cont.add(new ScrollPane(build)).grow().row();

		for (E_JSFuncDisplay value : E_JSFuncDisplay.values()) {
			events.fireIns(value);
		}
		pack();
	}
	@SuppressWarnings("Convert2Lambda")
	private void buildDeCompiler(Table t) {
		t.button("Decompile", Styles.flatBordert, new Runnable() {
			public void run() {
				StringWriter stringWriter = new StringWriter();
				com.strobel.decompiler.Decompiler.decompile(
				 // clazz.getClassLoader().getResource()
				 clazz.getName().replace('.', '/') + ".class",
				 new com.strobel.decompiler.PlainTextOutput(stringWriter)
				);
				TextAreaTab textarea = new TextAreaTab(stringWriter.toString());
				textarea.syntax = new JavaSyntax(textarea);
				window(d -> {
					d.cont.setSize(textarea.getArea().getPrefWidth(), textarea.getArea().getPrefHeight());
					d.cont.add(textarea).grow();
				});
			}
		}).size(100, 42);
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
			Boolf<Member> memberBoolf = member ->
			 (pattern == null || find(pattern, member.getName()) != isBlack)
			 && containsMod(member.getModifiers());
			fieldsTable.filter(memberBoolf);
			fieldsTable.labels.each(ValueLabel::setVal);
			methodsTable.filter(memberBoolf);
			methodsTable.labels.each(ValueLabel::clearVal);
			consTable.filter(memberBoolf);
			classesTable.filter(memberBoolf);
			return;
		}
		boolean[] c = new boolean[4];
		Arrays.fill(c, true);

		BiFunction<String, Integer, ReflectTable> func = (text, index) -> {
			cont.button(text, Styles.flatToggleMenut, () -> c[index] ^= true)
			 .growX().height(42).checked(c[index])
			 .with(b -> b.getLabelCell().padLeft(10).growX().labelAlign(Align.left))
			 .row();
			cont.image().color(Pal.accent).growX().height(2).row();
			var table = new ReflectTable();
			cont.collapser(table, true, () -> c[index])
			 .pad(4, 6, 4, 6)
			 .growX().padTop(8).get().setDuration(0.1f);
			cont.row();
			// 占位符
			cont.add().grow().top().row();
			return table;
		};
		fieldsTable = func.apply("@jsfunc.field", 0);
		methodsTable = func.apply("@jsfunc.method", 1);
		consTable = func.apply("@jsfunc.constructor", 2);
		classesTable = func.apply("@jsfunc.class", 3);

		// boolean displayClass = MySettings.settings.getBool("displayClassIfMemberIsNull", "false");
		Type type = clazz;
		for (Class<?> cls = clazz; cls != null; type = cls.getGenericSuperclass(), cls = cls.getSuperclass()) {
			buildAllByClass(o, cls, type);
		}
		if (clazz == null) return;

		Type[]     interfaceTypes = clazz.getGenericInterfaces();
		Class<?>[] interfaces     = clazz.getInterfaces();
		for (int i = 0, clazzInterfacesLength = interfaces.length; i < clazzInterfacesLength; i++) {
			buildAllByClass(o, interfaces[i], interfaceTypes[i]);
		}
		// return mainRun;
	}
	private void buildAllByClass(Object o, Class<?> cls, Type type) {
		Field[] fields1 = getFields1(cls);
		fieldsTable.build(cls, type, fields1);
		Method[] methods1 = getMethods1(cls);
		methodsTable.build(cls, type, methods1);
		Constructor<?>[] constructors1 = getConstructors1(cls);
		consTable.build(cls, type, constructors1);
		Class<?>[] classes1 = cls.getDeclaredClasses();
		classesTable.build(cls, type, classes1);
		// 字段
		for (Field f : fields1) {buildField(o, fieldsTable, f);}
		checkRemovePeek(fieldsTable);
		// 函数
		for (Method m : methods1) {buildMethod(o, methodsTable, m);}
		checkRemovePeek(methodsTable);
		// 构造器
		for (Constructor<?> cons : constructors1) {buildConstructor(consTable, cons);}
		checkRemovePeek(consTable);
		// 类
		for (Class<?> dcls : classes1) {buildClass(classesTable, dcls);}
		checkRemovePeek(classesTable);
	}

	public static boolean find(Pattern pattern, String name) {
		return E_JSFunc.search_exact.enabled() ? pattern.matcher(name).matches() : pattern.matcher(name).find();
	}
	private static void checkRemovePeek(ReflectTable table) {
		if (!table.lastEmpty && table.hasChildren()) table.getChildren().peek().remove();
	}
	private static Field[] getFields1(Class<?> cls) {
		try {return MyReflect.lookupGetFields(cls);} catch (Throwable e) {return new Field[0];}
	}
	private static Method[] getMethods1(Class<?> cls) {
		try {return MyReflect.lookupGetMethods(cls);} catch (Throwable e) {return new Method[0];}
	}
	private static Constructor<?>[] getConstructors1(Class<?> cls) {
		try {return MyReflect.lookupGetConstructors(cls);} catch (Throwable e) {return new Constructor<?>[0];}
	}


	private BindCell addDisplayListener(Cell<?> cell0, E_JSFuncDisplay type) {
		BindCell cell = new BindCell(cell0);
		events.onIns(type, b -> {
			if (b.enabled()) cell.build();
			else cell.remove();
		});
		return cell;
	}
	private void addModifier(Table table, CharSequence string) {
		addDisplayListener(table.add(new MyLabel(string, MOMO_LabelStyle))
		 .color(Tmp.c1.set(c_keyword)).fontScale(0.7f)
		 .padRight(8), E_JSFuncDisplay.modifier);
	}
	private void addRType(Table table, Class<?> type, Prov<String> details) {
		MyLabel label = makeGenericType(type, details);
		addDisplayListener(table.add(label)
		 .fontScale(0.86f)
		 .padRight(16), E_JSFuncDisplay.type);
	}

	static void applyChangedFx(Element label) {
		new LerpFun(Interp.smooth).rev().onUI().registerDispose(0.05f, fin -> {
			Draw.color(Pal.heal, fin);
			Lines.stroke(3f - fin * 2f);
			Vec2  e    = ElementUtils.getAbsPosCenter(label);
			float fout = 1 - fin;
			Fill.rect(e.x, e.y, fout * label.getWidth() * 1.2f, fout * label.getHeight() * 1.2f);

			Angles.randLenVectors(e.hashCode(), 3, 1, (x, y) -> {
				Fill.square(e.x + x, e.y + y, 1f);
			});
		});
	}

	public void buildFieldValue(Class<?> type, BindCell c_cell, ValueLabel l) {
		if (!l.isStatic() && l.obj == null) return;
		Cell<?> cell = c_cell.cell;
		if (l.val instanceof Color) {
			IntUI.colorBlock(cell, (Color) l.val, l::setVal);
			c_cell.reget();
		} else if (type == Boolean.TYPE || type == Boolean.class) {
			var btn = new TextButton("", HopeStyles.flatTogglet);
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
					IntUI.showException(e).setPosition(Core.input.mouse());
				}
				l.setVal(b);
			});
			cell.setElement(btn);
			cell.size(96, 42);
			c_cell.reget();
		} else if (Number.class.isAssignableFrom(box(type))) {
			var field = new AutoTextField();
			field.update(() -> {
				if (Core.scene.getKeyboardFocus() != field) {
					l.setVal();
					field.setText(l.val instanceof Float ? Strings.autoFixed((float) l.val, 2)
					 : String.valueOf(l.val));
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
			c_cell.reget();
			events.onIns(E_JSFuncEdit.number, edit -> {
				cell.setElement(edit.enabled() ? field : null);
				c_cell.reget();
			});
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
			c_cell.reget();
			events.onIns(E_JSFuncEdit.string, edit -> {
				cell.setElement(edit.enabled() ? field : null);
				c_cell.reget();
			});
		}

	}

	private void buildField(Object o, ReflectTable fields, Field f) {
		fields.bind(f);
		setAccessible(f);

		Class<?> type      = f.getType();
		int      modifiers = f.getModifiers();
		try {
			// modifiers
			addModifier(fields, Modifier.toString(modifiers));
			// type
			addRType(fields, type, makeDetails(type, f.getGenericType()));
			// name
			MyLabel label = newCopyLabel(fields, f.getName());
			IntUI.addShowMenuListenerp(label, () -> Seq.with(
			 IntUI.copyAsJSMenu("field", () -> f),
			 MenuList.with(Icon.copySmall, "copy offset", () -> {
				 JSFunc.copyText("" + FieldUtils.fieldOffset(f));
			 }),
			 MenuList.with(Icon.copySmall, "copy field getter", () -> {
				 copyFieldReflection(f);
			 }),
			 MenuList.with(Icon.copySmall, "copy value getter", () -> {
				 copyFieldArcReflection(f);
			 }),
			 ValueLabel.newDetailsMenuList(label, f, Field.class)
			));
			fields.add(new MyLabel(" = ", MOMO_LabelStyle))
			 .color(Color.lightGray).top()
			 .touchable(Touchable.disabled);
		} catch (Throwable e) {
			MyLabel label = new MyLabel("<" + e + ">", MOMO_LabelStyle);
			label.setColor(Color.red);
			fields.add(label);
			Log.err(e);
		}
		fields.table(t -> {
			t.left().defaults().left();
			// 占位符
			Cell<?> cell = t.add().top();
			BindCell c_cell = addDisplayListener(cell, E_JSFuncDisplay.value);
			/*Cell<?> lableCell = */
			ValueLabel l = new ValueLabel(ValueLabel.unset, type, f, o);
			if (Enum.class.isAssignableFrom(type)) l.addEnumSetter();
			fields.labels.add(l);
			t.add(l).minWidth(42).growX().uniformX()
			 .labelAlign(Align.topLeft);

			try {
				l.setVal();
				buildFieldValue(type, c_cell, l);
			} catch (Throwable e) {
				l.setText("<ERROR>");
				l.setColor(Color.red);
			}

			addDisplayListener(t.table(buttons -> {
				buttons.right().top().defaults().right().top();
				addLabelButton(buttons, () -> l.val, type);
				// addStoreButton(buttons, Core.bundle.get("jsfunc.field", "Field"), () -> f);
				addWatchButton(buttons, f.getDeclaringClass().getSimpleName() + ": " + f.getName(), () -> f.get(o));
			}).grow().top(), E_JSFuncDisplay.buttons);
		}).pad(4).growX().row();
		fields.image().color(Tmp.c1.set(c_underline)).growX().colspan(6).row();
	}
	private void buildMethod(Object o, ReflectTable methods, Method m) {
		if (!E_JSFunc.display_synthetic.enabled() && m.isSynthetic()) return;
		/* if (m.getName().equals("insert") && m.getParameterCount() == 2 && m.getParameterTypes()[1].isPrimitive() &&
				(
				 // m.getParameterTypes()[1] == char.class ||
				 m.getParameterTypes()[1] == boolean.class ||
				 m.getParameterTypes()[1] == long.class ||
				 // m.getParameterTypes()[1] == int.class ||
				 m.getParameterTypes()[1] == double.class ||
				 m.getParameterTypes()[1] == float.class ||
				 // m.getParameterTypes()[1] == byte.class ||
				 // m.getParameterTypes()[1] == short.class ||
				 false
				)) {
			// methods.unbind();
			methods.bind(m);
			// return;
		} else  */
		methods.bind(m);
		setAccessible(m);
		try {
			int mod = m.getModifiers();
			// modifiers
			addModifier(methods, buildExecutableModifier(m));
			// return type
			addRType(methods, m.getReturnType(),
			 makeDetails(m.getReturnType(), m.getGenericReturnType()));
			// method name
			MyLabel label = newCopyLabel(methods, m.getName());

			// method parameters + exceptions + buttons
			methods.table(t -> {
				t.left().defaults().left();
				t.add(buildArgsAndExceptions(m)).growY().pad(4).left();

				// 占位符
				Cell<?> cell = t.add().top();
				Cell<?> buttonsCell;

				boolean isSingle = m.getParameterCount() == 0;
				boolean isValid  = o != null || Modifier.isStatic(mod);
				// if (isSingle && !isValid) methods.add();

				ValueLabel l = new ValueLabel(ValueLabel.unset, o, m);
				l.clearVal();
				methods.labels.add(l);
				IntUI.addShowMenuListenerp(label, () -> Seq.with(
				 IntUI.copyAsJSMenu("method", () -> m),
				 MenuList.with(Icon.copySmall, "Cpy method getter", () -> {
					 copyExecutableReflection(m);
				 }),
				 MenuList.with(Icon.copySmall, "Cpy value getter", () -> {
					 copyExecutableArcReflection(m);
				 }),
				 MenuList.with(Icon.boxSmall, "Invoke", () -> {
					 m.setAccessible(true);
					 if (isSingle) {
						 try {
							 dealInvokeResult(m.invoke(o), cell, l);
						 } catch (Throwable th) {IntUI.showException(th);}
						 return;
					 }
					 JSRequest.<NativeArray>requestForMethod(m, o, arr -> {
						 dealInvokeResult(invokeForMethod(o, m, l, arr,
							args -> m.invoke(o, args)), cell, l);
					 });
				 }),
				 MenuList.with(Icon.boxSmall, "InvokeSpecial", () -> {
					 m.setAccessible(true);
					 MethodHandle handle;
					 try {
						 handle = lookup.unreflectSpecial(m, m.getDeclaringClass());
					 } catch (IllegalAccessException e) {
						 throw new RuntimeException(e);
					 }
					 if (isSingle) {
						 try {
							 dealInvokeResult(handle.invokeWithArguments(o), cell, l);
						 } catch (Throwable th) {IntUI.showException(th);}
						 return;
					 }
					 if (!l.isStatic()) handle.bindTo(o);
					 JSRequest.<NativeArray>requestForMethod(handle, o, arr -> {
						 dealInvokeResult(invokeForMethod(o, m, l, arr,
							handle::invokeWithArguments
						 ), cell, l);
					 });
				 }),
				 ValueLabel.newDetailsMenuList(label, m, Method.class)
				));
				// float[] prefW = {0};
				t.add(l).grow().uniformX();

				buttonsCell = t.table(buttons -> {
					buttons.right().top().defaults().right().top();
					if (isSingle && isValid) {
						buttons.button("Invoke", HopeStyles.flatBordert, catchRun("invoke出错", () -> {
							dealInvokeResult(m.invoke(o), cell, l);
						}, l)).size(96, 45);
					}
					addLabelButton(buttons, () -> l.val, l.type);
					// addStoreButton(buttons, Core.bundle.get("jsfunc.method", "Method"), () -> m);
				}).grow().top().right();
				if (buttonsCell != null) addDisplayListener(buttonsCell, E_JSFuncDisplay.buttons);
			}).grow();
		} catch (Throwable err) {
			MyLabel label = new MyLabel("<" + err + ">", MOMO_LabelStyle);
			label.setColor(Color.red);
			methods.add(label);
		}
		methods.row();
		methods.image().color(Tmp.c1.set(c_underline)).growX().colspan(7).row();
	}
	private void buildConstructor(ReflectTable t, Constructor<?> ctor) {
		setAccessible(ctor);
		try {
			addModifier(t, buildExecutableModifier(ctor));
			MyLabel label = new MyLabel(ctor.getDeclaringClass().getSimpleName(), MOMO_LabelStyle);
			label.color.set(c_type);
			boolean isSingle = ctor.getParameterCount() == 0;
			IntUI.addShowMenuListenerp(label, () -> Seq.with(
			 MenuList.with(Icon.copySmall, "copy reflect getter", () -> {
				 copyExecutableReflection(ctor);
			 }),
			 MenuList.with(Icon.boxSmall, "Invoke", () -> {
				 ctor.setAccessible(true);
				 if (isSingle) {
					 try {
						 JSFunc.copyValue("instance", ctor.newInstance());
					 } catch (Throwable th) {IntUI.showException(th);}
					 return;
				 }
				 JSRequest.<NativeArray>requestForMethod(ctor, o, arr -> {
					 JSFunc.copyValue("instance", ctor.newInstance(
						convertArgs(arr, ctor.getParameterTypes())
					 ));
				 });
			 }),
			 IntUI.copyAsJSMenu("constructor", () -> ctor),
			 ValueLabel.newDetailsMenuList(label, ctor, Constructor.class)
			));
			t.add(label);
			t.add(buildArgsAndExceptions(ctor)).growY();
			/* 占位符 */
			t.add().grow().top();

			/* addDisplayListener(t.table(buttons -> {
				addStoreButton(buttons, Core.bundle.get("jsfunc.constructor", "Constructor"), () -> cons);
			}).grow().top().right(), JSFuncDisplay.buttons); */
			t.row();
		} catch (Throwable e) {
			Log.err(e);
		}
		t.image().color(Tmp.c1.set(c_underline)).growX().colspan(6).row();
	}
	private void buildClass(ReflectTable table, Class<?> cls) {
		table.bind(wrapMember(cls));
		table.table(t -> {
			t.top().defaults().top();
			try {
				addModifier(t, Modifier.toString(cls.getModifiers() & ~Modifier.classModifiers()) + " class ");

				MyLabel l = newCopyLabel(t, getGenericString(cls));
				l.color.set(c_type);
				IntUI.addShowMenuListenerp(l, () -> Seq.with(
				 IntUI.copyAsJSMenu("class", () -> cls),
				 ValueLabel.newDetailsMenuList(l, cls, Class.class)
				));
				Class<?>[] types = cls.getInterfaces();
				if (types.length > 0) {
					t.add(new MyLabel(" implements ", MOMO_LabelStyle)).color(Tmp.c1.set(c_keyword)).padRight(8f).touchable(Touchable.disabled);
					for (Class<?> interf : types) {
						t.add(new MyLabel(getGenericString(interf), MOMO_LabelStyle)).padRight(8f).color(Tmp.c1.set(c_type));
					}
				}

				addDisplayListener(t.table(buttons -> {
					buttons.right().defaults().right();
					addDetailsButton(buttons, () -> null, cls);
					// addStoreButton(buttons, Core.bundle.get("jsfunc.class", "Class"), () -> cls);
				}).grow().padRight(40), E_JSFuncDisplay.buttons);
			} catch (Throwable e) {
				Log.err(e);
			}
		}).pad(4).growX().left().top().row();
		table.image().color(Tmp.c1.set(c_underline)).growX().colspan(6).row();
	}

	/** 双击复制文本内容 */
	private static MyLabel newCopyLabel(Table table, String text) {
		MyLabel label = new MyLabel(text, MOMO_LabelStyle);
		table.add(label).growY().labelAlign(Align.top)/* .self(c -> {
			if (Vars.mobile && type != null) c.tooltip(getGenericString(type));
		}) */;
		addDClickCopy(label);
		return label;
	}


	public String toString() {
		return getClass().getSimpleName() + "#" + title.getText();
	}
	public void hide() {
		super.hide();

		clearAll();
		clearChildren();
		events.removeIns();
		System.gc();
	}

	public boolean containsMod(int modifiers) {
		// Log.info("f: @, r: @", Modifier.toString(modifiers), Modifier.toString((short) this.modifiers));
		for (ModifierR value : ModifierR.values()) {
			int mod = 1 << value.ordinal();
			if ((modifiers & mod) != 0 && (this.modifiers & mod) != 0) return true;
		}
		return false;
	}

	public static final int colspan = 8;
	public static class ReflectTable extends FilterTable<Member> {
		public final Seq<ValueLabel> labels = new Seq<>();

		public ReflectTable() {
			left().defaults().left().top();
		}

		boolean lastEmpty;
		public void build(Class<?> cls, Type type, Object[] arr) {
			lastEmpty = false;
			if (arr.length == 0 && E_JSFunc.hidden_if_empty.enabled()) {
				lastEmpty = true;
				return;
			}
			unbind();
			add(makeGenericType(() -> getName(cls), makeDetails(cls, type)))
			 .style(MOMO_LabelStyle)
			 .labelAlign(Align.left).color(Pal.accent).colspan(colspan)
			 .with(l -> l.clicked(() -> IntUI.showSelectListTable(l,
				Seq.with(arr).map(String::valueOf),
				() -> null, __ -> {}, 400, 0, true)))
			 .row();
			image().color(Color.lightGray)
			 .growX().padTop(6).colspan(colspan).row();
		}
		public void clear() {
			labels.each(ValueLabel::clearVal);
			labels.clear().shrink();
		}
	}
}
