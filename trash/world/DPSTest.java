package modtools.world;

import arc.Core;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Font;
import arc.scene.event.Touchable;
import arc.scene.ui.layout.Table;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.UnitEntity;
import mindustry.type.UnitType;
import mindustry.ui.Fonts;

import java.util.ArrayList;

public class DPSTest extends UnitType {
	public DPSTest() {
		super("DPS-Test");

		constructor = DPSTestUnit::new;
	}

	static Table all;

	public static void initTable() {
		all = new Table();
		all.name = "dps";
		all.fillParent = true;
		all.touchable = Touchable.disabled;
		all.visible(() -> Vars.state.isGame());
		all.update(() -> {
			fps = 60 / Time.delta;
		});
		Core.scene.add(all);
	}

	public static final int maxSize = 4;
	public static float fps = 0;

	public static class DPSTestUnit extends UnitEntity {
		int dpsIndex = 0;
		float[] dpsArray = new float[maxSize];
		ArrayList<Float> damages = new ArrayList<>() {
			@Override
			public boolean add(Float integer) {
				boolean added = super.add(integer);
				if (size() >= fps) remove(0);
				return added;
			}
		};
		float lastDamage = 0, damage = 0;

		/*Table table = new Table(Styles.black6) {
		};

		{
			table.update(() -> {
				if (Vars.state.isMenu() || dead) table.remove();
			});
			all.add(table);
		}*/

		@Override
		public void update() {
//			if (!damages.isEmpty()) damages.remove(0);
			damages.add(damage);
			lastDamage = damage;
			damage = 0;
		}

		@Override
		public void damage(float amount) {
			this.damage += amount;
//			super.damage(amount);
		}

		public float dps;

		public void draw() {
			super.draw();
			Draw.z(10);
			Font font = Fonts.def;
			font.draw("damage: " + lastDamage, x, y);
			dps = 0;
			damages.forEach(damage -> dps += damage);
			font.draw("dps: " + dps / fps, x, y - font.getLineHeight() - 2);
		}

	}
}
