package modtools.ui.components.utils;

import arc.func.Prov;
import arc.graphics.Color;
import arc.struct.*;
import modtools.ui.components.input.NoMarkupLabel;

public class ElementInlineLabel extends NoMarkupLabel {

	public ElementInlineLabel(Prov<CharSequence> sup) {
		super(sup);
	}
	public ElementInlineLabel(CharSequence text) {
		super(text);
	}
	public ElementInlineLabel(CharSequence text, LabelStyle style) {
		super(text, style);
	}
	public ElementInlineLabel(float scale) {
		super(scale);
	}

	public  static abstract class InlineItem {
		float offX, offY;
		Color color;
		public InlineItem(Color color) {
			this.color = color;
		}
		public abstract CharSequence getText();
	}
	public static class InlineTable extends InlineItem {
		Seq<InlineItem> items = new Seq<>();
		public InlineTable() {
			super(Color.white);
		}

    public void add(InlineItem item) {items.add(item);}
		public CharSequence getText() {
			StringBuilder sb = new StringBuilder();
			items.each(i -> sb.append(i.getText()));
			return sb;
		}
	}
	InlineTable container = new InlineTable();
	public void add(InlineItem item) {
		container.add(item);
	}
	public CharSequence getTextFromItem() {
		return container.getText();
	}
	public static class InlineLabel extends InlineItem {
		public static IntMap<Color> intColorMap = new IntMap<>();
		CharSequence text;
		public InlineLabel(Color color, CharSequence text) {
			super(color);
			this.text = text;
		}
		public static InlineItem of(CharSequence text, Color color) {
			return new InlineLabel(color, text);
		}
		public static InlineItem of(CharSequence text, int color) {
			return new InlineLabel(intColorMap.get(color, () -> new Color(color)), text);
		}
		public CharSequence getText() {
			return text;
		}
	}
}
