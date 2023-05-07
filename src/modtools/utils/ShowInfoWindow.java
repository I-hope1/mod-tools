package modtools.utils;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.*;
import com.strobel.decompiler.Decompiler;
import com.strobel.decompiler.*;
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
import modtools.ui.components.limit.*;
import modtools.utils.search.BindCell;
import rhino.*;

import java.io.StringWriter;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import static modtools.IntVars.hasDecomplier;
import static modtools.ui.IntStyles.MOMO_LabelStyle;
import static modtools.ui.content.SettingsContent.addSettingsTable;
import static modtools.utils.JSFunc.*;
import static modtools.utils.MySettings.*;
import static modtools.utils.Tools.*;

class ShowInfoWindow extends Window implements DisposableInterface {

	private final Class<?> clazz;
	private final Object   o;
	private final MyEvents events = new MyEvents();

	private ReflectTable
	 fieldsTable,
	 methodsTable,
	 constructorsTable,
	 classesTable;

	public ShowInfoWindow(Object o, Class<?> clazz) {
		super(clazz.getSimpleName(), 200, 200, true);
		this.o = o;
		this.clazz = clazz;
		MyEvents.current = events;
		build();
		MyEvents.current = null;
	}
	Cons<String> rebuild;
	TextField    textField;

	public void build() {
		Table build = new LimitTable();
		// 默认左居中
		build.left().top().defaults().left();
		boolean[] isBlack = {false};
		textField = new TextField();
		// Runnable[] last = {null};
		rebuild = text -> {
			// build.clearChildren();
			Pattern pattern = Tools.compileRegExpCatch(text);
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
			if (hasDecomplier) t.button("Decompile", Styles.flatBordert, () -> {
				StringWriter stringWriter = new StringWriter();
				Decompiler.decompile(
				 // clazz.getClassLoader().getResource()
				 clazz.getName().replace('.', '/') + ".class",
				 new PlainTextOutput(stringWriter)
				);
				Log.info(stringWriter);
				TextAreaTab textarea = new TextAreaTab(stringWriter.toString());
				textarea.syntax = new JavaSyntax(textarea);
				window(d -> {
					d.cont.setSize(textarea.getArea().getPrefWidth(), textarea.getArea().getPrefHeight());
					d.cont.add(textarea).grow();
				});
			}).size(100, 42);
			t.button(Icon.refreshSmall, IntStyles.clearNonei, rebuild0).size(42);
			if (o != null) {
				addStoreButton(t, "", () -> o);
				t.label(() -> "" + addressOf(o)).padLeft(8f);
			}
		}).height(42).row();
		cont.table(t -> {
			t.button(Tex.whiteui, 32, null).size(42).with(img -> {
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
			t.add(clazz.getTypeName(), MOMO_LabelStyle);
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
			Boolf<String> stringBoolf = name -> pattern == null || find(pattern, name) != isBlack;
			fieldsTable.filter(stringBoolf);
			fieldsTable.labels.each(ValueLabel::setVal);
			methodsTable.filter(stringBoolf);
			methodsTable.labels.each(ValueLabel::clearVal);
			constructorsTable.filter(stringBoolf);
			classesTable.filter(stringBoolf);
			return;
		}
		ReflectTable fields, methods, constructors, classes;
		boolean[]    c = new boolean[4];
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
			cont.add().grow().row();
			return table;
		};
		fields = fieldsTable = func.apply("@jsfunc.field", 0);
		methods = methodsTable = func.apply("@jsfunc.method", 1);
		constructors = constructorsTable = func.apply("@jsfunc.constructor", 2);
		classes = classesTable = func.apply("@jsfunc.class", 3);

		// boolean displayClass = MySettings.settings.getBool("displayClassIfMemberIsNull", "false");
		for (Class<?> cls = clazz; cls != null; cls = cls.getSuperclass()) {
			fields.build(cls);
			methods.build(cls);
			constructors.build(cls);
			classes.build(cls);

			// 字段
			for (Field f : getFields1(cls)) {
				buildField(o, fields, f);
			}
			checkRemovePeek(fields);

			// 函数
			for (Method m : getMethods1(cls)) {
				buildMethod(o, methods, m);
			}
			checkRemovePeek(methods);

			// 构造器
			for (Constructor<?> cons : getConstructors1(cls)) {
				buildConstructor(constructors, cons);
			}
			checkRemovePeek(constructors);

			// 类
			for (Class<?> dcls : cls.getDeclaredClasses()) {
				buildClass(classes, dcls);
			}
			checkRemovePeek(classes);
		}
		// return mainRun;
	}
	public static boolean find(Pattern pattern, String name) {
		return E_JSFunc.search_exact.enabled() ? pattern.matcher(name).matches() : pattern.matcher(name).find();
	}
	private static void checkRemovePeek(ReflectTable table) {
		if (table.hasChildren()) table.getChildren().peek().remove();
	}
	private static Field[] getFields1(Class<?> cls) {
		try {return MyReflect.lookupGetFields(cls);} catch (Throwable e) {return new Field[0];}
	}
	private static Constructor<?>[] getConstructors1(Class<?> cls) {
		try {return MyReflect.lookupGetConstructors(cls);} catch (Throwable e) {return new Constructor<?>[0];}
	}
	private static Method[] getMethods1(Class<?> cls) {
		try {return MyReflect.lookupGetMethods(cls);} catch (Throwable e) {return new Method[0];}
	}

	private void buildField(Object o, ReflectTable fields, Field f) {
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
			addRType(fields, type);
			// name
			MyLabel label = fields.add(new MyLabel(f.getName(), MOMO_LabelStyle)).get();
			addDClickCopy(label);
			IntUI.addShowMenuListener(label, () -> Seq.with(
			 IntUI.copyAsJSMenu("field", () -> f),
			 MenuList.with(Icon.copy, "copy offset", () -> {
				 JSFunc.copyText("" + (Modifier.isStatic(modifiers) ? MyReflect.unsafe.staticFieldOffset(f) : MyReflect.unsafe.objectFieldOffset(f)));
			 })
			));
			fields.add(new MyLabel(" = ", MOMO_LabelStyle)).touchable(Touchable.disabled);
		} catch (Throwable e) {
			Log.err(e);
		}
		fields.table(t -> {
			t.left().defaults().left();
			// 占位符
			Cell<?>  cell   = t.add();
			BindCell c_cell = addDisplayListener(cell, E_JSFuncDisplay.value);
			float[]  prefW  = {0};
			/*Cell<?> lableCell = */
			ValueLabel l = new ValueLabel("", type, f, o);
			fields.labels.add(l);
			Cell<?> labelCell = t.add(l);
			// 太卡了
			IntVars.addResizeListener(() -> labelCell.width(Math.min(prefW[0], Core.graphics.getWidth())));

			try {
				l.setVal();
				buildFieldValue(type, c_cell, l);

				if (!unbox(type).isPrimitive() && type != String.class) {
					l.addShowInfoListener();
				}
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
		fields.image().color(c_underline).growX().colspan(6).row();
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
		addDisplayListener(table.add(new MyLabel(string, keywordStyle))
		 .padRight(8), E_JSFuncDisplay.modifier);
	}
	private void addRType(Table table, Class<?> type) {
		addDisplayListener(table.add(new MyLabel(getGenericString(type), typeStyle))
		 .padRight(16).touchable(Touchable.disabled), E_JSFuncDisplay.type);
	}

	private void buildFieldValue(Class<?> type, BindCell c_cell, ValueLabel l) {
		if (!l.isStatic() && l.obj == null) return;
		Cell<?> cell = c_cell.cell;
		if (l.val instanceof Color) {
			IntUI.colorBlock(cell, (Color) l.val, l::setVal);
			c_cell.reget();
		} else if (type == Boolean.TYPE || type == Boolean.class) {
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
			events.onIns(E_JSFuncEdit.number, edit -> {
				cell.setElement(edit.enabled() ? field : null);
				c_cell.reget();
			});
		}

	}
	private void buildMethod(Object o, ReflectTable methods, Method m) {
		// if (c++ > 10) continue;
		methods.bind(m.getName());
		try {
			MyReflect.setOverride(m);
		} catch (Throwable ignored) {}
		try {
			int mod = m.getModifiers();
			// modifiers
			addModifier(methods, buildExecutableModifier(m));
			// return type
			addRType(methods, m.getReturnType());
			// method name
			MyLabel label = methods.add(new MyLabel(m.getName(), MOMO_LabelStyle))
			 .get();
			addDClickCopy(label);
			// method parameters + exceptions + buttons
			methods.add(new LimitLabel(buildArgsAndExceptions(m)
			 , MOMO_LabelStyle)).pad(4).left().touchable(Touchable.disabled);


			// 占位符
			Cell<?> cell = methods.add();
			Cell<?> buttonsCell;

			boolean isSingle = m.getParameterTypes().length == 0;
			boolean isValid  = o != null || Modifier.isStatic(mod);
			// if (isSingle && !isValid) methods.add();

			ValueLabel l = new ValueLabel("", o, m);
			l.clearVal();
			methods.labels.add(l);
			IntUI.addShowMenuListener(label, () -> Seq.with(
			 IntUI.copyAsJSMenu("method", () -> m),
			 MenuList.with(Icon.copySmall, "copy reflection getter", () -> {
				 copyExecutableReflection(m);
			 }),
			 MenuList.with(Icon.boxSmall, "Invoke", () -> {
				 m.setAccessible(true);
				 if (isSingle) {
					 try {
						 dealInvokeResult(m.invoke(o), cell, l);
					 } catch (Throwable th) {IntUI.showException(th);}
					 return;
				 }
				 JSRequest.requestForMethod(m, o, ret -> {
					 Object[] arr = convertArgs((NativeArray) ret, m.getParameterTypes());
					 Object   res;
					 if (l.isStatic()) res = m.invoke(null, arr);
						 // it may not happen.
					 else if (o == null) throw new NullPointerException("'obj' is null.");
					 else res = m.invoke(o, arr);
					 dealInvokeResult(res, cell, l);
				 });
			 }),
			 MenuList.with(Icon.boxSmall, "InvokeSpecial", () -> {
				 MethodHandle handle;
				 try {
					 handle = MyReflect.lookup.findSpecial(m.getDeclaringClass(),
						m.getName(), MethodType.methodType(m.getReturnType(), m.getParameterTypes()), m.getDeclaringClass());
				 } catch (NoSuchMethodException | IllegalAccessException e) {
					 throw new RuntimeException(e);
				 }
				 if (isSingle) {
					 try {
						 dealInvokeResult(handle.invokeWithArguments(o), cell, l);
					 } catch (Throwable th) {IntUI.showException(th);}
					 return;
				 }
				 JSRequest.requestForMethod(handle, o, ret -> {
					 Object[] arr = convertArgs((NativeArray) ret, m.getParameterTypes());
					 Object   res;
					 if (l.isStatic()) res = handle.invokeWithArguments(arr);
						 // it may not happen.
					 else if (o == null) throw new NullPointerException("'obj' is null.");
					 else res = handle.invokeWithArguments(o, arr);
					 dealInvokeResult(res, cell, l);
				 });
			 })/* ,
			  MenuList.with(Icon.infoSmall, "ReplaceGenerate", () -> {
				  try {
					  Seq<CtClass> seq = new Seq<>(CtClass.class);
					  for (Class<?> type : m.getParameterTypes()) {
						  seq.add(ClassPool.getDefault().get(type.getName()));
					  }
					  if (ClassPool.getDefault().getOrNull("arc.util.Log") == null)
						  ClassPool.getDefault().appendClassPath(new ClassClassPath(Log.class));
					  CtMethod ctMethod = ClassPool.getDefault().get(m.getDeclaringClass().getName())
						.getDeclaredMethod(m.getName(), seq.toArray());
					  JSRequest.requestForMethod(ctMethod, o, ret -> {
						  ctMethod.setBody((String) ret);
					  });
				  } catch (NotFoundException e) {
					  IntUI.showException(e);
				  }
			  }) */
			));
			// float[] prefW = {0};
			methods.add(l);

			buttonsCell = methods.table(buttons -> {
				buttons.right().top().defaults().right().top();
				if (isSingle && isValid) {
					buttons.button("Invoke", IntStyles.flatBordert, catchRun("invoke出错", () -> {
						dealInvokeResult(m.invoke(o), cell, l);
					}, l)).size(96, 45);
				}
				addLabelButton(buttons, () -> l.val, l.type);
				// addStoreButton(buttons, Core.bundle.get("jsfunc.method", "Method"), () -> m);
			}).grow().top().right();
			if (buttonsCell != null) addDisplayListener(buttonsCell, E_JSFuncDisplay.buttons);
		} catch (Throwable err) {
			methods.add(new MyLabel("<" + err + ">", redStyle));
		}
		methods.row();
		methods.image().color(c_underline).growX().colspan(7).row();
	}
	private static Object[] convertArgs(NativeArray ret, Class<?>[] types) {
		Iterator<Class<?>> iterator = Seq.with(types).iterator();
		Seq                seq      = Seq.with(ret.toArray());
		seq.replace(a -> JavaAdapter.convertResult(a, iterator.next()));
		return seq.items;
	}

	static final int ACCESS_MODIFIERS =
	 Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE;
	/** copy from Executable
	 * @see Executable#sharedToGenericString(int, boolean) 
	 * */
	public static StringBuilder buildExecutableModifier(Executable m) {
		int     mod       = m.getModifiers() & (m instanceof Method ? Modifier.methodModifiers() : Modifier.constructorModifiers());
		boolean isDefault = m instanceof Method && ((Method) m).isDefault();

		StringBuilder sb = new StringBuilder();
		if (mod != 0 && !isDefault) {
			sb.append(Modifier.toString(mod)).append(' ');
		} else {
			int access_mod = mod & ACCESS_MODIFIERS;
			if (access_mod != 0)
				sb.append(Modifier.toString(access_mod)).append(' ');
			if (isDefault)
				sb.append("default ");
			mod = (mod & ~ACCESS_MODIFIERS);
			if (mod != 0)
				sb.append(Modifier.toString(mod)).append(' ');
		}
		return sb;
	}
	private static void copyExecutableReflection(Executable m) {
		StringBuffer sb    = new StringBuffer();
		Class<?>     dcl   = m.getDeclaringClass();
		String       name1 = m.getClass().getSimpleName();
		char         c     = Character.toLowerCase(name1.charAt(0));

		sb.append(name1);
		if (c == 'c') sb.append("<?>");
		sb.append(" ").append(c)
		 .append(" = ");
		sb.append(getClassString0(dcl));
		sb.append(".getDeclared").append(name1)
		 .append("(\"").append(m.getName())
		 .append('"');
		for (Class<?> type : m.getParameterTypes()) {
			sb.append(", ");
			sb.append(getClassString0(type));
		}
		sb.append(");");
		JSFunc.copyText(sb);
	}
	/** 返回类的java访问方法 */
	private static String getClassString0(Class<?> dcl) {
		return Modifier.isPublic(dcl.getModifiers())
		 ? dcl.getSimpleName() + ".class" : "Class.forName(" + dcl.getName() + ")";
	}
	private static void dealInvokeResult(Object res, Cell<?> cell, ValueLabel l) {
		l.setVal(res);
		if (l.val instanceof Color) {
			cell.setElement(new Image(IntUI.whiteui.tint((Color) l.val))).size(32).padRight(4).touchable(Touchable.disabled);
		}
	}
	private void buildConstructor(ReflectTable t, Constructor<?> ctor) {
		try {
			MyReflect.setOverride(ctor);
		} catch (Throwable ignored) {}
		try {
			addModifier(t, buildExecutableModifier(ctor));
			MyLabel label    = new MyLabel(ctor.getDeclaringClass().getSimpleName(), typeStyle);
			boolean isSingle = ctor.getParameterTypes().length == 0;
			IntUI.addShowMenuListener(label, () -> Seq.with(
			 MenuList.with(Icon.copySmall, "copy reflection getter", () -> {
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
				 JSRequest.requestForMethod(ctor, o, ret -> {
					 JSFunc.copyValue("instance", ctor.newInstance(
						convertArgs((NativeArray) ret, ctor.getParameterTypes())
					 ));
				 });
			 }),
			 IntUI.copyAsJSMenu("constructor", () -> ctor)
			));
			t.add(label);
			t.add(new LimitLabel(buildArgsAndExceptions(ctor)));

			/* addDisplayListener(t.table(buttons -> {
				addStoreButton(buttons, Core.bundle.get("jsfunc.constructor", "Constructor"), () -> cons);
			}).grow().top().right(), JSFuncDisplay.buttons); */
			t.row();
		} catch (Throwable e) {
			Log.err(e);
		}
		t.image().color(c_underline).growX().colspan(6).row();
	}
	private void buildClass(ReflectTable table, Class<?> cls) {
		table.bind(cls.getName());
		table.table(t -> {
			try {
				addModifier(t, Modifier.toString(cls.getModifiers() & ~Modifier.classModifiers()) + " class ");

				Label l = t.add(new MyLabel(getGenericString(cls), typeStyle)).padRight(8f).get();
				IntUI.addShowMenuListener(l, () -> Seq.with(IntUI.copyAsJSMenu("class", () -> cls)));
				addDClickCopy(l);
				Class<?>[] types = cls.getInterfaces();
				if (types.length > 0) {
					t.add(new MyLabel(" implements ", keywordStyle)).padRight(8f).touchable(Touchable.disabled);
					for (Class<?> interf : types) {
						t.add(new MyLabel(getGenericString(interf), MOMO_LabelStyle)).padRight(8f).color(c_type);
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
		}).pad(4).growX().left().row();
		table.image().color(c_underline).growX().colspan(6).row();
	}


	@Override
	public String toString() {
		return getClass().getSimpleName() + "#" + title.getText();
	}
	public void hide() {
		super.hide();
		/* fieldsTable.labels.each(ValueLabel::dispose);
		methodsTable.labels.each(ValueLabel::dispose);
		constructorsTable.labels.each(ValueLabel::dispose);
		classesTable.labels.each(ValueLabel::dispose); */
		events.removeIns();
	}
}
