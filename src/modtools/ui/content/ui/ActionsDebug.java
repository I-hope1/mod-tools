package modtools.ui.content.ui;

import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.ui.Image;
import arc.util.Align;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import modtools.ui.HopeStyles;
import modtools.ui.comp.Window;
import modtools.ui.content.Content;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static modtools.utils.Tools.as;

public class ActionsDebug extends Content {
	public ActionsDebug() {
		super("actionsdebug", Icon.logicSmall);
	}

	Window  ui;
	Element element;

	@Override
	public void load() {
		ui = new Window(localizedName());
		ui.cont.add(element = new Image()).size(64).row();
		element.update(() -> element.setOrigin(Align.center));
		// element.setOrigin(element.getWidth() / 2f, element.getHeight() / 2f);
		// element.translation.set(element.getWidth() / 2f, element.getHeight() / 2f);
		Set<Class<?>> classes = Arrays.stream(Actions.class.getDeclaredMethods())
		 .map(Method::getReturnType).collect(Collectors.toSet());
		ui.cont.button("Reset", Styles.flatt, () -> {
			element.clearActions();
			element.setTranslation(0, 0);
			element.invalidateHierarchy();
		}).growX().row();
		ui.cont.pane(t -> {
			int c = 0;
			for (Class<?> action : classes) {
				if (!Action.class.isAssignableFrom(action)) continue;
				t.button(action.getSimpleName(), HopeStyles.flatTogglet, () -> {
					applyToAction(as(action));
				}).size(200, 42);
				if (++c % 3 == 0) t.row();
			}
		}).grow();
	}
	private <T extends Action> void applyToAction(Class<T> action) {

	}

	@Override
	public void build() {
		ui.show();
	}
}
