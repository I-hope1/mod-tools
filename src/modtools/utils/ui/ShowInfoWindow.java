package modtools.utils.ui;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.math.Interp;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.events.*;
import modtools.jsfunc.INFO_DIALOG;
import modtools.jsfunc.reflect.*;
import modtools.ui.*;
import modtools.ui.components.Window;
import modtools.ui.components.Window.IDisposable;
import modtools.ui.components.input.*;
import modtools.ui.components.input.area.*;
import modtools.ui.components.input.highlight.JavaSyntax;
import modtools.ui.components.limit.LimitTable;
import modtools.ui.components.utils.*;
import modtools.ui.menu.MenuList;
import modtools.utils.*;
import modtools.struct.Pair;
import modtools.utils.reflect.*;
import modtools.utils.ui.search.*;
import rhino.*;

import java.io.StringWriter;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import static ihope_lib.MyReflect.lookup;
import static modtools.IntVars.hasDecompiler;
import static modtools.ui.HopeStyles.*;
import static modtools.ui.content.SettingsUI.addSettingsTable;
import static modtools.utils.JSFunc.*;
import static modtools.utils.JSFunc.JColor.*;
import static modtools.utils.MySettings.*;
import static modtools.utils.Tools.*;
import static modtools.utils.ui.MethodTools.*;
import static modtools.utils.ui.ReflectTools.*;

@SuppressWarnings("CodeBlock2Expr")
public class ShowInfoWindow extends Window implements IDisposable {
	/* non-null */
	private final Class<?> clazz;
	private final Object   o;
	private final MyEvents events = new MyEvents();

	private ReflectTable
	 fieldsTable,
	 methodsTable,
	 consTable,
	 classesTable;
	final Set<Class<?>> classSet = new HashSet<>();

	public ShowInfoWindow(Object o, Class<?> clazz) {
		super(getName(clazz), 200, 200, true);
		this.o = o;
		this.clazz = clazz;
		if (clazz.isPrimitive()) {
			cont.add("<PRIMITIVE>").color(Color.gray).row();
			cont.add(clazz.getName()).color(Tmp.c1.set(c_type)).row();
			cont.add(String.valueOf(o));
			return;
		}
		MyEvents prev = MyEvents.current;
		MyEvents.current = events;
		build();
		MyEvents.current = prev;
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
			pattern = PatternUtils.compileRegExpOrNull(text);
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
			t.button(Icon.settingsSmall, HopeStyles.clearNonei, () -> {
				IntUI.showSelectTableRB(Core.input.mouse().cpy(), (p, hide, ___) -> {
					addSettingsTable(p, "", n -> "jsfunc." + n, D_JSFUNC, E_JSFunc.values());
					addSettingsTable(p, "Display", n -> "jsfunc.display." + n, D_JSFUNC_DISPLAY, E_JSFuncDisplay.values());
					addSettingsTable(p, "Edit", n -> "jsfunc.edit." + n, D_JSFUNC_EDIT, E_JSFuncEdit.values());
				}, false);
			}).size(42);
			if (OS.isWindows && hasDecompiler) buildDeCompiler(t);
			t.button(Icon.refreshSmall, HopeStyles.clearNonei, rebuild0).size(42);
			if (o != null) {
				IntUI.addStoreButton(t, "", () -> o);
				t.label(() -> "" + UNSAFE.addressOf(o)).padLeft(8f);
			}
		}).height(42).row();
		cont.table(t -> {
			ElementUtils.addCodedBtn(t, "modifiers", 4,
			 i -> {
				 modifiers = i;
				 rebuild0.run();
			 }, () -> modifiers, ModifierR.values());
			t.button(Tex.whiteui, 32, null).size(42).with(img -> {
				img.clicked(() -> {
					isBlack = !isBlack;
					img.getStyle().imageUpColor = isBlack ? Color.darkGray : Color.white;
					rebuild0.run();
				});
			});
			t.image(Icon.zoom).size(42);
			t.add(textField).growX();
		}).row();
		cont.table(t -> {
			t.left().defaults().left();
			t.pane(t0 -> t0.left().add(clazz.getTypeName(), defaultLabel).left())
			 .with(p -> p.setScrollingDisabledY(true)).grow().uniform();
			t.button(Icon.copySmall, Styles.cleari, () -> {
				copyText(clazz.getTypeName(), t);
			}).size(32);
			if (o == null) t.add("NULL", defaultLabel).color(Color.red).padLeft(8f);
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
		t.button("Decompile", HopeStyles.flatBordert, new Runnable() {
			public void run() {
				StringWriter stringWriter = new StringWriter();
				com.strobel.decompiler.Decompiler.decompile(
				 // clazz.getClassLoader().getResource()
				 clazz.getName().replace('.', '/') + ".class",
				 new com.strobel.decompiler.PlainTextOutput(stringWriter)
				);
				TextAreaTab textarea = new TextAreaTab(stringWriter.toString());
				textarea.syntax = new JavaSyntax(textarea);
				INFO_DIALOG.window(d -> {
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
			 && containsMod(member.getModifiers()) != isBlack;
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

		// return mainRun;
	}
	private void buildInterface(Object o, Class<?> clazz) {
		Type[]     interfaceTypes = clazz.getGenericInterfaces();
		Class<?>[] interfaces     = clazz.getInterfaces();
		for (int i = 0, clazzInterfacesLength = interfaces.length; i < clazzInterfacesLength; i++) {
			buildAllByClass(o, interfaces[i], interfaceTypes[i]);
		}
	}
	private void buildAllByClass(Object o, Class<?> cls, Type type) {
		if (!classSet.add(cls)) return;
		Field[] fields = IReflect.impl.getFields(cls);
		fieldsTable.build(cls, type, fields);
		Method[] methods = IReflect.impl.getMethods(cls);
		methodsTable.build(cls, type, methods);
		Constructor<?>[] constructors1 = IReflect.impl.getConstructors(cls);
		consTable.build(cls, type, constructors1);
		Class<?>[] classes = cls.getDeclaredClasses();
		classesTable.build(cls, type, classes);
		// 字段
		for (Field f : fields) {buildField(o, fieldsTable, f);}
		checkRemovePeek(fieldsTable);
		// 函数
		for (Method m : methods) {buildMethod(o, methodsTable, m);}
		checkRemovePeek(methodsTable);
		// 构造器
		for (Constructor<?> cons : constructors1) {buildConstructor(consTable, cons);}
		checkRemovePeek(consTable);
		// 类
		for (Class<?> dcls : classes) {buildClass(classesTable, dcls);}
		checkRemovePeek(classesTable);

		// 实现接口
		buildInterface(o, cls);
	}

	public static boolean find(Pattern pattern, String name) {
		return E_JSFunc.search_exact.enabled() ? pattern.matcher(name).matches() : pattern.matcher(name).find();
	}
	private static void checkRemovePeek(ReflectTable table) {
		if (!table.lastEmpty && table.current.hasChildren()) table.current.getChildren().peek().remove();
	}


	private BindCell addDisplayListener(Cell<?> cell0, E_JSFuncDisplay type) {
		BindCell cell = new BindCell(cell0);
		events.onIns(type, b -> cell.toggle(b.enabled()));
		return cell;
	}
	private void addModifier(Table table, CharSequence string) {
		addDisplayListener(table.add(new MyLabel(string, defaultLabel))
		 .color(Tmp.c1.set(c_keyword)).fontScale(0.7f)
		 .padRight(8), E_JSFuncDisplay.modifier);
	}
	private void addRType(Table table, Class<?> type, Prov<String> details) {
		MyLabel label = makeGenericType(type, details);
		addDisplayListener(table.add(label)
		 .fontScale(0.9f)
		 .padRight(16), E_JSFuncDisplay.type);
	}

	static void applyChangedFx(Element element) {
		new LerpFun(Interp.slowFast).rev().onUI().registerDispose(0.05f, fin -> {
			Draw.color(Pal.heal, fin);
			Lines.stroke(3f - fin * 2f);
			Vec2  e    = ElementUtils.getAbsPosCenter(element);
			float fout = 1 - fin;
			Fill.rect(e.x, e.y, fout * element.getWidth() * 1.2f, fout * element.getHeight() * 1.2f);

			/* Draw.color(Pal.powerLight, fout);
			Angles.randLenVectors(new Rand().nextInt(), 4, element.getWidth(), (x, __) -> {
				Angles.randLenVectors(new Rand().nextInt(), 4, element.getHeight(), (___, y) -> {
					Fill.circle(e.x + x, e.y + y, fin * 2);
				});
			}); */
		});
	}

	public void buildFieldValue(Class<?> type, BindCell c_cell, FieldValueLabel l) {
		if (!l.isStatic() && l.getObject() == null) return;
		Cell<?> cell     = c_cell.cell;
		Boolp   editable = () -> !l.isFinal() || E_JSFuncEdit.final_modify.enabled();
		/* Color的实现是Color#set方法 */
		if (l.val instanceof Color) {
			IntUI.colorBlock(cell, (Color) l.val, l::setVal);
			c_cell.require();
		} else if (type == Boolean.TYPE || type == Boolean.class) {
			var btn = getBoolButton(l, editable);
			cell.setElement(btn);
			cell.size(96, 42);
			c_cell.require();
		} else if (Number.class.isAssignableFrom(box(type)) && editable.get()) {
			var field = new AutoTextField();
			l.afterSet = () -> field.setTextCheck(String.valueOf(l.getText()));
			l.afterSet.run();
			field.update(() -> {
				l.enableUpdate = Core.scene.getKeyboardFocus() != field;
			});
			field.setValidator(NumberHelper::isNumber);
			field.changed(() -> {
				if (!field.isValid()) return;
				l.setFieldValue(NumberHelper.cast(field.getText(), type));
			});
			cell.setElement(field);
			cell.height(42);
			c_cell.require();
			events.onIns(E_JSFuncEdit.number, edit -> {
				cell.setElement(edit.enabled() ? field : null);
				c_cell.require();
			});
		} else if (E_JSFuncEdit.string.enabled() && type == String.class && editable.get()) {
			cell.row();
			var field = new AutoTextField();
			l.afterSet = () -> field.setTextCheck((String) l.val);
			l.afterSet.run();
			field.update(() -> {
				l.enableUpdate = Core.scene.getKeyboardFocus() != field;
			});
			field.changed(() -> {
				String text = field.getText();
				l.setFieldValue(AutoTextField.NULL_STR.equals(text) ? null : text);
			});
			cell.setElement(field);
			cell.height(42);
			c_cell.require();
			events.onIns(E_JSFuncEdit.string, edit -> {
				cell.setElement(edit.enabled() ? field : null);
				c_cell.require();
			});
		}
	}
	private static TextButton getBoolButton(FieldValueLabel l, Boolp editable) {
		var btn = new TextButton("", HopeStyles.flatTogglet);
		btn.setDisabled(() -> !editable.get());
		btn.update(() -> {
			l.setVal();
			if (l.val == null) {
				btn.setDisabled(() -> true);
				btn.setText("ERROR");
				return;
			}
			btn.setText((boolean) l.val ? "TRUE" : "FALSE");
			btn.setChecked((boolean) l.val);
		});
		btn.clicked(() -> {
			boolean b = !(boolean) l.val;
			btn.setText(b ? "TRUE" : "FALSE");
			try {
				l.setFieldValue(b);
			} catch (Throwable e) {
				IntUI.showException(e).moveToMouse();
			}
			l.setVal(b);
		});
		return btn;
	}

	private void buildField(Object o, ReflectTable fields, Field f) {
		fields.bind(f);
		setAccessible(f);

		Class<?> type      = f.getType();
		int      modifiers = f.getModifiers();
		try {
			Element attribute = fields.table(attr -> {
				attr.defaults().left();
				// modifiers
				addModifier(attr, Modifier.toString(modifiers));
				// type
				addRType(attr.row(), type, makeDetails(type, f.getGenericType()));
			}).get();
			// name
			MyLabel label = newCopyLabel(fields, f.getName());
			foldUnwrap(fields, f, label, attribute);
			IntUI.addShowMenuListenerp(label, () -> Seq.with(
			 IntUI.copyAsJSMenu("Field", () -> f),
			 MenuList.with(Icon.copySmall, "Cpy offset", () -> {
				 JSFunc.copyText("" + FieldUtils.fieldOffset(f));
			 }),
			 MenuList.with(Icon.copySmall, "Cpy field getter", () -> {
				 copyFieldReflection(f);
			 }),
			 MenuList.with(Icon.copySmall, "Cpy value getter", () -> {
				 copyFieldArcReflection(f);
			 }),
			 ValueLabel.newDetailsMenuList(label, f, Field.class)
			));
			fields.add(new MyLabel(" = ", defaultLabel))
			 .color(Color.lightGray).top()
			 .touchable(Touchable.disabled);
		} catch (Throwable e) {
			MyLabel label = new MyLabel("<" + e + ">", defaultLabel);
			label.setColor(Color.red);
			fields.add(label);
			Log.err(e);
		}
		FieldValueLabel[] l = {null};
		fields.table(t -> {
			t.left().defaults().left();
			// 占位符
			Cell<?>  cell   = t.add().top();
			BindCell c_cell = addDisplayListener(cell, E_JSFuncDisplay.value);
			/*Cell<?> lableCell = */
			l[0] = new FieldValueLabel(ValueLabel.unset, type, f, o);
			if (Enum.class.isAssignableFrom(type)) l[0].addEnumSetter();
			fields.labels.add(l);

			try {
				l[0].setVal();
				buildFieldValue(type, c_cell, l[0]);
				t.add(l[0]).minWidth(42).growX().uniformX()
				 .labelAlign(Align.topLeft);
			} catch (Throwable e) {
				l[0].setError();
			}
		}).pad(4).growX();
		addDisplayListener(fields.add(new HoverTable(buttons -> {
			buttons.right().top().defaults().right().top();
			IntUI.addLabelButton(buttons, () -> l[0].val, type);
			IntUI.addWatchButton(buttons, f.getDeclaringClass().getSimpleName() + ": " + f.getName(), () -> f.get(o))
			 .disabled(!l[0].isValid());
		})).top().padRight(-10).width(64)/* .colspan(0) */, E_JSFuncDisplay.buttons);
		fields.row();
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
			Element attribute = methods.table(attr -> {
				attr.defaults().left();
				// modifiers
				addModifier(attr, buildExecutableModifier(m));
				// return type
				addRType(attr.row(), m.getReturnType(),
				 makeDetails(m.getReturnType(), m.getGenericReturnType()));
			}).get();
			// method name
			MyLabel label = newCopyLabel(methods, m.getName());
			foldUnwrap(methods, m, label, attribute);

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

				ValueLabel l = new MethodValueLabel(o, m);
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
					 if (isSingle) {
						 catchRun(() -> dealInvokeResult(m.invoke(o), cell, l),
							l).run();
						 return;
					 }
					 JSRequest.<NativeArray>requestForMethod(m, o, arr -> {
						 dealInvokeResult(invokeForMethod(o, m, l, arr,
							args -> m.invoke(o, args)), cell, l);
					 });
				 }),
				 MenuList.with(Icon.boxSmall, "InvokeSpecial", () -> {
					 MethodHandle handle = getHandle(m);
					 if (isSingle) {
						 catchRun(() -> dealInvokeResult(handle.invokeWithArguments(o), cell, l)
							, l).run();
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

				buttonsCell = t.add(new HoverTable(buttons -> {
					buttons.right().top().defaults().right().top();
					if (isSingle && isValid) {
						buttons.button(Icon.rightOpenOutSmall, flati, catchRun("invoke出错", () -> {
							dealInvokeResult(m.invoke(o), cell, l);
						}, l)).size(32, 32);
					}
					IntUI.addLabelButton(buttons, () -> l.val, l.type);
					// addStoreButton(buttons, Core.bundle.get("jsfunc.method", "Method"), () -> m);
				})).grow().top().right().colspan(2);
				if (buttonsCell != null) addDisplayListener(buttonsCell, E_JSFuncDisplay.buttons);
			}).grow();
		} catch (Throwable err) {
			MyLabel label = new MyLabel("<" + err + ">", defaultLabel);
			label.setColor(Color.red);
			methods.add(label);
			Log.err(err);
		}
		methods.row();
		methods.image().color(Tmp.c1.set(c_underline)).growX().colspan(7).row();
	}
	private static MethodHandle getHandle(Method m) {
		try {
			return lookup.unreflectSpecial(m, m.getDeclaringClass());
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}
	private static MethodHandle getHandle(Constructor<?> ctor) {
		try {
			return lookup.unreflectConstructor(ctor);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}
	private void foldUnwrap(ReflectTable table, Member member, MyLabel label, Element attribute) {
		if (table.skip || !E_JSFunc.folded_name.enabled()) return;
		Core.app.post(() -> {
			int size = table.map.get(member.getName()).getSecond(Seq::new).size;
			if (size == 1) return;
			label.setText(label.getText() + " [" + size + "]");
		});
		IntUI.doubleClick(label, () -> {
			if (!table.map.get(member.getName(), Pair::new).getFirst(ShowInfoWindow::newPairTable).hasChildren()) return;
			IntUI.showSelectTable(attribute, (p, hide, text) -> {
				table.left().top().defaults().left().top();
				var   pair = table.map.get(member.getName());
				Table one  = pair.getFirst(ShowInfoWindow::newPairTable);
				p.add(one).right().grow().get();
				Time.runTask(6f, one::invalidateHierarchy);
			}, false, Align.topLeft);
		}, null);
	}
	private void buildConstructor(ReflectTable t, Constructor<?> ctor) {
		setAccessible(ctor);
		try {
			addModifier(t, buildExecutableModifier(ctor));
			MyLabel label = new MyLabel(ctor.getDeclaringClass().getSimpleName(), defaultLabel);
			label.color.set(c_type);
			boolean isSingle = ctor.getParameterCount() == 0;
			IntUI.addShowMenuListenerp(label, () -> Seq.with(
			 MenuList.with(Icon.copySmall, "Cpy reflect getter", () -> {
				 copyExecutableReflection(ctor);
			 }),
			 MenuList.with(Icon.copySmall, "Cpy <init> handle", catchRun(() -> {
				 copyValue("Handle", InitMethodHandle.findInit(ctor.getDeclaringClass(), ctor));
			 })),
			 MenuList.with(Icon.boxSmall, "Invoke", () -> {
				 if (isSingle) {
					 catchRun(() -> JSFunc.copyValue("Instance", ctor.newInstance())
						, label).run();
					 return;
				 }
				 JSRequest.<NativeArray>requestForMethod(ctor, o, arr -> {
					 JSFunc.copyValue("Instance", ctor.newInstance(
						convertArgs(arr, ctor.getParameterTypes())
					 ));
				 });
			 }),
			 MenuList.with(Icon.boxSmall, "InvokeSpecial", () -> {
				 MethodHandle handle = getHandle(ctor);
				 if (isSingle) {
					 catchRun(() -> JSFunc.copyValue("Instance", handle.invoke())
						, label).run();
					 return;
				 }
				 JSRequest.<NativeArray>requestForMethod(ctor, o, arr -> {
					 JSFunc.copyValue("Instance", handle.invokeWithArguments(
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
		table.bind(wrapClass(cls));
		table.table(t -> {
			t.left().top().defaults().top();
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
					t.add(new MyLabel(" implements ", defaultLabel)).color(Tmp.c1.set(c_keyword)).padRight(8f).touchable(Touchable.disabled);
					for (Class<?> interf : types) {
						t.add(new MyLabel(getGenericString(interf), defaultLabel)).padRight(8f).color(Tmp.c1.set(c_type));
					}
				}

				addDisplayListener(t.add(new HoverTable(buttons -> {
					buttons.right().defaults().right();
					IntUI.addDetailsButton(buttons, () -> null, cls);
					// addStoreButton(buttons, Core.bundle.get("jsfunc.class", "Class"), () -> cls);
				})).grow().colspan(0), E_JSFuncDisplay.buttons);
			} catch (Throwable e) {
				Log.err(e);
			}
		}).pad(4).growX().left().top().row();
		table.image().color(Tmp.c1.set(c_underline)).growX().colspan(6).row();
	}

	/** 双击复制文本内容 */
	private static MyLabel newCopyLabel(Table table, String text) {
		MyLabel label = new MyLabel(text, defaultLabel);
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

		final ObjectMap<String, Pair<Table, Seq<Member>>> map = new ObjectMap<>();

		public void act(float delta) {
			Tools.runLoggedException(() -> super.act(delta));
		}
		public ReflectTable() {
			left().defaults().left().top();
		}
		boolean skip;
		Table   skipTable;

		public <T extends Element> Cell<T> add(T element) {
			var cell = skip ? skipTable.add(element) : (current == null || !isBound() ? super.add(element) : current.add(element));
			bindCell(element, cell);
			return cell;
		}

		boolean lastEmpty;
		Table   current;
		public void bind(Member member) {
			super.bind(member);
			skip = false;
			if (member.getClass().getClassLoader() != Class.class.getClassLoader()) return;

			skip = E_JSFunc.folded_name.enabled() && map.containsKey(member.getName());
			skipTable = skip ? map.get(member.getName(), Pair::new).getFirst(ShowInfoWindow::newPairTable) : null;
			if (skip) add(getName(member.getDeclaringClass()))
			 .fontScale(0.7f).color(Pal.accent).left().padRight(4f);

			map.get(member.getName(), Pair::new)
			 .getSecond(Seq::new).add(member);
		}
		public Table row() {
			return current == null || !isBound() ? super.row() : current.row();
		}
		public void unbind() {
			super.unbind();
			skip = false;
		}
		public void build(Class<?> cls, Type type, Object[] arr) {
			unbind();
			current = table().name(cls.getSimpleName()).get();
			current.left().defaults().left().top();
			super.row();
			lastEmpty = false;
			if (arr.length == 0 && E_JSFunc.hidden_if_empty.enabled()) {
				lastEmpty = true;
				return;
			}
			current.add(makeGenericType(() -> getName(cls), makeDetails(cls, type)))
			 .style(defaultLabel)
			 .labelAlign(Align.left).color(cls.isInterface() ? Color.lightGray : Pal.accent).colspan(colspan)
			 .with(l -> l.clicked(() -> IntUI.showSelectListTable(l,
				Seq.with(arr).map(String::valueOf),
				() -> null, __ -> {}, 400, 0, true, Align.left)))
			 .row();
			current.image().color(Color.lightGray)
			 .growX().padTop(6).colspan(colspan).row();
		}
		public void clear() {
			labels.each(ValueLabel::clearVal);
			labels.clear().shrink();
		}
	}
	static Table newPairTable() {
		return new Table(t -> t.left().defaults().left().top());
	}
}
