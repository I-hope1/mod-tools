package modtools.ui.components.utils;

import arc.func.Prov;
import arc.graphics.Color;
import arc.graphics.g2d.GlyphLayout;
import arc.struct.*;
import arc.util.Align;
import arc.util.pooling.Pools;
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

	private static final GlyphLayout layout = new GlyphLayout();

	private float currentX;
	private float currentY;

	private float textWidth(CharSequence text) {
		layout.setText(style.font, text);
		return layout.width;
	}
	public float lineHeight() {
		return style.font.getData().down;
	}
	private void addText(CharSequence text, Color color, float x, float y) {
		GlyphLayout layout = Pools.obtain(GlyphLayout.class, GlyphLayout::new);
		layout.setText(style.font, text, color, -1, Align.left, false);
		cache.addText(layout, x, y);
	}
	public void addText(CharSequence text, Color color) {
		StringBuilder word = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch == ' ') {
				float wordWidth = textWidth(word);
				if (currentX + wordWidth > width) {
					// 自动换行
					currentX = 0;
					currentY += lineHeight();
				}
				if (!word.isEmpty()) {
					addText(word, color, currentX, currentY);
					word.setLength(0); // 清空 StringBuilder
				}
				// 添加空格后的处理
				currentX += textWidth(word);
			} else {
				word.append(ch);
			}
		}
		// 添加最后一个单词（如果有）
		if (!word.isEmpty()) {
			addText(word, color, currentX, currentY);
		}
	}
	public void clearText() {
		Pools.freeAll(cache.getLayouts());
		cache.clear();
	}


	public static abstract class InlineItem {
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

		public void add(InlineItem item) { items.add(item); }
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
