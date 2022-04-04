package modmake.ui.content;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.func.Cons2;
import arc.func.Func;
import arc.graphics.Color;
import arc.scene.Action;
import arc.scene.Element;
import arc.scene.Scene;
import arc.scene.ui.Button;
import arc.scene.ui.Dialog;
import arc.scene.ui.Label;
import arc.scene.ui.TextArea;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import modmake.ui.IntStyles;
import modmake.ui.IntUI;
import modmake.ui.components.IntTextArea;
import rhino.*;

import java.lang.reflect.Modifier;
import java.util.StringJoiner;

public class Tester extends Content {
	String log = "";
	TextArea area;
	boolean loop = false, wrap = false; // scope: false,
	final float w = Core.graphics.getWidth() > Core.graphics.getHeight() ? 540f : 440f;

	public Tester() {
		super("tester");
	}

	BaseDialog ui;
	ListDialog history;
	ListDialog bookmark;

	public void show(Table table, Table buttons) {
		Table cont = new Table();

		var textarea = new IntTextArea("", w, 390);
		area = textarea.area;
		cont.add(textarea).row();

		cont.button("$ok", () -> {
			area.setText(getMessage().replaceAll("\r", ""));
			evalMessage();

			Fi d = history.file.child(Time.millis() + "");
			d.child("message.txt").writeString(getMessage());
			d.child("log.txt").writeString(log);
			history.list.add(d);
			for (int i = 0; i < history.list.size - 30; i++) {
				history.list.get(i).deleteDirectory();
				history.list.remove(i);
			}

		}).row();

		cont.table(Tex.button, t -> t.pane(p -> p.label(() -> log)).size(w, 390f));

		table.add(cont).row();

		table.pane(p -> {
			p.button("", Icon.star, Styles.cleart, () -> {
				Fi fi = bookmark.file.child(Time.millis() + ".txt");
				bookmark.list.add(fi);
				fi.writeString(getMessage());
			});
			p.button(b -> b.label(() -> loop ? "循环" : "默认"), Styles.defaultb, () -> loop = !loop).size(100f,
					55f);
			p.button(b -> b.label(() -> wrap ? "严格" : "非严格"), Styles.defaultb, () -> wrap = !wrap).size(100f, 55f);

			p.button("历史记录", () -> history.show()).size(100, 55);
			p.button("收藏", () -> bookmark.show()).size(100f, 55f);
		}).height(60f).fillX();

		buttons.button("$back", Icon.left, () -> ui.hide()).size(210, 64);

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
				}).marginLeft(12f);

				t.row();
				t.button("@schematic.copy", Icon.copy, style, () -> {
					dialog.hide();
					Core.app.setClipboardText(getMessage().replaceAll("\r", "\n"));
				}).marginLeft(12f);
			});
		});

		dialog.addCloseButton();

		buttons.button("$edit", Icon.edit, dialog::show).size(210f, 64f);
	}

	void setup() {
		ui.cont.clear();
		ui.buttons.clear();

		ui.cont.pane(p -> show(p, ui.buttons)).fillX().fillY();
	}

	@Override
	public void build() {
		ui.show();
	}

	public Scriptable scope;
	public Context cx;

	void evalMessage() {
		String def = getMessage();
		def = wrap ? "(function(){\"use strict\";" + def + "\n})();" : def;
		try {
			Object o = cx.evaluateString(scope, def, null, 1);
			if (o instanceof NativeJavaObject) o = ((NativeJavaObject) o).unwrap();
			if (o instanceof Undefined) o = "undefined";
			log = String.valueOf(o).replaceAll("\\[(.*?)]", "[ $1 ]");
		} catch (Throwable t) {
			Vars.ui.showException(t);
			log = "[red][" + t.getClass().getSimpleName() + "][]" + t.getMessage();
		}
	}

	@Override
	public void load() {
		ui = new BaseDialog(localizedName());
		ui.addCloseListener();

		history = new ListDialog("history", Vars.dataDirectory.child("mods(I hope...)").child("historical record"), f -> f.child("message.txt"), f -> {
			area.setText(f.child("message.txt").readString());
			log = f.child("log.txt").readString();
		}, (f, p) -> {
			p.add(f.child("message.txt").readString()).row();
			p.image().height(3).fillX().row();
			p.add(f.child("log.txt").readString());
		}, true);

		bookmark = new ListDialog("bookmark", Vars.dataDirectory.child("mods(I hope...)").child("bookmarks"),
				f -> f, f -> area.setText(f.readString()), (f, p) -> p.add(f.readString()).row(), false);

		var scripts = Vars.mods.getScripts();
		this.cx = scripts.context;
		this.scope = scripts.scope;

		try {
			Object obj = Context.javaToJS(new JSFunc(), scope);
			ScriptableObject.putProperty(scope, "IntFunc", obj);
			// cx.evaluateString(scope, "Log.info(IntFunc)", null, 1);
		} catch (Exception e) {
			Vars.ui.showException(e);
		}
		// scripts.runConsole(mod.root.child("tester.js").readString());

		setup();

		btn.update(() -> {
			if (loop && !getMessage().equals(""))
				evalMessage();
		});
	}

	@Override
	public void loadString() {
	}

	public String getMessage() {
		return area.getText();
	}

	class ListDialog extends BaseDialog {
		public Seq<Fi> list = new Seq<>();

		public ListDialog(String title, Fi file, Func<Fi, Fi> fileHolder, Cons<Fi> consumer, Cons2<Fi, Table> pane,
		                  boolean sort) {
			super(Core.bundle.get("title." + title, title));
			this.file = file;
			this.list.addAll(file.list());
			this.fileHolder = fileHolder;
			this.consumer = consumer;
			this.pane = pane;
			this.sort = sort;
		}

		final Table p = new Table();
		boolean sort;
		Fi file;
		Func<Fi, Fi> fileHolder;
		Cons<Fi> consumer;
		Cons2<Fi, Table> pane;

		public Dialog show(Scene stage, Action action) {
			build();
			return super.show(stage, action);
		}

		public void build() {
			p.clearChildren();
			for (int j = list.size - 1; j >= 0; j--) {
				Fi f = list.get(j);
				p.table(Tex.button, t -> {
					Button btn = t.left().button(b -> b.pane(c -> c.add(fileHolder.get(f).readString())
											.left()).fillY().fillX()
									.left(), IntStyles.clearb, () -> {
							})
							.height(70f).minWidth(400f).growX().fillX().left().get();
					IntUI.longPress(btn, 600f, longPress -> {
						if (longPress) {
							Dialog ui = new Dialog("");
							ui.cont.pane(p1 -> pane.get(f, p1)).size(400f).row();
							ui.cont.button(Icon.trash, () -> {
								ui.hide();
								f.delete();
							}).row();
							ui.cont.button("$ok", ui::hide).fillX().height(60);
							ui.show();
						} else {
							consumer.get(f);
							build();
							hide();
						}
					});
					t.button("", Icon.trash, Styles.cleart,
							() -> {
								if (!f.deleteDirectory()) f.delete();
								list.remove(f);
								this.build();
							}).fill().right();
				}).width(w).row();
			}
		}

		{
			cont.pane(p).fillX().fillY();
			addCloseButton();
		}
	}

	public static class JSFunc {
		// 以下提供给 JavaScript
		public void showInfo(Object o) {
			Class<?> finalC = o.getClass();
			Table cont = new Table();
			cont.table(t -> {
				t.left().defaults().left();
				t.add(finalC.getTypeName());
				t.button(Icon.copy, Styles.clearPartiali, () -> Core.app.setClipboardText(finalC.getTypeName()));
			}).fillX().pad(6, 10, 6, 10).row();
			cont.image().color(Pal.accent).fillX().row();
			Table fields = cont.table(t -> t.left().defaults().left().top()).pad(4, 6, 4, 6).fillX().get();
			cont.row();
			cont.image().color(Pal.accent).fillX().row();
			Table methods = cont.table(t -> t.left().defaults().left().top()).pad(4, 6, 4, 6).fill().get();
			for (Class<?> c = finalC; c != Object.class; c = c.getSuperclass()) {
				if (fields.getChildren().size != 0) {
					fields.add(c.getSimpleName()).row();
					fields.image().color(Color.lightGray).fillX().row();
					methods.add(c.getSimpleName()).row();
					methods.image().color(Color.lightGray).fillX().row();
				}
				for (var f : c.getDeclaredFields()) {
					fields.table(t -> {
						t.add(Modifier.toString(f.getModifiers()), Color.valueOf("#ff657a")).padRight(2);
						t.add(" ");
						t.add(f.getGenericType().getTypeName(), Color.valueOf("#9cd1bb"));
						t.add(" ");
						t.add(f.getName());
						t.add(" = ");
						try {
							f.setAccessible(true);
							if (f.getType().isPrimitive() || f.getType().equals(String.class)) {
								t.add("" + f.get(o), Color.valueOf("#bad761"));
							} else {
								Label l = t.add("???").get();
								l.clicked(() -> {
									try {
										Object v = f.get(o);
										l.setText("" + v);
										IntUI.longPress(l, 600f, b -> {
											if (b) this.showInfo(v);
										});
									} catch (IllegalAccessException e) {
										l.setText("");
									}
								});
							}
						} catch (Exception e) {
							t.add("Unknow", Color.red);
						}
					}).pad(4).row();
				}
				for (var m : c.getDeclaredMethods()) {
					methods.table(t -> {
						try {
							StringBuilder sb = new StringBuilder();

							int mod = m.getModifiers() & Modifier.methodModifiers();

							sb.append("[#ff657a]");
							if (mod != 0 && !m.isDefault()) {
								sb.append(Modifier.toString(mod)).append(' ');
							} else {
								sb.append(Modifier.toString(mod)).append(' ');
								if (m.isDefault())
									sb.append("default ");
							}
							sb.append("[]");
							sb.append("[#9cd1bb]").append(m.getReturnType().getTypeName()).append("[] ");
							sb.append(m.getName());

							sb.append("[lightgray]([]");
							StringJoiner sj = new StringJoiner(", ");
							for (Class<?> parameterType : m.getParameterTypes()) {
								sj.add("[#9cd1bb]" + parameterType.getTypeName() + "[]");
							}
							sb.append(sj);
							sb.append("[lightgray])[]");

							Class<?>[] exceptionTypes = m.getExceptionTypes();
							if (exceptionTypes.length > 0) {
								StringJoiner joiner = new StringJoiner(",", " [#ff657a]throws[] ", "");
								for (Class<?> exceptionType : exceptionTypes) {
									joiner.add(exceptionType.getTypeName());
								}
								sb.append(joiner);
							}
							t.add(sb);
							if (m.getParameterTypes().length == 0) {
								Label l = t.add("").padLeft(10f).get();
								t.button("invoke", () -> {
									try {
										l.setText("" + m.invoke(o));
									} catch (Exception e) {
										Vars.ui.showException(e);
									}
								}).width(100f);
							}
						} catch (Exception e) {
							t.add("<" + e + ">", Color.red);
						}
						// t.add(m.toGenericString());
						/*
						 * t.add(m.getName());
						 * t.add("(");
						 * var types = m.getParameterTypes();
						 * if (types.length != 0) {
						 * int iMax = types.length - 1;
						 * for (int i = 0;; i++) {
						 * final boolean[] simple = { true };
						 * final Class<?> clazz = types[i];
						 * t.add(clazz.getSimpleName()).with(l -> {
						 * l.setText(simple[0] ? clazz.getName() : clazz.getSimpleName());
						 * simple[0] = !simple[0];
						 * });
						 * if (i == iMax)
						 * break;
						 * t.add(",").padRight(3);
						 * }
						 * }
						 * t.add(")");
						 */
					}).pad(4).row();
				}
			}
			Table _cont = cont;
			new BaseDialog(finalC.getSimpleName()) {
				{
					cont.pane(_cont).fillX().fillY();
					addCloseButton();
				}
			}.show();
		}

		public BaseDialog dialog(Cons<BaseDialog> cons) {
			return new BaseDialog("test") {
				{
					cons.get(this);
					addCloseButton();
					show();
				}
			};
		}

		public BaseDialog testElement(Element element) {
			return dialog(d -> {
				Table t = new Table(table -> table.add(element));
				d.cont.pane(t).fillX().fillY();
			});
		}

		public BaseDialog testElement(String text) {
			return  testElement(new Label(text));
		}

		public Selection.Function<?> getFunction(String name) {
			return Selection.all.get(name);
		}
	}
}
