package be.ntmn.inficam;

import android.content.Context;
import android.util.AtomicFile;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;

/** Transactional, per-physical-camera offset-map persistence. */
final class SpatialCalibrationStore {
	private static final int MAGIC = 0x5346504e; // SFPN
	/* v2 profiles are acquired against a hardware-held internal shutter where
	 * supported.  Do not load v1 external-cover P2 profiles: their cover/air
	 * gradients can be mistaken for sensor FPN and survive correction. */
	private static final int VERSION = 2;

	static final class Profile {
		final float[] offsets;
		final long createdAtMs;

		Profile(float[] offsets, long createdAtMs) {
			this.offsets = offsets;
			this.createdAtMs = createdAtMs;
		}
	}

	private final File directory;

	SpatialCalibrationStore(Context context) {
		directory = new File(context.getNoBackupFilesDir(), "spatial_fpn");
	}

	Profile load(String deviceId, int width, int height) throws IOException {
		File file = profileFile(deviceId);
		if (!file.isFile())
			return null;
		AtomicFile atomicFile = new AtomicFile(file);
		try (FileInputStream input = atomicFile.openRead();
			 DataInputStream data = new DataInputStream(new BufferedInputStream(input))) {
			if (data.readInt() != MAGIC)
				throw new IOException("Unsupported spatial calibration profile.");
			if (data.readInt() != VERSION)
				return null;
			String storedDeviceId = data.readUTF();
			int storedWidth = data.readInt();
			int storedHeight = data.readInt();
			long createdAtMs = data.readLong();
			int count = data.readInt();
			if (!deviceId.equals(storedDeviceId) || storedWidth != width ||
					storedHeight != height || count != width * height)
				return null;
			float[] offsets = new float[count];
			CRC32 crc = new CRC32();
			for (int i = 0; i < count; ++i) {
				int bits = data.readInt();
				updateCrc(crc, bits);
				offsets[i] = Float.intBitsToFloat(bits);
				if (!Float.isFinite(offsets[i]))
					throw new IOException("Spatial calibration profile contains invalid data.");
			}
			if (data.readLong() != crc.getValue())
				throw new IOException("Spatial calibration profile checksum failed.");
			return new Profile(offsets, createdAtMs);
		}
	}

	void commit(String deviceId, int width, int height, float[] offsets) throws IOException {
		if (offsets == null || offsets.length != width * height)
			throw new IOException("Candidate offset-map dimensions are invalid.");
		for (float offset : offsets) {
			if (!Float.isFinite(offset))
				throw new IOException("Candidate offset map contains invalid data.");
		}
		if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
			throw new IOException("Unable to create calibration profile directory.");
		AtomicFile atomicFile = new AtomicFile(profileFile(deviceId));
		FileOutputStream output = null;
		try {
			output = atomicFile.startWrite();
			DataOutputStream data = new DataOutputStream(output);
			data.writeInt(MAGIC);
			data.writeInt(VERSION);
			data.writeUTF(deviceId);
			data.writeInt(width);
			data.writeInt(height);
			data.writeLong(System.currentTimeMillis());
			data.writeInt(offsets.length);
			CRC32 crc = new CRC32();
			for (float offset : offsets) {
				int bits = Float.floatToIntBits(offset);
				data.writeInt(bits);
				updateCrc(crc, bits);
			}
			data.writeLong(crc.getValue());
			data.flush();
			atomicFile.finishWrite(output);
		} catch (IOException | RuntimeException e) {
			if (output != null)
				atomicFile.failWrite(output);
			if (e instanceof IOException)
				throw (IOException) e;
			throw new IOException("Unable to commit spatial calibration profile.", e);
		}
	}

	private File profileFile(String deviceId) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(deviceId.getBytes(StandardCharsets.UTF_8));
			StringBuilder name = new StringBuilder(hash.length * 2);
			for (byte value : hash)
				name.append(String.format("%02x", value & 0xff));
			return new File(directory, name + ".sfpn");
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("SHA-256 is unavailable.", e);
		}
	}

	private static void updateCrc(CRC32 crc, int value) {
		crc.update(value >>> 24);
		crc.update(value >>> 16);
		crc.update(value >>> 8);
		crc.update(value);
	}
}
