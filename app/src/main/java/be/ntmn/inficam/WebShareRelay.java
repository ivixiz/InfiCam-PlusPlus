package be.ntmn.inficam;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stages Android ACTION_SEND content for streaming to Web Control.
 *
 * Content URIs are copied immediately because their temporary read permission may disappear when
 * the sharing activity changes state. Files are never loaded into the Java heap.
 */
final class WebShareRelay implements AutoCloseable {
	interface Listener {
		void onReady(String name);
		void onFailure(String name, Exception error);
	}

	private static final int MAX_READY_FILES = 8;
	private static final long MAX_AGE_MS = 30L * 60L * 1000L;
	private final ContentResolver resolver;
	private final File directory;
	private final Listener listener;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "InfiCam shared file staging");
		thread.setDaemon(true);
		return thread;
	});
	private final AtomicLong nextId = new AtomicLong(System.currentTimeMillis());
	private final Object lock = new Object();
	private final LinkedHashMap<Long, Entry> ready = new LinkedHashMap<>();
	private volatile boolean closed;

	private static final class Entry {
		final long id;
		final long createdAt;
		final String name;
		final String mimeType;
		final File file;
		final long length;

		Entry(long id, String name, String mimeType, File file, long length) {
			this.id = id;
			this.createdAt = System.currentTimeMillis();
			this.name = name;
			this.mimeType = mimeType;
			this.file = file;
			this.length = length;
		}
	}

	WebShareRelay(Context context, Listener listener) {
		Context appContext = context.getApplicationContext();
		resolver = appContext.getContentResolver();
		directory = new File(appContext.getCacheDir(), "web-shares");
		this.listener = listener;
		if (!directory.exists() && !directory.mkdirs())
			throw new IllegalStateException("Unable to create Web Control share cache");
		File[] leftovers = directory.listFiles();
		if (leftovers != null)
			for (File file : leftovers) // A previous process cannot have a valid pending browser.
				if (!file.delete()) file.deleteOnExit();
	}

	/** Returns the number of share items accepted from this intent. */
	int enqueue(Intent intent) {
		if (closed || intent == null)
			return 0;
		String action = intent.getAction();
		if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action))
			return 0;

		Set<Uri> uris = new LinkedHashSet<>();
		if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
			ArrayList<Uri> values = getStreamList(intent);
			if (values != null) uris.addAll(values);
		} else {
			Uri value = getStream(intent);
			if (value != null) uris.add(value);
		}
		ClipData clip = intent.getClipData();
		if (clip != null)
			for (int i = 0; i < clip.getItemCount(); ++i) {
				Uri uri = clip.getItemAt(i).getUri();
				if (uri != null) uris.add(uri);
			}

		String fallbackType = safeMimeType(intent.getType());
		for (Uri uri : uris)
			worker.execute(() -> stageUri(uri, fallbackType));

		if (uris.isEmpty()) {
			CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
			if (text != null) {
				worker.execute(() -> stageText(text.toString()));
				return 1;
			}
		}
		return uris.size();
	}

	@SuppressWarnings("deprecation")
	private static Uri getStream(Intent intent) {
		return intent.getParcelableExtra(Intent.EXTRA_STREAM);
	}

	@SuppressWarnings("deprecation")
	private static ArrayList<Uri> getStreamList(Intent intent) {
		return intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
	}

	private void stageUri(Uri uri, String fallbackType) {
		String name = queryName(uri);
		String type = fallbackType;
		try { type = safeMimeType(resolver.getType(uri)); }
		catch (RuntimeException ignored) { }
		if ("application/octet-stream".equals(type)) type = fallbackType;
		File temporary = null;
		try {
			temporary = File.createTempFile("incoming-", ".part", directory);
			try (InputStream input = new BufferedInputStream(open(uri), 64 * 1024);
				 BufferedOutputStream output = new BufferedOutputStream(
						 new FileOutputStream(temporary), 64 * 1024)) {
				copy(input, output);
			}
			commit(temporary, name, type);
			temporary = null;
		} catch (Exception error) {
			if (listener != null) listener.onFailure(name, error);
		} finally {
			if (temporary != null && !temporary.delete()) temporary.deleteOnExit();
		}
	}

	private void stageText(String text) {
		String name = "shared-text-" + System.currentTimeMillis() + ".txt";
		File temporary = null;
		try {
			temporary = File.createTempFile("incoming-", ".part", directory);
			try (FileOutputStream output = new FileOutputStream(temporary)) {
				output.write(text.getBytes(StandardCharsets.UTF_8));
			}
			commit(temporary, name, "text/plain; charset=utf-8");
			temporary = null;
		} catch (Exception error) {
			if (listener != null) listener.onFailure(name, error);
		} finally {
			if (temporary != null && !temporary.delete()) temporary.deleteOnExit();
		}
	}

	private InputStream open(Uri uri) throws IOException {
		InputStream stream = resolver.openInputStream(uri);
		if (stream == null) throw new IOException("Unable to open shared URI");
		return stream;
	}

	private void copy(InputStream input, BufferedOutputStream output) throws IOException {
		byte[] buffer = new byte[64 * 1024];
		int count;
		while (!closed && (count = input.read(buffer)) != -1)
			output.write(buffer, 0, count);
		if (closed) throw new IOException("Share relay stopped");
	}

	private void commit(File temporary, String name, String type) throws IOException {
		if (closed) throw new IOException("Share relay stopped");
		long id = nextId.incrementAndGet();
		File stored = new File(directory, "share-" + id);
		if (!temporary.renameTo(stored))
			throw new IOException("Unable to commit shared file");
		Entry entry = new Entry(id, sanitizeName(name), safeMimeType(type), stored, stored.length());
		synchronized (lock) {
			cleanupLocked(System.currentTimeMillis());
			ready.put(id, entry);
			while (ready.size() > MAX_READY_FILES) {
				Map.Entry<Long, Entry> oldest = ready.entrySet().iterator().next();
				ready.remove(oldest.getKey());
				delete(oldest.getValue().file);
			}
		}
		if (listener != null) listener.onReady(entry.name);
	}

	void appendPendingJson(StringBuilder json) {
		synchronized (lock) {
			cleanupLocked(System.currentTimeMillis());
			json.append('[');
			boolean first = true;
			for (Entry entry : ready.values()) {
				if (!first) json.append(',');
				first = false;
				json.append("{\"id\":").append(entry.id).append(",\"name\":");
				appendJsonString(json, entry.name);
				json.append(",\"type\":");
				appendJsonString(json, entry.mimeType);
				json.append(",\"size\":").append(entry.length).append('}');
			}
			json.append(']');
		}
	}

	WebViewServer.SharedFileData open(long id) throws IOException {
		Entry entry;
		synchronized (lock) {
			cleanupLocked(System.currentTimeMillis());
			entry = ready.get(id);
		}
		if (entry == null || !entry.file.isFile()) return null;
		return new WebViewServer.SharedFileData(new FileInputStream(entry.file), entry.length,
				entry.mimeType, entry.name, entry.id);
	}

	void complete(long id) {
		Entry entry;
		synchronized (lock) { entry = ready.remove(id); }
		if (entry != null) delete(entry.file);
	}

	private void cleanupLocked(long now) {
		java.util.Iterator<Map.Entry<Long, Entry>> iterator = ready.entrySet().iterator();
		while (iterator.hasNext()) {
			Entry entry = iterator.next().getValue();
			if (now - entry.createdAt <= MAX_AGE_MS && entry.file.isFile()) continue;
			iterator.remove();
			delete(entry.file);
		}
	}

	private String queryName(Uri uri) {
		try (Cursor cursor = resolver.query(uri,
				new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
			if (cursor != null && cursor.moveToFirst()) {
				String value = cursor.getString(0);
				if (value != null && !value.trim().isEmpty()) return value;
			}
		} catch (RuntimeException ignored) { }
		String segment = uri.getLastPathSegment();
		return segment == null || segment.isEmpty() ? "shared-file" : segment;
	}

	private static String sanitizeName(String value) {
		if (value == null) return "shared-file";
		StringBuilder result = new StringBuilder(Math.min(value.length(), 180));
		for (int i = 0; i < value.length() && result.length() < 180; ++i) {
			char c = value.charAt(i);
			result.append(c < 32 || c == 127 || c == '/' || c == '\\' ? '_' : c);
		}
		String name = result.toString().trim();
		return name.isEmpty() ? "shared-file" : name;
	}

	private static String safeMimeType(String value) {
		if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
			return "application/octet-stream";
		String type = value.trim();
		return type.isEmpty() ? "application/octet-stream" : type;
	}

	private static void appendJsonString(StringBuilder json, String value) {
		json.append('"');
		for (int i = 0; i < value.length(); ++i) {
			char c = value.charAt(i);
			switch (c) {
				case '"': json.append("\\\""); break;
				case '\\': json.append("\\\\"); break;
				case '\b': json.append("\\b"); break;
				case '\f': json.append("\\f"); break;
				case '\n': json.append("\\n"); break;
				case '\r': json.append("\\r"); break;
				case '\t': json.append("\\t"); break;
				default:
					if (c < 32) json.append(String.format("\\u%04x", (int)c));
					else json.append(c);
			}
		}
		json.append('"');
	}

	private static void delete(File file) {
		if (file != null && file.exists() && !file.delete()) file.deleteOnExit();
	}

	@Override
	public void close() {
		closed = true;
		worker.shutdownNow();
		synchronized (lock) {
			for (Entry entry : ready.values()) delete(entry.file);
			ready.clear();
		}
	}
}
