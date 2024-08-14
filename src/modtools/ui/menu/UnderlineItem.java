package modtools.ui.menu;

import arc.scene.ui.layout.*;
import arc.util.Tmp;
import arc.util.pooling.Pools;
import modtools.ui.comp.Underline;
import modtools.utils.JSFunc.JColor;

public class UnderlineItem extends MenuItem {
	public static UnderlineItem with() {
		return Pools.get(UnderlineItem.class, UnderlineItem::new, max).obtain();
	}
	@Override
	public Cell<?> build(Table p, Runnable hide) {
		return Underline.of(p, 1, Tmp.c1.set(JColor.c_underline)).pad(4);
	}
}
