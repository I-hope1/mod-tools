package modtools.content.debug;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.graphics.Color;
import arc.scene.ui.*;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.*;
import mindustry.Vars;
import mindustry.gen.*;
import mindustry.ui.Styles;
import mindustry.ui.fragments.ConsoleFragment;
import modtools.ui.*;
import modtools.ui.comp.*;
import modtools.ui.comp.input.MyLabel;
import modtools.ui.comp.limit.LimitTable;
import modtools.ui.comp.linstener.AutoWrapListener;
import modtools.content.Content;

import static modtools.utils.Tools.readFiOrEmpty;

public class LogDisplay extends Content {
	public LogDisplay() {
		super("logdisplay", Icon.fileTextSmall);
	}
	{
		defLoadable = false;
	}

	Window ui;
	Table  crashes;
	/** @see mindustry.net.CrashSender#send(Throwable, Cons) */
	private static Fi crashesDir() {
		return Fi.get(OS.getAppDataDirectoryString(Vars.appName)).child("crashes");
	}


	public void rebuild() {
		float w = getW();
		ui = new Window(localizedName(), w, 150, true);
		ui.update(() -> ui.minWidth = getW());

		Color[] colors = {Color.sky, Color.gold};

		Table[] tables = {new LimitTable(t -> {
			final Fi last_log = Vars.dataDirectory.child("last_log.txt");
			t.button("Source", HopeStyles.flatBordert, () -> {
				Core.app.openFolder(last_log.path());
			}).height(42).growX();
			t.button("Clear All", HopeStyles.flatBordert, () ->
			 IntUI.showConfirm("Confirm to clear",
				"Are you sure to clear?",
				() -> Vars.ui.consolefrag.clearMessages())
			).height(42).growX().row();
			t.pane(new LimitTable(MessageBuilder::new))
			 .colspan(2).grow().with(pane -> pane.update(new AutoWrapListener(pane)));
			t.invalidateHierarchy();
		}), new LimitTable(p -> {
			Seq<Fi> list = new Seq<>(crashesDir().list()).sort(f -> -f.lastModified());
			p.left().defaults().left().top().growX();
			for (var fi : list) {
				var label = new MyLabel("");
				TextButton button = p.button(fi.nameWithoutExtension(), new TextButtonStyle(Styles.logicTogglet), () -> {
					if (label.getText().length() == 0/* default method */) {
						label.setText(readFiOrEmpty(fi));
					}
				}).height(45).get();
				button.getStyle().up = Tex.underline;
				p.row();
				p.collapser(new Table(Tex.pane,
					cont -> cont.left().add(label)
					 .labelAlign(Align.left).growX().wrap().left()
				 ), true, button::isChecked)
				 .growX().row();
			}
		})};
		String[] names = {"last_log", "crashes"};
		IntTab   itab  = new IntTab(-1, names, colors, tables);
		itab.pane.update(new AutoWrapListener(itab.pane));
		itab.setPrefSize(w, -1);
		ui.cont.add(itab.build()).grow();
		ui.shown(() -> Core.app.post(() -> tables[0].invalidateHierarchy()));

		// ui.addCloseButton();
	}
	/** For log  */
	public static class MessageBuilder {
		private final Table       p;
		private final Seq<String> messages;
		MessageBuilder(Table p) {
			this.p = p;
			p.left().defaults().left().growX();
			messages = Reflect.get(ConsoleFragment.class, Vars.ui.consolefrag, "messages");
			Reflect.set(ConsoleFragment.class, Vars.ui.consolefrag, "messages", new DelegatingSeq(messages));
			rebuildAll();
		}
		void rebuildAll() {
			p.clearChildren();
			for (int i = messages.size - 1; i >= 0; i--) {
				String text = messages.get(i);
				p.add(text).wrap().row();
			}
		}
		class DelegatingSeq extends Seq<String> {
			public DelegatingSeq(Seq<? extends String> array) {
				super(array);
			}
			@Override
			public Seq<String> clear() {
				Core.app.post(MessageBuilder.this::rebuildAll);
				return super.clear();
			}
			@Override
			public void insert(int index, String value) {
				if (index == 0) {
					p.add(value).wrap().row();
				}
				super.insert(index, value);
			}
			@Override
			public boolean remove(String value) {
				p.find(elem -> elem instanceof Label label && label.getText().toString().equals(value)).remove();
				return super.remove(value);
			}
		}
	}

	private static float getW() {
		return Core.graphics.isPortrait() ? 400 : 600;
	}

	public void build() {
		if (ui == null) rebuild();
		else ui.toFront();
		Core.app.post(() -> ui.show());
	}
}
