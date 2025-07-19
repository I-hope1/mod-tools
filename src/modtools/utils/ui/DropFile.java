package modtools.utils.ui;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.scene.event.VisibilityListener;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import modtools.ui.IntUI;
import modtools.utils.reflect.ClassUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.io.*;
import java.util.List;
import java.util.*;

import static mindustry.Vars.ui;

public class DropFile {
	public static final String LABEL_NAME = "ImportFromDrop";
	public static boolean valid() {
		return ClassUtils.exists("javax.swing.JFrame") && !Objects.equals(ui.mods.buttons.getChildren().peek().name, LABEL_NAME);
	}
	public static void load() {
		if (!DropFile.valid()) return;
		// 给mods添加导入按钮
		ui.mods.addListener(new VisibilityListener() {
			public boolean shown() {
				ui.mods.removeListener(this);
				DropFile.buildSelector(ui.mods.buttons.row());
				return false;
			}
		});
	}


	public static void openFiSelector(Cons<List<Fi>> fiCons) {
		new FileSelector(fiCons);
	}

	public static void buildSelector(Table t) {
		if (t.getChildren().peek() instanceof TextButton tb && tb.getText().equals(LABEL_NAME)) return;
		t.button(LABEL_NAME, () -> DropFile.openFiSelector(
		 list -> {
			 try {
				 for (Fi fi : list) {
					 if (!fi.extEquals("zip") && !fi.extEquals("jar"))
						 throw new IllegalArgumentException("Unexpected file type: " + fi.extension());
					 Core.app.post(() -> {
						 try {
							 Vars.mods.importMod(fi);
						 } catch (IOException e) {
							 Vars.ui.showException("Failed to import mod: " + fi, e);
						 }
					 });
				 }
			 } catch (Throwable e) {
				 IntUI.showException("Failed to import mod from selector", e);
			 }
		 })).name(LABEL_NAME);
	}
	private static class FileSelector extends JFrame {
		public FileSelector(Cons<List<Fi>> fiCons) throws HeadlessException {
			new DropTarget(getContentPane(), DnDConstants.ACTION_COPY_OR_MOVE,
			 new DropTargetAdapter() {
				 public void drop(DropTargetDropEvent event) {
					 try {
						 // 如果拖入的文件格式受支持
						 if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
							 // 接收拖拽来的数据
							 event.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
							 List<File> list = (List<File>) (event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor));
							 // 指示拖拽操作已完成
							 event.dropComplete(true);
							 setVisible(false);

							 fiCons.get(list.stream().map(Fi::new).toList());
							 dispose();
						 } else {
							 // 拒绝拖拽来的数据
							 event.rejectDrop();
						 }
					 } catch (Exception e) {
						 IntUI.showException(e);
					 }
				 }
			 });

			setSize(300, 300);
			setLocationRelativeTo(null);
			// setDefaultCloseOperation(EXIT_ON_CLOSE);
			setVisible(true);
		}
	}
	/* public static void shareFile(Fi file) {
		Context app         = ((AndroidApplication) Core.app);
		Intent  shareIntent = new Intent();
		shareIntent.setAction(Intent.ACTION_SEND);
		Uri uri;
		if (VERSION.SDK_INT >= VERSION_CODES.N) {
			ContentValues values = new ContentValues();
			values.put(FileColumns.DATA, file.readBytes());
			values.put(FileColumns.MIME_TYPE, "text/plain");
			uri = app.getContentResolver().insert(Files.getContentUri("external"), values);
		} else {
			uri = Uri.fromFile(file.file());
		}
		shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
		shareIntent.setType("text/plain");
		shareIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

		app.startActivity(Intent.createChooser(shareIntent, "分享文件"));
	} */
}
