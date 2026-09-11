package be.ntmn.inficam;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.BufferedOutputStream;
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

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

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
	public interface SharedFileProvider {
		SharedFileData open(long id) throws IOException;
		void complete(long id);
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
	public static final class SharedFileData implements AutoCloseable {
		private final InputStream input;
		private final long length;
		private final String mimeType;
		private final String name;
		private final long id;

		public SharedFileData(InputStream input, long length, String mimeType, String name,
				long id) {
			this.input = input;
			this.length = length;
			this.mimeType = mimeType;
			this.name = name;
			this.id = id;
		}

		@Override public void close() throws IOException { input.close(); }
	}
	private static final int FIRST_PORT = 8080;
	private static final int LAST_PORT = 8090;
	private final Object frameLock = new Object();
	private final Object encoderLock = new Object();
	private final Set<Socket> clients = Collections.newSetFromMap(
			new ConcurrentHashMap<Socket, Boolean>());
	private final Set<Socket> bridgeClients = Collections.newSetFromMap(
			new ConcurrentHashMap<Socket, Boolean>());
	private final AtomicInteger streamClients = new AtomicInteger();
	private final byte[] indexPage;
	private volatile byte[] latestJpeg;
	private volatile long frameNumber;
	private volatile boolean running;
	private volatile boolean encryptedHttps;
	private volatile ServerSocket serverSocket;
	private volatile ServerSocket bridgeServerSocket;
	private volatile int port;
	private volatile int bridgePort;
	private Thread acceptThread;
	private Thread bridgeAcceptThread;
	private Thread encoderThread;
	private Bitmap pendingFrame;
	private Bitmap reusableFrame;
	private boolean encoderRunning;
	private volatile CommandHandler commandHandler;
	private volatile StateProvider stateProvider;
	private volatile VideoProvider videoProvider;
	private volatile SharedFileProvider sharedFileProvider;

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
	public void setSharedFileProvider(SharedFileProvider provider) { sharedFileProvider = provider; }

	public synchronized String start() throws IOException {
		return start(false, true);
	}

	/** Starts before a local address exists when the ESP bridge is still joining its SoftAP. */
	public synchronized String start(boolean allowNoLocalAddress) throws IOException {
		return start(allowNoLocalAddress, true);
	}

	/** Starts either a plain HTTP or TLS listener according to the user setting. */
	public synchronized String start(boolean allowNoLocalAddress, boolean encryptedHttps)
			throws IOException {
		if (running)
			return getUrl();
		if (!allowNoLocalAddress && getLocalIp() == null)
			throw new IOException("No local network address");
		SSLServerSocketFactory socketFactory = encryptedHttps ?
				WebViewTlsIdentity.createContext().getServerSocketFactory() : null;
		IOException lastError = null;
		for (int candidate = FIRST_PORT; candidate <= LAST_PORT; ++candidate) {
			try {
				if (encryptedHttps) {
					SSLServerSocket secureSocket = (SSLServerSocket)
							socketFactory.createServerSocket(candidate, 8,
									InetAddress.getByName("0.0.0.0"));
					WebViewTlsIdentity.configure(secureSocket);
					serverSocket = secureSocket;
				} else {
					serverSocket = new ServerSocket(candidate, 8,
							InetAddress.getByName("0.0.0.0"));
				}
				port = candidate;
				break;
			} catch (IOException e) {
				lastError = e;
			}
		}
		if (serverSocket == null)
			throw lastError == null ? new IOException("Unable to bind web server") : lastError;
		this.encryptedHttps = encryptedHttps;
		if (allowNoLocalAddress) {
			try {
				openBridgeListener();
			} catch (IOException e) {
				try { serverSocket.close(); } catch (IOException ignored) { }
				serverSocket = null;
				port = 0;
				throw e;
			}
		}
		running = true;
		encoderRunning = true;
		encoderThread = new Thread(this::encodeLoop, "InfiCam web encoder");
		encoderThread.setDaemon(true);
		encoderThread.start();
		ServerSocket directListener = serverSocket;
		acceptThread = new Thread(() -> acceptLoop(directListener, false),
				"InfiCam " + (encryptedHttps ? "HTTPS" : "HTTP") + " server");
		acceptThread.setDaemon(true);
		acceptThread.start();
		if (bridgeServerSocket != null)
			startBridgeAcceptThread();
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
		ServerSocket bridge = bridgeServerSocket;
		bridgeServerSocket = null;
		bridgePort = 0;
		if (bridge != null) {
			try { bridge.close(); } catch (IOException ignored) { }
		}
		/* SSLSocket.close() sends a TLS close_notify and is therefore a network
		 * operation. stop() is normally called by the Web Control button on the UI
		 * thread, where Android deliberately throws NetworkOnMainThreadException.
		 * Detach this session's sockets synchronously, then close them in the
		 * background. */
		detachAndCloseClientsAsync(clients);
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

	public int getPort() { return running ? port : 0; }

	/** Plain HTTP is exposed only to the ESP AP gateway; browser-facing traffic stays HTTPS. */
	public synchronized int setBridgeEnabled(boolean enabled) throws IOException {
		if (!running)
			return 0;
		if (enabled) {
			if (bridgeServerSocket == null) {
				openBridgeListener();
				startBridgeAcceptThread();
			}
			return bridgePort;
		}
		ServerSocket listener = bridgeServerSocket;
		bridgeServerSocket = null;
		bridgePort = 0;
		if (listener != null)
			try { listener.close(); } catch (IOException ignored) { }
		detachAndCloseClientsAsync(bridgeClients);
		return 0;
	}

	public int getBridgePort() { return running ? bridgePort : 0; }

	/** Drop a stale camera image while preserving connected browser sessions. */
	public void resetFrames() {
		synchronized (frameLock) {
			latestJpeg = null;
			frameNumber++;
			frameLock.notifyAll();
		}
	}

	public String getUrl() {
		String ip = getLocalIp();
		return (encryptedHttps ? "https://" : "http://") +
				(ip == null ? "127.0.0.1" : ip) + ":" + port;
	}

	public boolean isEncryptedHttps() { return encryptedHttps; }

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
		/* Reuse the compression buffer. At 25 FPS allocating both a stream and its
		 * backing array for every frame creates avoidable GC pauses on the phone. */
		ByteArrayOutputStream out = new ByteArrayOutputStream(48 * 1024);
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
				out.reset();
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

	private void openBridgeListener() throws IOException {
		ServerSocket listener = new ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"));
		bridgeServerSocket = listener;
		bridgePort = listener.getLocalPort();
	}

	private void startBridgeAcceptThread() {
		ServerSocket listener = bridgeServerSocket;
		if (listener == null)
			return;
		bridgeAcceptThread = new Thread(() -> acceptLoop(listener, true),
				"InfiCam ESP backend");
		bridgeAcceptThread.setDaemon(true);
		bridgeAcceptThread.start();
	}

	private void acceptLoop(ServerSocket listener, boolean bridge) {
		while (isCurrentListener(listener, bridge)) {
			try {
				Socket socket = listener.accept();
				/* A previous accept thread can wake while stop/start is replacing its
				 * listener. Never attach that stale connection to the new session. */
				if (!isCurrentListener(listener, bridge)) {
					try { socket.close(); } catch (IOException ignored) { }
					break;
				}
				if (bridge && !isEspBridgePeer(socket)) {
					/* The backend is intentionally HTTP to avoid nested TLS on the ESP link,
					 * but it is never available to ordinary LAN peers. */
					try { socket.close(); } catch (IOException ignored) { }
					continue;
				}
				clients.add(socket);
				if (bridge)
					bridgeClients.add(socket);
				Thread client = new Thread(() -> serve(socket), "InfiCam web client");
				client.setDaemon(true);
				client.start();
			} catch (IOException e) {
				if (running && !listener.isClosed())
					continue;
				break;
			}
		}
	}

	private boolean isCurrentListener(ServerSocket listener, boolean bridge) {
		return running && !listener.isClosed() &&
				listener == (bridge ? bridgeServerSocket : serverSocket);
	}

	/** Removes sockets from the active session before asynchronously closing them. */
	private void detachAndCloseClientsAsync(Set<Socket> sockets) {
		Socket[] stale = sockets.toArray(new Socket[0]);
		if (stale.length == 0)
			return;
		for (Socket socket : stale) {
			clients.remove(socket);
			bridgeClients.remove(socket);
		}
		Thread closer = new Thread(() -> {
			for (Socket socket : stale) {
				try { socket.close(); }
				catch (IOException | RuntimeException ignored) { }
			}
		}, "InfiCam web connection closer");
		closer.setDaemon(true);
		closer.start();
	}

	private void serve(Socket socket) {
		try {
			socket.setSoTimeout(15000);
			socket.setTcpNoDelay(true);
			BufferedReader reader = new BufferedReader(new InputStreamReader(
					socket.getInputStream(), StandardCharsets.US_ASCII));
			while (running && !socket.isClosed()) {
				String request = reader.readLine();
				if (request == null)
					return;
				if (request.isEmpty())
					continue;
				String[] parts = request.split(" ");
				if (parts.length < 2)
					return;
				boolean headOnly = "HEAD".equals(parts[0]);
				String path = parts[1];
				boolean keepAlive = parts.length > 2 && "HTTP/1.1".equals(parts[2]);
				String header;
				while ((header = reader.readLine()) != null && !header.isEmpty()) {
					if ("Connection: close".equalsIgnoreCase(header))
						keepAlive = false;
					else if ("Connection: keep-alive".equalsIgnoreCase(header))
						keepAlive = true;
				}
				if (header == null)
					return;

				if (path.startsWith("/control")) {
					handleControl(path);
					writeText(socket, "OK", keepAlive);
				} else if (path.startsWith("/state")) {
					serveState(socket, path, headOnly, keepAlive);
				} else if (path.startsWith("/chart-video")) {
					serveVideo(socket, true, headOnly);
					return;
				} else if (path.startsWith("/video")) {
					serveVideo(socket, false, headOnly);
					return;
				} else if (path.startsWith("/shared-file")) {
					serveSharedFile(socket, path, headOnly);
					return;
				} else if (path.startsWith("/stream")) {
					if (headOnly) {
						writeHeaders(socket.getOutputStream(), "200 OK",
								"multipart/x-mixed-replace; boundary=frame", -1, false);
						return;
					}
					serveStream(socket);
					return;
				} else {
					serveIndex(socket, keepAlive);
				}
				if (!keepAlive)
					return;
			}
		} catch (IOException ignored) {
		} finally {
			clients.remove(socket);
			bridgeClients.remove(socket);
			try { socket.close(); } catch (IOException ignored) { }
		}
	}

	private static boolean isEspBridgePeer(Socket socket) {
		byte[] address = socket.getInetAddress().getAddress();
		return address.length == 4 && (address[0] & 0xff) == 192 &&
				(address[1] & 0xff) == 168 && (address[2] & 0xff) == 8 &&
				(address[3] & 0xff) == 1;
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

	private void writeText(Socket socket, String text, boolean keepAlive) throws IOException {
		byte[] body = text.getBytes(StandardCharsets.UTF_8);
		OutputStream out = socket.getOutputStream();
		writeHeaders(out, "200 OK", "text/plain; charset=utf-8", body.length, keepAlive);
		out.write(body);
		out.flush();
	}

	private void serveState(Socket socket, String path, boolean headOnly,
			boolean keepAlive) throws IOException {
		StateProvider provider = stateProvider;
		if (provider == null) {
			writeText(socket, "No state provider", keepAlive);
			return;
		}
		long generation = queryLong(path, "generation", -1L);
		long requestedFrom = queryLong(path, "from", 0L);
		int from = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, requestedFrom));
		byte[] body = provider.getState(generation, from).getBytes(StandardCharsets.UTF_8);
		OutputStream out = socket.getOutputStream();
		writeHeaders(out, "200 OK", "application/json; charset=utf-8", body.length,
				keepAlive);
		if (!headOnly)
			out.write(body);
		out.flush();
	}

	private void serveVideo(Socket socket, boolean chart, boolean headOnly) throws IOException {
		VideoProvider provider = videoProvider;
		VideoData video = provider == null ? null : provider.open(chart);
		if (video == null) {
			byte[] message = "No video ready".getBytes(StandardCharsets.US_ASCII);
			writeHeaders(socket.getOutputStream(), "404 Not Found", "text/plain",
					message.length, false);
			if (!headOnly)
				socket.getOutputStream().write(message);
			return;
		}
		try (VideoData source = video) {
			OutputStream out = socket.getOutputStream();
			writeHeaders(out, "200 OK", "video/mp4", source.length, false);
			if (!headOnly) {
				byte[] buffer = new byte[64 * 1024];
				int count;
				while ((count = source.input.read(buffer)) != -1)
					out.write(buffer, 0, count);
			}
			out.flush();
		}
	}

	private void serveSharedFile(Socket socket, String path, boolean headOnly) throws IOException {
		SharedFileProvider provider = sharedFileProvider;
		long id = queryLong(path, "id", -1L);
		SharedFileData file = provider == null || id < 0 ? null : provider.open(id);
		if (file == null) {
			byte[] message = "Shared file is no longer available"
					.getBytes(StandardCharsets.UTF_8);
			writeHeaders(socket.getOutputStream(), "404 Not Found",
					"text/plain; charset=utf-8", message.length, false);
			if (!headOnly) socket.getOutputStream().write(message);
			return;
		}
		boolean completed = false;
		try (SharedFileData source = file) {
			OutputStream out = socket.getOutputStream();
			writeDownloadHeaders(out, source.mimeType, source.length, source.name);
			if (!headOnly) {
				byte[] buffer = new byte[64 * 1024];
				int count;
				while ((count = source.input.read(buffer)) != -1)
					out.write(buffer, 0, count);
				out.flush();
				completed = true;
			}
		} finally {
			/* A failed/interrupted transfer remains queued so the browser can retry. */
			if (completed && provider != null) provider.complete(file.id);
		}
	}

	private void serveIndex(Socket socket, boolean keepAlive) throws IOException {
		OutputStream out = socket.getOutputStream();
		writeHeaders(out, "200 OK", "text/html; charset=utf-8", indexPage.length,
				keepAlive);
		out.write(indexPage);
		out.flush();
	}

	private void serveStream(Socket socket) throws IOException {
		streamClients.incrementAndGet();
		try {
			socket.setSoTimeout(0);
			socket.setTcpNoDelay(true);
			/* Buffer the boundary, JPEG and trailing CRLF into one socket write. */
			OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
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

	private static void writeHeaders(OutputStream out, String status, String type, long length,
			boolean keepAlive)
			throws IOException {
		String lengthHeader = length >= 0 ? "Content-Length: " + length + "\r\n" : "";
		out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\n" +
				lengthHeader + "Connection: " + (keepAlive ? "keep-alive" : "close") +
				"\r\n\r\n")
				.getBytes(StandardCharsets.US_ASCII));
	}

	private static void writeDownloadHeaders(OutputStream out, String type, long length,
			String filename) throws IOException {
		String disposition = "attachment; filename=\"" + asciiFilename(filename) +
				"\"; filename*=UTF-8''" + rfc5987(filename);
		out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\n" +
				"Content-Length: " + length + "\r\nContent-Disposition: " + disposition +
				"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
				.getBytes(StandardCharsets.US_ASCII));
	}

	private static String asciiFilename(String value) {
		StringBuilder result = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); ++i) {
			char c = value.charAt(i);
			result.append(c >= 32 && c < 127 && c != '"' && c != '\\' ? c : '_');
		}
		return result.length() == 0 ? "shared-file" : result.toString();
	}

	private static String rfc5987(String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		StringBuilder result = new StringBuilder(bytes.length);
		final char[] hex = "0123456789ABCDEF".toCharArray();
		for (byte item : bytes) {
			int c = item & 0xff;
			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
					(c >= '0' && c <= '9') || "!#$&+-.^_`|~".indexOf(c) >= 0) {
				result.append((char)c);
			} else {
				result.append('%').append(hex[c >>> 4]).append(hex[c & 15]);
			}
		}
		return result.toString();
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
