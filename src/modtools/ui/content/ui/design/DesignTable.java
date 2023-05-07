package modtools.ui.content.ui.design;

import arc.func.Floatc;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.event.*;
import arc.struct.FloatSeq;
import mindustry.graphics.Drawf;

public class DesignTable<T extends Group> extends Element {
	private             Element selected;
	public              T       template;
	public static final float   OFFSET = 7;
	final               Vec2    delta  = new Vec2(), last = new Vec2();
	public DesignTable(T template) {
		this.template = template;
		template.x = width / 2f;
		template.y = height / 2f;
		addCaptureListener(new InputListener() {
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				selected = template.hit(x, y, false);
				if (selected == null) return false;
				last.set(selected.x, selected.y);
				delta.set(x, y);
				return true;
			}
			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				selected.x = lookfor(xSpecial, last.x + x - delta.x, selected.getHeight());
				selected.y = lookfor(ySpecial, last.y + y - delta.y, selected.getHeight());
			}
			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				event.cancel();
			}
		});
	}
	public void addChild(Element actor) {
		template.addChild(actor);
	}
	final MyFloatSeq xSpecial = new MyFloatSeq(), ySpecial = new MyFloatSeq();
	public void drawSpecialLineX(float x) {
		/* if (template.x == x)  */
		Drawf.dashLine(Color.white, x, 0, x, height);
	}
	public void drawSpecialLineY(float y) {
		/* if (template.y == y)  */
		Drawf.dashLine(Color.white, 0, y, width, y);
	}
	public void sizeChanged() {
		super.sizeChanged();
		xSpecial.clear();
		xSpecial.add(0);
		xSpecial.add(width / 2f);
		xSpecial.add(width);

		ySpecial.clear();
		ySpecial.add(0);
		ySpecial.add(height / 2f);
		ySpecial.add(height);
	}
	public void draw() {
		template.draw();
		xSpecial.each(this::drawSpecialLineX);
		ySpecial.each(this::drawSpecialLineY);
	}

	private float lookfor(MyFloatSeq seq, float fx, float delta) {
		LookupForParmas lookup = LookupForParmas.INSTANCE.seq(seq).val(fx).reset();
		lookup.lookup(0);
		lookup.lookup(-delta / 2f);
		lookup.lookup(delta / 2f);
		lookup.lookup(-delta);
		lookup.lookup(delta);
		return lookup.getRes();
		/* seq.lookfor(fx, 0, OFFSET, Float.POSITIVE_INFINITY, fx,
				p -> seq.lookfor(p.a(-delta / 2f),
						____ -> seq.lookfor(p.a(delta / 2f),
								__ -> seq.lookfor(p.a(-delta),
										___ -> seq.lookfor(p.a(delta), null))
						)
				)
		); */
	}
	public static class MyFloatSeq extends FloatSeq {
		public void each(Floatc floatc) {
			for (int i = 0; i < size; i++) {
				floatc.get(items[i]);
			}
		}
	}

	private static class LookupForParmas {
		MyFloatSeq seq;
		float      val, minOff, minOffValue;
		private static final LookupForParmas INSTANCE = new LookupForParmas();
		LookupForParmas() {
			if (INSTANCE != null) throw new RuntimeException();
		}
		LookupForParmas reset() {
			minOff = Float.POSITIVE_INFINITY;
			minOffValue = val;
			return this;
		}
		LookupForParmas seq(MyFloatSeq seq) {
			this.seq = seq;
			return this;
		}
		LookupForParmas val(float v) {
			val = v;
			return this;
		}

		float getRes() {
			return minOff < DesignTable.OFFSET ? minOffValue : val;
		}

		void lookup(float offset) {
			// a(a);
			seq.each(v0 -> {
				float v1  = v0 + offset;
				float off = Math.abs(val - v1);
				if (off < minOff) {
					minOff = off;
					minOffValue = v1;
				}
			});
		}
	}
}
