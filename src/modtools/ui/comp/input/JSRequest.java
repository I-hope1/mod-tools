package modtools.ui.comp.input;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.scene.ui.*;
import arc.util.Align;
import modtools.content.debug.Tester;
import modtools.jsfunc.IScript;
import modtools.jsfunc.type.CAST;
import modtools.ui.*;
import modtools.ui.comp.Window;
import modtools.ui.comp.Window.DisWindow;
import modtools.ui.comp.input.area.TextAreaTab;
import modtools.ui.comp.input.highlight.JSSyntax;
import rhino.*;

import static modtools.IntVars.mouseVec;
import static modtools.utils.Tools.*;

@SuppressWarnings("SpellCheckingInspection")
public class JSRequest {
	public static class JSRequestWindow<R> extends Window {
		TextAreaTab area = new TextAreaTab("", false);
		String      log;
		boolean     notHideAuto;

		void buildButtons(ConsT<R, Throwable> callback) {
			buttons.check("@jsrequest.nothideauto", b -> notHideAuto = b).checked(_ -> notHideAuto)
			 .colspan(3).padRight(100f).get().left();
			buttons.margin(6, 8, 6, 8);
			buttons.row();

			buttons.button("@cancel", HopeStyles.flatt, this::hide).growX().height(42);
			buttons.button("Test", HopeStyles.flatt, runT(() -> {
				Object o   = eval();
				String log = String.valueOf(o);
				if (log == null) log = "null";
				this.log = log;
			})).growX().height(42);
			buttons.button("@ok", HopeStyles.flatt, runT(() -> {
				Object o = eval();
				callback.get(as(o));
				if (!notHideAuto) hide();
			})).growX().height(42);
		}

		public JSRequestWindow() {
			super("", 220, 220, true, false);
			sticky = true;
			if (titleTable.find("sticky") instanceof Button b) {
				b.setDisabled(true);
			}

			cont.add(tips = new Label("")).color(Color.lightGray).growX().wrapLabel(true).row();
			area.getArea().setPrefRows(4);
			cont.add(area).grow().row();
			area.syntax = new JSSyntax(area);
			cont.pane(t -> t.label(() -> log)).height(42);

			shown(() -> {
				area.getArea().setText(null);
				log = "";
				Core.app.post(this::keepInStage);
			});
		}
		public Object eval() {
			Object o = cx.evaluateString(scope, area.getText(), "mini_console.js", 1);
			return CAST.unwrap(o);
		}
	}

	public static JSRequestWindow<?> window   = new JSRequestWindow<>();
	public static Context            cx       = IScript.cx;
	public static Scriptable         topScope = IScript.scope;


	public static Scriptable scope;

	public static Label tips;

	/** for field */
	public static <R> void requestForField(Object value, Object self, ConsT<R, Throwable> callback) {
		tips.setText(IntUI.tips("jsrequest.field"));
		request0(callback, self, "p1", value);
	}
	/** for field */
	public static <R> void requestForMethod(Object value, Object self, ConsT<R, Throwable> callback) {
		tips.setText(IntUI.tips("jsrequest.method", "" + value));
		request0(callback, self, "m0", value);
	}
	/** for display */
	public static <R> void requestForDisplay(Object value, Object self, ConsT<R, Throwable> callback) {
		tips.setText(IntUI.tips("jsrequest.display"));
		request0(callback, self, "sp", value);
	}

	/** for selection */
	public static <R> void requestForSelection(Object value, Object self, ConsT<R, Throwable> callback) {
		tips.setText(IntUI.tips("jsrequest.selection"));
		request0(callback, self, "list", value);
	}

	public static <R> void requestFor(Object self, Class<R> expect, ConsT<R, Throwable> callback) {
		tips.setText(IntUI.tips("jsrequest.any", expect.getSimpleName()));
		request0(v -> {
			callback.get(as(ScriptRuntime.doTopCall((_, _, _, _) -> Context.jsToJava(v, expect),
			 cx, scope, null, null, false)));
		}, self);
	}
	/**
	 * 请求js
	 * @param callback 提供js执行的返回值
	 * @param self     this指针，用于js绑定
	 * @param args     每两个为一组，一个String（key），一个Object（value）
	 */
	public static <R> void request0(ConsT<R, Throwable> callback, Object self, Object... args) {
		// resetScope();
		Scriptable parent = wrapper(self != null && Tester.wrap(self) instanceof Scriptable sc ? sc : null, args);

		JSRequestWindow<?> window = JSRequest.window;
		window.show().setPosition(mouseVec, Align.center);
		window.buttons.clearChildren();

		window.area.syntax = new JSSyntax(window.area, parent);
		scope = parent;
		window.buildButtons(as(callback));
	}

	public static Scriptable wrapper(Scriptable self) {
		return wrapper(self, new Object[0]);
	}
	public static Scriptable wrapper(Scriptable self, Object... args) {
		var parent = new ScriptableObject(topScope, self) {
			public String getClassName() {
				return "Wrapper";
			}
		};
		for (int i = 0; i < args.length; i += 2) {
			if (!(args[i] instanceof String name)) {
				throw new IllegalArgumentException("key args[" + i + "](" + args[i] + ") must be String");
			}

			Object value = args[i + 1];
			if (value instanceof LazyArg lazyArg) {
				Core.app.post(() -> parent.put(name, parent, lazyArg.get()));
			} else {
				parent.put(name, parent, value);
			}
		}
		return parent;
	}
	public interface LazyArg {
		Object get();
	}

	private static Object eval() {
		return window.eval();
	}
	public static void requestCode(Scriptable scope, Cons<String> callback) {
		new DisWindow("Code") {{
			TextAreaTab area = new TextAreaTab("");
			area.syntax = new JSSyntax(area, scope);
			cont.add(area).grow().row();
			cont.button("@ok", runT(() -> {
				callback.get(area.getText());
				hide();
			})).size(120, 45);
			moveToMouse();
		}}.show();
	}
}
