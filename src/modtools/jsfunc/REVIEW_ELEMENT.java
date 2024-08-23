package modtools.jsfunc;

import arc.func.Cons;
import arc.scene.Element;
import modtools.ui.*;

import static modtools.ui.IntUI.topGroup;

public interface REVIEW_ELEMENT {
	// ------------- ReviewElement --------------
	static void inspect(Element element) {
		Contents.review_element.inspect(element);
	}
	static void pickElement(Cons<Element> callback) {
		topGroup.requestSelectElem(TopGroup.defaultDrawer, callback);
	}

	static void setDrawPadElem(Element elem) {
		TopGroup.setDrawPadElem(elem);
	}
	static void toggleDrawPadElem(Element elem) {
		TopGroup.setDrawPadElem(TopGroup.getDrawPadElem() == elem ? null : elem);
	}
}
