package modtools.ui;

import android.view.KeyEvent;
import arc.Core;
import arc.backend.android.AndroidInput;
import arc.util.Log;

public class HopeInput {
	static {
		((AndroidInput) Core.input).addKeyListener((view, i, keyEvent) -> {
			Log.info(keyEvent.getAction() == KeyEvent.ACTION_DOWN);
			return false;
		});
	}
}
