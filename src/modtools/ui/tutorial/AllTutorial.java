package modtools.ui.tutorial;


import arc.Core;
import arc.func.Boolp;
import arc.scene.Element;
import arc.util.viewport.Viewport;
import modtools.ui.IntUI;

public class AllTutorial {
	public static void f(Element elem, Boolp boolp) {
		IntUI.focusOnElement(elem, boolp);
	}
	public static void init() {
		Viewport viewport = Core.scene.getViewport();
		/* Events.run(Trigger.update, () -> {
			viewport.getCamera().position.set(Core.graphics.getWidth() / 2f, Core.graphics.getHeight() / 2f - 50);
			viewport.setWorldHeight(Core.graphics.getHeight() - 50);
		}); */
		// viewport.setScreenBounds(20, 40,
		//  Core.graphics.getWidth() -20,
		//  Core.graphics.getHeight() -40);
		// Draw.trans().scl(2);
	}
}
