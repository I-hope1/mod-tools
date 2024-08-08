package modtools.jsfunc;


import arc.Core;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.scene.*;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.gen.Icon;
import modtools.IntVars;
import modtools.events.MyEvents;
import modtools.ui.*;
import modtools.ui.comp.Window;
import modtools.ui.comp.Window.*;
import modtools.ui.comp.limit.*;
import modtools.ui.comp.utils.ArrayItemLabel;
import modtools.ui.control.HopeInput;
import modtools.utils.*;
import modtools.utils.JSFunc.JColor;
import modtools.utils.search.BindCell;
import modtools.utils.ui.ShowInfoWindow;
import modtools.utils.world.WorldDraw;

import java.lang.reflect.Array;

import static modtools.utils.ElementUtils.getAbsolutePos;

public interface INFO_DIALOG {
	static Window showInfo(Object o) {
		if (o == null) return null;
		return showInfo(o, o.getClass());
	}
	static Window showInfo(Class<?> clazz) {
		return showInfo(null, clazz);
	}
	static Window showInfo(Object o, Class<?> clazz) {
		Window[] dialog = {null};
		if (clazz != null && clazz.isArray()) {
			if (o == null) return new DisWindow("none");
			Table _cont = new LimitTable();

			int length = Array.getLength(o);
			_cont.add("Length: " + length).left();

			_cont.button(Icon.refresh, HopeStyles.clearNonei, () -> {
				dialog[0].hide();
				var pos = getAbsolutePos(dialog[0]);
				try {
					showInfo(o, clazz).setPosition(pos);
				} catch (Throwable e) {
					IntUI.showException(e).setPosition(pos);
				}
				dialog[0] = null;
			}).left().size(50).row();

			MyEvents events = new MyEvents();

			MyEvents prev = MyEvents.current;
			MyEvents.current = events;
			buildArrayCont(o, clazz, length, _cont);
			MyEvents.current = prev;
			_cont.row();

			dialog[0] = new DisWindow(clazz.getSimpleName(), 200, 200, true);
			dialog[0].cont.pane(_cont).grow();
			dialog[0].show();
			dialog[0].hidden(() -> {
				events.fireIns(Disposable.class);
				events.removeIns();
			});
			return dialog[0];
		}

		dialog[0] = new ShowInfoWindow(o, clazz == null ? Class.class : clazz);
		//		dialog.addCloseButton();
		dialog[0].show();
		assert dialog[0] != null;
		return dialog[0];
	}
	private static void buildArrayCont(Object arr, Class<?> clazz, int length, Table cont) {
		Class<?> componentType = clazz.getComponentType();
		Table    c1            = null;
		for (int i = 0; i < length; i++) {
			int j = i;
			if (i % 100 == 0) c1 = cont.row().table().grow().colspan(2).get();

			var button = new LimitTextButton("", HopeStyles.cleart);
			button.clearChildren();
			button.add(i + "[lightgray]:", HopeStyles.defaultLabel).padRight(8f);
			var label = new ArrayItemLabel(componentType, arr, i);

			var cell  = BindCell.of(ShowInfoWindow.extentCell(button,
			 componentType == Object.class && label.val != null ? label.val.getClass() : componentType,
			 () -> label));
			ShowInfoWindow.buildExtendingField(cell, label);
			MyEvents.on(Disposable.class, cell::clear);

			button.add(label).grow();
			button.clicked(() -> {
				if (label.val != null) IntVars.postToMain(Tools.runT0(() ->
				 showInfo(label.val).setPosition(getAbsolutePos(button))));
				else IntUI.showException(new NullPointerException("item is null"));
			});
			c1.add(button).growX().minHeight(40);
			IntUI.addWatchButton(c1, arr + "#" + i, () -> Array.get(arr, j)).row();
			c1.image().color(Tmp.c1.set(JColor.c_underline)).colspan(2).growX().row();
		}
	}

	static JSWindow window(final Cons<Window> cons) {
		return new JSWindow(cons);
	}
	static JSWindow btn(String text, Runnable run) {
		return dialog(t -> t.button(text, HopeStyles.flatt, run).size(64, 45));
	}
	static JSWindow testDraw(Runnable draw) {
		return testDraw0(_ -> draw.run());
	}
	static JSWindow testDraw0(Cons<Group> draw) {
		return dialog(new Group() {
			{ transform = true; }

			public void drawChildren() {
				draw.get(this);
			}
		});
	}
	// static FrameBuffer buffer = new FrameBuffer();
	static JSWindow testShader(Shader shader, Runnable draw) {
		return testDraw0(t -> {
			// buffer.resize(Core.graphics.getWidth(), Core.graphics.getHeight());
			FrameBuffer buffer  = new FrameBuffer(Core.graphics.getWidth(), Core.graphics.getHeight());
			Texture     texture = WorldDraw.drawTexture(buffer, draw);
			Draw.blit(texture, shader);
			buffer.dispose();
		});
	}
	static JSWindow dialog(Element element) {
		return window(d -> d.cont.pane(element).grow());
	}
	static JSWindow dialog(String text) {
		return dialog(new Label(text));
	}
	static JSWindow dialog(TextureRegion region) {
		return dialog(new Image(region));
	}
	static JSWindow dialog(Texture texture) {
		return dialog(new TextureRegion(texture));
	}
	static JSWindow dialog(Drawable drawable) {
		return dialog(new Image(drawable));
	}
	static JSWindow dialog(Color color) {
		return dialog(new ColorImage(color));
	}
	static JSWindow pixmap(int size, Cons<Pixmap> cons) {
		return pixmap(size, size, cons);
	}
	static JSWindow pixmap(int width, int height, Cons<Pixmap> cons) {
		Pixmap pixmap = new Pixmap(width, height);
		cons.get(pixmap);
		JSWindow dialog = dialog(new TextureRegion(new Texture(pixmap)));
		dialog.hidden(pixmap::dispose);
		return dialog;
	}
	static JSWindow dialog(Cons<Table> cons) {
		return dialog(new Table(cons));
	}
	static void dialog(Element element, boolean disposable) {
		dialog(element).autoDispose = disposable;
	}
	static void dialog(String text, boolean disposable) {
		dialog(text).autoDispose = disposable;
	}
	static void dialog(TextureRegion region, boolean disposable) {
		dialog(region).autoDispose = disposable;
	}

	static void dialog(Texture texture, boolean disposable) {
		dialog(texture).autoDispose = disposable;
	}

	static void dialog(Drawable drawable, boolean disposable) {
		dialog(drawable).autoDispose = disposable;
	}
	static void dialog(Color color, boolean disposable) {
		dialog(color).autoDispose = disposable;
	}
	static void dialog(Pixmap pixmap, boolean disposable) {
		dialog(new TextureRegion(new Texture(pixmap))).autoDispose = disposable;
	}


	class $ {
		@SuppressWarnings("rawtypes")
		public static void buildLongPress(ImageButton button, Prov o) {
			EventHelper.longPress0(button, () -> INFO_DIALOG.showInfo(o));
		}
	}

	class JSWindow extends HiddenTopWindow implements IDisposable {
		Cons<Window> cons;
		/** 是否会自动关闭并销毁 */
		boolean      autoDispose = false;
		Runnable     autoDisposeRun;
		private void dispose() {
			if (autoDispose && !HopeInput.pressed.isEmpty()) {
				hide();
			}
		}
		public void act(float delta) {
			super.act(delta);
			if (autoDisposeRun != null) autoDisposeRun.run();
		}
		public JSWindow(Cons<Window> cons) {
			super("TEST", 64, 64);
			this.cons = cons;
			show();
			title.setFontScale(0.7f);
			for (Cell<?> child : titleTable.getCells()) {
				if (child.get() instanceof ImageButton) {
					child.size(24);
				}
			}
			titleHeight = 28;
			((Table) titleTable.parent).getCell(titleTable).height(titleHeight);
			cons.get(this);
			moveToMouse();
			Time.runTask(20, () -> {
				if (autoDispose) autoDisposeRun = this::dispose;
			});
		}
	}
}
