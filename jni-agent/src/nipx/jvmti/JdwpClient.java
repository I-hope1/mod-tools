package nipx.jvmti;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public class JdwpClient implements Closeable {

	private final Socket           socket;
	private final DataOutputStream out;
	private final DataInputStream  in;
	private final AtomicInteger    packetId = new AtomicInteger(1);

	// 从 IDSizes 查询到的实际大小
	private int objectIDSize;
	private int frameIDSize;

	private static final byte[] HANDSHAKE = "JDWP-Handshake".getBytes();

	// ─────────────────────────────────────────────────────────────
	// 1. 建立连接 + Handshake + 查 IDSizes
	// ─────────────────────────────────────────────────────────────
	public JdwpClient(String host, int port) throws IOException {
		socket = new Socket(host, port);
		socket.setTcpNoDelay(true);
		out = new DataOutputStream(socket.getOutputStream());
		in = new DataInputStream(socket.getInputStream());

		// Handshake
		out.write(HANDSHAKE);
		out.flush();
		byte[] resp = in.readNBytes(HANDSHAKE.length);
		if (!Arrays.equals(resp, HANDSHAKE)) { throw new IOException("JDWP handshake failed"); }

		// 查询 ID 尺寸 (VirtualMachine.IDSizes cmdSet=1 cmd=7)
		byte[]     reply = sendCommand(1, 7, new byte[0]);
		ByteBuffer buf   = ByteBuffer.wrap(reply);
		buf.getInt();              // fieldIDSize    (不需要)
		buf.getInt();              // methodIDSize   (不需要)
		objectIDSize = buf.getInt(); // objectIDSize  ← threadID 用这个
		buf.getInt();              // referenceTypeIDSize
		frameIDSize = buf.getInt(); // frameIDSize
		System.out.printf("[JDWP] objectIDSize=%d frameIDSize=%d%n",
		 objectIDSize, frameIDSize);
	}

	public interface ThreadConsumer {
		void accept(String name, long id);
	}
	// ─────────────────────────────────────────────────────────────
	// 2. 获取所有线程 (VirtualMachine.AllThreads cmdSet=1 cmd=4)
	//    返回 Map<threadName, threadID>
	// ─────────────────────────────────────────────────────────────
	public void allThreads(ThreadConsumer consumer) throws IOException {
		byte[]     reply = sendCommand(1, 4, new byte[0]);
		ByteBuffer buf   = ByteBuffer.wrap(reply);
		int        count = buf.getInt();

		// 先把所有 ID 读出来
		long[] ids = new long[count];
		for (int i = 0; i < count; i++) {
			ids[i] = readID(buf, objectIDSize);
		}

		// 再逐个查名字 (ThreadReference.Name cmdSet=11 cmd=1)
		for (long id : ids) {
			byte[] nameReply = sendCommand(11, 1, writeID(id, objectIDSize));
			String name      = readJdwpString(ByteBuffer.wrap(nameReply));
			consumer.accept(name, id);
		}
	}

	// ─────────────────────────────────────────────────────────────
	// 3. 挂起线程 (ThreadReference.Suspend cmdSet=11 cmd=2)
	// ─────────────────────────────────────────────────────────────
	public void suspendThread(long threadId) throws IOException {
		byte[] data = writeID(threadId, objectIDSize);
		sendCommand(11, 2, data);  // Reply 无数据，只看 errorCode
		// System.out.printf("[JDWP] Suspended thread 0x%X%n", threadId);
	}

	// ─────────────────────────────────────────────────────────────
	// 4. 恢复线程 (ThreadReference.Resume cmdSet=11 cmd=3)
	// ─────────────────────────────────────────────────────────────
	public void resumeThread(long threadId) throws IOException {
		sendCommand(11, 3, writeID(threadId, objectIDSize));
		// System.out.printf("[JDWP] Resumed thread 0x%X%n", threadId);
	}

	// ─────────────────────────────────────────────────────────────
	// 5. 挂起整个 VM (VirtualMachine.Suspend cmdSet=1 cmd=8)
	// ─────────────────────────────────────────────────────────────
	public void suspendVM() throws IOException {
		sendCommand(1, 8, new byte[0]);
	}

	public void resumeVM() throws IOException {
		sendCommand(1, 9, new byte[0]);
	}

	// ─────────────────────────────────────────────────────────────
	// 核心：发送 Command，等待对应 Reply
	// ─────────────────────────────────────────────────────────────
	private byte[] sendCommand(int cmdSet, int cmd, byte[] data) throws IOException {
		int id  = packetId.getAndIncrement();
		int len = 11 + data.length;

		ByteBuffer pkt = ByteBuffer.allocate(len);
		pkt.putInt(len);      // length
		pkt.putInt(id);       // id
		pkt.put((byte) 0x00); // flags = Command
		pkt.put((byte) cmdSet);
		pkt.put((byte) cmd);
		pkt.put(data);

		synchronized (this) {
			out.write(pkt.array());
			out.flush();
			return readReply(id);
		}
	}

	// 读 Reply（跳过不匹配的包，直到找到对应 id）
	private byte[] readReply(int expectedId) throws IOException {
		while (true) {
			int  replyLen  = in.readInt();
			int  replyId   = in.readInt();
			byte flags     = in.readByte();
			int  errorCode = in.readUnsignedShort(); // flags=0x80时这里是errorCode

			byte[] data = in.readNBytes(replyLen - 11);

			if ((flags & 0x80) == 0) {
				// 收到 Command 包（JVM 主动发的 Event），跳过
				continue;
			}
			if (replyId != expectedId) {
				// 乱序包，实际生产中应该用 Map<id, CompletableFuture> 处理
				continue;
			}
			if (errorCode != 0) {
				throw new IOException(String.format(
				 "JDWP error: cmdId=%d errorCode=%d (%s)",
				 expectedId, errorCode, jdwpErrorName(errorCode)));
			}
			return data;
		}
	}
	public static final String TMP_THREAD_NAME = "nipx-jdwp-suspend";
	public long getThreadId(Thread thread) throws IOException {
		long[] ids      = {0};
		String prevName = thread.getName();
		thread.setName(TMP_THREAD_NAME);
		try {
			allThreads((threadName, id) -> {
				if (TMP_THREAD_NAME.equals(threadName)) {
					ids[0] = id;
				}
			});
		} finally {
			thread.setName(prevName);
		}
		return ids[0];
	}

	// ─────────────────────────────────────────────────────────────
	// 工具函数
	// ─────────────────────────────────────────────────────────────
	private static long readID(ByteBuffer buf, int size) {
		return switch (size) {
			case 4 -> buf.getInt() & 0xFFFFFFFFL;
			case 8 -> buf.getLong();
			default -> throw new IllegalArgumentException("Unsupported ID size: " + size);
		};
	}

	private static byte[] writeID(long id, int size) {
		ByteBuffer buf = ByteBuffer.allocate(size);
		if (size == 4) { buf.putInt((int) id); } else buf.putLong(id);
		return buf.array();
	}

	private static String readJdwpString(ByteBuffer buf) {
		int    len   = buf.getInt();
		byte[] bytes = new byte[len];
		buf.get(bytes);
		return new String(bytes);
	}

	private static String jdwpErrorName(int code) {
		return switch (code) {
			case 10 -> "INVALID_THREAD";
			case 13 -> "THREAD_NOT_SUSPENDED";
			case 14 -> "THREAD_SUSPENDED";
			case 20 -> "INVALID_OBJECT";
			case 502 -> "NOT_IMPLEMENTED";
			default -> "UNKNOWN(" + code + ")";
		};
	}

	@Override
	public void close() throws IOException { socket.close(); }
}