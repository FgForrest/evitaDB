/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.grpc.certificate;

import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.ClientTlsConfig;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CertificateUtils;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
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
import java.io.Reader;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Main purpose of this class is to build the {@link SslContext} for the client. It contains a builder that is used to
 * collect and hold all necessary parameters from EvitaClientConfiguration and modify them if necessary to be
 * used in the {@link SslContext} building process.
 */
@Slf4j
public class ClientCertificateManager {
	private static final Path DEFAULT_CLIENT_CERTIFICATE_FOLDER_PATH = Paths.get(System.getProperty("java.io.tmpdir"), "evita-client-certificates");
	private static final String TRUST_STORE_FILE_NAME = "trustStore.jks";
	/**
	 * Password for the trust store that stores all of trusted CAs by the client.
	 */
	private final String trustStorePassword;
	private final Path certificateClientFolderPath;
	private final Path rootCaCertificateFilePath;
	private final Path clientCertificateFilePath;
	private final Path clientPrivateKeyFilePath;
	private final String clientPrivateKeyPassword;
	private final boolean mtlsEnabled;
	private final boolean useGeneratedCertificate;
	private final boolean usingTrustedRootCaCertificate;

	public static Path getDefaultClientCertificateFolderPath() {
		return DEFAULT_CLIENT_CERTIFICATE_FOLDER_PATH;
	}

	/**
	 * Fetches the certificate from the server and stores it in the client certificate folder.
	 *
	 * @param host server host
	 * @param port server port
	 * @return path to the folder with the certificates specific to the server instance
	 */
	@Nonnull
	private static Path getCertificatesFromServer(@Nonnull String host, int port, @Nonnull Path certificateClientFolderPath, @Nonnull CertificateType... certificateType) {
		if (ArrayUtils.isEmpty(certificateType)) {
			throw new EvitaInvalidUsageException("At least one certificate type must be provided");
		}

		final String apiEndpoint = "http://" + host + ":" + port + "/system/";
		try {
			final String serverName = getServerName(apiEndpoint);
			final Path serverSpecificDirectory = certificateClientFolderPath.resolve(serverName);
			final File clientFolder = new File(serverSpecificDirectory.toUri());
			if (!clientFolder.exists()) {
				Assert.isTrue(clientFolder.mkdirs(), "Cannot create folder `" + clientFolder + "`!");
			} else {
				final File serverCert = new File(serverSpecificDirectory.resolve(CertificateUtils.getGeneratedServerCertificateFileName()).toUri());
				final File cert = new File(serverSpecificDirectory.resolve(CertificateUtils.getGeneratedClientCertificateFileName()).toUri());
				final File key = new File(serverSpecificDirectory.resolve(CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName()).toUri());
				if (serverCert.exists() && cert.exists() && key.exists()) {
					return serverSpecificDirectory;
				}
			}

			if (Arrays.asList(certificateType).contains(CertificateType.SERVER)) {
				downloadFileFromServer(apiEndpoint, serverSpecificDirectory, CertificateUtils.getGeneratedServerCertificateFileName());
			}

			if (Arrays.asList(certificateType).contains(CertificateType.CLIENT)) {
				downloadFileFromServer(apiEndpoint, serverSpecificDirectory, CertificateUtils.getGeneratedClientCertificateFileName());
				downloadFileFromServer(apiEndpoint, serverSpecificDirectory, CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName());
			}

			return serverSpecificDirectory;
		} catch (IOException e) {
			throw new EvitaInvalidUsageException("Failed to download certificates from server", e);
		}
	}

	/**
	 * Reads a server name from the server.
	 *
	 * @param apiEndpoint The endpoint to fetch the file from.
	 * @throws IOException If the API could not be reached.
	 */
	@Nonnull
	private static String getServerName(@Nonnull String apiEndpoint) throws IOException {
		final URL website = new URL(apiEndpoint + "server-name");
		try (
			final Reader reader = Channels.newReader(Channels.newChannel(website.openStream()), StandardCharsets.UTF_8);
		) {
			final StringBuilder stringBuilder = new StringBuilder(128);
			final char[] buffer = new char[128];
			int read;
			while ((read = reader.read(buffer)) != -1) {
				stringBuilder.append(buffer, 0, read);
			}
			return stringBuilder.toString();
		}
	}

	/**
	 * Downloads a file from the server and stores it in the client certificate folder.
	 *
	 * @param apiEndpoint The endpoint to fetch the file from.
	 * @param baseDir     the name of the directory for the file
	 * @param filename    The name of the file to fetch.
	 * @throws IOException If the file could not be downloaded.
	 */
	private static void downloadFileFromServer(@Nonnull String apiEndpoint, @Nonnull Path baseDir, @Nonnull String filename) throws IOException {
		final URL website = new URL(apiEndpoint + filename);
		try (
			final ReadableByteChannel rbc = Channels.newChannel(website.openStream());
			final FileOutputStream fos = new FileOutputStream(baseDir.resolve(filename).toFile());
			final FileChannel channel = fos.getChannel()
		) {
			channel.transferFrom(rbc, 0, Long.MAX_VALUE);
		}
	}

	public ClientCertificateManager(
		@Nullable String trustStorePassword,
		@Nonnull Path certificateClientFolderPath,
		@Nullable Path serverCertificateFilePath,
		@Nullable Path clientCertificateFilePath,
		@Nullable Path clientPrivateKeyFilePath,
		@Nullable String clientPrivateKeyPassword,
		boolean mtlsEnabled,
		boolean useGeneratedCertificate,
		@Nullable String serverName,
		@Nullable String host,
		int port,
		boolean usingTrustedRootCaCertificate
	) {
		if (useGeneratedCertificate) {
			Assert.isTrue(host != null, "Host must be provided when using generated certificate!");
			this.certificateClientFolderPath = getCertificatesFromServer(
				host, port, certificateClientFolderPath, Stream.concat(
					Stream.of(CertificateType.SERVER),
					mtlsEnabled ? Stream.of(CertificateType.CLIENT) : Stream.empty()
				).toArray(CertificateType[]::new)
			);
			this.rootCaCertificateFilePath = this.certificateClientFolderPath.resolve(CertificateUtils.getGeneratedServerCertificateFileName());
			this.clientCertificateFilePath = this.certificateClientFolderPath.resolve(CertificateUtils.getGeneratedClientCertificateFileName());
			this.clientPrivateKeyFilePath = this.certificateClientFolderPath.resolve(CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName());
		} else {
			Assert.isTrue(host != null || serverName != null, "Host or server name must be provided when using custom certificate!");
				this.certificateClientFolderPath = serverName == null ?
				identifyServerDirectory(host, port, certificateClientFolderPath) :
				certificateClientFolderPath.resolve(serverName);
			this.rootCaCertificateFilePath = serverCertificateFilePath;
			this.clientCertificateFilePath = clientCertificateFilePath;
			this.clientPrivateKeyFilePath = clientPrivateKeyFilePath;
		}

		this.trustStorePassword = trustStorePassword;
		this.clientPrivateKeyPassword = clientPrivateKeyPassword;
		this.mtlsEnabled = mtlsEnabled;
		this.useGeneratedCertificate = useGeneratedCertificate;
		this.usingTrustedRootCaCertificate = usingTrustedRootCaCertificate;
	}

	/**
	 * This method is used to build the {@link SslContext} for the client, that is necessary for the client to connect
	 * to the server. It takes into account the configuration of the client, and builds the {@link SslContext} accordingly.
	 *
	 * @return built {@link SslContext} for client
	 */
	public ClientFactoryBuilder buildClientSslContext(
		@Nullable BiConsumer<CertificateType, Certificate> onCertificateLoaded,
		@Nonnull ClientFactoryBuilder clientFactoryBuilder
	) {
		try {
			if (this.mtlsEnabled) {
				if (this.clientPrivateKeyFilePath == null || this.clientCertificateFilePath == null) {
					throw new EvitaInvalidUsageException("Client certificate and private key must be provided for mTLS connection.");
				}
				return clientFactoryBuilder.tlsProvider(
					TlsProvider.builder()
						.trustedCertificates(
							loadCertificate(String.valueOf(Path.of("evita-server-certificates").resolve(this.rootCaCertificateFilePath)))
						)
						.keyPair(
							TlsKeyPair.of(
								new File(this.clientPrivateKeyFilePath.toString()),
								this.clientPrivateKeyPassword,
								new File(this.clientCertificateFilePath.toString())
							)
						)
						.build(),
					ClientTlsConfig.builder().tlsCustomizer(customizer -> customizer.trustManager(InsecureTrustManagerFactory.INSTANCE)).tlsNoVerify().build()
				);
			} else {
				return clientFactoryBuilder.tlsCustomizer(sslContextBuilder -> {
					final Path usedCertificatePath = getUsedRootCaCertificatePath();
					final TrustManager trustManagerTrustingProvidedRootCertificate = usedCertificatePath == null ?
						null : getTrustManager(usedCertificatePath, onCertificateLoaded);
					sslContextBuilder.applicationProtocolConfig(
						new ApplicationProtocolConfig(
							Protocol.ALPN,
							SelectorFailureBehavior.NO_ADVERTISE,
							SelectedListenerFailureBehavior.ACCEPT,
							ApplicationProtocolNames.HTTP_2)
					);
					if (!this.usingTrustedRootCaCertificate && trustManagerTrustingProvidedRootCertificate != null) {
						sslContextBuilder.trustManager(trustManagerTrustingProvidedRootCertificate);
					} else if (usedCertificatePath != null && usedCertificatePath.toFile().exists()) {
						sslContextBuilder.trustManager(new File(usedCertificatePath.toUri()));
					}
				});
			}

		} catch (Exception e) {
			throw new GenericEvitaInternalError(e.getMessage(), e);
		}
	}

	@Nonnull
	private static X509Certificate loadCertificate(@Nonnull String certificatePath) throws CertificateException, IOException {
		final CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
		try (InputStream inputStream = new FileInputStream(certificatePath)) {
			return (X509Certificate) certificateFactory.generateCertificate(inputStream);
		}
	}

	/**
	 * Add a provided certificate to the local truststore by usage of {@link TrustManagerDelegate}.
	 *
	 * @param toTrustCertificatePath refers to the path of the certificate to trust
	 * @param certificateAlias       name, that will be used as a key in the truststore file
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
	 * According to the client configuration, returns a path to the root CA certificate which may be located in either
	 * user specified folder or in system TEMP directory.
	 *
	 * @return The path to the certificate that should be used.
	 */
	public Path getUsedRootCaCertificatePath() {
		return !this.useGeneratedCertificate ?
			this.rootCaCertificateFilePath :
			this.certificateClientFolderPath.resolve(CertificateUtils.getGeneratedServerCertificateFileName());
	}

	/**
	 * Returns trust manager initialized with provided certificate - either automatically downloaded from the server
	 * or initialized from local certificate file. Method may also return NULL if the client is provided with none
	 * of those, and we will rely on Java internal trust store.
	 */
	@Nullable
	private TrustManager getTrustManager(
		@Nonnull Path usedCertificatePath,
		@Nullable BiConsumer<CertificateType, Certificate> onCertificateLoaded
	) {
		final Optional<BiConsumer<CertificateType, Certificate>> optionalCallback = ofNullable(onCertificateLoaded);
		final TrustManager trustManagerTrustingProvidedRootCertificate;
		try {
			final CertificateFactory cf = CertificateFactory.getInstance("X.509");
			if (usedCertificatePath.toFile().exists()) {
				final Certificate serverCert;
				try (InputStream in = new FileInputStream(usedCertificatePath.toFile())) {
					serverCert = cf.generateCertificate(in);
				}
				optionalCallback.ifPresent(it -> it.accept(CertificateType.SERVER, serverCert));
				trustManagerTrustingProvidedRootCertificate = getTrustManagerTrustingProvidedCertificate(usedCertificatePath, "evita-root-ca");
			} else {
				trustManagerTrustingProvidedRootCertificate = null;
			}
			if (this.mtlsEnabled) {
				final Certificate clientCert;
				try (InputStream in = new FileInputStream(this.clientCertificateFilePath.toFile())) {
					clientCert = cf.generateCertificate(in);
				}
				optionalCallback.ifPresent(it -> it.accept(CertificateType.CLIENT, clientCert));
			}
		} catch (CertificateException | IOException e) {
			throw new GenericEvitaInternalError(e.getMessage(), e);
		}
		return trustManagerTrustingProvidedRootCertificate;
	}

	/**
	 * Add a provided certificate to the local truststore.
	 *
	 * @param rootCaCertificatePath refers to the path of the certificate to trust
	 * @param certificateAlias      name, that will be used as a key in the truststore file
	 * @return keystore that contains the provided certificate
	 */
	private KeyStore getKeyStore(@Nonnull Path rootCaCertificatePath, @Nonnull String certificateAlias) throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
		// Create or get a KeyStore instance with the specified type and store it in a file
		final File trustStoreFile = new File(this.certificateClientFolderPath.resolve(TRUST_STORE_FILE_NAME).toUri());
		final KeyStore trustStore = KeyStore.getInstance("JKS");
		final char[] trustStorePasswordCharArray = this.trustStorePassword.toCharArray();
		if (!this.certificateClientFolderPath.toFile().exists()) {
			Assert.isTrue(this.certificateClientFolderPath.toFile().mkdirs(), () -> "Cannot create folder `" + this.certificateClientFolderPath + "`!");
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
	 * Fetches the certificate from the server and stores it in the client certificate folder.
	 *
	 * @param host server host
	 * @param port server port
	 * @return path to the folder with the certificates specific to the server instance
	 */
	@Nonnull
	private static Path identifyServerDirectory(@Nonnull String host, int port, @Nonnull Path certificateClientFolderPath) {
		final String apiEndpoint = "http://" + host + ":" + port + "/system/";
		try {
			final String serverName = getServerName(apiEndpoint);
			return certificateClientFolderPath.resolve(serverName);
		} catch (IOException e) {
			throw new EvitaInvalidUsageException("Failed to download certificates from server", e);
		}
	}

	public static class Builder {
		private Path certificateClientFolderPath = DEFAULT_CLIENT_CERTIFICATE_FOLDER_PATH;
		private Path serverCertificatePath = Path.of(CertificateUtils.getGeneratedServerCertificateFileName());
		@Nullable private Path clientCertificateFilePath = null;
		@Nullable private Path clientPrivateKeyFilePath = null;
		@Nullable private String clientPrivateKeyPassword = null;
		@Nullable private String trustStorePassword = "trustStorePassword";
		private boolean isMtlsEnabled = false;
		private boolean useGeneratedCertificate = true;
		@Nullable private String serverName;
		private String host;
		private int port;
		private boolean usingTrustedRootCaCertificate = false;

		public Builder certificateClientFolderPath(@Nonnull Path certificateClientFolderPath) {
			this.certificateClientFolderPath = certificateClientFolderPath;
			return this;
		}

		public Builder serverCertificateFilePath(@Nonnull Path rootCaCertificateFilePath) {
			this.serverCertificatePath = rootCaCertificateFilePath;
			return this;
		}

		public Builder clientCertificateFilePath(@Nullable Path clientCertificateFilePath) {
			this.clientCertificateFilePath = clientCertificateFilePath;
			return this;
		}

		public Builder clientPrivateKeyFilePath(@Nullable Path clientPrivateKeyFilePath) {
			this.clientPrivateKeyFilePath = clientPrivateKeyFilePath;
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

		public Builder dontUseGeneratedCertificate() {
			this.useGeneratedCertificate = false;
			return this;
		}

		public Builder useGeneratedCertificate(boolean useGeneratedCertificate, @Nonnull String host, int port) {
			this.useGeneratedCertificate = useGeneratedCertificate;
			this.host = host;
			this.port = port;
			return this;
		}

		public Builder serverName(@Nullable String serverName) {
			this.serverName = serverName;
			return this;
		}

		public Builder usingTrustedServerCertificate(boolean usingTrustedRootCaCertificate) {
			this.usingTrustedRootCaCertificate = usingTrustedRootCaCertificate;
			return this;
		}

		public ClientCertificateManager build() {
			return new ClientCertificateManager(
				this.trustStorePassword,
				this.certificateClientFolderPath,
				this.serverCertificatePath,
				ofNullable(this.clientCertificateFilePath)
					.map(it -> this.certificateClientFolderPath.resolve(it))
					.orElse(null),
				ofNullable(this.clientPrivateKeyFilePath)
					.map(it -> this.certificateClientFolderPath.resolve(it))
					.orElse(null),
				this.clientPrivateKeyPassword,
				this.isMtlsEnabled,
				this.useGeneratedCertificate,
				this.serverName,
				this.host,
				this.port,
				this.usingTrustedRootCaCertificate
			);
		}
	}

	/**
	 * The type of the certificate to generate.
	 */
	public enum CertificateType {
		SERVER,
		CLIENT
	}

}
