package modtools.ui.content.ui;

import arc.scene.*;
import arc.scene.actions.*;
import arc.scene.ui.Image;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.*;
import arc.util.pooling.Pools;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import modtools.jsfunc.reflect.*;
import modtools.ui.*;
import modtools.ui.IntUI.SelectTable;
import modtools.ui.comp.Window;
import modtools.ui.content.Content;
import modtools.ui.control.HopeInput;
import modtools.ui.reflect.RBuilder;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static modtools.Constants.nl;
import static modtools.utils.Tools.*;

public class ActionsDebug extends Content {
	public ActionsDebug() {
		super("actionsdebug", Icon.logicSmall);
	}

	Window  ui;
	Element element;

	MethodHandle init = nl(() -> InitMethodHandle.findInit(Image.class, Image.class.getConstructor()));

	@Override
	public void load() {
		ui = new Window(localizedName());
		Table cont = ui.cont;
		Cell<Element> cell = cont.add(element = new Image()).size(64).pad(24);
		cont.row();
		element.update(() -> element.setOrigin(Align.center));
		// element.setOrigin(element.getWidth() / 2f, element.getHeight() / 2f);
		// element.translation.set(element.getWidth() / 2f, element.getHeight() / 2f);
		Set<Class<?>> classes = Arrays.stream(Actions.class.getDeclaredMethods())
		 .map(Method::getReturnType).collect(Collectors.toSet());
		cont.button("Reset", Styles.flatt, runT(() -> {
			// element.clear();
			// element.visible = true;
			// element.setColor(Color.white);
			// element.setTranslation(0, 0);
			// element.invalidateHierarchy();
			element.rotation = 0;
			cell.setElement(element);
			init.invoke(element);
			cont.layout();
		})).growX().height(42).row();
		cont.pane(t -> {
			int c = 0;
			for (Class<?> action : classes) {
				if (!Action.class.isAssignableFrom(action)) continue;
				t.button(action.getSimpleName(), HopeStyles.flatt, () -> {
					applyToAction(as(action));
				}).size(200, 42);
				if (++c % 3 == 0) t.row();
			}
		}).grow();
	}
	final Seq<String> blackList = Seq.with("time", "began", "complete", "lastPercent", "color",
	 "start",
	 "startR", "startG", "startB", "startA",
	 "startX", "startY");
	private <T extends Action> void applyToAction(Class<T> actionClass) {
		T action = Pools.obtain(actionClass, () -> UNSAFE.allocateInstance(actionClass));
		action.reset();
		SelectTable table = IntUI.showSelectTable(HopeInput.mouseHit(), (p, hide, _) -> {
			RBuilder.build(p);
			RBuilder.buildFor(actionClass, action, blackList);
		}, false);
		table.hidden(() -> {
			element.addAction(action);
		});
	}

	@Override
	public void build() {
		ui.show();
	}
}
