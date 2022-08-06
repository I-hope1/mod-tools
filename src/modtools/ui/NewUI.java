package modtools.ui;

import arc.*;
import arc.graphics.Color;
import arc.graphics.g2d.Batch;
import arc.scene.*;
import arc.util.Log;
import mindustry.game.EventType;

import java.lang.reflect.Field;

public class NewUI {
	public static void main() {
		Field field;
		try {
			field = Batch.class.getDeclaredField("color");
			field.setAccessible(true);
			field.set(Core.batch, new Color() {
				@Override
				public Color set(Color color) {
					// if (color.a == 0) return super.set(color);
					return mySet(color.cpy()).a(color.a);
				}

				public Color mySet(Color color) {
					return super.set(color.lerp(Color.sky, 0.4f));
				}

				@Override
				public Color set(float r, float g, float b, float a) {
					// if (a == 0) return super.set(r, g, b, a);
					return mySet(new Color(r, g, b, a)).a(a);
				}
			});
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
		Field finalField = field;
		final Batch[] lastBatch = {Core.batch};
		Events.run(EventType.Trigger.update, () -> {
			if (lastBatch[0] == Core.batch) return;
			lastBatch[0] = Core.batch;
			try {
				Log.debug(Core.batch);

				finalField.set(Core.batch, new Color() {
					@Override
					public Color set(Color color) {
						// if (color.a == 0) return super.set(color);
						return mySet(color.cpy()).a(color.a);
					}

					public Color mySet(Color color) {
						return super.set(color.lerp(Color.sky, 0.4f));
					}

					@Override
					public Color set(float r, float g, float b, float a) {
						// if (a == 0) return super.set(r, g, b, a);
						return mySet(new Color(r, g, b, a)).a(a);
					}
				});
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		});
		/*Events.on(EventType.ClientLoadEvent.class, __ -> {
			Core.scene.root.color.set(Color.sky);
		});*/
	}
}
