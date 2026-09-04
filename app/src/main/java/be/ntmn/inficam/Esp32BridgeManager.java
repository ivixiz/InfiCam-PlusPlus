package be.ntmn.inficam;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the optional local-only Wi-Fi connection to the InfiCam ESP32-S3 bridge.
 *
 * The process is deliberately not bound to this network: only the registration socket uses the
 * ESP network's SocketFactory. This keeps Android internet access and the existing WebViewServer
 * behaviour independent from the bridge.
 */
public final class Esp32BridgeManager implements AutoCloseable {
	public static final String SSID = "InfiCamBridge";
	public static final String PASSWORD = "5KfHSF21";
	public static final String ESP_WIFI_ADDRESS = "192.168.8.1";
	public static final int REGISTRATION_PORT = 7777;
	public static final String PUBLIC_URL = "http://192.168.7.1";

	public enum State { DISCONNECTED, CONNECTING, REGISTERING, CONNECTED, ERROR }

	public interface Listener {
		void onStateChanged(State state, String detail);
	}

	private static final String TAG = "InfiCamEspBridge";
	private static final long RETRY_DELAY_MS = 1500;
	private static final long KEEPALIVE_DELAY_MS = 2000;
	private static final long NETWORK_RETRY_DELAY_MS = 10000;

	private final Context context;
	private final ConnectivityManager connectivityManager;
	private final WifiManager wifiManager;
	private final Listener listener;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "InfiCam ESP registration");
		thread.setDaemon(true);
		return thread;
	});
	private final Object lock = new Object();

	private volatile boolean started;
	private volatile boolean closed;
	private volatile int serverPort;
	private volatile Network activeNetwork;
	private volatile Socket registrationSocket;
	private BridgeNetworkCallback networkCallback;
	private boolean registrationWorkerRunning;
	private State lastState;
	private String lastDetail;

	public Esp32BridgeManager(Context context, Listener listener) {
		this.context = context.getApplicationContext();
		this.connectivityManager = (ConnectivityManager)
				this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
		this.wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
		this.listener = listener;
	}

	public void start() {
		synchronized (lock) {
			if (closed || started)
				return;
			started = true;
		}
		notifyState(State.CONNECTING, "Searching for " + SSID);
		try {
			requestBridgeNetwork();
		} catch (RuntimeException e) {
			Log.w(TAG, "Unable to request ESP bridge network", e);
			notifyState(State.ERROR, "ESP Wi-Fi request failed; retrying");
			scheduleNetworkRetry();
		}
	}

	public void stop() {
		BridgeNetworkCallback callback;
		synchronized (lock) {
			if (!started && networkCallback == null)
				return;
			started = false;
			activeNetwork = null;
			callback = networkCallback;
			networkCallback = null;
			closeRegistrationSocketLocked();
		}
		mainHandler.removeCallbacksAndMessages(null);
		if (callback != null) {
			try {
				connectivityManager.unregisterNetworkCallback(callback);
			} catch (IllegalArgumentException ignored) { }
		}
		notifyState(State.DISCONNECTED, null);
	}

	/** A zero port suspends registration while retaining the Wi-Fi network request. */
	public void setWebServerPort(int port) {
		serverPort = port > 0 && port <= 65535 ? port : 0;
		synchronized (lock) {
			closeRegistrationSocketLocked();
			if (started && activeNetwork != null && serverPort != 0)
				startRegistrationWorkerLocked();
		}
	}

	private void requestBridgeNetwork() {
		final NetworkRequest request;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
					.setSsid(SSID)
					.setWpa2Passphrase(PASSWORD)
					.build();
			request = new NetworkRequest.Builder()
					.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
					.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
					.setNetworkSpecifier(specifier)
					.build();
		} else {
			connectLegacyWifi();
			request = new NetworkRequest.Builder()
					.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
					.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
					.build();
		}

		BridgeNetworkCallback callback = new BridgeNetworkCallback();
		synchronized (lock) {
			if (!started || closed || networkCallback != null)
				return;
			networkCallback = callback;
		}
		try {
			connectivityManager.requestNetwork(request, callback);
		} catch (RuntimeException e) {
			synchronized (lock) {
				if (networkCallback == callback)
					networkCallback = null;
			}
			throw e;
		}
	}

	private final class BridgeNetworkCallback extends ConnectivityManager.NetworkCallback {
		@Override public void onAvailable(Network network) {
			boolean register;
			synchronized (lock) {
				if (!started || networkCallback != this)
					return;
				activeNetwork = network;
				closeRegistrationSocketLocked();
				register = serverPort != 0;
			}
			notifyState(register ? State.REGISTERING : State.CONNECTING,
					register ? "Registering Web Control" : "ESP Wi-Fi connected");
			if (register) synchronized (lock) {
				if (started && activeNetwork == network && serverPort != 0)
					startRegistrationWorkerLocked();
			}
		}

		@Override public void onLost(Network network) {
			synchronized (lock) {
				if (networkCallback != this || !network.equals(activeNetwork))
					return;
				activeNetwork = null;
				closeRegistrationSocketLocked();
			}
			notifyState(State.CONNECTING, "ESP Wi-Fi connection lost; reconnecting");
			scheduleLostNetworkRecovery(this);
		}

		@Override public void onUnavailable() {
			synchronized (lock) {
				if (networkCallback != this)
					return;
				networkCallback = null;
				activeNetwork = null;
				closeRegistrationSocketLocked();
			}
			notifyState(State.ERROR,
					"InfiCam ESP32 access point is unavailable; retrying");
			scheduleNetworkRetry();
		}
	}

	/**
	 * Most Android versions keep an unsatisfied request alive after onLost(). The watchdog also
	 * covers vendor implementations that do not: it replaces the request only if no replacement
	 * network has appeared by the retry deadline.
	 */
	private void scheduleLostNetworkRecovery(BridgeNetworkCallback callback) {
		mainHandler.postDelayed(() -> {
			synchronized (lock) {
				if (!started || closed || networkCallback != callback || activeNetwork != null)
					return;
				networkCallback = null;
			}
			try {
				connectivityManager.unregisterNetworkCallback(callback);
			} catch (IllegalArgumentException ignored) { }
			notifyState(State.CONNECTING, "Searching for " + SSID);
			try {
				requestBridgeNetwork();
			} catch (RuntimeException e) {
				Log.w(TAG, "Unable to recover ESP bridge network", e);
				notifyState(State.ERROR, "ESP Wi-Fi request failed; retrying");
				scheduleNetworkRetry();
			}
		}, NETWORK_RETRY_DELAY_MS);
	}

	private void scheduleNetworkRetry() {
		mainHandler.postDelayed(() -> {
			synchronized (lock) {
				if (!started || closed || networkCallback != null)
					return;
			}
			notifyState(State.CONNECTING, "Searching for " + SSID);
			try {
				requestBridgeNetwork();
			} catch (RuntimeException e) {
				Log.w(TAG, "Unable to retry ESP bridge network", e);
				notifyState(State.ERROR, "ESP Wi-Fi request failed; retrying");
				scheduleNetworkRetry();
			}
		}, NETWORK_RETRY_DELAY_MS);
	}

	@SuppressWarnings("deprecation")
	@SuppressLint("MissingPermission") // API < 29 path; manifest grants Wi-Fi state access.
	private void connectLegacyWifi() {
		if (wifiManager == null)
			throw new IllegalStateException("Wi-Fi service unavailable");
		WifiConfiguration config = new WifiConfiguration();
		config.SSID = quote(SSID);
		config.preSharedKey = quote(PASSWORD);
		int networkId = wifiManager.addNetwork(config);
		if (networkId < 0) {
			for (WifiConfiguration saved : wifiManager.getConfiguredNetworks()) {
				if (quote(SSID).equals(saved.SSID)) {
					networkId = saved.networkId;
					break;
				}
			}
		}
		if (networkId < 0 || !wifiManager.enableNetwork(networkId, true))
			throw new IllegalStateException("Unable to enable ESP Wi-Fi network");
		wifiManager.reconnect();
	}

	private static String quote(String value) { return "\"" + value + "\""; }

	private void startRegistrationWorkerLocked() {
		if (registrationWorkerRunning || closed)
			return;
		registrationWorkerRunning = true;
		worker.execute(this::registrationLoop);
	}

	private void registrationLoop() {
		try {
			while (!closed) {
				Network network = activeNetwork;
				int port = serverPort;
				if (!started || network == null || port == 0)
					return;
				notifyState(State.REGISTERING, "Registering Web Control");
				try (Socket socket = network.getSocketFactory().createSocket()) {
					registrationSocket = socket;
					socket.connect(new InetSocketAddress(ESP_WIFI_ADDRESS,
							REGISTRATION_PORT), 3000);
					socket.setSoTimeout(5000);
					socket.setKeepAlive(true);
					BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
							socket.getOutputStream(), StandardCharsets.US_ASCII));
					BufferedReader input = new BufferedReader(new InputStreamReader(
							socket.getInputStream(), StandardCharsets.US_ASCII));
					output.write("REGISTER 1 " + port + "\n");
					output.flush();
					String response = input.readLine();
					if (response == null || !response.startsWith("OK "))
						throw new IOException("ESP rejected registration");
					notifyState(State.CONNECTED, PUBLIC_URL);
					while (started && activeNetwork == network && serverPort == port) {
						Thread.sleep(KEEPALIVE_DELAY_MS);
						output.write("PING\n");
						output.flush();
						if (!"PONG".equals(input.readLine()))
							throw new IOException("ESP keepalive failed");
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				} catch (IOException e) {
					if (started && activeNetwork == network && serverPort == port) {
						Log.d(TAG, "Registration retry", e);
						notifyState(State.REGISTERING, "ESP link interrupted; reconnecting");
						try { Thread.sleep(RETRY_DELAY_MS); }
						catch (InterruptedException interrupted) {
							Thread.currentThread().interrupt();
							return;
						}
					} else return;
				} finally {
					synchronized (lock) {
						if (registrationSocket != null && registrationSocket.isClosed())
							registrationSocket = null;
					}
				}
			}
		} finally {
			synchronized (lock) {
				registrationWorkerRunning = false;
				registrationSocket = null;
				if (!closed && started && activeNetwork != null && serverPort != 0)
					startRegistrationWorkerLocked();
			}
		}
	}

	private void closeRegistrationSocketLocked() {
		Socket socket = registrationSocket;
		registrationSocket = null;
		if (socket != null) try { socket.close(); } catch (IOException ignored) { }
	}

	private void notifyState(State state, String detail) {
		synchronized (lock) {
			if (state == lastState && (detail == null ? lastDetail == null : detail.equals(lastDetail)))
				return;
			lastState = state;
			lastDetail = detail;
		}
		if (listener != null)
			listener.onStateChanged(state, detail);
	}

	@Override public void close() {
		closed = true;
		stop();
		worker.shutdownNow();
	}
}
