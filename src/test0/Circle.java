package test0;

import arc.graphics.*;
import arc.graphics.g2d.*;
import mindustry.graphics.Shaders;
import modtools.utils.JSFunc;

public class Circle {
	public static void draw() {
		JSFunc.testDraw(() -> {
			Gl.clearColor(0, 0, 0, 0);
			Draw.shader(Shaders.shield);
			Draw.alpha(0);
			// 绘制遮罩形状（例如，一个圆）
			Fill.circle(50, 50, 50);

			// 绘制纹理
			Draw.rect("alpha-bg", 50, 50, 100, 100);

			Draw.shader();
		});
	}
}
