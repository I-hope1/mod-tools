package modtools.utils;

import arc.Core;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.gl.FrameBuffer;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.ui.IntStyles;
import modtools.ui.IntUI;
import modtools.ui.MyFonts;
import modtools.ui.components.*;
import modtools.ui.content.ElementShow.ElementShowDialog;
import modtools.ui.content.Selection;
import modtools_lib.MyReflect;
import rhino.*;

import java.lang.reflect.*;
import java.util.StringJoiner;
import java.util.regex.Pattern;

import static modtools.ui.Contents.tester;

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
			type = Color.valueOf("9cd1bb");

	public static Window showInfo(Object o, Class<?> clazz) {
		//			if (!clazz.isInstance(o)) return;
		/*try {
			MyReflect.lookupSetClassLoader(clazz, Field.class.getClassLoader());
		} catch (Throwable e) {
			Log.err(e);
		}*/

		Window[] dialog = {null};
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
					} catch (Exception e) {
						IntUI.showException(e).setPosition(Tools.getAbsPos(dialog[0]));
					} catch (Throwable e) {
						Log.err(e);
					}
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
		final Table cont = new Table();
		// 默认左居中
		cont.left().defaults().left();
		cont.table(t -> {
			t.left().defaults().left();
			t.button(Icon.refresh, Styles.clearNonei, () -> {
				// 使用Time.runTask避免stack overflow
				Time.runTask(0, () -> {
					dialog[0].hide();
					try {
						showInfo(o, clazz).setPosition(Tools.getAbsPos(dialog[0]));
					} catch (Exception e) {
						IntUI.showException(e).setPosition(Tools.getAbsPos(dialog[0]));
					} catch (Throwable e) {
						Log.err(e);
					}
				});
			}).size(50);
			t.button("存储为js变量", () -> {}).padLeft(10f).height(50).growX().maxWidth(600).with(b -> {
				b.clicked(() -> tester.put(b, o));
			}).row();
		}).growX().row();
		Table build = new Table();
		// 默认左居中
		build.left().defaults().left();
		boolean[] isBlack = {false};
		// Runnable[] last = {null};
		Cons<String> rebuild = text -> {
			build.clearChildren();
			Pattern pattern;
			try {
				pattern = text == null || text.isEmpty() ? null : Pattern.compile(text, Pattern.CASE_INSENSITIVE);
			} catch (Exception e) {
				pattern = null;
			}
			buildReflect(o, clazz, build, pattern, isBlack[0]);
		};
		cont.table(t -> {
			t.button(Tex.whiteui, 35, () -> {}).size(42).with(img -> {
				isBlack[0] = !isBlack[0];
				img.clicked(() -> img.setColor(isBlack[0] ? Color.black : Color.white));
			});
			t.image(Icon.zoom).size(42);
			t.field("", rebuild).growX();
		}).growX().row();
		cont.table(t -> {
			t.left().defaults().left();
			t.add(clazz.getTypeName(), IntStyles.myLabel);
			t.button(Icon.copy, Styles.cleari, () -> {
				Core.app.setClipboardText(clazz.getTypeName());
			});
		}).fillX().pad(6, 10, 6, 10).row();
		rebuild.get(null);
		cont.add(build).grow();

		dialog[0] = new Window(clazz.getSimpleName(), 200, 200, true);
		dialog[0].cont.pane(cont).grow();
		//		dialog.addCloseButton();
		dialog[0].show();
		assert dialog[0] != null;
		return dialog[0];
	}

	/**
	 * @param pattern 用于搜索
	 * @param isBlack 是否为黑名单模式
	 **/
	private static void buildReflect(Object o, Class<?> clazz, Table cont, Pattern pattern, boolean isBlack) {
		/*final Seq<Runnable> runnables = new Seq<>();
		final Runnable mainRun;
		IntVars.addResizeListener(mainRun = () -> {
			runnables.each(Runnable::run);
		});*/
		cont.add("字段").row();
		cont.image().color(Pal.accent).fillX().row();
		Table fields = cont.table(t -> t.left().defaults().left().top())
				.pad(4, 6, 4, 6).fillX().padTop(8).get();
		cont.row();
		cont.add("函数").row();
		cont.image().color(Pal.accent).fillX().row();
		Table methods = cont.table(t -> {
			t.left().defaults().left().top();
		}).pad(4, 6, 4, 6).fill().padTop(8).get();
		cont.row();
		cont.add("构造器").row();
		cont.image().color(Pal.accent).fillX().row();
		Table constructors = cont.table(t -> {
			t.left().defaults().left().top();
		}).pad(4, 6, 4, 6).fill().padTop(8).get();
		cont.row();
		cont.add("类").row();
		cont.image().color(Pal.accent).fillX().row();
		Table classes = cont.table(t -> {
			t.left().defaults().left().top();
		}).pad(4, 6, 4, 6).fill().padTop(8).get();

		for (Class<?> cls = clazz; cls != null; cls = cls.getSuperclass()) {
			Class<?> finalCls = cls;

			fields.add(cls.getSimpleName(), IntStyles.myLabel).row();
			fields.image().color(Color.lightGray).fillX().padTop(6).row();
			methods.add(cls.getSimpleName(), IntStyles.myLabel).row();
			methods.image().color(Color.lightGray).fillX().padTop(6).row();
			constructors.add(cls.getSimpleName(), IntStyles.myLabel).row();
			constructors.image().color(Color.lightGray).fillX().padTop(6).row();
			classes.add(cls.getSimpleName(), IntStyles.myLabel).row();
			classes.image().color(Color.lightGray).fillX().padTop(6).row();

			//				for (Field f : cls.getDeclaredFields()) {
			Field[] fields2 = {};
			try {
				fields2 = MyReflect.lookupGetFields(cls);
			} catch (Throwable e) {
				try {
					fields2 = cls.getDeclaredFields();
				} catch (Exception ignored) {}
			}
			// 字段
			for (Field f : fields2) {
				if (pattern != null && pattern.matcher(f.getName()).find() != isBlack) continue;
				int modifiers = f.getModifiers();
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
				fields.table(t -> {
					try {
						// modifiers
						fields.add(Modifier.toString(modifiers) + " ", new LabelStyle(MyFonts.MSYHMONO, keyword)).growY();
						// type
						fields.add(type.getSimpleName() + " ", new LabelStyle(MyFonts.MSYHMONO, JSFunc.type)).growY();
						// name
						fields.add(f.getName(), IntStyles.myLabel).growY();
						fields.add(" = ", IntStyles.myLabel).growY();
					} catch (Exception e) {
						Log.err(e);
					}

					Object[] val = {null};

					// 占位符
					Cell<?> cell = t.add();
					Label l = new MyLabel("", IntStyles.myLabel);
					// float[] prefW = {0};
					/*Cell<?> lableCell = */
					t.add(l).labelAlign(Align.left);
					// 太卡了
					// Runnable listener = () -> lableCell.width(Math.min(prefW[0], Core.graphics.getWidth()));
					// IntVars.addResizeListener(listener);
					l.setWrap(false);

					if (type.isPrimitive() || type == String.class) {
						try {
							val[0] = MyReflect.getValueExact(o, f);
							String base = "" + val[0];
							l.setText(type == String.class ? '"' + base + '"' : base);
							// prefW[0] = l.getPrefWidth();
							// listener.run();
							// Time.runTask(0, () -> l.setWrap(true));
							l.setColor(Color.valueOf("#bad761"));
						} catch (Exception e) {
							//								`Log.info`(e);
							t.add("Unknown", new LabelStyle(MyFonts.MSYHMONO, Color.red));
						}
					} else {
						l.setText("???");
						boolean[] ok = {false};
						l.clicked(() -> {
							if (ok[0]) return;
							ok[0] = true;
							try {
								val[0] = MyReflect.getValueExact(o, f);
								l.setText("" + val[0]);
								// prefW[0] = l.getPrefWidth();
								// l.setWrap(true);
								if (val[0] instanceof Color) {
									cell.setElement(new Image(IntUI.whiteui.tint((Color) val[0]))).size(32).padRight(4);
								}
								IntUI.longPress(l, 600, b -> {
									if (!b) return;
									// 使用Time.runTask避免stack overflow
									Time.runTask(0, () -> {
										try {
											if (val[0] != null) {
												showInfo(val[0]).setPosition(Tools.getAbsPos(l));
											} else {
												showInfo(null, f.getType()).setPosition(Tools.getAbsPos(l));
											}
										} catch (Exception e) {
											IntUI.showException(e).setPosition(Tools.getAbsPos(l));
										} catch (Throwable e) {
											Log.err(e);
										}
									});
								});
							} catch (Exception ex) {
								Log.err(ex);
								l.setText("");
							}
						});
					}

					t.button("将字段储存为js变量", () -> {}).padLeft(10f).size(180, 40).with(b -> {
						b.clicked(() -> tester.put(b, f));
					});
					t.button("将值存储为js变量", () -> {}).padLeft(10f).size(180, 40).with(b -> {
						b.clicked(() -> tester.put(b, val[0]));
					});
				}).pad(4).row();
			}


			Method[] methods2 = {};
			try {
				methods2 = MyReflect.lookupGetMethods(cls);
			} catch (Throwable e) {
				try {
					methods2 = cls.getDeclaredMethods();
				} catch (Exception ignored) {}
			}
			// 函数
			for (Method m : methods2) {
				if (pattern != null && pattern.matcher(m.getName()).find() != isBlack) continue;
				try {
					MyReflect.setOverride(m);
				} catch (Throwable ignored) {}
				methods.table(t -> {
					try {
						StringBuilder sb = new StringBuilder();
						int mod = m.getModifiers() & Modifier.methodModifiers();
						if (mod != 0 && !m.isDefault()) {
							sb.append(Modifier.toString(mod)).append(' ');
						} else {
							sb.append(Modifier.toString(mod)).append(' ');
							if (m.isDefault()) {
								sb.append("default ");
							}
						}
						// modifiers
						methods.add(sb, new LabelStyle(MyFonts.MSYHMONO, keyword)).growY();
						// return type
						methods.add(m.getReturnType().getSimpleName() + " ", new LabelStyle(MyFonts.MSYHMONO, type)).growY();
						// method name
						methods.add(m.getName(), IntStyles.myLabel).growY();
						sb = new StringBuilder();
						sb.append("[lightgray]([]");
						StringJoiner sj = new StringJoiner(", ");
						Class<?>[] exceptionTypes = m.getParameterTypes();

						for (Class<?> parameterType : exceptionTypes) {
							sj.add(format(parameterType));
						}

						sb.append(sj);
						sb.append("[lightgray])[]");
						exceptionTypes = m.getExceptionTypes();
						if (exceptionTypes.length > 0) {
							StringJoiner joiner = new StringJoiner(",", " [#" + keyword + "]throws[] ", "");

							for (Class<?> exceptionType : exceptionTypes) {
								joiner.add(exceptionType.getTypeName());
							}

							sb.append(joiner);
						}

						t.add(sb, IntStyles.myLabel).growY();
						// 占位符
						Cell<?> cell = t.add();
						ifl:
						if (m.getParameterTypes().length == 0) {
							Label l = new MyLabel("", IntStyles.myLabel);
							// float[] prefW = {0};
							t.add(l).labelAlign(Align.left)/*.self(c -> c.update(__ -> c.width(Math.min(prefW[0], Core.graphics.getWidth()))))*/;
							if (o == null && !Modifier.isStatic(m.getModifiers())) break ifl;

							t.button("invoke", () -> {
								try {
									Object returnV = m.invoke(o);
									l.setText("" + returnV);
									// l.setWrap(false);
									// prefW[0] = l.getPrefWidth();
									// l.setWrap(true);

									if (returnV instanceof Color) {
										cell.setElement(new Image(IntUI.whiteui.tint((Color) returnV))).size(32).padRight(4);
									}
									if (returnV != null && !(returnV instanceof String) && !returnV.getClass().isPrimitive()) {
										//											l.setColor(Color.white);
										IntUI.longPress(l, 600, b -> {
											if (b) {
												// 使用Time.runTask避免stack overflow
												Time.runTask(0, () -> {
													try {
														showInfo(returnV).setPosition(Tools.getAbsPos(l));
													} catch (Exception e) {
														IntUI.showException(e).setPosition(Tools.getAbsPos(l));
													} catch (Throwable e) {
														Log.err(e);
													}
												});
											}
										});
									}
								} catch (Exception ex) {
									IntUI.showException("invoke出错", ex).setPosition(Tools.getAbsPos(l));
								}

							}).width(100);
						}

						t.button("将函数存储为js变量", () -> {}).padLeft(10f).size(180, 40).with(b -> {
							b.clicked(() -> tester.put(b, m));
						});
					} catch (Exception err) {
						t.add("<" + err + ">", new LabelStyle(MyFonts.MSYHMONO, Color.red));
					}

				}).pad(4).row();
			}


			Constructor[] constructors2 = {};
			try {
				constructors2 = MyReflect.lookupGetConstructors(cls);
			} catch (Throwable e) {
				try {
					constructors2 = cls.getDeclaredConstructors();
				} catch (Exception ignored) {}
			}
			// 构造器
			for (Constructor<?> cons : constructors2) {
				if (pattern != null && pattern.matcher(cons.getName()).find() != isBlack) continue;
				try {
					MyReflect.setOverride(cons);
				} catch (Throwable ignored) {}
				constructors.table(t -> {
					try {
						StringBuilder sb = new StringBuilder();
						int mod = cons.getModifiers() & Modifier.methodModifiers();
						sb.append("[#").append(keyword).append("]");
						sb.append(Modifier.toString(mod)).append(' ');

						sb.append("[]");
						sb.append("[#").append(type).append("]").append(finalCls.getSimpleName()).append("[] ");
						sb.append("[lightgray]([]");

						StringJoiner sj = new StringJoiner(", ");
						// 参数
						Class<?>[] parameterTypes = cons.getParameterTypes();
						for (Class<?> pt : parameterTypes) {
							sj.add(format(pt));
						}
						sb.append(sj);
						sb.append("[lightgray])");
						Type[] exceptionTypes = cons.getGenericExceptionTypes();
						// 报错
						if (exceptionTypes.length > 0) {
							StringJoiner joiner = new StringJoiner(",", " [#" + keyword + "]throws[] ", "");

							for (Type exceptionType : exceptionTypes) {
								joiner.add(exceptionType.getTypeName());
							}

							sb.append(joiner);
						}
						t.add(sb, IntStyles.myLabel);

						t.button("将函数存储为js变量", () -> {}).padLeft(10f).size(180, 40).with(b -> {
							b.clicked(() -> tester.put(b, cons));
						});
					} catch (Exception e) {
						Log.err(e);
					}
				}).pad(4).row();
			}

			for (Class<?> dcls : cls.getDeclaredClasses()) {
				if (pattern != null && pattern.matcher(dcls.getName()).find() != isBlack) continue;
				classes.table(t -> {
					try {
						int mod = dcls.getModifiers() & Modifier.classModifiers();
						t.add(Modifier.toString(mod), new LabelStyle(MyFonts.MSYHMONO, keyword)).padRight(8f);
						Label l = t.add(dcls.getSimpleName(), new LabelStyle(MyFonts.MSYHMONO, type)).padRight(8f).get();
						Class<?>[] types = dcls.getInterfaces();
						if (types.length > 0) {
							t.add("implements", new LabelStyle(MyFonts.MSYHMONO, keyword)).padRight(8f);
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
									} catch (Exception e) {
										IntUI.showException(e).setPosition(Tools.getAbsPos(l));
									} catch (Throwable e) {
										Log.err(e);
									}
								});
							}
						});

						t.button("将类存储为js变量", () -> {}).padLeft(10f).size(180, 40).with(b -> {
							b.clicked(() -> tester.put(b, dcls));
						});
					} catch (Exception e) {
						Log.err(e);
					}
				}).pad(4).row();
			}
		}
		// return mainRun;
	}

	public static CharSequence format(Class<?> cls) {
		StringBuilder base = new StringBuilder();
		base.append("[#").append(type).append("]").append(cls.getTypeName()).append("[]");
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
}
