package modtools.utils;

import arc.graphics.Color;
import arc.math.Mathf;
import arc.util.Time;

public class ColorFul {
	private static final Color[] colors = {
	  Color.red, Color.orange, Color.yellow,
	  Color.green, Color.cyan, Color.sky, Color.pink};

	public static final Color color = new Color(colors[0]);
	private static      int   i     = 1;
	private static      float timer = 0;

	static {
		float precent = 60;
		Tools.tasks.add(() -> {
			timer += Time.delta;
			color.lerp(colors[i], Mathf.clamp(timer / precent));
			if (timer >= precent) {
				timer = 0;
				i++;
			}
			if (i >= colors.length) i = 0;
		});
	}
}
