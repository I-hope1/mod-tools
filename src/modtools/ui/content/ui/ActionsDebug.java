package modtools.ui.content.ui;

import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.*;
import arc.scene.actions.*;
import arc.scene.ui.Image;
import arc.util.Align;
import modtools.ui.IntStyles;
import modtools.ui.components.Window;
import modtools.ui.content.Content;
import modtools.utils.reflect.ClassUtils;

public class ActionsDebug extends Content {
	public ActionsDebug() {
		super("actionsdebug");
	}

	Window ui;
	Element element;

	@Override
	public void load() {
		ui = new Window(localizedName());
		ui.cont.add(element = new Image()).size(64).row();
		element.update(() -> element.setOrigin(Align.center));
		// element.setOrigin(element.getWidth() / 2f, element.getHeight() / 2f);
		// element.translation.set(element.getWidth() / 2f, element.getHeight() / 2f);
		var classes = ClassUtils.getClasses("arc.scene.actions");
		ui.cont.pane(t -> {
			int c = 0;
			for (var action : classes) {
				if (!Action.class.isAssignableFrom(action)) continue;
				t.button(action.getSimpleName(), IntStyles.flatTogglet, () -> {
					// element.actions((Action) MyReflect.unsafe.allocateInstance(action));
					if (action == ScaleToAction.class) {
						element.actions(Actions.sequence(Actions.scaleTo(0, 0, 0.2f), Actions.scaleTo(1, 1, 0.2f)));
					}
					if (action == RotateByAction.class) {
						element.actions(Actions.rotateBy(-360, 0.2f));
					}
					if (action == MoveByAction.class) {
						float mx = Mathf.random(-100f, 100f), my = Mathf.random(-100f, 100f);
						element.actions(
						 Actions.moveBy(mx, my, 1f),
						 Actions.moveBy(-mx, -my, 1f)
						);
					}
					if (action == ColorAction.class) {
						element.actions(Actions.sequence(Actions.color(Color.pink, 0.1f)
						 , Actions.color(Color.sky, 0.1f)
						 , Actions.color(Color.blue, 0.1f)
						 , Actions.color(Color.green, 0.1f)
						 , Actions.color(Color.yellow, 0.1f)
						 , Actions.color(Color.pink, 0.1f)
						 , Actions.color(element.color, 0.1f)
						));
					}
				}).size(200, 42);
				if (++c % 4 == 0) t.row();
			}
		});
	}

	@Override
	public void build() {
		ui.show();
	}
}
