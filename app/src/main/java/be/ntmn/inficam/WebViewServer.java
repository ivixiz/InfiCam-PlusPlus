package be.ntmn.inficam;

import android.graphics.Bitmap;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** A small dependency-free MJPEG server for the current thermal view. */
public final class WebViewServer {
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

	public synchronized String start() throws IOException {
		if (running)
			return getUrl();
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
		return "http://" + getLocalIp() + ":" + port;
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
			if (path.startsWith("/stream"))
				serveStream(socket);
			else
				serveIndex(socket);
		} catch (IOException ignored) {
		} finally {
			clients.remove(socket);
			try { socket.close(); } catch (IOException ignored) { }
		}
	}

	private void serveIndex(Socket socket) throws IOException {
		byte[] body = ("<!doctype html><html><head><meta name=viewport " +
				"content=width=device-width,initial-scale=1><title>InfiCam</title></head>" +
				"<body style='margin:0;background:#111;color:#eee;text-align:center'>" +
				"<h3>InfiCam Web View</h3><img src='/stream' " +
				"style='max-width:100%;height:auto' /></body></html>")
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
			for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!network.isUp() || network.isLoopback())
					continue;
				for (InetAddress address : Collections.list(network.getInetAddresses())) {
					if (address instanceof Inet4Address && !address.isLoopbackAddress())
						return address.getHostAddress();
				}
			}
		} catch (SocketException ignored) { }
		return "127.0.0.1";
	}
}
