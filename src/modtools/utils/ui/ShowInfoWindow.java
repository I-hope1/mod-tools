package modtools.utils.ui;

import arc.Core;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.style.*;
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
import modtools.ui.comp.*;
import modtools.ui.comp.Window.IDisposable;
import modtools.ui.comp.input.*;
import modtools.ui.comp.input.area.AutoTextField;
import modtools.ui.comp.limit.LimitTable;
import modtools.ui.comp.linstener.FocusSearchListener;
import modtools.ui.comp.utils.*;
import modtools.ui.menu.*;
import modtools.utils.*;
import modtools.utils.reflect.*;
import modtools.utils.ui.search.*;
import rhino.*;

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
import static modtools.utils.Tools.runT;
import static modtools.utils.ui.MethodBuilder.*;
import static modtools.utils.ui.ReflectTools.*;
import static modtools.IntVars.mouseVec;

@SuppressWarnings("CodeBlock2Expr")
public class ShowInfoWindow extends Window implements IDisposable {
	public static final String whenExecuting       = "An exception occurred when executing";
	public static final String METHOD_COUNT_PREFIX = " [";


	/* non-null */
	private final       Class<?> clazz;
	private final       Object   obj;
	private final       MyEvents events   = new MyEvents();
	public static final Color    tmpColor = new Color();

	private ReflectTable
	 fieldsTable,
	 methodsTable,
	 consTable,
	 classesTable;
	final Set<Class<?>> classSet = new HashSet<>();

	public ShowInfoWindow(Object obj, Class<?> clazz) {
		super(getName(clazz), 200, 200, true);
		this.obj = obj;
		this.clazz = clazz;
		if (clazz.isPrimitive()) {
			cont.add("<PRIMITIVE>").color(Color.gray).row();
			cont.add(clazz.getName().toLowerCase()).color(tmpColor.set(c_type)).row();
			cont.add(String.valueOf(obj));
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
		buildReflect(obj, build, pattern, isBlack);
	}
	public boolean isBlack;
	public Table   build;
	public Pattern pattern;
	public void build() {
		build = new LimitTable();
		// 默认左居中
		build.left().top().defaults().left();
		textField = new TextField();

		addCaptureListener(new FocusSearchListener(textField));
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

		final Table topTable = new Table();
		// 默认左居中
		topTable.left().defaults().left().growX();
		// 功能按钮栏
		topTable.pane(t -> {
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
			if (obj != null) {
				IntUI.addStoreButton(t, "", () -> obj);
				markDisplay(
				 t.label(() -> "" + UNSAFE.vaddressOf(obj)).padLeft(8f),
				 E_JSFuncDisplay.address);
			}
		}).height(42).row();
		// 搜索栏
		topTable.table(t -> {
			addCodedBtn(t, "modifiers", 4,
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
		}).growX().row();
		// 信息栏
		topTable.table(t -> {
			t.left().defaults().left();
			t.pane(t0 -> t0.left().add(clazz.getTypeName(), defaultLabel).left())
			 .with(p -> p.setScrollingDisabledY(true)).grow().uniform();
			t.button(Icon.copySmall, Styles.cleari, () -> {
				copyText(clazz.getTypeName(), t);
			}).size(32);
			if (obj == null) t.add("NULL", defaultLabel).color(Color.red).padLeft(8f);
		}).pad(6, 10, 6, 10).row();
		rebuild.get(null);
		// cont.add(build).grow();

		this.cont.add(topTable).row();
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
		if (cont.getChildren().size > 0) {
			Boolf<Member> memberBoolf = member ->
			 (pattern == null || find(pattern, member.getName()) != isBlack)
			 && containsFlags(member.getModifiers()) != isBlack;
			fieldsTable.filter(memberBoolf);
			fieldsTable.labels.each(ValueLabel::flushVal);
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
			Underline.of(cont, 7, Pal.accent).height(2).fill(0.75f, 0);
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

		Type type = clazz;
		for (Class<?> cls = clazz; cls != null; type = cls.getGenericSuperclass(), cls = cls.getSuperclass()) {
			buildAllByClass(o, cls, type);
		}
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
		// if (cls.isHidden()) fields = new Field[0];
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

	static BindCell markDisplay(Cell<?> cell0, E_JSFuncDisplay type) {
		BindCell cell = BindCell.of(cell0);
		MyEvents.on(type, () -> cell.toggle(type.enabled()));
		MyEvents.on(Disposable.class, cell::clear);
		return cell;
	}

	static void buildModifier(Table table, CharSequence string) {
		buildModifier(table, string, 0.7f);
	}
	static void buildModifier(Table table, CharSequence string, float scale) {
		markDisplay(keyword(table, string).fontScale(scale), E_JSFuncDisplay.modifier);
	}
	static void buildReturnType(Table table, Class<?> type, Prov<String> details) {
		MyLabel label = makeGenericType(type, details);
		markDisplay(table.add(label)
		 .fontScale(0.9f)
		 .padRight(16), E_JSFuncDisplay.type);
	}

	public static void buildExtendingField(BindCell c_cell, ValueLabel l) {
		if (l.readOnly()) return;
		Class<?> type     = l.type;
		Cell<?>  cell     = c_cell.cell;
		Boolp    editable = () -> !l.isFinal() || E_JSFuncEdit.final_modify.enabled();
		/* Color的实现是Color#set方法 */
		if (l.val instanceof Color) {
			ColorBlock.of(cell, (Color) l.val, l::setVal);
			c_cell.require();
		} else if (type == Boolean.TYPE || type == Boolean.class) {
			var btn = newBoolButton(l, editable);
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
			field.changed(Tools.runT(() -> {
				if (!field.isValid()) return;
				l.setNewVal(NumberHelper.parse(field.getText(), l.type));
			}));
			cell.setElement(field);
			cell.height(42);
			c_cell.require();
			MyEvents.on(E_JSFuncEdit.number, () -> {
				cell.setElement(E_JSFuncEdit.number.enabled() ? field : null);
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
			field.changed(Tools.runT(() -> {
				String text = field.getText();
				l.setNewVal(AutoTextField.NULL_STR.equals(text) ? null : text);
			}));
			cell.setElement(field);
			cell.height(42);
			c_cell.require();
			MyEvents.on(E_JSFuncEdit.string, () -> {
				cell.setElement(E_JSFuncEdit.string.enabled() ? field : null);
				c_cell.require();
			});
		}
	}
	private static TextButton newBoolButton(ValueLabel l, Boolp editable) {
		var btn = new TextButton("", HopeStyles.flatTogglet);
		btn.setDisabled(() -> !editable.get());
		btn.update(() -> {
			l.flushVal();
			if (l.val == null) {
				btn.setDisabled(() -> true);
				btn.setText("ERROR");
				return;
			}
			btn.setText((boolean) l.val ? "TRUE" : "FALSE");
			btn.setChecked((boolean) l.val);
		});
		btn.clicked(Tools.runT(() -> {
			boolean b = !(boolean) l.val;
			btn.setText(b ? "TRUE" : "FALSE");
			l.setNewVal(b);
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
				buildModifier(attr, Modifier.toString(modifiers));
				// type
				buildReturnType(attr.row(), type, makeDetails(type, f.getGenericType()));
			}).get();
			// name
			MyLabel label = newCopyLabel(fields, f.getName());
			mergeOne(fields, f, label, attribute);
			MenuBuilder.addShowMenuListenerp(label, () -> Seq.with(
			 MenuBuilder.copyAsJSMenu("Field", () -> f),
			 MenuItem.with("field.offset.copy", Icon.copySmall, "Cpy offset", () -> {
				 JSFunc.copyText("" + FieldUtils.fieldOffset(f));
			 }),
			 MenuItem.with("field.getter.copy", Icon.copySmall, "Cpy field getter", () -> {
				 copyFieldReflection(f);
			 }),
			 MenuItem.with("val.getter.copy", Icon.copySmall, "Cpy value getter", () -> {
				 copyFieldArcReflection(f);
			 }),
			 ValueLabel.newDetailsMenuList(label, () -> f, Field.class)
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
			Cell<?>  cell   = extentCell(t, type, () -> l[0]);
			BindCell c_cell = markDisplay(cell, E_JSFuncDisplay.value);
			/*Cell<?> lableCell = */
			l[0] = new FieldValueLabel(ValueLabel.unset, type, f, o);
			if (Enum.class.isAssignableFrom(type)) l[0].addEnumSetter();
			fields.labels.add(l);

			try {
				l[0].flushVal();
				buildExtendingField(c_cell, l[0]);
				t.add(l[0]).minWidth(64).grow()
				 // .update(Element::validate)
				 .labelAlign(Align.topLeft);
			} catch (Throwable e) {
				l[0].setError();
			}
		}).pad(4).growX();
		markDisplay(fields.add(new MyHoverTable(buttons -> {
			if (!l[0].type.isPrimitive()) IntUI.addLabelButton(buttons, () -> l[0].val, type);
			IntUI.addWatchButton(buttons,
				STR."\{f.getDeclaringClass().getSimpleName()}: \{f.getName()}",
				() -> f.get(o))
			 .disabled(_ -> !l[0].isValid());
		})).right().top(), E_JSFuncDisplay.buttons);
		fields.row();

		addUnderline(fields, 8);
	}
	public static Cell<?> extentCell(Table t, Class<?> type, Prov<ValueLabel> l) {
		Cell<?> cell;
		if (Drawable.class.isAssignableFrom(type)) {
			cell = PreviewUtils.buildImagePreviewButton(null, t,
				() -> (Drawable) l.get().val,
				v -> l.get().setNewVal(v))
			 .padRight(4f);
		} else if (Texture.class.isAssignableFrom(type)) {
			cell = PreviewUtils.buildImagePreviewButton(null, t,
				() -> TmpVars.trd.set(Draw.wrap((Texture) l.get().val)),
				null)
			 .padRight(4f);
		} else if (TextureRegion.class.isAssignableFrom(type)) {
			cell = PreviewUtils.buildImagePreviewButton(null, t,
				() -> TmpVars.trd.set((TextureRegion) l.get().val),
				null)
			 .padRight(4f);
		} else {
			cell = t.add().top();
		}
		return cell;
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
				buildModifier(attr, buildExecutableModifier(m));
				// return type
				buildReturnType(attr.row(), m.getReturnType(),
				 makeDetails(m.getReturnType(), m.getGenericReturnType()));
			}).get();
			// method name
			MyLabel label = newCopyLabel(methods, m.getName());
			mergeOne(methods, m, label, attribute);

			// method parameters + exceptions + buttons
			methods.table(t -> {
				t.left().defaults().left();
				t.add(buildArgsAndExceptions(m)).growY().pad(4).left();

				ValueLabel[] array = {null};
				// 占位符
				Cell<?> cell = extentCell(t, m.getReturnType(), () -> array[0]);
				Cell<?> buttonsCell;

				boolean noParam = m.getParameterCount() == 0;
				boolean isValid = o != null || Modifier.isStatic(mod);
				// if (noParam && !isValid) methods.add();

				MethodValueLabel l = new MethodValueLabel(o, m);
				array[0] = l;
				methods.labels.add(l);
				MenuBuilder.addShowMenuListenerp(label, () -> Seq.with(
				 MenuBuilder.copyAsJSMenu("method", () -> m),
				 MenuItem.with("method.getter.copy", Icon.copySmall, "Cpy method getter", () -> copyExecutableReflection(m)),
				 MenuItem.with("val.getter.copy", Icon.copySmall, "Cpy value getter", () -> copyExecutableArcReflection(m)),
				 /* o == null ? null : MenuItem.with("method.override", Icon.editSmall, "Override", () -> {
					 JavaAdapter.getAdapterSelf()
				 }), */
				 MenuItem.with("method.invoke", Icon.boxSmall, "Invoke", methodInvoker(o, m, noParam, cell, l)),
				 MenuItem.with("method.invokeSpecial", Icon.boxSmall, "InvokeSpecial", methodSpecialInvoker(o, m, noParam, cell, l)),
				 ValueLabel.newDetailsMenuList(label, () -> m, Method.class)
				));
				// float[] prefW = {0};
				t.add(l).grow();

				buttonsCell = t.add(new MyHoverTable(buttons -> {
					if (noParam && isValid) {
						buttons.button(Icon.rightOpenOutSmall, flati, Tools.runT(whenExecuting, () -> {
							dealInvokeResult(m.invoke(o), cell, l);
						}, l)).size(IntUI.FUNCTION_BUTTON_SIZE);
					}
					if (!l.type.isPrimitive()) IntUI.addLabelButton(buttons, () -> l.val, l.type);
				})).right().colspan(1);
				if (buttonsCell != null) markDisplay(buttonsCell, E_JSFuncDisplay.buttons);
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

	public static void buildConstructor(Object o, ReflectTable table, Constructor<?> ctor) {
		table.bind(ctor);
		setAccessible(ctor);
		try {
			buildModifier(table, buildExecutableModifier(ctor));
			MyLabel label = new MyLabel(ctor.getDeclaringClass().getSimpleName(), defaultLabel);
			label.color.set(c_type);
			boolean noParam = ctor.getParameterCount() == 0;
			MenuBuilder.addShowMenuListenerp(label, () -> ArrayUtils.seq(
			 MenuItem.with("ctor.getter.copy", Icon.copySmall, "Cpy reflect getter", () -> copyExecutableReflection(ctor)),
			 MenuItem.with("<init>handle.copy", o == null ? Icon.copySmall : Icon.boxSmall,
				o == null ? "Cpy <init> handle" : "Invoke <init> method", ctorInitInvoker(o, ctor, noParam, label)),
			 MenuItem.with("constructor.invokeSpecial", Icon.boxSmall, "InvokeSpecial", ctorInvoker(o, ctor, noParam, label)),
			 MenuBuilder.copyAsJSMenu("constructor", () -> ctor),
			 ValueLabel.newDetailsMenuList(label, () -> ctor, Constructor.class)
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
				buildModifier(t,
				 STR."""
				 \{isNestStatic(cls) ? "static" : ""}\
				 \{Modifier.toString(cls.getModifiers() & ~Modifier.classModifiers())}\
				 \{cls.isInterface() ? "" : " class"}
				 """, 1);

				MyLabel l = newCopyLabel(t, getGenericString(cls));
				l.color.set(c_type);
				MenuBuilder.addShowMenuListenerp(l, () -> Seq.with(
				 MenuBuilder.copyAsJSMenu("class", () -> cls),
				 ValueLabel.newDetailsMenuList(l, null, cls)
				));
				Class<?>[] types = cls.getInterfaces();
				if (types.length > 0) {
					keyword(t, " implements ");
					for (Class<?> interf : types) {
						t.add(new MyLabel(getGenericString(interf), defaultLabel)).padRight(8f).color(tmpColor.set(c_type));
					}
				}

				markDisplay(t.add(new MyHoverTable(buttons -> {
					IntUI.addDetailsButton(buttons, () -> cls, cls);
					// addStoreButton(buttons, Core.bundle.get("jsfunc.class", "Class"), () -> cls);
				})).right(), E_JSFuncDisplay.buttons);
			} catch (Throwable e) {
				Log.err(e);
			}
		}).growX().left().top().row();
		addUnderline(table, 6);
	}
	private static boolean isNestStatic(Class<?> cls) {
		if (cls.getConstructors().length == 0) return true;
		Class<?>[] parameterTypes = cls.getConstructors()[0].getParameterTypes();
		return parameterTypes.length == 0 || parameterTypes[0] != cls.getNestHost();
	}
	static Cell<MyLabel> keyword(Table t, CharSequence text) {
		return t.add(new MyLabel(text, defaultLabel)).color(tmpColor.set(c_keyword)).padRight(8f).touchable(Touchable.disabled);
	}

	/** 双击复制文本内容 */
	private static MyLabel newCopyLabel(Table table, String text) {
		MyLabel label = new MyLabel(text, defaultLabel);
		table.add(label).growY().labelAlign(Align.top)/* .self(c -> {
			if (Vars.mobile && type != null) c.tooltip(getGenericString(type));
		}) */;
		addDClickCopy(label, s -> s.replaceAll(" \\[\\d+]$", ""));
		return label;
	}


	public String toString() {
		return STR."\{getClass().getSimpleName()}#\{title.getText()}";
	}
	/** @see IDisposable#clearAll() */
	public void hide() {
		super.hide();

		events.fireIns(Disposable.class);
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

	public static final int COLSPAN = 8;
	public static class ReflectTable extends FilterTable<Member> {
		public final Seq<ValueLabel> labels = new Seq<>();

		final ObjectMap<String, Pair<Table, Seq<Member>>> map = new ObjectMap<>();

		public ReflectTable() {
			left().defaults().left().top();
		}
		public void act(float delta) {
			super.act(delta);
			children.sort(el -> el instanceof MyHoverTable ? -1 : 1);
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
			 .colspan(COLSPAN)
			 .with(l -> l.clicked(() -> IntUI.showSelectListTable(l,
				Seq.with(arr).map(String::valueOf),
				() -> null, _ -> { }, 400, 0, true, Align.left)))
			 .row();
			Underline.of(current, COLSPAN, Color.lightGray).padTop(6);
		}
		public void clear() {
			super.clear();
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
		Underline.of(table, colspan, tmpColor.set(c_underline));
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

	private static void mergeOne(ReflectTable table, Member member, MyLabel label,
	                             Element attribute) {
		if (table.skip || !E_JSFunc.folded_name.enabled()) return;
		Core.app.post(() -> {
			int size = table.map.get(member.getName()).getSecond(Seq::new).size;
			if (size == 1) return;
			label.setText(STR."\{label.getText()}\{METHOD_COUNT_PREFIX}\{size}]");
		});

		EventHelper.doubleClick(label, () -> {
			if (!table.map.get(member.getName(), Pair::new)
			 .getFirst(ShowInfoWindow::newPairTable).hasChildren())
				return;
			IntUI.showSelectTable(attribute, (p, _, _) -> {
				 table.left().top().defaults().left().top();
				 var   pair = table.map.get(member.getName());
				 Table one  = pair.getFirst(ShowInfoWindow::newPairTable);
				 p.add(one).self(c -> c.update(_ -> {
					 c.width(table.getWidth());
				 }).right().grow().get());
				 Time.runTask(6f, one::invalidateHierarchy);
			 }, false, Align.topLeft)
			 .table.background(Styles.black6);
		}, null);
	}

	static Table newPairTable() {
		return new Table(t -> t.left().defaults().left().top());
	}

	private static Runnable methodInvoker(Object o, Method m, boolean noParam, Cell<?> cell, ReflectValueLabel l) {
		return runT(() -> {
			if (noParam) {
				dealInvokeResult(m.invoke(o), cell, l);
				return;
			}
			JSRequest.<NativeArray>requestForMethod(m, o, arr -> {
				dealInvokeResult(invokeForMethod(o, m, l, arr,
				 args -> m.invoke(o, args)), cell, l);
			});
		}, l);
	}
	private static Runnable methodSpecialInvoker(Object o, Method m, boolean noParam, Cell<?> cell, ReflectValueLabel l) {
		return runT(() -> {
			MethodHandle handle = getHandle(m);
			if (noParam) {
				dealInvokeResult(handle.invokeWithArguments(o), cell, l);
				return;
			}
			if (!l.isStatic) handle.bindTo(o);
			JSRequest.<NativeArray>requestForMethod(handle, o, arr -> {
				dealInvokeResult(invokeForMethod(o, m, l, arr,
				 handle::invokeWithArguments
				), cell, l);
			});
		}, l);
	}

	private static Runnable ctorInitInvoker(Object o, Constructor<?> ctor, boolean noParam, Label l) {
		return runT(() -> {
			MethodHandle init = InitMethodHandle.findInit(ctor.getDeclaringClass(), ctor);
			if (o == null) {
				copyValue("Handle", init);
			}
			if (noParam) {
				if (o != null) init.invoke(o);
			} else JSRequest.<NativeArray>requestForMethod(ctor, o, arr -> {
				init.bindTo(o).invokeWithArguments(convertArgs(arr, ctor.getParameterTypes()));
			});
		}, l);
	}
	private static Runnable ctorInvoker(Object o, Constructor<?> ctor, boolean noParam, Label l) {
		return runT(() -> {
			MethodHandle handle = getHandle(ctor);
			if (noParam) {
				JSFunc.copyValue("Instance", handle.invoke());
				return;
			}
			JSRequest.<NativeArray>requestForMethod(ctor, o, arr -> {
				JSFunc.copyValue("Instance", handle.invokeWithArguments(
				 convertArgs(arr, ctor.getParameterTypes())
				));
			});
		}, l);
	}
}
