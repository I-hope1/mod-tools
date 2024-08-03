package modtools.ui.comp;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.math.Interp;
import arc.math.geom.Vec2;
import arc.scene.Element;
import modtools.utils.ui.ColorFul;

public class InterpImage extends Element {
	Interp interp;
	public InterpImage(Interp interp) {
		this.interp = interp;
	}
	public static float size = 42;
	public float getPrefWidth() {
		return size;
	}
	public float getPrefHeight() {
		return size;
	}
	public static int  pointAmount     = 100;
	public        int  selfPointAmount = 0;
	static final  Vec2 v1              = new Vec2();

	public void draw() {
		super.draw();
		Core.graphics.requestRendering();

		Draw.color(color);
		Draw.alpha(parentAlpha * color.a);
		selfPointAmount++;
		if (selfPointAmount > pointAmount + 30) selfPointAmount = 0;
		/* 左下角 */
		float x0 = this.x + width / 8f, y0 = this.y + height / 8f,
		 w = width / 8f * 6f, h = height / 8f * 6f;
		float lastX = x0 + 0, lastY = y0 + h * interp.apply(0);
		Lines.stroke(3f);
		Draw.color(Color.lightGray);
		Lines.rect(x0, y0, w, h);
		Draw.color(ColorFul.color(getZIndex() * 10));
		for (int i = 0; i < selfPointAmount && i < pointAmount; i++) {

			float fin  = i / (float) pointAmount;
			Vec2  next = v1.set(x0 + w * fin, y0 + h * interp.apply(fin));

			Lines.line(lastX, lastY, next.x, next.y, false);

			lastX = next.x;
			lastY = next.y;
		}
	}
}
