package modtools.jframe;

import modtools.jsfunc.reflect.UNSAFE;

import javax.swing.*;
import java.awt.*;

public class MyJFrame extends JFrame {
	public MyJFrame(Thread thread) throws HeadlessException {
		super("ParkPane");
		// 设置窗口的初始大小，这样它就不会是一个小点而看不见
		setSize(400, 300); // 宽度400，高度300
		JButton button = new JButton("Resume");
		button.addActionListener(e -> {
			if (UNSAFE.PARK_COUNT.get(thread) > 0) UNSAFE.unpark(thread);
		});
		getContentPane().add(button, BorderLayout.CENTER);
		setVisible(true);
	}
}
