package be.ntmn.inficam;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Date;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.security.auth.x500.X500Principal;

/** Creates a stable, non-exportable TLS identity for direct Web Control connections. */
final class WebViewTlsIdentity {
	private static final String KEYSTORE = "AndroidKeyStore";
	/* v1 only authorized SHA-256. Conscrypt signs a pre-hashed TLS transcript with
	 * NONEwithECDSA, so Android Keystore rejected every handshake as an
	 * incompatible digest. Use a new alias so upgraded installs cannot retain the
	 * unusable key. */
	private static final String KEY_ALIAS = "inficam_web_control_tls_v2";
	private static final long DAY_MS = 24L * 60L * 60L * 1000L;

	private WebViewTlsIdentity() { }

	static SSLContext createContext() throws IOException {
		try {
			KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
			keyStore.load(null);
			if (!keyStore.containsAlias(KEY_ALIAS))
				createIdentity();

			/* Reload after generation so the KeyManager sees the new private-key entry. */
			keyStore.load(null);
			KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
					KeyManagerFactory.getDefaultAlgorithm());
			keyManagers.init(keyStore, null);
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(keyManagers.getKeyManagers(), null, new SecureRandom());
			context.getServerSessionContext().setSessionCacheSize(32);
			context.getServerSessionContext().setSessionTimeout(24 * 60 * 60);
			return context;
		} catch (GeneralSecurityException e) {
			throw new IOException("Unable to initialize Web Control HTTPS", e);
		}
	}

	static void configure(SSLServerSocket socket) {
		/* Prefer TLS 1.3 where Conscrypt provides it. TLS 1.2 remains the secure
		 * compatibility floor for every Android release supported by the app. */
		if (Arrays.asList(socket.getSupportedProtocols()).contains("TLSv1.3"))
			socket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
		else
			socket.setEnabledProtocols(new String[]{"TLSv1.2"});
		socket.setUseClientMode(false);
		socket.setNeedClientAuth(false);
		socket.setEnableSessionCreation(true);
	}

	private static void createIdentity() throws GeneralSecurityException {
		long now = System.currentTimeMillis();
		SecureRandom random = new SecureRandom();
		BigInteger serial = new BigInteger(63, random).add(BigInteger.ONE);
		KeyGenParameterSpec parameters = new KeyGenParameterSpec.Builder(KEY_ALIAS,
				KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
				.setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
				/* TLS providers may pre-hash the transcript (NONEwithECDSA) or ask the
				 * Keystore to hash it. Authorize both forms used by TLS 1.2/1.3. */
				.setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
						KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
				.setCertificateSubject(new X500Principal("CN=InfiCamPlus Web Control"))
				.setCertificateSerialNumber(serial)
				.setCertificateNotBefore(new Date(now - DAY_MS))
				.setCertificateNotAfter(new Date(now + 3650L * DAY_MS))
				.setUserAuthenticationRequired(false)
				.build();
		KeyPairGenerator generator = KeyPairGenerator.getInstance(
				KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
		generator.initialize(parameters);
		generator.generateKeyPair();
	}
}
