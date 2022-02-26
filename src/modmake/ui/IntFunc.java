package modmake.ui;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.Texture;
import arc.graphics.g2d.TextureRegion;
import arc.scene.Element;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.mod.Mods;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import modmake.ui.content.Selection;

import java.lang.reflect.Modifier;
import java.util.StringJoiner;

public class IntFunc {
	/* 选择文件 */
	public static void selectFile(boolean open, String purpose, String ext, Cons<Fi> cons) {
		StringBuilder b = new StringBuilder(purpose);
		String _purpose = purpose.charAt(0) == '@' || purpose.charAt(0) == '$'
				? Core.bundle.get(b.substring(1), purpose)
				: purpose;

		Vars.platform.showFileChooser(open, _purpose + " (." + ext + ")", ext, fi -> {
			try {
				cons.get(fi);
			} catch (Throwable err) {
				Log.err("Throw error when failed to select file: ", err);
			}
		});
	}

	/* 查找图片 */
	public static TextureRegion find(Mods.LoadedMod mod, String name) {
		Seq<Fi> all = mod.root.child("sprites").findAll(fi -> fi.name().equals(name + "png"));
		return all.size == 0 ? Core.atlas.find("error") : new TextureRegion(new Texture(all.first()));
	}

	// 以下提供给 JavaScript
	public static void showInfo(Object o) {
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
				Class<?> finalC1 = c;
				fields.table(t -> {
					t.add(Modifier.toString(f.getModifiers()), Color.valueOf("#ff657a")).padRight(2);
					t.add(" ");
					t.add(f.getGenericType().getTypeName(), Color.valueOf("#9cd1bb"));
					t.add(" ");
					t.add(f.getName());
					t.add(" = ");
					try {
						if (f.getType().isPrimitive() || f.getType().equals(String.class)) {
							t.add("" + f.get(finalC1.cast(o)), Color.valueOf("#bad761"));
						} else {
							Label l = t.add("???").get();
							l.clicked(() -> {
								try {
									l.setText("" + f.get(finalC1.cast(o)));
								} catch (IllegalAccessException e) {
									l.setText("");
								}
							});
						}
					} catch (IllegalAccessException e) {
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
							sb.append(joiner.toString());
						}
						t.add(sb);
					} catch (Exception e) {
						t.add("<" + e + ">", Color.red);
					}
					// t.add(m.toGenericString());
					/*t.add(m.getName());
					t.add("(");
					var types = m.getParameterTypes();
					if (types.length != 0) {
						int iMax = types.length - 1;
						for (int i = 0;; i++) {
							final boolean[] simple = { true };
							final Class<?> clazz = types[i];
							t.add(clazz.getSimpleName()).with(l -> {
								l.setText(simple[0] ? clazz.getName() : clazz.getSimpleName());
								simple[0] = !simple[0];
							});
							if (i == iMax)
								break;
							t.add(",").padRight(3);
						}
					}
					t.add(")");*/
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

	public static BaseDialog dialog(Cons<BaseDialog> cons) {
		return new BaseDialog("test") {
			{
				cons.get(this);
				addCloseButton();
				show();
			}
		};
	}

	public static BaseDialog testElement(Element element) {
		return dialog(d -> {
			Table t = new Table(table -> table.add(element));
			d.cont.pane(t).fillX().fillY();
		});
	}

	public static Selection.Function<?> getFunction(String name) {
		return Selection.all.get(name);
	}
}
