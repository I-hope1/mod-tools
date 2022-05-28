package modtools.ui.content;

import arc.Core;
import arc.Events;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.ui.ImageButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.gen.Unit;
import mindustry.graphics.Pal;
import mindustry.type.UnitType;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import modtools.ui.Contents;
import modtools.ui.IntUI;
import modtools.ui.components.MyItemSelection;

import static mindustry.Vars.player;
import static rhino.ScriptRuntime.*;

public class UnitSpawn extends Content {

	public UnitSpawn() {
		super("unitSpawn");
	}

	BaseDialog ui;
	UnitType selectUnit;
	int amount = 0;
	Team team;
	Table unitCont;
	boolean loop = false;
	TextField xField, yField, amountField, teamField;

	public void setup() {
		unitCont.clearChildren();
		MyItemSelection.buildTable(unitCont, Vars.content.units(), () -> selectUnit, u -> selectUnit = u,
				Vars.mobile ? 6 : 10);
		unitCont.table(right -> {
			right.label(() -> selectUnit.name).row();
			right.label(() -> selectUnit.localizedName);
		});
	}

	public void setX(float x) {
		xField.setText("" + x);
//		swapnX = x;
	}

	public void setY(float y) {
		yField.setText("" + y);
//		swapnY = y;
	}

	@Override
	public void load() {
		selectUnit = UnitTypes.alpha;
		team = Team.derelict;

		ui = new BaseDialog(localizedName());
		ui.cont.table(table -> unitCont = table).row();
		// Options1 (生成pos)
		ui.cont.table(Tex.button, table -> {
			table.table(x -> {
				x.add("x:");
				xField = x.field("" + player.x, newX -> {
//					if (!isNaN(newX)) swapnX = Float.parseFloat(newX);
				}).valid(val -> !myIsNaN(val)).get();
			});
			table.table(y -> {
				y.add("y:");
				yField = y.field("" + player.y, newY -> {
//					if (!isNaN(newY)) swapnY = Float.parseFloat(newY);
				}).valid(val -> !myIsNaN(val)).get();
			}).row();
			table.button("选取坐标", () -> {
				ui.hide();
				Element el = new Element();
				el.fillParent = true;
				InputListener listener = new InputListener() {
					@Override
					public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
						Core.scene.removeListener(this);
						Vec2 vec2 = Core.camera.unproject(x, y);
						setX(vec2.x);
						setY(vec2.y);
						el.remove();
						ui.show();
						return false;
					}
				};
				Core.scene.addListener(listener);
				Core.scene.add(el);
			}).fillX();
			table.button("获取玩家坐标", () -> {
				setX(player.x);
				setY(player.y);
			}).fillX();
		}).row();
		// Options2
		ui.cont.table(Tex.button, table -> {
			table.table(t -> {
				t.add("队伍");
				teamField = t.field("" + team.id, text -> {
					int id = (int) toInteger(text);
					team = Team.get(id);
				}).valid(val -> !myIsNaN(val) && toInteger(val) >= 0).get();
				var btn = new ImageButton(Icon.edit, Styles.cleari);
				btn.clicked(() -> IntUI.showSelectImageTableWithFunc(btn, new Seq<>(Team.all),
						() -> team, newTeam -> {
							team = newTeam;
							teamField.setText("" + team.id);
						}, 48, 32, 6,
						team -> IntUI.whiteui.tint(team.color), true));
				t.add(btn);
			});
			table.table(t -> {
				t.add("数量");
				amountField = t.field("0", text -> {
					amount = (int) toInteger(text);
				}).valid(val -> !myIsNaN(val) && toNumber(val) >= 0).get();
			});
		}).row();
		ui.cont.table(table -> {
			table.button("@ok", Styles.cleart, this::spawn).size(90, 50)
					.disabled(b -> !isOk());
			table.check("loop", b -> loop = b);
		});
		ui.addCloseButton();

		btn.setDisabled(() -> Vars.state.isMenu());
		btn.update(() -> {
			if (loop) {
				spawn();
			}
		});

		loadSettings();

	}

	public boolean isOk() {
		return xField.isValid() && yField.isValid() && amountField.isValid() && teamField.isValid();
	}

	public boolean myIsNaN(String str) {
		try {
			double d = toNumber(str);
			return isNaN(d);
		} catch (Exception ignored) {}
		return false;
	}

	public void spawn() {
		if (!isOk()) return;

		float x = Float.parseFloat(xField.getText());
		float y = Float.parseFloat(yField.getText());

		for (int i = 0; i < amount; i++) {
			selectUnit.spawn(team, x, y);
		}
	}

	@Override
	public void loadSettings() {
		Table table = new Table();
		table.left().defaults().left();
		table.add(localizedName()).color(Pal.accent).row();
		table.table(cont -> {
			cont.left().defaults().left().width(200);
			int[] defCap = {0};
			Events.run(EventType.WorldLoadEvent.class, () -> defCap[0] = Vars.state.rules.unitCap);
			cont.check("单位无限制", b -> Vars.state.rules.unitCap = b ? 0xffffff : defCap[0]).fillX().row();
			cont.button("杀死所有单位", () -> Groups.unit.each(Unit::kill)).fillX().row();
			cont.button("清除所有单位", () -> Groups.unit.each(Unit::remove)).fillX().row();
//			cont.check("服务器适配", b -> server = b);
		}).padLeft(6);

		Contents.settings.add(table);
	}

	@Override
	public void build() {
		setup();
		ui.show();
	}
}
