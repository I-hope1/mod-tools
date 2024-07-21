package modtools.ui.content;

import arc.Events;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.Pal;
import mindustry.type.UnitType;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import modtools.ui.Contents;
import modtools.ui.IntFunc;
import modtools.ui.comp.MyItemSelection;

public class UnitSpwan extends Content {

	public UnitSpwan() {
		super("unitSpwan");
	}

	BaseDialog ui;
	UnitType selectUnit;
	int amount = 0;
	Team team;
	boolean loop = false;
	Table unitCont;

	public void setup() {
		unitCont.clearChildren();
		MyItemSelection.buildTable(unitCont, Vars.content.units(), () -> selectUnit, u -> selectUnit = u,
				Vars.mobile ? 6 : 10);
		unitCont.table(right -> {
			right.label(() -> selectUnit.name).row();
			right.label(() -> selectUnit.localizedName);
		});
	}

	@Override
	public void load() {
		selectUnit = UnitTypes.alpha;
		team = Team.derelict;

		ui = new BaseDialog(localizedName());
		ui.cont.table(table -> unitCont = table).row();
		ui.cont.table(table -> {
			table.table(t -> {
				t.add("队伍");
				t.field("" + team.id, text -> {
					int id = IntFunc.parseInt(text);
					team = Team.get(id);
				});
			});
			table.table(t -> {
				t.add("数量");
				t.field("0", text -> {
					amount = IntFunc.parseInt(text);
				});
			});
		}).row();
		ui.cont.table(table -> {
			table.button("@ok", Styles.clearPartialt, this::spawn).size(90, 50);
			table.check("loop", b -> loop = b);
		});
		ui.addCloseButton();

		btn.setDisabled(() -> Vars.state.isMenu());
		btn.update(() -> {
			if (loop)
				spawn();
		});

		loadSettings();
	}

	public void spawn() {
		if (Vars.player == null)
			return;
		for (int i = 0; i < amount; i++) {
			selectUnit.spawn(team, Vars.player.x, Vars.player.y);
		}
	}

	@Override
	public void loadSettings() {
		Table table = new Table();
		table.left().defaults().left();
		table.add(localizedName()).color(Pal.accent).row();
		table.table(cont -> {
			cont.left().defaults().left();
			int[] defCap = {0};
			Events.run(EventType.WorldLoadEvent.class, () -> defCap[0] = Vars.state.rules.unitCap);
			cont.check("单位无限制", b -> Vars.state.rules.unitCap = b ? 0xffffff : defCap[0]).row();
			cont.button("杀死所有单位", () -> Groups.unit.each(Unit::kill)).fillX();
		}).padLeft(6);

		Contents.settings.add(table);
	}

	@Override
	public void build() {
		setup();
		ui.show();
	}
}
