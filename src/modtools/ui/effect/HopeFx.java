package modtools.ui.effect;

import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.actions.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.util.Tmp;
import modtools.utils.ElementUtils;
import modtools.utils.ui.LerpFun;

import java.util.Arrays;

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

	static final ObjectMap<Element, LerpFun> all = new ObjectMap<>();
	public static void changedFx(Element element) {
		// if (true) return;
		all.get(element, () -> new LerpFun(Interp.fastSlow)
		 // 1 -> 0
		 .rev().onUI(element).registerDispose(0.05f, fin -> {
			 if (!element.visible) return;
			 Draw.color(Color.sky, fin * 0.5f);
			 Lines.stroke(3f - fin * 2f);
			 ScrollPane pane   = ElementUtils.findClosestPane(element);
			 Vec2       position;// left-bottom
			 float      width  = element.getWidth();
			 float      height = element.getHeight();
			 if (pane != null) {
				 position = Tmp.v1;
				 float maxX, maxY;
				 maxX = pane.getWidth();
				 maxY = pane.getHeight();
				 element.localToAscendantCoordinates(pane, position.set(0, 0));
				 if (position.x > maxX || position.y > maxY) return;

				 float lx = position.x, ly = position.y;
				 position.clamp(0, 0, maxX, maxY);
				 if (lx < 0) width += lx;
				 if (ly < 0) height += ly;
				 if (position.x + width > maxX) width = maxX - position.x;
				 if (position.y + height > maxY) height = maxY - position.y;

				 pane.localToStageCoordinates(position);
			 }
			 // float fout = 1 - fin;
			 Fill.crect(0, 0, width, height);

			/* Draw.color(Pal.powerLight, fout);
			Angles.randLenVectors(new Rand().nextInt(), 4, element.getWidth(), (x, __) -> {
				Angles.randLenVectors(new Rand().nextInt(), 4, element.getHeight(), (___, y) -> {
					Fill.circle(e.x + x, e.y + y, fin * 2);
				});
			}); */
		 }).onDispose(() -> all.remove(element))).back(0.8f);
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
			target.setTranslation(Mathf.lerp(startX, endX, percent)
			 , Mathf.lerp(startY, endY, percent));
		}
	}
}
