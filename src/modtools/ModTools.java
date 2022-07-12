package modtools;

import arc.Core;
import arc.Events;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import arc.util.Time;
import mindustry.game.EventType;
import mindustry.mod.Mod;
import mindustry.ui.dialogs.BaseDialog;
import modtools.ui.Background;
import modtools.ui.MyReflect;

import java.util.Objects;

import static modtools.IntVars.modName;

public class ModTools extends Mod {
	public boolean stop = false;

	public ModTools() {
		Log.info("Loaded ModTools constructor.");
		MyReflect.load();

//		MyPacket.register();

		/*new ItemSourceNode("物品源节点") {{
			localizedName = "物品源节点";

			requirements(Category.distribution, ItemStack.with());
		}};*/
		Events.on(EventType.ClientLoadEvent.class, e -> {
			stop = true;
			/*try {
				MyPlacement.load();
			} catch (Throwable ex) {
				Log.err(ex);
			}*/
			Time.runTask(10f, () -> {
				BaseDialog dialog = new BaseDialog("frog");
				dialog.addCloseListener();

				Table cont = dialog.cont;
				cont.image(Core.atlas.find(modName + "-frog")).pad(20f).row();
				cont.add("behold,ok").row();
				Objects.requireNonNull(dialog);
				cont.button("I see", dialog::hide).size(100f, 50f);
				dialog.show();
			});
			IntVars.load();
			/*DPSTest.initTable();
			new DPSTest() {{
				localizedName = "DPS测试";
				health = Integer.MAX_VALUE;
				hitSize = 8;

				init();
				load();
				loadIcon();
			}};*/

			if (Core.settings.getBool(modName + "-ShowMainMenuBackground")) Background.main();
		});
	}
}
