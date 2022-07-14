
package modtools.ui.content;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.func.Cons2;
import arc.func.Floatf;
import arc.func.Func;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.scene.Action;
import arc.scene.Element;
import arc.scene.Scene;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Button;
import arc.scene.ui.Dialog;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.mod.Scripts;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import modtools.ui.Contents;
import modtools.ui.IntStyles;
import modtools.ui.IntUI;
import modtools.ui.components.TextAreaTable;
import modtools.ui.components.TextAreaTable.MyTextArea;
import modtools.ui.content.Selection.Function;
import modtools_lib.MyReflect;
import rhino.*;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.StringJoiner;

import static modtools.ui.Contents.elementShow;
import static modtools.ui.Contents.tester;

public class Tester extends Content {
	String log = "";
	MyTextArea area;
	boolean loop = false, wrap = false, error, ignoreError = false;
	final float w = Core.graphics.isPortrait() ? 440 : 540;
	BaseDialog ui;
	ListDialog history, bookmark;
	public Scripts scripts;
	public Scriptable scope;
	public Context cx;

	public Tester() {
		super("tester");
	}

	private static float sort(Fi f) {
		try {
			return -Long.parseLong(f.nameWithoutExtension());
		} catch (Exception e) {
			return Long.MAX_VALUE;
		}
	}

	public void show(Table table, Table buttons) {
		Table cont = new Table();
		TextAreaTable textarea = new TextAreaTable("");
		area = textarea.getArea();
		boolean[] execed = {false};
		textarea.keyDonwB = (event, keycode) -> {
			if (Core.input.ctrl() && Core.input.shift() && keycode == KeyCode.enter) {
				evalMessage();
				execed[0] = true;
				return false;
			}
//			Core.input.ctrl() && keycode == KeyCode.rightBracket
			if (keycode == KeyCode.tab) {
				area.insert("  ");
				area.setCursorPosition(area.getCursorPosition() + 2);
				area.updateDisplayText();
			}
			return true;
		};
		textarea.keyTypedB = (event, character) -> !execed[0];
		textarea.keyUpB = (event, keycode) -> {
			execed[0] = false;
			return true;
		};

		cont.add(textarea).size(w, 390).row();
		cont.image().color(Color.gray).growX().row();
		cont.table(t -> {
			t.button(Icon.left, area::left);
			t.button("@ok", () -> {
				error = false;
				area.setText(getMessage().replaceAll("\r", ""));
				evalMessage();
				Fi d = history.file.child("" + Time.millis());
				d.child("message.txt").writeString(getMessage());
				d.child("log.txt").writeString(log);
				history.list.insert(0, d);

				int max = history.list.size - 1;
				int min = Math.min(30, max);
				for (int i = min; i < max; i++) {
					history.list.get(i).deleteDirectory();
					history.list.remove(i);
				}
			}).padLeft(8f).padRight(8f);
			t.button(Icon.right, area::right);
		}).row();
		cont.table(Tex.button, t -> t.pane(p -> {
			p.label(() -> log);
		}).size(w, 390));
		table.add(cont).row();
		table.pane(p -> {
			p.button("", Icon.star, Styles.cleart, () -> {
				Fi fi = bookmark.file.child(Time.millis() + ".txt");
				bookmark.list.add(fi);
				fi.writeString(getMessage());
			});
			p.button(b -> {
				b.label(() -> loop ? "循环" : "默认");
			}, Styles.defaultb, () -> {
				loop = !loop;
			}).size(100, 55);
			p.button(b -> {
				b.label(() -> wrap ? "严格" : "非严格");
			}, Styles.defaultb, () -> wrap = !wrap).size(100, 55);
			p.button("历史记录", history::show).size(100, 55);
			p.button("收藏", bookmark::show).size(100, 55);
		}).height(60).fillX();

		buttons.button("$back", Icon.left, ui::hide).size(210, 64);
		BaseDialog dialog = new BaseDialog("$edit");
		dialog.cont.pane(p -> {
			p.margin(10);
			p.table(Tex.button, t -> {
				TextButtonStyle style = Styles.cleart;
				t.defaults().size(280, 60).left();
				t.row();
				t.button("@schematic.copy.import", Icon.download, style, () -> {
					dialog.hide();
					area.setText(Core.app.getClipboardText());
				}).marginLeft(12);
				t.row();
				t.button("@schematic.copy", Icon.copy, style, () -> {
					dialog.hide();
					Core.app.setClipboardText(getMessage().replaceAll("\r", "\n"));
				}).marginLeft(12);
			});
		});
		dialog.addCloseButton();
		TextureRegionDrawable drawable = Icon.edit;
		Objects.requireNonNull(dialog);
		buttons.button("$edit", drawable, dialog::show).size(210, 64);
	}

	void setup() {
		ui.cont.clear();
		ui.buttons.clear();
		ui.cont.pane(p -> show(p, ui.buttons)).fillX().fillY();
	}

	public void build() {
		ui.show();
	}

	void evalMessage() {
		String def = getMessage();
		def = wrap ? "(function(){\"use strict\";" + def + "\n})();" : def;

		try {
			Object o = cx.evaluateString(scope, def, null, 1);
			if (o instanceof NativeJavaObject) {
				o = ((NativeJavaObject) o).unwrap();
			}

			if (o instanceof Undefined) {
				o = "undefined";
			}

			log = String.valueOf(o);
			if (log == null) log = "null";
			else log = log.replaceAll("\\[(.*?)]", "[ $1 ]");
		} catch (Throwable ex) {
			error = true;
			loop = false;
			if (!ignoreError) Vars.ui.showException("执行出错", ex);
			String type = ex.getClass().getSimpleName();
			log = "[red][" + type + "][]" + ex.getMessage();
		}

	}

	public void load() {
		ui = new BaseDialog(localizedName());
		ui.addCloseListener();
		history = new ListDialog("history", Vars.dataDirectory.child("mods(I hope...)").child("historical record"),
				f -> f.child("message.txt"), f -> {
			area.setText(f.child("message.txt").readString());
			log = f.child("log.txt").readString();
		}, (f, p) -> {
			p.add(f.child("message.txt").readString()).row();
			p.image().height(3).fillX().row();
			p.add(f.child("log.txt").readString());
		}, Tester::sort);
		bookmark = new ListDialog("bookmark", Vars.dataDirectory.child("mods(I hope...)").child("bookmarks"),
				f -> f, f -> {
			area.setText(f.readString());
		}, (f, p) -> {
			p.add(f.readString()).row();
		}, Tester::sort);
		scripts = Vars.mods.getScripts();
		cx = scripts.context;
		scope = scripts.scope;

		try {
			var obj = new NativeJavaClass(scope, JSFunc.class, true);
			ScriptableObject.putProperty(scope, "IntFunc", obj);
		} catch (Exception ex) {
			if (ignoreError) {
				Log.err(ex);
			} else {
				Vars.ui.showException("IntFunc出错", ex);
			}

		}

		setup();
		btn.update(() -> {
			if (loop && !getMessage().equals("")) {
				evalMessage();
			}
		});
		loadSettings();
	}

	public void loadSettings() {
		Table table = new Table();
		table.check("忽略报错", b -> ignoreError = b);

		Contents.settings.add(localizedName(), table);
	}

	public String getMessage() {
		return area.getText();
	}

	public void put(String name, Object val) {
		ScriptableObject.putProperty(scope, name, Context.javaToJS(val, scope));
	}

	public void put(Object val) {
		int i = 0;
		String prefix = "temp";
		while (ScriptableObject.hasProperty(scope, prefix + i)) {
			i++;
		}
		put(prefix + i, val);
		Vars.ui.showInfoFade("已储存为[accent]" + prefix + i);
	}

	public static class JSFunc {
		public static ClassLoader main;
		public static Scriptable scope;
		public static ObjectMap<String, NativeJavaClass> classes;
		public static NativeJavaClass Reflect;

		/*public static Object eval(String code) {
			var scripts = new Scripts();
			return scripts.context.evaluateString(scripts.scope, code, "none", 1);
		}*/

		public static void showInfo(Object o) {
			showInfo(o, o.getClass());
		}

		public static void showInfo(Class<?> clazz) {
			showInfo(null, clazz);
		}

		public static void showInfo(Object o, Class<?> clazz) {
//			if (!clazz.isInstance(o)) return;
			try {
				MyReflect.lookupSetClassLoader(clazz, Field.class.getClassLoader());
			} catch (Throwable e) {
				Log.err(e);
			}
			final Table fields;
			if (clazz.isArray()) {
				if (o == null) return;
				Table _cont = new Table();
				_cont.defaults().grow();
				int length = Array.getLength(o);

				for (int i = 0; i < length; ++i) {
					Object item = Array.get(o, i);
					var button = new TextButton("" + item);
					button.clicked(() -> {
						showInfo(item);
					});
					_cont.add(button).fillX().minHeight(40).row();
				}

				new BaseDialog(clazz.getSimpleName()) {{
					cont.pane(_cont).grow();
					addCloseButton();
				}}.show();

				return;
			}
			final Table _cont = new Table();
			_cont.button("存储为js变量", () -> tester.put(o)).padLeft(10f).height(50).growX().row();
			_cont.table(t -> {
				t.left().defaults().left();
				t.add(clazz.getTypeName());
				t.button(Icon.copy, Styles.cleari, () -> {
					Core.app.setClipboardText(clazz.getTypeName());
				});
			}).fillX().pad(6, 10, 6, 10).row();
			_cont.image().color(Pal.accent).fillX().row();
			fields = _cont.table(t -> t.left().defaults().left().top())
					.pad(4, 6, 4, 6).fillX().get();
			_cont.row();
			_cont.image().color(Pal.accent).fillX().row();
			Table methods = _cont.table(t -> {
				t.left().defaults().left().top();
			}).pad(4, 6, 4, 6).fill().get();

			for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
				if (fields.getChildren().size != 0) {
					fields.add(c.getSimpleName()).row();
					fields.image().color(Color.lightGray).fillX().row();
					methods.add(c.getSimpleName()).row();
					methods.image().color(Color.lightGray).fillX().row();
				}

//				for (Field f : c.getDeclaredFields()) {
				Field[] fields2 = {};
				try {
					fields2 = MyReflect.lookupGetFields(c);
					if (fields2.length == 0) throw new RuntimeException();
				} catch (Throwable e) {
					try {
						fields2 = c.getDeclaredFields();
					} catch (Exception ignored) {}
				}
				for (Field f : fields2) {
					int modifiers = f.getModifiers();
					try {
						MyReflect.lookupRemoveFinal(f);
					} catch (Throwable ignored) {}
					try {
						MyReflect.setOverride(f);
					} catch (Throwable t) {Log.err(t);}

					Class<?> type = f.getType();
					fields.table(t -> {
						try {
							t.add(Modifier.toString(modifiers), Color.valueOf("#ff657a")).padRight(2);
							t.add(" ");
							t.add(f.getGenericType().getTypeName(), Color.valueOf("#9cd1bb"));
							t.add(" ");
							t.add(f.getName());
							t.add(" = ");
						} catch (Exception e) {
							Log.err(e);
						}

						Object[] val = {null};

						if (type.isPrimitive() || type.equals(String.class)) {
							try {
								val[0] = MyReflect.getValueExact(o, f);
								Label l = new Label("" + val[0]);
								l.setColor(Color.valueOf("#bad761"));
								t.add(l);
							} catch (Exception e) {
//								`Log.info`(e);
								t.add("Unknown", Color.red);
							}
						} else {
							Label l = t.add("???").get();
							l.clicked(() -> {
								try {
									val[0] = MyReflect.getValueExact(o, f);
									l.setText("" + val[0]);
									if (val[0] instanceof Color) {
										t.image(IntUI.whiteui.tint((Color) val[0])).size(32);
									}
									IntUI.longPress(l, 600, b -> {
										if (b) {
											if (val[0] != null)
												showInfo(val[0]);
											else showInfo(null, f.getType());
										}
									});
								} catch (Exception ex) {
									Log.err(ex);
									l.setText("");
								}
							});
						}

						t.button("将字段储存为js变量", () -> tester.put(f)).padLeft(10f).size(180, 40);
						t.button("将值存储为js变量", () -> tester.put(val[0])).padLeft(10f).size(180, 40);
					}).pad(4).row();
				}

				for (Method m : c.getDeclaredMethods()) {
					try {
						MyReflect.setOverride(m);
					} catch (Throwable ignored) {}
					methods.table(t -> {
						try {
							StringBuilder sb = new StringBuilder();
							int mod = m.getModifiers() & Modifier.methodModifiers();
							sb.append("[#ff657a]");
							if (mod != 0 && !m.isDefault()) {
								sb.append(Modifier.toString(mod)).append(' ');
							} else {
								sb.append(Modifier.toString(mod)).append(' ');
								if (m.isDefault()) {
									sb.append("default ");
								}
							}

							sb.append("[]");
							sb.append("[#9cd1bb]").append(m.getReturnType().getTypeName()).append("[] ");
							sb.append(m.getName());
							sb.append("[lightgray]([]");
							StringJoiner sj = new StringJoiner(", ");
							Class<?>[] exceptionTypes = m.getParameterTypes();

							for (Class<?> parameterType : exceptionTypes) {
								sj.add("[#9cd1bb]" + parameterType.getTypeName() + "[]");
							}

							sb.append(sj);
							sb.append("[lightgray])[]");
							exceptionTypes = m.getExceptionTypes();
							if (exceptionTypes.length > 0) {
								StringJoiner joiner = new StringJoiner(",", " [#ff657a]throws[] ", "");

								for (Class<?> exceptionType : exceptionTypes) {
									joiner.add(exceptionType.getTypeName());
								}

								sb.append(joiner);
							}

							t.add(sb);
							if (m.getParameterTypes().length == 0) {
								Label l = t.add("").padLeft(10).get();
								if (o != null || Modifier.isStatic(m.getModifiers())) t.button("invoke", () -> {
									try {
										Object returnV = m.invoke(o);
										l.setText("" + returnV);
										if (returnV != null && !(returnV instanceof String) && !returnV.getClass().isPrimitive()) {
//											l.setColor(Color.white);
											IntUI.longPress(l, 600, b -> {
												if (b) {
													showInfo(returnV);
												}
											});
										}
									} catch (Exception ex) {
										Vars.ui.showException("invoke出错", ex);
									}

								}).width(100);
							}

							t.button("将函数存储为js变量", () -> tester.put(m)).padLeft(10f).size(180, 40);
						} catch (Exception err) {
							t.add("<" + err + ">", Color.red);
						}

					}).pad(4).row();
				}
			}

			new BaseDialog(clazz.getSimpleName()) {{
				cont.pane(_cont).grow();
				addCloseButton();
			}}.show();
		}


		public static BaseDialog dialog(final Cons<BaseDialog> cons) {
			return new BaseDialog("test") {{
				cons.get(this);
				addCloseButton();
				show();
			}};
		}

		public static BaseDialog testElement(Element element) {
			return dialog(d -> {
				Table t = new Table(table -> {
					table.add(element);
				});
				d.cont.pane(t).fillX().fillY();
			});
		}

		public static BaseDialog testElement(String text) {
			return testElement(new Label(text));
		}

		public static void showElement(Element element) {
			elementShow.dialog.show(element);
		}

		public static Function<?> getFunction(String name) {
			return Selection.all.get(name);
		}

		public static Object unwrap(Object o) {
			if (o instanceof NativeJavaObject) {
				NativeJavaObject n = (NativeJavaObject) o;
				return n.unwrap();
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
			Reflect = new NativeJavaClass(scope, MyReflect.class, true);
		}
	}

	class ListDialog extends BaseDialog {
		public Seq<Fi> list = new Seq<>();
		final Table p = new Table();
		Floatf<Fi> sorter;
		Fi file;
		Func<Fi, Fi> fileHolder;
		Cons<Fi> consumer;
		Cons2<Fi, Table> pane;

		public ListDialog(String title, Fi file, Func<Fi, Fi> fileHolder, Cons<Fi> consumer, Cons2<Fi, Table> pane, Floatf<Fi> sorter) {
			super(Core.bundle.get("title." + title, title));
			cont.pane(p).fillX().fillY();
			addCloseButton();
			this.file = file;
			list.addAll(file.list());
			this.fileHolder = fileHolder;
			this.consumer = consumer;
			this.pane = pane;
			this.sorter = sorter;

			list.sort(sorter);
		}

		public Dialog show(Scene stage, Action action) {
			build();
			return super.show(stage, action);
		}

		public void build() {
			p.clearChildren();

			list.each(f -> {
				p.table(Tex.button, t -> {
					Button btn = t.left().button(b -> {
								b.pane(c -> {
									c.add(fileHolder.get(f).readString()).left();
								}).fillY().fillX().left();
							}, IntStyles.clearb, () -> {}).height(70)
							.minWidth(400).growX().fillX().left().get();
					IntUI.longPress(btn, 600, longPress -> {
						if (longPress) {
							Dialog ui = new Dialog("");
							ui.cont.pane(p1 -> {
								pane.get(f, p1);
							}).size(400).row();
							ui.cont.button(Icon.trash, () -> {
								ui.hide();
								f.delete();
							}).row();
							Objects.requireNonNull(ui);
							ui.cont.button("@ok", ui::hide).fillX().height(60);
							ui.show();
						} else {
							consumer.get(f);
							build();
							hide();
						}

					});
					t.button("", Icon.trash, Styles.cleart, () -> {
						if (!f.deleteDirectory()) {
							f.delete();
						}

						list.remove(f);
						build();
					}).fill().right();
				}).width(w).row();
			});
		}
	}
}
