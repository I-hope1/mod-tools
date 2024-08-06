package modtools.ui.comp.input;

import arc.func.Prov;
import arc.graphics.Color;
import arc.util.Log;
import mindustry.graphics.Pal;
import modtools.utils.PatternUtils;

import java.util.regex.*;

public class SearchedLabel extends InlineLabel {
	final Prov<Pattern> patternProv;
	public SearchedLabel(Prov<CharSequence> sup, Prov<Pattern> patternProv) {
		super(sup);
		this.patternProv = patternProv;
		layout();
	}
	private Pattern       pattern = null;
	private StringBuilder text;
	public boolean needsLayout() {
		return super.needsLayout();
	}
	public void act(float delta) {
		super.act(delta);
		if (pattern != patternProv.get() || getText().compareTo(text) != 0) invalidate();
	}
	public Color normalColor    = Pal.accent;
	public Color highlightColor = Color.sky;
	public void layout() {
		pattern = patternProv.get();
		text = getText();

		colorMap.clear();
		colorMap.put(0, normalColor);
		colorMap.put(text.length(), Color.white);

		if (pattern == null || pattern == PatternUtils.ANY)  {
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
		Log.info(colorMap);

		super.layout();
	}
}
