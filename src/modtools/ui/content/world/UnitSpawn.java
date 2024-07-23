package modtools.ui.content.world;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.*;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.core.Version;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.UnitType;
import mindustry.ui.Styles;
import modtools.ModTools;
import modtools.events.*;
import modtools.jsfunc.INFO_DIALOG;
import modtools.net.packet.HopeCall;
import modtools.ui.*;
import modtools.ui.comp.*;
import modtools.ui.comp.linstener.WorldSelectListener;
import modtools.ui.comp.utils.MyItemSelection;
import modtools.ui.content.Content;
import modtools.ui.content.debug.Tester;
import modtools.ui.gen.HopeIcons;
import modtools.ui.menu.*;
import modtools.utils.*;
import modtools.utils.MySettings.Data;
import modtools.utils.ui.FormatHelper;
import modtools.utils.world.WorldUtils;

import static mindustry.Vars.player;
import static modtools.utils.world.WorldUtils.UNIT;
import static rhino.ScriptRuntime.*;

public class UnitSpawn extends Content {
	public static final String noScorchMarksKey  = "@settings.noScorchMarks";
	public static final String killAllUnitsKey   = "@unitspawn.killAllUnits";
	public static final String removeAllUnitsKey = "@unitspawn.removeAllUnits";
	public static final String unitUnlimitedKey  = "@settings.unitUnlimited";

	public UnitSpawn() {
		super("unitSpawn", Icon.unitsSmall);
		defLoadable = false;
	}

	Window   ui;
	UnitType selectUnit;
	int      amount = 1;
	Team     team;
	Table    unitCont;

	boolean unitUnlimited, multi;
	float spawnX, spawnY;
	TextField xField, yField, amountField, teamField;
	ButtonGroup<ImageButton> group;

	Label nameL, localizedNameL;
	public void setup() {
		unitCont.clearChildren();
		group = MyItemSelection.buildTable0(unitCont, Vars.content.units(), Vars.mobile ? 8 : 10,
		 u -> new TextureRegionDrawable(u.uiIcon));
		group.setMinCheckCount(1);

		unitCont.find(el -> el.userObject == UnitTypes.alpha).fireClick();

		unitCont.update(() -> {
			group.setMaxCheckCount(multi ? -1 : 1);
			if (multi) return;

			Seq<ImageButton> allChecked = group.getAllChecked();
			for (int i = 1; i < allChecked.size; i++) {
				allChecked.get(i).setChecked(false);
			}
		});

		unitCont.row();
		unitCont.table(right -> {
			nameL = new Label("");
			localizedNameL = new Label("");
			EventHelper.longPressOrRclick(nameL, l -> Tester.put(l, selectUnit));
			JSFunc.addDClickCopy(nameL);
			JSFunc.addDClickCopy(localizedNameL);
			right.update(() -> {
				selectUnit = (UnitType) group.getChecked().userObject;
				Seq<ImageButton> allChecked = group.getAllChecked();
				nameL.setText(allChecked.size == 0 ? "[red]ERROR" : STR."\{selectUnit.name}\{allChecked.size > 1 ? "[]..." : ""}");
				localizedNameL.setText(allChecked.size == 0 ? "[red]ERROR" : STR."\{selectUnit.localizedName}\{allChecked.size > 1 ? "[]..." : ""}");
			});
			right.add(nameL).growX().wrap().row();
			right.add(localizedNameL).growX().wrap().row();
		}).growX();
	}
	public void setSpawnX(float spawnX) {
		if (Mathf.equal(this.spawnX, spawnX) || !validNumber(spawnX)) return;
		xField.setText(FormatHelper.fixed(spawnX, 1));
		this.spawnX = spawnX;
		//		swapnX = x;
	}
	public void setSpawnY(float spawnY) {
		if (Mathf.equal(this.spawnX, spawnX) || !validNumber(spawnY)) return;
		yField.setText(FormatHelper.fixed(spawnY, 1));
		this.spawnY = spawnY;
	}

	public void _load() {
		root = ExecuteTree.nodeRoot(null, "unitSpawn", "<internal>", Icon.unitsSmall, () -> { });
		listener.acquireWorldPos(Core.graphics.getWidth() / 2f, Core.graphics.getHeight() / 2f);

		selectUnit = UnitTypes.dagger;
		team = Team.sharded;

		ui = new Window(localizedName(), 40 * 8, 400, true);
		ui.cont.table(table -> unitCont = table).grow().row();
		// Options1 (生成pos)
		ui.cont.table(Window.myPane, table -> {
			table.marginTop(-4f);
			table.table(x0 -> {
				x0.add("x:");
				xField = x0.field(String.valueOf(spawnX), newX -> spawnX = NumberHelper.asFloat(newX))
				 .valid(this::validNumber).growX()
				 .get();
			}).growX();
			table.table(y0 -> {
				y0.add("y:");
				yField = y0.field(String.valueOf(spawnY), newY -> spawnY = NumberHelper.asFloat(newY))
				 .valid(this::validNumber).growX()
				 .get();
			}).growX().row();
			table.button("@unitspawn.selectAposition", HopeStyles.flatToggleMenut, listener::toggleSelect).growX().height(42)
			 .update(b -> b.setChecked(listener.isSelecting()));
			table.button("@unitspawn.getfromplayer", HopeStyles.cleart, () -> {
				setSpawnX(player.x);
				setSpawnY(player.y);
			}).growX().height(42);
		}).growX().row();
		// Options2
		ui.cont.table(Window.myPane, table -> {
			table.margin(-4f, 0f, -4f, 0f);
			table.table(t -> {
				t.add("@rules.title.teams");
				teamField = t.field("" + team.id, text -> {
					 int id = (int) toInteger(text);
					 team = Team.get(id);
				 })
				 .valid(val -> NumberHelper.isPositiveInt(val) && toInteger(val) < Team.all.length)
				 .width(100)
				 .get();
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
				t.add("@filter.option.amount");
				amountField = t.field("" + amount, text -> amount = (int) toInteger(text))
				 .valid(val -> validNumber(val) && NumberHelper.isPositiveInt(val))
				 .width(100)
				 .get();
			});
		}).growX().row();
		// Buttons
		ui.cont.table(Window.myPane, table -> {
			table.margin(-4f, 0f, -4f, 0f);
			table.check("Multi", false, b -> multi = b).get().setStyle(HopeStyles.hope_defaultCheck);
			table.button("@ok", HopeStyles.cleart, this::spawnIgnored)
			 .size(90, 50)
			 .disabled(b -> !isOk(spawnX, spawnY, amount, team, selectUnit));
			table.button("Post task", HopeStyles.cleart, () -> {
				ExecuteTree.context(root, () ->
				 ExecuteTree.node(String.valueOf(localizedNameL.getText()),
					STR."(\{spawnX},\{spawnY})\n{\{team}}[accent]×\{amount}",
					getSpawnRun()).code(generateCode()).resubmitted().worldTimer().apply()
				);
				Window dialog = INFO_DIALOG.dialog(t -> {
					t.add("Posted").row();
					t.button("View", () -> Contents.executor.build());
				});
				Time.runTask(2.5f * 60f, dialog::hide);
			}).size(90, 50);
			table.button(Icon.menuSmall, HopeStyles.flati, 24, () -> {
				MenuBuilder.showMenuListDispose(() -> Seq.with(
				 CheckboxList.withc("loop", HopeIcons.loop, unitUnlimitedKey, unitUnlimited, () -> toggleUnitCap(!unitUnlimited)),
				 MenuItem.with("noScorchMarks", Icon.eyeOffSmall, noScorchMarksKey, UNIT::noScorchMarks),
				 MenuItem.with("all.kill", Icon.cancelSmall, killAllUnitsKey, UNIT::killAllUnits),
				 MenuItem.with("all.remove", Icon.cancelSmall, removeAllUnitsKey, UNIT::removeAllUnits)
				));
			}).size(32);
		}).growX();
		ui.getCell(ui.cont).minHeight(ui.cont.getPrefHeight());
		// ui.addCloseButton();
	}
	TaskNode root;
	public void load() {
		loadSettings();
		listener = new UnitSpawnSelectListener();
	}
	public boolean isOk(float x, float y, int amount, Team team, UnitType selectUnit) {
		return validNumber(x) && validNumber(y) && selectUnit != null
		       && validNumber(amount) && amount > 0 && team != null;
	}

	public Button buildButton(boolean isSmallized) {
		Button btn = super.buildButton(isSmallized);
		btn.setDisabled(() -> Vars.state.isMenu());
		return btn;
	}
	public boolean validNumber(String str) {
		try {
			return validNumber(NumberHelper.asFloat(str));
		} catch (Exception ignored) { }
		return false;
	}
	public boolean validNumber(float d) {
		return Math.abs(d) < 1E6 && !Float.isNaN(d);
	}
	public void spawnIgnored() {
		Tools.runIgnoredException(() -> {
			group.getAllChecked().each(u -> spawn((UnitType) u.userObject, amount, team, spawnX, spawnY));
		});
	}
	public Runnable getSpawnRun() {
		float         x0      = spawnX, y0 = spawnY;
		int           amount0 = amount;
		Team          team0   = team;
		Seq<UnitType> units   = new Seq<>(group.getAllChecked().size);
		group.getAllChecked().each(u -> units.add((UnitType) u.userObject));
		return () -> units.each(u -> spawn(u, amount0, team0, x0, y0));
	}
	public String generateCode() {
		return STR."$.Contents.unit_spawn.spawn($.unit(\{
		 selectUnit.name}),\{amount},Team.get(\{team.id}),\{spawnX},\{spawnY})";
	}

	public void spawn(UnitType selectUnit, int amount, Team team, float x, float y) {
		if (!isOk(x, y, amount, team, selectUnit)) return;

		if (selectUnit.uiIcon == null || selectUnit.fullIcon == null) {
			RuntimeException exception = new RuntimeException("所选单位的图标为null，可能会崩溃", new NullPointerException("selectUnit icon is null"));
			IntUI.showException(exception);
			throw exception;
		}

		Unit unit = Version.number >= 136 ?
		 selectUnit.sample :
		 selectUnit.constructor.get();

		if (unit instanceof BlockUnitc) {
			RuntimeException exception = new RuntimeException("所选单位为blockUnit，可能会崩溃",
			 new IllegalArgumentException("selectedUnit is blockunit"));
			IntUI.showException(exception);
			throw exception;
		}
		HopeCall.spawnUnit(selectUnit, x + (Mathf.random() - 1) * 0.001f, y + (Mathf.random() - 1) * 0.001f, amount, team);
	}

	int defCap;
	public void loadSettings(Data SETTINGS) {
		Events.run(EventType.WorldLoadEvent.class, Tools.delegate(() -> {
			defCap = Vars.state.rules.unitCap;
			Vars.state.rules.unitCap = unitUnlimited ? 0xfff_fff : defCap;
		}, ModTools::isDisposed));
		Contents.settings_ui.add(localizedName(), icon, new Table() {{
			left().defaults().left();
			check(unitUnlimitedKey, 28, unitUnlimited, b -> toggleUnitCap(b))
			 .with(cb -> cb.setStyle(HopeStyles.hope_defaultCheck))
			 .row();
			defaults().growX().height(42);
			button(noScorchMarksKey, HopeStyles.flatBordert, UNIT::noScorchMarks).row();
			button(killAllUnitsKey, HopeStyles.flatBordert, UNIT::killAllUnits).row();
			button(removeAllUnitsKey, HopeStyles.flatBordert, UNIT::removeAllUnits);
		}});
	}
	private void toggleUnitCap(boolean b) {
		unitUnlimited = b;
		Vars.state.rules.unitCap = b ? 0xffffff : defCap;
	}
	public void build() {
		if (ui == null) {
			_load();
			setup();
		}
		ui.show();
	}
	// 用于获取点击的坐标
	UnitSpawnSelectListener listener;
	class UnitSpawnSelectListener extends WorldSelectListener {
		Element hitter = new Hitter();

		{
			hitter.addListener(this);
			WorldUtils.uiWD.submit(this::draw);
		}

		float lastX = Float.NaN, lastY = Float.NaN;
		protected void acquireWorldPos(float x, float y) {
			lastX = x;
			lastY = y;
			super.acquireWorldPos(x, y);
			setSpawnX(end.x);
			setSpawnY(end.y);
		}
		public void touchUp(InputEvent event, float mx, float my, int pointer, KeyCode button) {
			super.touchUp(event, mx, my, pointer, button);
			hitter.remove();
		}
		public void draw() {
			if (ui == null) return;
			if (!Float.isNaN(lastX) && !Float.isNaN(lastY)) acquireWorldPos(lastX, lastY);
			Gl.flush();
			Draw.z(Layer.overlayUI);
			Draw.color();
			Lines.stroke(2);
			Color color = Tmp.c1.set(isOk(spawnX, spawnY, amount, team, selectUnit) ? Pal.accent : Pal.remove)
			 .a(ui.isShown() ? 0.7f : 0.3f);
			Drawf.dashCircle(spawnX, spawnY, 5, color);
			Draw.color();
		}
		public void toggleSelect() {
			ElementUtils.addOrRemove(hitter, !isSelecting());
		}
		public boolean isSelecting() {
			return hitter.getScene() != null;
		}
	}
}
