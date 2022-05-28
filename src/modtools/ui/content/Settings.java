
package modtools.ui.content;

import arc.Core;
import arc.scene.ui.layout.Table;
import mindustry.graphics.Pal;
import mindustry.ui.dialogs.BaseDialog;

import static modtools.IntVars.modName;

public class Settings extends Content {
    BaseDialog ui;
    Table cont = new Table();
    final Table loadTable = new Table(t -> {
        t.left().defaults().left();
    });

    public void build() {
        ui.show();
    }

    public Settings() {
        super("settings");
    }

    public <T extends Content> void addLoad(T cont) {
        loadTable.check(cont.localizedName(), (boolean)Core.settings.get(modName + "-load-" + cont.name, true), b -> {
            Core.settings.put(modName + "-load-" + cont.name, b);
        }).row();
    }

    public Table add(String title, Table t) {
        Table table = new Table();
        table.add(title).color(Pal.accent).growX().left().row();
        t.left().defaults().left();
        table.add(t).growX().left().padLeft(16);
        add(table);
        return table;
    }

    public void add(Table t) {
        cont.add(t).width(400).padTop(6).row();
    }

    public void load() {
        ui = new BaseDialog(localizedName());
        cont = new Table();
        ui.cont.pane(cont).fillX().fillY();
        cont.defaults().width(400);
        cont.add("load").color(Pal.accent).growX().left().row();
        cont.add(loadTable).growX().left().padLeft(16).row();
        Content.all.forEach(cont -> {
            if (!(cont instanceof Settings)) {
                addLoad(cont);
            }

        });
        ui.addCloseButton();
    }
}
