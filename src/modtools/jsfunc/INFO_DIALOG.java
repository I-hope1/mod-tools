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
import arc.util.Tmp;
import mindustry.gen.Icon;
import modtools.ui.*;
import modtools.ui.components.Window;
import modtools.ui.components.Window.*;
import modtools.ui.components.limit.*;
import modtools.ui.components.utils.PlainValueLabel;
import modtools.utils.JSFunc.JColor;
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
				Core.app.post(() -> {
					dialog[0].hide();
					var pos = getAbsolutePos(dialog[0]);
					try {
						showInfo(o, clazz).setPosition(pos);
					} catch (Throwable e) {
						IntUI.showException(e).setPosition(pos);
					}
					dialog[0] = null;
				});
			}).left().size(50).row();


			buildArrayCont(o, clazz, length, _cont);
			_cont.row();

			dialog[0] = new DisWindow(clazz.getSimpleName(), 200, 200, true);
			dialog[0].cont.pane(_cont).grow();
			dialog[0].show();
			return dialog[0];
		}

		dialog[0] = new ShowInfoWindow(o, clazz == null ? Class.class : clazz);
		//		dialog.addCloseButton();
		dialog[0].show();
		assert dialog[0] != null;
		return dialog[0];
	}
	static void buildArrayCont(Object o, Class<?> clazz, int length, Table cont) {
		Class<?> componentType = clazz.getComponentType();
		Table    c1            = null;
		for (int i = 0; i < length; i++) {
			if (i % 100 == 0) c1 = cont.row().table().grow().colspan(2).get();
			var button = new LimitTextButton("", HopeStyles.cleart);
			button.clearChildren();
			button.add(i + "[lightgray]:", HopeStyles.defaultLabel).padRight(8f);
			int j = i;
			button.add(new PlainValueLabel<Object>((Class) componentType, () -> Array.get(o, j))).grow();
			button.clicked(() -> {
				Object item = Array.get(o, j);
				// 使用post避免stack overflow
				if (item != null) Core.app.post(() -> showInfo(item).setPosition(getAbsolutePos(button)));
				else IntUI.showException(new NullPointerException("item is null"));
			});
			c1.add(button).growX().minHeight(40);
			IntUI.addWatchButton(c1, o + "#" + i, () -> Array.get(o, j)).row();
			c1.image().color(Tmp.c1.set(JColor.c_underline)).colspan(2).growX().row();
		}
	}
	static Window window(final Cons<Window> cons) {
		class JSWindow extends HiddenTopWindow implements IDisposable {
			{
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
				// addCloseButton();
				hidden(this::clearAll);
				moveToMouse();
			}

			public JSWindow() {
				super("TEST", 64, 64);
			}
		}
		return new JSWindow();
	}
	static Window btn(String text, Runnable run) {
		return dialog(t -> t.button(text, HopeStyles.flatt, run).size(64, 45));
	}
	static Window testDraw(Runnable draw) {
		return testDraw0(_ -> draw.run());
	}
	static Window testDraw0(Cons<Group> draw) {
		return dialog(new Group() {
			{ transform = true; }

			public void drawChildren() {
				draw.get(this);
			}
		});
	}
	// static FrameBuffer buffer = new FrameBuffer();
	static Window testShader(Shader shader, Runnable draw) {
		return testDraw0(t -> {
			// buffer.resize(Core.graphics.getWidth(), Core.graphics.getHeight());
			FrameBuffer buffer  = new FrameBuffer(Core.graphics.getWidth(), Core.graphics.getHeight());
			Texture     texture = WorldDraw.drawTexture(buffer, draw);
			Draw.blit(texture, shader);
			buffer.dispose();
		});
	}
	static Window dialog(Element element) {
		return window(d -> d.cont.pane(element).grow());
	}
	static Window dialog(String text) {
		return dialog(new Label(text));
	}
	static Window dialog(TextureRegion region) {
		return dialog(new Image(region));
	}
	static Window dialog(Texture texture) {
		return dialog(new TextureRegion(texture));
	}
	static Window dialog(Drawable drawable) {
		return dialog(new Image(drawable));
	}
	static Window dialog(Color color) {
		return dialog(new ColorImage(color));
	}
	static Window pixmap(int size, Cons<Pixmap> cons) {
		return pixmap(size, size, cons);
	}
	static Window pixmap(int width, int height, Cons<Pixmap> cons) {
		Pixmap pixmap = new Pixmap(width, height);
		cons.get(pixmap);
		Window dialog = dialog(new TextureRegion(new Texture(pixmap)));
		dialog.hidden(pixmap::dispose);
		return dialog;
	}
	static Window dialog(Cons<Table> cons) {
		return dialog(new Table(cons));
	}

	class $ {
		@SuppressWarnings("rawtypes")
		public static void buildLongPress(ImageButton button, Prov o) {
			IntUI.longPress0(button, () -> INFO_DIALOG.showInfo(o));
		}
	}
}
