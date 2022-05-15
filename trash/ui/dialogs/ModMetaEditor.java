package modmake.ui.dialogs;

import arc.Core;
import arc.files.Fi;
import arc.func.Func;
import arc.scene.event.ClickListener;
import arc.scene.ui.Button;
import arc.scene.ui.Dialog;
import arc.scene.ui.TextField;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.serialization.JsonValue;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.gen.Icon;
import modmake.ui.components.JsonParser;
import modmake.ui.IntUI;

public class ModMetaEditor extends Dialog {
	public ModMetaEditor() {
	}

	Fi modsDirectory = Vars.dataDirectory.child("mods(I hope...)").child("mods");

	private void write(Fi mod) {
		if (!isNull)
			file.moveTo(mod);
		Seq<String> str = new Seq<>();
		str.add("minGameVersion: " + Fields.get("minGameVersion").getText());

		for (String item : arr) {
			String text = Fields.get(item).getText();
			str.add(item + ": " + (text.replace("\\s+", "") == "" ? "\"\"" : text));
		}
		Func<Seq<String>, String> join = s -> {
			StringBuffer b = new StringBuffer();
			for (byte i = 0; i < s.size; i++) {
				b.append(s.get(i));
				if (i < s.size - 1)
					b.append("\n");
			}
			return b.toString();
		};
		mod.child(isNull ? "mod.json" : "mod." + file.extension()).writeString(join.get(str));
		modsDirectory.child("tmp").deleteDirectory();
		// IntModsDialog.constructor()
		hide();
	}

	String[] arr = { "name", "displayName", "author", "description", "version", "main", "repo" };

	ObjectMap<String, TextField> Fields = new ObjectMap<>();

	Button ok;
	boolean isNull;
	JsonValue obj;
	Fi file;

	void load() {
		float w = Core.graphics.getWidth(),
				h = Core.graphics.getHeight();
		buttons.button("$back", Icon.left, this::hide)
				.size(Math.max(w, h) * 0.1f, Math.min(w, h) * 0.1f);
		ok = buttons.button("$ok", Icon.ok, () -> {
			Fi mod = modsDirectory.child(Fields.get("fileName").getText());
			if (mod.path() != file.parent().path() && mod.exists()) {
				Vars.ui.showConfirm("覆盖", "同名文件已存在\n是否要覆盖", () -> {
					mod.deleteDirectory();
					write(mod);
				});
			} else
				write(mod);
		}).size(Math.max(w, h) * 0.1f, Math.min(w, h) * 0.1f).get();

		cont.add("@mod.fileName");

		Fields.put("fileName", cont.add(new TextField()).valid(text -> {
			boolean valid;
			ok.setDisabled(valid = (text.replaceAll("\\s", "") == "") || (text == "tmp"));
			return !valid;
		}).get());
		cont.row();

		cont.add("@minGameVersion");
		Fields.put("minGameVersion", cont.add(new TextField()).valid(text -> {
			float num = Float.parseFloat(text);
			boolean valid;
			ok.setDisabled(valid = Float.isNaN(num) || (num < 105) || (num > Version.build));
			return !valid;
		}).get());
		cont.row();

		for (String i : arr) {
			cont.add(Core.bundle.get(i, i));
			TextField field = new TextField();
			field.addListener(new ClickListener() {
				@Override
				public void clicked(arc.scene.event.InputEvent event, float x, float y) {
					// 如果长按时间大于600毫秒
					if (Time.millis() - visualPressedTime - visualPressedDuration * 1000 > 600)
						IntUI.showTextArea(field);
				}
			});
			Fields.put(i, field);
			cont.add(field).row();
		}
		closeOnBack();
	}

	public void build(Fi f) {
		file = f;
		isNull = !f.exists();
		if (isNull)
			file.writeString("");
		obj = JsonParser.parse(file.readString());
		title.setText(isNull ? "@mod.create" : "@edit");
		Fields.get("fileName").setText(isNull ? "" : f.parent().name());
		Fields.get("minGameVersion").setText(obj.has("minGameVersion") ? obj.get("minGameVersion").asString() : "105");

		for (String item : arr) {
			Fields.get(item).setText(obj.has(item) ? obj.get(item).asString().replaceAll("(\\n)|(\\r)", "\\n") : "");
		}

		show();
	}

}