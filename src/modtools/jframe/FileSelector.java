package modtools.jframe;

import arc.files.Fi;
import arc.func.Cons;
import modtools.ui.IntUI;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.io.File;
import java.util.List;

public class FileSelector extends JFrame {
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
