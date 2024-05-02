package modtools.utils.ui;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.events.*;
import modtools.jsfunc.reflect.*;
import modtools.struct.Pair;
import modtools.ui.*;
import modtools.ui.components.*;
import modtools.ui.components.Window.IDisposable;
import modtools.ui.components.input.*;
import modtools.ui.components.input.area.AutoTextField;
import modtools.ui.components.limit.LimitTable;
import modtools.ui.components.utils.*;
import modtools.ui.menu.MenuItem;
import modtools.utils.*;
import modtools.utils.reflect.*;
import modtools.utils.ui.search.*;
import rhino.NativeArray;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import static ihope_lib.MyReflect.lookup;
import static modtools.events.E_JSFunc.display_synthetic;
import static modtools.jsfunc.type.CAST.box;
import static modtools.ui.HopeStyles.*;
import static modtools.utils.JSFunc.JColor.*;
import static modtools.utils.JSFunc.*;
import static modtools.utils.Tools.catchRun;
import static modtools.utils.ui.MethodTools.*;
import static modtools.utils.ui.ReflectTools.*;
import static modtools.utils.world.TmpVars.mouseVec;

@SuppressWarnings("CodeBlock2Expr")
public class ShowInfoWindow extends Window implements IDisposable {
	/* non-null */
	private final       Class<?> clazz;
	private final       Object   o;
	private final       MyEvents events = new MyEvents();
	public static final Color    tmpC1  = new Color();

	public static final String METHOD_COUNT_PREFIX = " [";

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
			cont.add(clazz.getName().toLowerCase()).color(tmpC1.set(c_type)).row();
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

		addCaptureListener(new InputListener() {
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (Core.input.ctrl() && keycode == KeyCode.f) {
					textField.requestKeyboard();
					textField.setCursorPosition(Integer.MAX_VALUE);
					if (Core.input.shift()) textField.clear();
				}
				return false;
			}
		});
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
			t.button(Icon.settingsSmall, clearNonei, () -> {
				IntUI.showSelectTableRB(mouseVec.cpy(), (p, _, _) -> {
					p.background(Styles.black6);
					p.left().defaults().left().growX();
					ISettings.buildAll("jsfunc", p, E_JSFunc.class);
					// addSettingsTable(p, "", n -> "jsfunc." + n, E_JSFunc.class);
					ISettings.buildAllWrap("jsfunc.display", p, "Display", E_JSFuncDisplay.class);
					ISettings.buildAllWrap("jsfunc.edit", p, "Edit", E_JSFuncEdit.class);
				}, false);
			}).size(42);
			// if (OS.isWindows && hasDecompiler) buildDeCompiler(t);
			t.button(Icon.refreshSmall, clearNonei, rebuild0).size(42);
			if (o != null) {
				IntUI.addStoreButton(t, "", () -> o);
				addDisplayListener(
				 t.label(() -> "" + UNSAFE.vaddressOf(o)).padLeft(8f),
				 E_JSFuncDisplay.address);
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
		this.cont.add(new ScrollPane(build, Styles.smallPane))
		 .with(p -> p.setOverscroll(false, true))
		 .grow().row();

		for (E_JSFuncDisplay value : E_JSFuncDisplay.values()) {
			events.fireIns(value);
		}
		pack();
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
			 && containsFlags(member.getModifiers()) != isBlack;
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
			Underline.of(cont, 7, Pal.accent).height(2);
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

		// classSet.clear();

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
		Constructor<?>[] constructors = IReflect.impl.getConstructors(cls);
		consTable.build(cls, type, constructors);
		Class<?>[] classes = cls.getDeclaredClasses();
		classesTable.build(cls, type, classes);
		// 字段
		for (Field f : fields) { buildField(o, fieldsTable, f); }
		checkRemovePeek(fieldsTable);
		// 函数
		for (Method m : methods) { buildMethod(o, methodsTable, m); }
		checkRemovePeek(methodsTable);
		// 构造器
		for (Constructor<?> ctor : constructors) { buildConstructor(o, consTable, ctor); }
		checkRemovePeek(consTable);
		// 类
		for (Class<?> dcls : classes) { buildClass(classesTable, dcls); }
		checkRemovePeek(classesTable);
		// 实现接口
		buildInterface(o, cls);
	}

	public static boolean find(Pattern pattern, String name) {
		return E_JSFunc.search_exact.enabled() ? pattern.matcher(name).matches() : pattern.matcher(name).find();
	}
	private static void checkRemovePeek(ReflectTable table) {
		if (!table.lastEmpty && table.current.hasChildren()) {
			table.current.getChildren().peek().remove();
		}
	}


	private static BindCell addDisplayListener(Cell<?> cell0, E_JSFuncDisplay type) {
		BindCell cell = new BindCell(cell0);
		MyEvents.on(type, b -> cell.toggle(b.enabled()));
		return cell;
	}
	private static void addModifier(Table table,
	                                CharSequence string) {
		addModifier(table, string, 0.7f);
	}
	private static void addModifier(Table table, CharSequence string, float scale) {
		addDisplayListener(table.add(new MyLabel(string, defaultLabel))
		 .color(tmpC1.set(c_keyword)).fontScale(scale)
		 .padRight(8), E_JSFuncDisplay.modifier);
	}
	private static void addRType(Table table, Class<?> type, Prov<String> details) {
		MyLabel label = makeGenericType(type, details);
		addDisplayListener(table.add(label)
		 .fontScale(0.9f)
		 .padRight(16), E_JSFuncDisplay.type);
	}

	public static void buildFieldValue(BindCell c_cell, FieldValueLabel l) {
		if (!l.isStatic() && l.getObject() == null) return;
		Class<?> type     = l.type;
		Cell<?>  cell     = c_cell.cell;
		Boolp    editable = () -> !l.isFinal() || E_JSFuncEdit.final_modify.enabled();
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
				l.enableUpdate = !field.hasKeyboard();
			});
			field.setValidator(NumberHelper::isNumber);
			field.changed(catchRun(() -> {
				if (!field.isValid()) return;
				l.setFieldValue(NumberHelper.cast(field.getText(), l.type));
			}));
			cell.setElement(field);
			cell.height(42);
			c_cell.require();
			MyEvents.on(E_JSFuncEdit.number, edit -> {
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
			field.changed(catchRun(() -> {
				String text = field.getText();
				l.setFieldValue(AutoTextField.NULL_STR.equals(text) ? null : text);
			}));
			cell.setElement(field);
			cell.height(42);
			c_cell.require();
			MyEvents.on(E_JSFuncEdit.string, edit -> {
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
		btn.clicked(catchRun(() -> {
			boolean b = !(boolean) l.val;
			btn.setText(b ? "TRUE" : "FALSE");
			l.setFieldValue(b);
		}));
		return btn;
	}

	private static void buildField(Object o, ReflectTable fields, Field f) {
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
			 MenuItem.with("field.offset.copy", Icon.copySmall, "Cpy offset", () -> {
				 JSFunc.copyText("" + FieldUtils.fieldOffset(f));
			 }),
			 MenuItem.with("field.getter.copy", Icon.copySmall, "Cpy field getter", () -> {
				 copyFieldReflection(f);
			 }),
			 MenuItem.with("val.getter.copy", Icon.copySmall, "Cpy value getter", () -> {
				 copyFieldArcReflection(f);
			 }),
			 ValueLabel.newDetailsMenuList(label, f, Field.class)
			));
			fields.add(new MyLabel(" = ", defaultLabel))
			 .color(Color.lightGray).top()
			 .touchable(Touchable.disabled);
		} catch (Throwable e) {
			MyLabel label = new MyLabel(STR."<\{e}>", defaultLabel);
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
				buildFieldValue(c_cell, l[0]);
				t.add(l[0]).minWidth(42).grow()
				 // .update(Element::validate)
				 .labelAlign(Align.topLeft);
			} catch (Throwable e) {
				l[0].setError();
			}
		}).pad(4).growX();
		addDisplayListener(fields.add(new MyHoverTable(buttons -> {
			if (!l[0].type.isPrimitive()) IntUI.addLabelButton(buttons, () -> l[0].val, type);
			IntUI.addWatchButton(buttons,
				STR."\{f.getDeclaringClass().getSimpleName()}: \{f.getName()}",
				() -> f.get(o))
			 .disabled(_ -> !l[0].isValid());
		})).right().top().colspan(0), E_JSFuncDisplay.buttons);
		fields.row();

		addUnderline(fields, 8);
	}
	private static void buildMethod(Object o, ReflectTable methods, Method m) {
		if (!display_synthetic.enabled() && m.isSynthetic()) return;
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
				 MenuItem.with("method.getter.copy", Icon.copySmall, "Cpy method getter", () -> {
					 copyExecutableReflection(m);
				 }),
				 MenuItem.with("val.getter.copy", Icon.copySmall, "Cpy value getter", () -> {
					 copyExecutableArcReflection(m);
				 }),
				 MenuItem.with("method.invoke", Icon.boxSmall, "Invoke", () -> {
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
				 MenuItem.with("method.invokeSpecial", Icon.boxSmall, "InvokeSpecial", () -> {
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
				t.add(l).grow();

				buttonsCell = t.add(new MyHoverTable(buttons -> {
					if (isSingle && isValid) {
						buttons.button(Icon.rightOpenOutSmall, flati, catchRun("invoke出错", () -> {
							dealInvokeResult(m.invoke(o), cell, l);
						}, l)).size(IntUI.FUNCTION_BUTTON_SIZE);
					}
					if (!l.type.isPrimitive()) IntUI.addLabelButton(buttons, () -> l.val, l.type);
				})).right().colspan(1);
				if (buttonsCell != null) addDisplayListener(buttonsCell, E_JSFuncDisplay.buttons);
			}).grow().left();
		} catch (Throwable err) {
			MyLabel label = new MyLabel(STR."<\{err}>", defaultLabel);
			label.setColor(Color.red);
			methods.add(label);
			Log.err(err);
		}
		methods.row();

		addUnderline(methods, 4);
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
	private static void foldUnwrap(ReflectTable table, Member member, MyLabel label,
	                               Element attribute) {
		if (table.skip || !E_JSFunc.folded_name.enabled()) return;
		Core.app.post(() -> {
			int size = table.map.get(member.getName()).getSecond(Seq::new).size;
			if (size == 1) return;
			label.setText(STR."\{label.getText()}\{METHOD_COUNT_PREFIX}\{size}]");
		});
		IntUI.doubleClick(label, () -> {
			if (!table.map.get(member.getName(), Pair::new).getFirst(ShowInfoWindow::newPairTable).hasChildren())
				return;
			IntUI.showSelectTable(attribute, (p, _, _) -> {
				table.left().top().defaults().left().top();
				var   pair = table.map.get(member.getName());
				Table one  = pair.getFirst(ShowInfoWindow::newPairTable);
				p.add(one).self(c -> c.update(_ -> {
					c.width(table.getWidth());
				}).right().grow().get());
				Time.runTask(6f, one::invalidateHierarchy);
			}, false, Align.topLeft).table.background(Styles.black6);
		}, null);
	}
	private static void buildConstructor(Object o, ReflectTable table, Constructor<?> ctor) {
		table.bind(ctor);
		setAccessible(ctor);
		try {
			addModifier(table, buildExecutableModifier(ctor));
			MyLabel label = new MyLabel(ctor.getDeclaringClass().getSimpleName(), defaultLabel);
			label.color.set(c_type);
			boolean isSingle = ctor.getParameterCount() == 0;
			IntUI.addShowMenuListenerp(label, () -> Seq.with(
			 MenuItem.with("ctor.getter.copy", Icon.copySmall, "Cpy reflect getter", () -> {
				 copyExecutableReflection(ctor);
			 }),
			 MenuItem.with("<init>handle.copy", o == null ? Icon.copySmall : Icon.boxSmall,
				o == null ? "Cpy <init> handle" : "Invoke <init> method", catchRun(() -> {
					MethodHandle init = InitMethodHandle.findInit(ctor.getDeclaringClass(), ctor);
					if (o == null) {
						copyValue("Handle", init);
					}
					if (isSingle) {
						if (o != null) init.invoke(o);
					} else JSRequest.<NativeArray>requestForMethod(ctor, o, arr -> {
						Seq<Class<?>> parmas = Seq.with(ctor.getParameterTypes());
						parmas.insert(0, ctor.getDeclaringClass());
						init.invokeWithArguments(convertArgs(arr, parmas.toArray()));
					});
				})),
			 MenuItem.with("constructor.invokeSpecial", Icon.boxSmall, "InvokeSpecial", () -> {
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
			table.add(label);
			table.add(buildArgsAndExceptions(ctor)).growY();
			/* 占位符 */
			table.add().grow().top();

			/* addDisplayListener(t.table(buttons -> {
				addStoreButton(buttons, Core.bundle.get("jsfunc.constructor", "Constructor"), () -> cons);
			}).grow().top().right(), JSFuncDisplay.buttons); */
			table.row();
		} catch (Throwable e) {
			Log.err(e);
		}
		addUnderline(table, 7);
	}
	private static void buildClass(ReflectTable table, Class<?> cls) {
		table.bind(new ClassMember(cls));
		table.table(t -> {
			t.left().top().defaults().top();
			try {
				addModifier(t, STR."\{Modifier.toString(cls.getModifiers() & ~Modifier.classModifiers())} \{cls.isInterface() ? "" : "class"}", 1);

				MyLabel l = newCopyLabel(t, getGenericString(cls));
				l.color.set(c_type);
				IntUI.addShowMenuListenerp(l, () -> Seq.with(
				 IntUI.copyAsJSMenu("class", () -> cls),
				 ValueLabel.newDetailsMenuList(l, cls, Class.class)
				));
				Class<?>[] types = cls.getInterfaces();
				if (types.length > 0) {
					t.add(new MyLabel(" implements ", defaultLabel)).color(tmpC1.set(c_keyword)).padRight(8f).touchable(Touchable.disabled);
					for (Class<?> interf : types) {
						t.add(new MyLabel(getGenericString(interf), defaultLabel)).padRight(8f).color(tmpC1.set(c_type));
					}
				}

				addDisplayListener(t.add(new MyHoverTable(buttons -> {
					IntUI.addDetailsButton(buttons, () -> null, cls);
					// addStoreButton(buttons, Core.bundle.get("jsfunc.class", "Class"), () -> cls);
				})).grow().colspan(0), E_JSFuncDisplay.buttons);
			} catch (Throwable e) {
				Log.err(e);
			}
		}).growX().left().top().row();
		addUnderline(table, 6);
	}

	/** 双击复制文本内容 */
	private static MyLabel newCopyLabel(Table table, String text) {
		MyLabel label = new MyLabel(text, defaultLabel);
		table.add(label).growY().labelAlign(Align.top)/* .self(c -> {
			if (Vars.mobile && type != null) c.tooltip(getGenericString(type));
		}) */;
		addDClickCopy(label, s -> s.replaceAll(" \\[\\d+]", ""));
		return label;
	}


	public String toString() {
		return STR."\{getClass().getSimpleName()}#\{title.getText()}";
	}
	public void hide() {
		super.hide();

		clearAll();
		clearChildren();
		events.removeIns();
		System.gc();
	}

	public boolean containsFlags(int modifiers) {
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

		public ReflectTable() {
			left().defaults().left().top();
		}
		boolean skip;
		Table   skipTable;

		public <T extends Element> Cell<T> add(T element) {
			var cell = skip ? skipTable.add(element) :
			 (current == null || !isBound() ? super.add(element) : current.add(element));
			bindCell(element, cell);
			return cell;
		}

		boolean lastEmpty;
		Table   current;
		public void bind(Member member) {
			super.bind(member);
			skip = false;
			if (member instanceof Constructor || member instanceof ClassMember) return;

			skip = E_JSFunc.folded_name.enabled() && map.containsKey(member.getName());
			skipTable = skip ? map.get(member.getName(), Pair::new).getFirst(ShowInfoWindow::newPairTable) : null;
			if (skip) add(getName(member.getDeclaringClass()))
			 .fontScale(0.7f).color(Pal.accent).left().padRight(4f);

			map.get(member.getName(), Pair::new)
			 .getSecond(Seq::new).add(member);
		}
		public Table row() {
			return current == null || !isBound() ? super.row() :
			 (skip ? skipTable : current).row();
		}
		public void unbind() {
			super.unbind();
			skip = false;
		}
		public void build(Class<?> cls, Type type, Object[] arr) {
			unbind();
			current = table().growX().name(cls.getSimpleName()).get();
			current.left().defaults().left().top();
			super.row();
			if (arr.length == 0 && E_JSFunc.hidden_if_empty.enabled()) {
				lastEmpty = true;
				return;
			}
			lastEmpty = false;
			current.add(makeGenericType(() -> getName(cls), makeDetails(cls, type)))
			 .style(defaultLabel)
			 .labelAlign(Align.left)
			 .color(cls.isInterface() ? Color.lightGray : Pal.accent)
			 .colspan(colspan)
			 .with(l -> l.clicked(() -> IntUI.showSelectListTable(l,
				Seq.with(arr).map(String::valueOf),
				() -> null, _ -> { }, 400, 0, true, Align.left)))
			 .row();
			Underline.of(current, colspan, Color.lightGray).padTop(6);
		}
		public void clear() {
			labels.each(ValueLabel::clearVal);
			labels.clear().shrink();
		}
	}
	static class MyHoverTable extends HoverTable {
		public MyHoverTable(Cons<Table> cons) {
			super(cons);
			right().top().defaults().right().top();
			marginRight(6f);
			stickX = true;
		}
	}
	private static void addUnderline(ReflectTable table,
	                                 int colspan) {
		Underline.of(table, colspan, tmpC1.set(c_underline));
	}

	static Table newPairTable() {
		return new Table(t -> t.left().defaults().left().top());
	}
}
