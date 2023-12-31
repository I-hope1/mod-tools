package modtools.ui.components.input;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.scene.ui.Label;
import arc.util.*;
import mindustry.ui.Styles;
import modtools.ui.IntUI;
import modtools.ui.components.Window.*;
import modtools.ui.components.input.area.TextAreaTab;
import modtools.ui.components.input.highlight.JSSyntax;
import modtools.ui.content.debug.Tester;
import modtools.utils.JSFunc;
import rhino.*;

import static mindustry.Vars.mods;
import static modtools.utils.Tools.*;

public class JSRequest {
	static class JSRequestWindow<R> extends HiddenTopWindow {
		TextAreaTab area = new TextAreaTab("", false);
		String  log;
		boolean notHideAuto;

		void buildButtons(ConsT<R, Throwable> callback) {
			buttons.check("@jsrequest.nothideauto", b -> notHideAuto = b).checked(__ -> notHideAuto)
			 .colspan(3).padRight(100f).get().left();
			buttons.row();

			buttons.button("@cancel", Styles.flatt, this::hide).growX().height(42);
			buttons.button("Test", Styles.flatt, catchRun(() -> {
				Object o   = eval();
				String log = String.valueOf(o);
				if (log == null) log = "null";
				this.log = log;
			})).growX().height(42);
			buttons.button("@ok", Styles.flatt, catchRun(() -> {
				Object o = eval();
				callback.get(as(o));
				if (!notHideAuto) hide();
			})).growX().height(42);
		}

		public JSRequestWindow() {
			super("", 220, 220, true, false);
			cont.add(tips = new Label("")).color(Color.lightGray).growX().row();
			area.getArea().setPrefRows(4);
			cont.add(area).grow().row();
			area.syntax = new JSSyntax(area);
			cont.pane(t -> t.label(() -> log)).height(42);

			shown(() -> {
				area.getArea().setText0(null);
				log = "";
			});
		}
		public Object eval() {
			Object o = cx.evaluateString(scope, area.getText(), "mini_console.js", 1);
			return JSFunc.unwrap(o);
		}
	}

	public static JSRequestWindow window   = new JSRequestWindow<>();
	public static Context         cx       = mods.getScripts().context;
	public static Scriptable      topScope = Tester.scope;
	public static Scriptable      scope;

	public static Label tips;

	/** for field */
	public static <R> void requestForField(Object value, Object self, ConsT<R, Throwable> callback) {
		tips.setText(IntUI.tips("jsrequest.field"));
		request0(callback, self, "p1", value);
	}
	/** for field */
	public static <R> void requestForMethod(Object value, Object self, ConsT<R, Throwable> callback) {
		tips.setText(IntUI.tips("jsrequest.method"));
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

	/**
	 * 请求js
	 * @param callback 提供js执行的返回值
	 * @param self     this指针，用于js绑定
	 * @param args     每两个为一组，一个String（key），一个Object（value）
	 */
	public static <R> void request0(ConsT<R, Throwable> callback, Object self, Object... args) {
		// resetScope();
		BaseFunction parent = new BaseFunction(topScope, null);
		Scriptable selfScope = self != null ? cx.getWrapFactory()
		 .wrapAsJavaObject(cx, topScope, self, self.getClass()) : null;
		if (selfScope != null) {
			selfScope.setPrototype(parent);
			scope = selfScope;
		} else scope = parent;
		// scope = new Delegator(parent);
		window.show().setPosition(Core.input.mouse(), Align.center);
		window.buttons.clearChildren();

		for (int i = 0; i < args.length; i += 2) {
			parent.put((String) args[0], parent, args[1]);
		}
		window.buildButtons(callback);
	}

	private static Object eval() {
		return window.eval();
	}
	public static void requestCode(Cons<String> callback) {
		new DisWindow("code") {{
			TextAreaTab area = new TextAreaTab("");
			area.syntax = new JSSyntax(area);
			cont.add(area).grow().row();
			cont.button("ok", () -> {
				callback.get(area.getText());
				hide();
			}).size(120, 45);
			setPosition(Core.input.mouse());
		}}.show();
	}
}
