package modtools.ui.menu;

import arc.Core;
import arc.scene.ui.layout.*;
import arc.util.pooling.Pools;
import modtools.ui.comp.Underline;

public class UnderlineItem extends MenuItem {
	public static UnderlineItem with() {
		return Pools.get(UnderlineItem.class, UnderlineItem::new, max).obtain();
	}
	@Override
	public Cell<?> build(Table p, Runnable hide) {
		Cell<Underline> cell = Underline.of(p, 1).pad(4);
		Core.app.post(() -> {
			if (cell.get() == p.getChildren().peek()) {
				cell.clearElement().reset();
			}
		});
		return cell;
	}
}
