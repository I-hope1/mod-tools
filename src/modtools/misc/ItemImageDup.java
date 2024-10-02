package modtools.misc;

import arc.graphics.g2d.TextureRegion;
import arc.scene.ui.Image;
import arc.scene.ui.layout.*;
import arc.util.Scaling;
import mindustry.core.UI;
import mindustry.type.*;
import modtools.ui.HopeStyles;

/** @see mindustry.ui.ItemImage */
public class ItemImageDup extends Stack {
	public ItemImageDup(TextureRegion region, int amount) {

		add(new Table(o -> {
			o.left();
			o.add(new Image(region)).size(32f).scaling(Scaling.fit);
		}));

		if (amount != 0) {
			add(new Table(t -> {
				t.left().bottom();
				t.add(amount >= 1000 ? UI.formatAmount(amount) : amount + "")
				 .fontScale(0.8f)
				 .style(HopeStyles.defaultLabel);
				t.pack();
			}));
		}
	}

	public ItemImageDup(ItemStack stack) {
		this(stack.item.uiIcon, stack.amount);
	}

	public ItemImageDup(PayloadStack stack) {
		this(stack.item.uiIcon, stack.amount);
	}
}
