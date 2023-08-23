package modtools.ui.effect;

import arc.graphics.Color;
import arc.math.Interp;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.actions.*;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import arc.util.Tmp;

import java.util.*;

public class HopeFx {
	public static final float duration = 0.3f;
	public static Element colorFulText(String text) {
		Table    table = new Table();
		String[] split = text.split("");
		for (int i = 0, splitLength = split.length; i < splitLength; i++) {
			Label label = table.add(split[i]).get();

			label.actions(
			 Actions.delay(i / 10f),
			 Actions.repeat(-1, Actions.parallel(
				transitionColor(Color.red, Color.pink, Color.sky, Color.purple, Color.cyan, Color.green, Color.yellow, Color.orange),
				transitionTranslation(1.2f, Tmp.v1.set(0, 8f), Tmp.v2.set(0, -8f))
			 ))
			);
		}
		return table;
	}
	public static SequenceAction transitionColor(Color color) {
		return Actions.sequence(Actions.color(color, duration));
	}
	public static SequenceAction transitionColor(Color... colors) {
		return Actions.sequence(Arrays.stream(colors)
		 .map(color -> Actions.color(color, duration)).toArray(ColorAction[]::new));
	}
	public static SequenceAction transitionTranslation(float duration, Vec2... vecs) {
		return Actions.sequence(Arrays.stream(vecs).map(vec -> translateTo(vec.x, vec.y, duration)).toArray(TranslateToAction[]::new));
	}
	public static TranslateToAction translateTo(float amountX, float amountY, float duration) {
		return translateTo(amountX, amountY, duration, null);
	}

	public static TranslateToAction translateTo(float amountX, float amountY, float duration, Interp interpolation) {
		TranslateToAction action = Actions.action(TranslateToAction.class, TranslateToAction::new);
		action.setTranslation(amountX, amountY);
		action.setDuration(duration);
		action.setInterpolation(interpolation);
		return action;
	}
	public static class TranslateToAction extends TemporalAction {
		private float startX, startY;
		private float endX, endY;
		protected void begin() {
			startX = target.translation.x;
			startY = target.translation.y;
		}
		public void setTranslation(float x, float y) {
			endX = x;
			endY = y;
		}
		public float getX() {
			return endX;
		}
		public void setX(float x) {
			endX = x;
		}
		public float getY() {
			return endY;
		}
		public void setY(float y) {
			endY = y;
		}
		protected void update(float percent) {
			target.setTranslation(startX + (endX - startX) * percent, startY + (endY - startY) * percent);
		}
	}
}
