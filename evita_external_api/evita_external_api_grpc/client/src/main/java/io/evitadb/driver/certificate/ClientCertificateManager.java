/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.driver.certificate;

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CertificateUtils;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;

/**
 * Main purpose of this class is to build the {@link SslContext} for the client. It contains a builder that is used to
 * collect and hold all necessary parameters from EvitaClientConfiguration and modify them if necessary to be
 * used in the {@link SslContext} building process.
 */
@Slf4j
@RequiredArgsConstructor
public class ClientCertificateManager {
	private static final Path DEFAULT_CLIENT_CERTIFICATE_FOLDER_PATH = Paths.get(System.getProperty("java.io.tmpdir"), "evita-client-certificates");
	/**
	 * Password for the trust store that stores all of trusted CAs by the client.
	 */
	private final String trustStorePassword;
	private final Path certificateClientFolderPath;
	private final Path rootCaCertificateFilePath;
	private final Path clientCertificateFilePath;
	private final Path clientPrivateKeyFilePath;
	private final String clientPrivateKeyPassword;
	private final boolean isMtlsEnabled;
	private final boolean useGeneratedCertificate;
	private final String host;
	private final int port;
	private final boolean usingTrustedRootCaCertificate;
	private static final String TRUST_STORE_FILE_NAME = "trustStore.jks";
	public static Path getDefaultClientCertificateFolderPath() {
		return DEFAULT_CLIENT_CERTIFICATE_FOLDER_PATH;
	}

	public static class Builder {
		private Path certificateClientFolderPath = DEFAULT_CLIENT_CERTIFICATE_FOLDER_PATH;
		private Path rootCaCertificatePath = Path.of(CertificateUtils.getGeneratedRootCaCertificateFileName());
		private Path clientCertificateFilePath = null;
		private Path clientPrivateKeyFilePath = null;
		private String clientPrivateKeyPassword = null;
		private String trustStorePassword = "trustStorePassword";
		private boolean isMtlsEnabled = false;
		private boolean useGeneratedCertificate = true;
		private String host;
		private int port;
		private boolean usingTrustedRootCaCertificate = false;

		public Builder certificateClientFolderPath(@Nonnull Path certificateClientFolderPath) {
			this.certificateClientFolderPath = certificateClientFolderPath;
			return this;
		}

		public Builder rootCaCertificateFilePath(@Nonnull Path rootCaCertificateFilePath) {
			this.rootCaCertificatePath = rootCaCertificateFilePath;
			return this;
		}

		public Builder clientCertificateFilePath(@Nullable Path clientCertificateFilePath) {
			this.clientCertificateFilePath = certificateClientFolderPath.resolve(clientCertificateFilePath);
			return this;
		}

		public Builder clientPrivateKeyFilePath(@Nullable Path clientPrivateKeyFilePath) {
			this.clientPrivateKeyFilePath = certificateClientFolderPath.resolve(clientPrivateKeyFilePath);
			return this;
		}

		public Builder clientPrivateKeyPassword(@Nullable String clientPrivateKeyPassword) {
			this.clientPrivateKeyPassword = clientPrivateKeyPassword;
			return this;
		}

		public Builder trustStorePassword(@Nullable String trustStorePassword) {
			this.trustStorePassword = trustStorePassword;
			return this;
		}

		public Builder mtls(boolean isMtlsEnabled) {
			this.isMtlsEnabled = isMtlsEnabled;
			return this;
		}

		public Builder useGeneratedCertificate(boolean useGeneratedCertificate, @Nonnull String host, int port) {
			this.useGeneratedCertificate = useGeneratedCertificate;
			this.host = host;
			this.port = port;
			return this;
		}

		public Builder usingTrustedRootCaCertificate(boolean usingTrustedRootCaCertificate) {
			this.usingTrustedRootCaCertificate = usingTrustedRootCaCertificate;
			return this;
		}

		public ClientCertificateManager build() {
			return new ClientCertificateManager(
				trustStorePassword,
				certificateClientFolderPath,
				rootCaCertificatePath,
				clientCertificateFilePath,
				clientPrivateKeyFilePath,
				clientPrivateKeyPassword,
				isMtlsEnabled,
				useGeneratedCertificate,
				host, port,
				usingTrustedRootCaCertificate
			);
		}
	}

	/**
	 * This method is used to build the {@link SslContext} for the client, that is necessary for the client to connect
	 * to the server. It takes into account the configuration of the client, and builds the {@link SslContext} accordingly.
	 * @return built {@link SslContext} for client
	 */
	public SslContext buildClientSslContext() {
		try {
			final Path usedCertificatePath = getUsedRootCaCertificatePath();

			try {
				if (!getDefaultClientCertificateFolderPath().toFile().exists()) {
					Assert.isPremiseValid(
						getDefaultClientCertificateFolderPath().toFile().mkdirs(),
						() -> "Cannot create path `" + getDefaultClientCertificateFolderPath() + "`!"
					);
				}

				if (useGeneratedCertificate && !usedCertificatePath.toFile().exists()) {
					getCertificatesFromServer(host, port);
				}

				final CertificateFactory cf = CertificateFactory.getInstance("X.509");
				final Certificate serverCert;
				try (InputStream in = new FileInputStream(usedCertificatePath.toFile())) {
					serverCert = cf.generateCertificate(in);
				}
				log.info("Server's CA certificate fingerprint: {}", CertificateUtils.getCertificateFingerprint(serverCert));
				if (isMtlsEnabled) {
					final Certificate clientCert;
					try (InputStream in = new FileInputStream(clientCertificateFilePath.toFile())) {
						clientCert = cf.generateCertificate(in);
					}
					log.info("Client's certificate fingerprint: {}", CertificateUtils.getCertificateFingerprint(clientCert));
				}
			} catch (CertificateException | IOException | NoSuchAlgorithmException e) {
				throw new EvitaInternalError(e.getMessage(), e);
			}

			final TrustManager trustManagerTrustingProvidedRootCertificate = getTrustManagerTrustingProvidedCertificate(usedCertificatePath, "evita-root-ca");
			final SslContextBuilder sslContextBuilder = SslContextBuilder.forClient()
				.applicationProtocolConfig(
					new ApplicationProtocolConfig(
						Protocol.ALPN,
						SelectorFailureBehavior.NO_ADVERTISE,
						SelectedListenerFailureBehavior.ACCEPT,
						ApplicationProtocolNames.HTTP_2));
			if (isMtlsEnabled) {
				if (clientPrivateKeyFilePath == null) {
					throw new EvitaInvalidUsageException("Client certificate path is not set while using mTLS. Check evita configuration file.");
				}
				sslContextBuilder.keyManager(new File(clientCertificateFilePath.toUri()), new File(clientPrivateKeyFilePath.toUri()), clientPrivateKeyPassword);
			}
			if (!usingTrustedRootCaCertificate) {
				sslContextBuilder.trustManager(trustManagerTrustingProvidedRootCertificate);
			} else {
				sslContextBuilder.trustManager(new File(usedCertificatePath.toUri()));
			}
			return sslContextBuilder.build();
		} catch (Exception e) {
			throw new EvitaInvalidUsageException("Failed to initialize EvitaClient", e);
		}
	}

	/**
	 * Fetches the certificate from the server and stores it in the client certificate folder.
	 * @param host server host
	 * @param port server port
	 */
	public void getCertificatesFromServer(@Nonnull String host, int port) {
		final String apiEndpoint = "http://" + host + ":" + port + "/system/";
		final File clientFolder = new File(certificateClientFolderPath.toUri());
		if (!clientFolder.exists()) {
			//noinspection ResultOfMethodCallIgnored
			clientFolder.mkdir();
		} else {
			final File rootCaCert = new File(certificateClientFolderPath.resolve(CertificateUtils.getGeneratedRootCaCertificateFileName()).toUri());
			final File cert = new File(certificateClientFolderPath.resolve(CertificateUtils.getGeneratedClientCertificateFileName()).toUri());
			final File key = new File(certificateClientFolderPath.resolve(CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName()).toUri());
			if (rootCaCert.exists() && cert.exists() && key.exists()) {
				return;
			}
		}
		try {
			downloadFileFromServer(apiEndpoint, CertificateUtils.getGeneratedRootCaCertificateFileName());
			downloadFileFromServer(apiEndpoint, CertificateUtils.getGeneratedClientCertificateFileName());
			downloadFileFromServer(apiEndpoint, CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName());
		} catch (IOException e) {
			throw new EvitaInvalidUsageException("Failed to download certificates from server", e);
		}
	}

	/**
	 * Downloads a file from the server and stores it in the client certificate folder.
	 * @param apiEndpoint The endpoint to fetch the file from.
	 * @param filename The name of the file to fetch.
	 * @throws IOException If the file could not be downloaded.
	 */
	private void downloadFileFromServer(@Nonnull String apiEndpoint, @Nonnull String filename) throws IOException {
		final URL website = new URL(apiEndpoint + filename);
		try (
			final ReadableByteChannel rbc = Channels.newChannel(website.openStream());
			final FileOutputStream fos = new FileOutputStream(certificateClientFolderPath.resolve(filename).toFile());
			final FileChannel channel = fos.getChannel()
		) {
			channel.transferFrom(rbc, 0, Long.MAX_VALUE);
		}
	}

	/**
	 * Add a provided certificate to the local truststore by usage of {@link TrustManagerDelegate}.
	 * @param toTrustCertificatePath refers to the path of the certificate to trust
	 * @param certificateAlias name, that will be used as a key in the truststore file
	 * @return a trust manager that trusts the provided certificate
	 */
	public TrustManager getTrustManagerTrustingProvidedCertificate(@Nonnull Path toTrustCertificatePath, @Nonnull String certificateAlias) {
		try {
			final TrustManagerFactory javaDefaultTrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			javaDefaultTrustManager.init((KeyStore) null);
			final TrustManagerFactory serverSelfSignedTrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			serverSelfSignedTrustManager.init(getKeyStore(toTrustCertificatePath, certificateAlias));
			return new TrustManagerDelegate(
				(X509TrustManager) serverSelfSignedTrustManager.getTrustManagers()[0],
				(X509TrustManager) javaDefaultTrustManager.getTrustManagers()[0]
			);
		} catch (Exception e) {
			throw new EvitaInvalidUsageException("Failed to initialize SSL context.", e);
		}
	}

	/**
	 * Add a provided certificate to the local truststore.
	 * @param rootCaCertificatePath refers to the path of the certificate to trust
	 * @param certificateAlias name, that will be used as a key in the truststore file
	 * @return keystore that contains the provided certificate
	 */
	private KeyStore getKeyStore(@Nonnull Path rootCaCertificatePath, @Nonnull String certificateAlias) throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
		// Create or get a KeyStore instance with the specified type and store it in a file
		final File trustStoreFile = new File(certificateClientFolderPath.resolve(TRUST_STORE_FILE_NAME).toUri());
		final KeyStore trustStore = KeyStore.getInstance("JKS");
		final char[] trustStorePasswordCharArray = this.trustStorePassword.toCharArray();
		if (!certificateClientFolderPath.toFile().exists()) {
			Assert.isTrue(certificateClientFolderPath.toFile().mkdirs(), () -> "Cannot create folder `" + certificateClientFolderPath + "`!");
		}
		if (!trustStoreFile.exists()) {
			trustStore.load(null, null);
			try (final FileOutputStream trustStoreOutputStream = new FileOutputStream(trustStoreFile)) {
				trustStore.store(trustStoreOutputStream, trustStorePasswordCharArray);
			}
		} else {
			try (final FileInputStream trustStoreInputStream = new FileInputStream(trustStoreFile)) {
				trustStore.load(trustStoreInputStream, trustStorePasswordCharArray);
			}
		}
		final File certificateFile = new File(rootCaCertificatePath.toUri());
		final CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
		final Certificate certificate;
		try (final FileInputStream certificateInputStream = new FileInputStream(certificateFile)) {
			certificate = certificateFactory.generateCertificate(certificateInputStream);
		}
		// Import the certificate into the trust store
		trustStore.setCertificateEntry(certificateAlias, certificate);
		try (final FileOutputStream trustStoreOutputStream = new FileOutputStream(trustStoreFile)) {
			trustStore.store(trustStoreOutputStream, trustStorePasswordCharArray);
		}
		return trustStore;
	}


	/**
	 * According to the client configuration, returns a path to the root CA certificate which may be located in either
	 * user specified folder or in system TEMP directory.
	 * @return The path to the certificate that should be used.
	 */
	public Path getUsedRootCaCertificatePath() {
		return !useGeneratedCertificate ?
			rootCaCertificateFilePath :
			certificateClientFolderPath.resolve(CertificateUtils.getGeneratedRootCaCertificateFileName());
	}
}
