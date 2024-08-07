package modtools.ui.comp.input;

import arc.func.Prov;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.graphics.g2d.GlyphLayout.GlyphRun;
import arc.struct.Seq;
import arc.util.*;
import mindustry.graphics.Pal;
import modtools.utils.PatternUtils;

import java.util.Objects;
import java.util.regex.*;

public class SearchedLabel extends InlineLabel {
	final Prov<Pattern> patternProv;
	public SearchedLabel(Prov<CharSequence> sup, Prov<Pattern> patternProv) {
		super(sup);
		this.patternProv = patternProv;
		layout();
	}
	private Pattern pattern = null;
	public void act(float delta) {
		super.act(delta);
		if (pattern != patternProv.get()) layout();
	}
	public Color normalColor    = Pal.accent;
	public Color highlightColor = Color.sky;
	private Color bgColor = Color.brick;
	public void initColor(Color normalColor, Color highlightColor) {
		this.normalColor    = normalColor;
		this.highlightColor = highlightColor;
		bgColor = Tmp.c1.set(highlightColor).inv().saturation(1).value(1).a(0.8f);
	}
	public void layout() {
		pattern = patternProv.get();

		colorMap.clear();
		colorMap.put(0, normalColor);
		colorMap.put(text.length(), Color.white);

		if (pattern == null || pattern == PatternUtils.ANY) {
			super.layout();
			return;
		}

		// 对text进行匹配，如果匹配到了，则将匹配到的部分高亮显示：colorMap.put(index, highlightColor)
		Matcher matcher = pattern.matcher(text);
		while (matcher.find()) {
			if (matcher.start() == matcher.end()) break;
			colorMap.put(matcher.start(), highlightColor);
			colorMap.put(matcher.end(), normalColor);
		}
		super.layout();
	}
	public void draw() {
		if (cache == null) return;
		drawHighlightBackground();

		super.draw();
	}
	private void drawHighlightBackground() {
		float lineHeight = cache.getFont().getLineHeight();
		// 绘制背景
		Seq<GlyphRun> runs = layout.runs;
		Draw.color(bgColor);
		for (GlyphRun run : runs) {
			if (!Objects.equals(run.color, highlightColor)) continue;
			Fill.crect(x + run.xAdvances.first() + run.x, y + run.y, run.width, lineHeight);
		}
	}
}
