package be.ntmn.inficam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
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

/** A small dependency-free MJPEG server for the current thermal view. */
public final class WebViewServer {
	public interface CommandHandler {
		void onCommand(String command, String value);
	}
	private static final int FIRST_PORT = 8080;
	private static final int LAST_PORT = 8090;
	private final Object frameLock = new Object();
	private final Set<Socket> clients = Collections.newSetFromMap(
			new ConcurrentHashMap<Socket, Boolean>());
	private volatile byte[] latestJpeg;
	private volatile long frameNumber;
	private volatile boolean running;
	private volatile ServerSocket serverSocket;
	private volatile int port;
	private Thread acceptThread;
	private volatile CommandHandler commandHandler;
	private volatile byte[] latestSnapshot;
	private volatile String snapshotMime = "image/jpeg";
	private volatile int snapshotType = Util.IMGTYPE_JPEG;
	private volatile int snapshotQuality = 92;
	private volatile byte[] latestVideo;

	public void setCommandHandler(CommandHandler handler) {
		commandHandler = handler;
	}

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
		acceptThread = new Thread(this::acceptLoop, "InfiCam web server");
		acceptThread.setDaemon(true);
		acceptThread.start();
		return getUrl();
	}

	public synchronized void stop() {
		running = false;
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
	}

	public boolean isRunning() {
		return running;
	}

	public String getUrl() {
		String ip = getLocalIp();
		return "http://" + (ip == null ? "127.0.0.1" : ip) + ":" + port;
	}

	/** Compresses a copy of the displayed frame; the resulting bytes are immutable. */
	public void publish(Bitmap bitmap) {
		if (!running || bitmap == null)
			return;
		ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
		if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out))
			return;
		byte[] jpeg = out.toByteArray();
		synchronized (frameLock) {
			latestJpeg = jpeg;
			frameNumber++;
			frameLock.notifyAll();
		}
	}

	/** Publishes the browser download image in the format selected in app settings. */
	public void publishSnapshot(Bitmap bitmap, int type, int quality) {
		if (bitmap == null)
			return;
		Bitmap source = bitmap;
		try {
			if (type == Util.IMGTYPE_PNG565) {
				source = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.RGB_565);
				android.graphics.Canvas canvas = new android.graphics.Canvas(source);
				canvas.drawBitmap(bitmap, 0, 0, null);
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
			android.graphics.Bitmap.CompressFormat format = type == Util.IMGTYPE_JPEG
					? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG;
			if (source.compress(format, quality, out)) {
				latestSnapshot = out.toByteArray();
				snapshotMime = format == Bitmap.CompressFormat.JPEG ? "image/jpeg" : "image/png";
			}
		} finally {
			if (source != bitmap)
				source.recycle();
		}
	}

	public void publishVideo(byte[] video) {
		latestVideo = video;
	}

	public void setSnapshotFormat(int type, int quality) {
		snapshotType = type;
		snapshotQuality = quality;
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
			String path = parts.length > 1 ? parts[1] : "/";
			String header;
			while ((header = reader.readLine()) != null && !header.isEmpty()) { /* headers */ }
			if (path.startsWith("/control")) {
				handleControl(path);
				writeText(socket, "OK");
			} else if (path.startsWith("/snapshot")) {
				serveSnapshot(socket);
			} else if (path.startsWith("/video")) {
				serveVideo(socket);
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

	private void serveSnapshot(Socket socket) throws IOException {
		byte[] image = latestSnapshot;
		String mime = snapshotMime;
		if (image == null && latestJpeg != null) {
			if (snapshotType == Util.IMGTYPE_JPEG) {
				image = latestJpeg;
				mime = "image/jpeg";
			} else {
				Bitmap decoded = BitmapFactory.decodeByteArray(latestJpeg, 0, latestJpeg.length);
				if (decoded != null) {
					Bitmap source = decoded;
					try {
						if (snapshotType == Util.IMGTYPE_PNG565) {
							source = Bitmap.createBitmap(decoded.getWidth(), decoded.getHeight(),
									Bitmap.Config.RGB_565);
							new Canvas(source).drawBitmap(decoded, 0, 0, null);
						}
						ByteArrayOutputStream encoded = new ByteArrayOutputStream(64 * 1024);
						source.compress(Bitmap.CompressFormat.PNG, snapshotQuality, encoded);
						image = encoded.toByteArray();
						mime = "image/png";
					} finally {
						if (source != decoded) source.recycle();
						decoded.recycle();
					}
				}
			}
		}
		if (image == null) {
			writeText(socket, "No frame yet");
			return;
		}
		OutputStream out = socket.getOutputStream();
		writeHeaders(out, "200 OK", mime, image.length);
		out.write(image);
		out.flush();
	}

	private void serveVideo(Socket socket) throws IOException {
		byte[] video = latestVideo;
		if (video == null) {
			writeHeaders(socket.getOutputStream(), "404 Not Found", "text/plain", 17);
			socket.getOutputStream().write("No video ready".getBytes(StandardCharsets.US_ASCII));
			return;
		}
		OutputStream out = socket.getOutputStream();
		writeHeaders(out, "200 OK", "video/mp4", video.length);
		out.write(video);
		out.flush();
	}

	private void serveIndex(Socket socket) throws IOException {
		byte[] body = ("<!doctype html><html><head><meta name=viewport " +
				"content=width=device-width,initial-scale=1><title>InfiCam</title></head>" +
				"<body style='margin:0;background:#111;color:#eee;text-align:center;font-family:sans-serif'>" +
				"<h3>InfiCam Web Control</h3><img id='live' src='/stream' " +
				"style='display:block;width:min(100%,1280px);height:auto;margin:0 auto' /><div style='display:flex;flex-wrap:nowrap;gap:6px;justify-content:center;align-items:center;padding:8px;overflow-x:auto'>" +
				"<button onclick=cmd('palette')>Palette</button>" +
				"<button onclick=cmd('mirror')>Mirror</button>" +
				"<button onclick=cmd('calibrate')>Calibrate</button>" +
				"<a href='/snapshot' download='inficam'><button>Save Picture</button></a>" +
				"<button id=rec onclick=record()>Record Video</button></div>" +
				"<script>function cmd(c){fetch('/control?cmd='+c)}let recording=false;function record(){" +
				"if(!recording){recording=true;document.getElementById('rec').textContent='Stop Recording';fetch('/control?cmd=record_start')}" +
				"else{recording=false;document.getElementById('rec').textContent='Saving MP4...';fetch('/control?cmd=record_stop').then(()=>{let n=0;let t=setInterval(()=>{fetch('/video',{method:'HEAD'}).then(r=>{if(r.ok){clearInterval(t);let a=document.createElement('a');a.href='/video?'+Date.now();a.download='inficam.mp4';a.click();document.getElementById('rec').textContent='Record Video'}});if(++n>30)clearInterval(t)},500)})}}" +
				"</script></body></html>")
				.getBytes(StandardCharsets.UTF_8);
		OutputStream out = socket.getOutputStream();
		writeHeaders(out, "200 OK", "text/html; charset=utf-8", body.length);
		out.write(body);
		out.flush();
	}

	private void serveStream(Socket socket) throws IOException {
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
	}

	private static void writeHeaders(OutputStream out, String status, String type, int length)
			throws IOException {
		out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type +
				"\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n")
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
