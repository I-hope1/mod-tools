package modtools.utils.ui;

import arc.graphics.Color;
import arc.math.Mathf;
import arc.util.*;
import modtools.utils.Tools;

public class ColorFul {
	private static final Color[] colors = {
	 Color.red, Color.orange, Color.yellow,
	 Color.green, Color.cyan, Color.sky, Color.pink,
	 Color.purple
	};

	public static final Color color = new Color(colors[0]);
	private static      int   i     = 1;
	private static      float timer = 0;

	private static final float percent = 60;
	public static Color color(int value) {
		value = (int) (value % ((colors.length - 1) * percent));
		float fin   = value / percent;
		int   index = (int) fin;
		return Tmp.c1.set(colors[index]).lerp(colors[index + 1], fin - index);
	}

	static {
		Tools.TASKS.add(() -> {
			timer += Time.delta;
			color.lerp(colors[i], Mathf.clamp(timer / percent));
			if (timer >= percent) {
				timer = 0;
				i++;
			}
			if (i >= colors.length) i = 0;
		});
	}
}
