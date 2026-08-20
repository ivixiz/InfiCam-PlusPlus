package be.ntmn.inficam;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** A small dependency-free MJPEG server for the current thermal view. */
public final class WebViewServer {
	public interface CommandHandler {
		void onCommand(String command, String value);
	}
	public interface StateProvider {
		String getState(long generation, int from);
	}
	public interface VideoProvider {
		VideoData open(boolean chart) throws IOException;
	}
	public static final class VideoData implements AutoCloseable {
		private final InputStream input;
		private final long length;

		public VideoData(InputStream input, long length) {
			this.input = input;
			this.length = length;
		}

		@Override public void close() throws IOException { input.close(); }
	}
	private static final int FIRST_PORT = 8080;
	private static final int LAST_PORT = 8090;
	private final Object frameLock = new Object();
	private final Object encoderLock = new Object();
	private final Set<Socket> clients = Collections.newSetFromMap(
			new ConcurrentHashMap<Socket, Boolean>());
	private final AtomicInteger streamClients = new AtomicInteger();
	private final byte[] indexPage;
	private volatile byte[] latestJpeg;
	private volatile long frameNumber;
	private volatile boolean running;
	private volatile ServerSocket serverSocket;
	private volatile int port;
	private Thread acceptThread;
	private Thread encoderThread;
	private Bitmap pendingFrame;
	private Bitmap reusableFrame;
	private boolean encoderRunning;
	private volatile CommandHandler commandHandler;
	private volatile StateProvider stateProvider;
	private volatile VideoProvider videoProvider;

	public WebViewServer(Context context) {
		try {
			indexPage = Util.readStringAsset(context, "web_control.html")
					.getBytes(StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Missing Web Control page", e);
		}
	}

	public void setCommandHandler(CommandHandler handler) {
		commandHandler = handler;
	}

	public void setStateProvider(StateProvider provider) { stateProvider = provider; }
	public void setVideoProvider(VideoProvider provider) { videoProvider = provider; }

	public synchronized String start() throws IOException {
		if (running)
			return getUrl();
		if (getLocalIp() == null)
			throw new IOException("No local network address");
		IOException lastError = null;
		for (int candidate = FIRST_PORT; candidate <= LAST_PORT; ++candidate) {
			try {
				serverSocket = new ServerSocket(candidate, 8, InetAddress.getByName("0.0.0.0"));
				port = candidate;
				break;
			} catch (IOException e) {
				lastError = e;
			}
		}
		if (serverSocket == null)
			throw lastError == null ? new IOException("Unable to bind web server") : lastError;
		running = true;
		encoderRunning = true;
		encoderThread = new Thread(this::encodeLoop, "InfiCam web encoder");
		encoderThread.setDaemon(true);
		encoderThread.start();
		acceptThread = new Thread(this::acceptLoop, "InfiCam web server");
		acceptThread.setDaemon(true);
		acceptThread.start();
		return getUrl();
	}

	public synchronized void stop() {
		running = false;
		synchronized (encoderLock) {
			encoderRunning = false;
			if (pendingFrame != null) {
				pendingFrame.recycle();
				pendingFrame = null;
			}
			if (reusableFrame != null) {
				reusableFrame.recycle();
				reusableFrame = null;
			}
			encoderLock.notifyAll();
		}
		ServerSocket ss = serverSocket;
		serverSocket = null;
		if (ss != null) {
			try { ss.close(); } catch (IOException ignored) { }
		}
		for (Socket socket : clients) {
			try { socket.close(); } catch (IOException ignored) { }
		}
		clients.clear();
		synchronized (frameLock) {
			frameLock.notifyAll();
		}
		Thread encoder = encoderThread;
		encoderThread = null;
		if (encoder != null && encoder != Thread.currentThread()) {
			try { encoder.join(1000); }
			catch (InterruptedException e) { Thread.currentThread().interrupt(); }
		}
	}

	public boolean isRunning() {
		return running;
	}

	public String getUrl() {
		String ip = getLocalIp();
		return "http://" + (ip == null ? "127.0.0.1" : ip) + ":" + port;
	}

	/** True only when a browser is watching and the encoder can accept a new frame. */
	public boolean wantsFrame() {
		if (!running || streamClients.get() == 0)
			return false;
		synchronized (encoderLock) {
			return encoderRunning && pendingFrame == null;
		}
	}

	/**
	 * Queues a displayed frame without blocking the render thread. Ownership of an accepted
	 * bitmap is transferred to the server; rejected bitmaps remain owned by the caller.
	 */
	public boolean publish(Bitmap bitmap) {
		if (bitmap == null)
			return false;
		synchronized (encoderLock) {
			if (!running || !encoderRunning || streamClients.get() == 0 || pendingFrame != null)
				return false;
			pendingFrame = bitmap;
			encoderLock.notifyAll();
			return true;
		}
	}

	/** Returns a bitmap recycled by the encoder, or allocates one for the two-frame pipeline. */
	public Bitmap acquireFrame(int width, int height) {
		synchronized (encoderLock) {
			Bitmap bitmap = reusableFrame;
			reusableFrame = null;
			if (bitmap != null && (bitmap.isRecycled() || bitmap.getWidth() != width ||
					bitmap.getHeight() != height)) {
				if (!bitmap.isRecycled())
					bitmap.recycle();
				bitmap = null;
			}
			return bitmap != null ? bitmap :
					Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
		}
	}

	private void releaseFrame(Bitmap bitmap) {
		synchronized (encoderLock) {
			if (encoderRunning && reusableFrame == null && !bitmap.isRecycled())
				reusableFrame = bitmap;
			else if (!bitmap.isRecycled())
				bitmap.recycle();
		}
	}

	private void encodeLoop() {
		while (true) {
			Bitmap bitmap;
			synchronized (encoderLock) {
				while (encoderRunning && pendingFrame == null) {
					try { encoderLock.wait(); }
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
				}
				if (!encoderRunning)
					return;
				bitmap = pendingFrame;
				pendingFrame = null;
			}
			try {
				ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
				if (bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)) {
					byte[] jpeg = out.toByteArray();
					synchronized (frameLock) {
						latestJpeg = jpeg;
						frameNumber++;
						frameLock.notifyAll();
					}
				}
			} finally {
				releaseFrame(bitmap);
			}
		}
	}

	private void acceptLoop() {
		while (running) {
			try {
				Socket socket = serverSocket.accept();
				clients.add(socket);
				Thread client = new Thread(() -> serve(socket), "InfiCam web client");
				client.setDaemon(true);
				client.start();
			} catch (IOException e) {
				if (running)
					continue;
				break;
			}
		}
	}

	private void serve(Socket socket) {
		try {
			socket.setSoTimeout(3000);
			BufferedReader reader = new BufferedReader(new InputStreamReader(
					socket.getInputStream(), StandardCharsets.US_ASCII));
			String request = reader.readLine();
			if (request == null)
				return;
			String[] parts = request.split(" ");
			boolean headOnly = parts.length > 0 && "HEAD".equals(parts[0]);
			String path = parts.length > 1 ? parts[1] : "/";
			String header;
			while ((header = reader.readLine()) != null && !header.isEmpty()) { /* headers */ }
			if (path.startsWith("/control")) {
				handleControl(path);
				writeText(socket, "OK");
			} else if (path.startsWith("/state")) {
				serveState(socket, path, headOnly);
			} else if (path.startsWith("/chart-video")) {
				serveVideo(socket, true, headOnly);
			} else if (path.startsWith("/video")) {
				serveVideo(socket, false, headOnly);
			} else if (path.startsWith("/stream"))
				serveStream(socket);
			else
				serveIndex(socket);
		} catch (IOException ignored) {
		} finally {
			clients.remove(socket);
			try { socket.close(); } catch (IOException ignored) { }
		}
	}

	private void handleControl(String path) {
		int queryStart = path.indexOf('?');
		if (queryStart < 0 || commandHandler == null)
			return;
		String query = path.substring(queryStart + 1);
		String command = null, value = "";
		for (String pair : query.split("&")) {
			String[] kv = pair.split("=", 2);
			if (kv.length != 2)
				continue;
			try {
				String key = URLDecoder.decode(kv[0], "UTF-8");
				String val = URLDecoder.decode(kv[1], "UTF-8");
				if ("cmd".equals(key)) command = val;
				if ("value".equals(key)) value = val;
			} catch (Exception ignored) { }
		}
		if (command != null)
			commandHandler.onCommand(command, value);
	}

	private void writeText(Socket socket, String text) throws IOException {
		byte[] body = text.getBytes(StandardCharsets.UTF_8);
		OutputStream out = socket.getOutputStream();
		writeHeaders(out, "200 OK", "text/plain; charset=utf-8", body.length);
		out.write(body);
		out.flush();
	}

	private void serveState(Socket socket, String path, boolean headOnly) throws IOException {
		StateProvider provider = stateProvider;
		if (provider == null) {
			writeText(socket, "No state provider");
			return;
		}
		long generation = queryLong(path, "generation", -1L);
		long requestedFrom = queryLong(path, "from", 0L);
		int from = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, requestedFrom));
		byte[] body = provider.getState(generation, from).getBytes(StandardCharsets.UTF_8);
		OutputStream out = socket.getOutputStream();
		writeHeaders(out, "200 OK", "application/json; charset=utf-8", body.length);
		if (!headOnly)
			out.write(body);
		out.flush();
	}

	private void serveVideo(Socket socket, boolean chart, boolean headOnly) throws IOException {
		VideoProvider provider = videoProvider;
		VideoData video = provider == null ? null : provider.open(chart);
		if (video == null) {
			byte[] message = "No video ready".getBytes(StandardCharsets.US_ASCII);
			writeHeaders(socket.getOutputStream(), "404 Not Found", "text/plain", message.length);
			if (!headOnly)
				socket.getOutputStream().write(message);
			return;
		}
		try (VideoData source = video) {
			OutputStream out = socket.getOutputStream();
			writeHeaders(out, "200 OK", "video/mp4", source.length);
			if (!headOnly) {
				byte[] buffer = new byte[64 * 1024];
				int count;
				while ((count = source.input.read(buffer)) != -1)
					out.write(buffer, 0, count);
			}
			out.flush();
		}
	}

	private void serveIndex(Socket socket) throws IOException {
		OutputStream out = socket.getOutputStream();
		writeHeaders(out, "200 OK", "text/html; charset=utf-8", indexPage.length);
		out.write(indexPage);
		out.flush();
	}

	private void serveStream(Socket socket) throws IOException {
		streamClients.incrementAndGet();
		try {
			socket.setSoTimeout(0);
			OutputStream out = socket.getOutputStream();
			String headers = "HTTP/1.1 200 OK\r\n" +
					"Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
					"Cache-Control: no-cache, no-store, must-revalidate\r\n" +
					"Connection: close\r\n\r\n";
			out.write(headers.getBytes(StandardCharsets.US_ASCII));
			out.flush();
			long sentFrame = -1;
			while (running && !socket.isClosed()) {
				byte[] jpeg;
				synchronized (frameLock) {
					while (running && frameNumber == sentFrame)
						try { frameLock.wait(1000); } catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							return;
						}
					jpeg = latestJpeg;
					sentFrame = frameNumber;
				}
				if (jpeg == null)
					continue;
				out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " +
						jpeg.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
				out.write(jpeg);
				out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
				out.flush();
			}
		} finally {
			streamClients.decrementAndGet();
		}
	}

	private static long queryLong(String path, String name, long fallback) {
		int queryStart = path.indexOf('?');
		if (queryStart < 0)
			return fallback;
		for (String pair : path.substring(queryStart + 1).split("&")) {
			String[] keyValue = pair.split("=", 2);
			if (keyValue.length != 2 || !name.equals(keyValue[0]))
				continue;
			try { return Long.parseLong(keyValue[1]); }
			catch (NumberFormatException ignored) { return fallback; }
		}
		return fallback;
	}

	private static void writeHeaders(OutputStream out, String status, String type, long length)
			throws IOException {
		String lengthHeader = length >= 0 ? "Content-Length: " + length + "\r\n" : "";
		out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\n" +
				lengthHeader + "Connection: close\r\n\r\n")
				.getBytes(StandardCharsets.US_ASCII));
	}

	private static String getLocalIp() {
		try {
			java.util.List<NetworkInterface> interfaces =
					Collections.list(NetworkInterface.getNetworkInterfaces());
			/* Prefer the LAN interface. A phone can have Wi-Fi and LTE active at the
			 * same time; returning LTE's CGNAT address makes Web Control unreachable
			 * from the computer on the same Wi-Fi network. */
			for (int pass = 0; pass < 2; ++pass) {
				for (NetworkInterface network : interfaces) {
					if (!network.isUp() || network.isLoopback())
						continue;
					String name = network.getName().toLowerCase(java.util.Locale.US);
					boolean lan = name.startsWith("wlan") || name.startsWith("wifi") ||
							name.startsWith("eth") || name.startsWith("usb") || name.startsWith("rndis");
					if ((pass == 0) != lan)
						continue;
					for (InetAddress address : Collections.list(network.getInetAddresses())) {
						if (address instanceof Inet4Address && !address.isLoopbackAddress())
							return address.getHostAddress();
					}
				}
			}
		} catch (SocketException ignored) { }
		return null;
	}
}
