package arc.scene.ui;

import arc.scene.event.ChangeListener.ChangeEvent;
import arc.util.pooling.Pools;

@SuppressWarnings("SizeReplaceableByIsEmpty")
public class GenTextArea extends TextArea {
	public GenTextArea(String text) {
		super(text);
	}
	public GenTextArea(String text, TextFieldStyle style) {
		super(text, style);
	}
	public String insert(int position, CharSequence text, String to) {
		if (to.length() == 0) return text.toString();
		return to.substring(0, position) + text + to.substring(position);
	}
	/**
	 * @param oldText May be null.
	 * @return True if the text was changed.
	 */
	public boolean changeText(String oldText, String newText) {
		if (newText.equals(oldText)) return false;
		text = newText;
		ChangeEvent changeEvent = Pools.obtain(ChangeEvent.class, ChangeEvent::new);
		boolean     cancelled   = fire(changeEvent);
		text = cancelled ? oldText : newText;
		Pools.free(changeEvent);
		return !cancelled;
	}
}
