
package modtools.ui.content;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.func.Cons2;
import arc.func.Func;
import arc.graphics.Color;
import arc.scene.Action;
import arc.scene.Element;
import arc.scene.Scene;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Button;
import arc.scene.ui.Dialog;
import arc.scene.ui.Label;
import arc.scene.ui.TextArea;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.mod.Scripts;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import modtools.ui.IntStyles;
import modtools.ui.IntUI;
import modtools.ui.components.IntTextArea;
import modtools.ui.content.Selection.Function;
import rhino.*;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.StringJoiner;

public class Tester extends Content {
	String log = "";
	TextArea area;
	boolean loop = false, wrap = false, error;
	final float w = Core.graphics.isPortrait() ? 440 : 540;
	BaseDialog ui;
	ListDialog history, bookmark;
	public Scriptable scope;
	public Context cx;

	public Tester() {
		super("tester");
	}

	public void show(Table table, Table buttons) {
		Table cont = new Table();
		IntTextArea textarea = new IntTextArea("", w, 390);
		area = textarea.area;
		cont.add(textarea).row();
		cont.button("$ok", () -> {
			error = false;
			area.setText(getMessage().replaceAll("\r", ""));
			evalMessage();
			Fi d = history.file.child("" + Time.millis());
			d.child("message.txt").writeString(getMessage());
			d.child("log.txt").writeString(log);
			history.list.add(d);

			for (int i = 0; i < history.list.size - 30; ++i) {
				history.list.get(i).deleteDirectory();
				history.list.remove(i);
			}

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
		ui.cont.pane(p -> {
			show(p, ui.buttons);
		}).fillX().fillY();
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

			log = String.valueOf(o).replaceAll("\\[(.*?)]", "[ $1 ]");
		} catch (Throwable ex) {
			error = true;
			loop = false;
			Vars.ui.showException(ex);
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
		}, true);
		bookmark = new ListDialog("bookmark", Vars.dataDirectory.child("mods(I hope...)").child("bookmarks"),
				f -> f, f -> {
			area.setText(f.readString());
		}, (f, p) -> {
			p.add(f.readString()).row();
		}, false);
		Scripts scripts = Vars.mods.getScripts();
		cx = scripts.context;
		scope = scripts.scope;

		try {
			Object obj = Context.javaToJS(JSFunc.class, scope);
			ScriptableObject.putProperty(scope, "IntFunc", obj);
		} catch (Exception ex) {
			Vars.ui.showException(ex);
		}

		setup();
		btn.update(() -> {
			if (loop && !getMessage().equals("")) {
				evalMessage();
			}

		});
	}

	public void loadSettings() {
	}

	public String getMessage() {
		return area.getText();
	}

	public static class JSFunc {
		public static ClassLoader main;
		public static Scriptable scope;
		public static ObjectMap<String, NativeJavaClass> classes;

		public JSFunc() {
		}

		public static void showInfo(Object o) {
			Class<?> finalC = o.getClass();
			final Table fields;
			if (finalC.isArray()) {
				int length = Array.getLength(o);
				fields = new Table();

				for (int i = 0; i < length; ++i) {
					Object item = Array.get(o, i);
					Label l = new Label("" + item);
					IntUI.longPress(l, 600, b -> {
						if (b) {
							showInfo(item);
						}
					});
					fields.add(l).row();
				}

				new BaseDialog(finalC.getSimpleName()) {{
					cont.pane(fields).fillX().fillY();
					addCloseButton();
				}}.show();

				return;
			}
			final Table cont = new Table();
			cont.table(t -> {
				t.left().defaults().left();
				t.add(finalC.getTypeName());
				t.button(Icon.copy, Styles.cleari, () -> {
					Core.app.setClipboardText(finalC.getTypeName());
				});
			}).fillX().pad(6, 10, 6, 10).row();
			cont.image().color(Pal.accent).fillX().row();
			fields = cont.table(t -> {
				t.left().defaults().left().top();
			}).pad(4, 6, 4, 6).fillX().get();
			cont.row();
			cont.image().color(Pal.accent).fillX().row();
			Table methods = cont.table(t -> {
				t.left().defaults().left().top();
			}).pad(4, 6, 4, 6).fill().get();

			for (Class<?> c = finalC; c != Object.class; c = c.getSuperclass()) {
				if (fields.getChildren().size != 0) {
					fields.add(c.getSimpleName()).row();
					fields.image().color(Color.lightGray).fillX().row();
					methods.add(c.getSimpleName()).row();
					methods.image().color(Color.lightGray).fillX().row();
				}

				for (Field f : c.getDeclaredFields()) {
					f.setAccessible(true);
					fields.table(t -> {
						t.add(Modifier.toString(f.getModifiers()), Color.valueOf("#ff657a")).padRight(2);
						t.add(" ");
						t.add(f.getGenericType().getTypeName(), Color.valueOf("#9cd1bb"));
						t.add(" ");
						t.add(f.getName());
						t.add(" = ");

						try {
							if (!f.getType().isPrimitive() && !f.getType().equals(String.class)) {
								Label l = t.add("???").get();
								l.clicked(() -> {
									try {
										Object v = f.get(o);
										l.setText("" + v);
										IntUI.longPress(l, 600, b -> {
											if (b) {
												showInfo(v);
											}

										});
									} catch (IllegalAccessException var4) {
										l.setText("");
									}

								});
							} else {
								t.add("" + f.get(o), Color.valueOf("#bad761"));
							}
						} catch (Exception var4) {
							t.add("Unknow", Color.red);
						}

					}).pad(4).row();
				}

				for (Method m : c.getDeclaredMethods()) {
					m.setAccessible(true);
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
							Class[] exceptionTypes = m.getParameterTypes();

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
								t.button("invoke", () -> {
									try {
										Object returnV = m.invoke(o);
										l.setText("" + returnV);
										if (!(returnV instanceof String) && !returnV.getClass().isPrimitive()) {
											IntUI.longPress(l, 600, b -> {
												if (b) {
													showInfo(returnV);
												}

											});
										}
									} catch (Exception var4) {
										Vars.ui.showException(var4);
									}

								}).width(100);
							}
						} catch (Exception err) {
							t.add("<" + err + ">", Color.red);
						}

					}).pad(4).row();
				}
			}

			new BaseDialog(finalC.getSimpleName()) {{
				cont.pane(cont).fillX().fillY();
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

		public static Function<?> getFunction(String name) {
			return Selection.all.get(name);
		}

		public static Class<?> toClass(Class<?> clazz) {
			return clazz;
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

		static {
			main = Vars.mods.mainLoader();
			scope = Vars.mods.getScripts().scope;
			classes = new ObjectMap<>();
		}
	}

	class ListDialog extends BaseDialog {
		public Seq<Fi> list = new Seq<>();
		final Table p = new Table();
		boolean sort;
		Fi file;
		Func<Fi, Fi> fileHolder;
		Cons<Fi> consumer;
		Cons2<Fi, Table> pane;

		public ListDialog(String title, Fi file, Func<Fi, Fi> fileHolder, Cons<Fi> consumer, Cons2<Fi, Table> pane, boolean sort) {
			super(Core.bundle.get("title." + title, title));
			cont.pane(p).fillX().fillY();
			addCloseButton();
			this.file = file;
			list.addAll(file.list());
			this.fileHolder = fileHolder;
			this.consumer = consumer;
			this.pane = pane;
			this.sort = sort;
		}

		public Dialog show(Scene stage, Action action) {
			build();
			return super.show(stage, action);
		}

		public void build() {
			p.clearChildren();

			for (int j = list.size - 1; j >= 0; --j) {
				Fi f = list.get(j);
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
							ui.cont.button("$ok", ui::hide).fillX().height(60);
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
			}

		}
	}
}
