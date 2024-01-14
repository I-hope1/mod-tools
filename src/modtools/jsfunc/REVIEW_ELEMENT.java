package modtools.jsfunc;

import arc.func.Cons;
import arc.scene.Element;
import modtools.ui.*;
import modtools.ui.content.ui.ReviewElement.ReviewElementWindow;

import static modtools.ui.IntUI.topGroup;

public interface REVIEW_ELEMENT {
	// ------------- ReviewElement --------------
	static void inspect(Element element) {
		new ReviewElementWindow().show(element);
	}
	static void pickElement(Cons<Element> callback) {
		topGroup.requestSelectElem(TopGroup.defaultDrawer, callback);
	}

	static void setDrawPadElem(Element elem) {
		topGroup.setDrawPadElem(elem);
	}
	static void toggleDrawPadElem(Element elem) {
		topGroup.setDrawPadElem(topGroup.drawPadElem == elem ? null : elem);
	}
}
