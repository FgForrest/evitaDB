/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.http;

import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.certificate.ServerCertificateManager;
import io.evitadb.externalApi.certificate.ServerCertificateManager.CertificateType;
import io.evitadb.externalApi.configuration.AbstractApiConfiguration;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.ApiWithSpecificPrefix;
import io.evitadb.externalApi.configuration.CertificatePath;
import io.evitadb.externalApi.configuration.CertificateSettings;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.log.NoopAccessLogReceiver;
import io.evitadb.externalApi.log.Slf4JAccessLogReceiver;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CertificateUtils;
import io.evitadb.utils.ConsoleWriter;
import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import io.evitadb.utils.ConsoleWriter.ConsoleDecoration;
import io.evitadb.utils.StringUtils;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.accesslog.AccessLogHandler;
import io.undertow.server.handlers.accesslog.AccessLogReceiver;
import io.undertow.server.handlers.encoding.ContentEncodingRepository;
import io.undertow.server.handlers.encoding.DeflateEncodingProvider;
import io.undertow.server.handlers.encoding.EncodingHandler;
import io.undertow.server.handlers.encoding.GzipEncodingProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.io.pem.PemReader;
import org.jboss.threads.EnhancedQueueExecutor;
import org.xnio.Xnio;
import org.xnio.XnioWorker;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * HTTP server of external APIs. It is responsible for starting HTTP server with all configured external API providers
 * (GraphQL, REST, ...).
 * It uses {@link Undertow} server under the hood and uses {@link ExternalApiProviderRegistrar} for registering
 * each external API provider to be run on this HTTP server.
 *
 * @author Lukáš Hornych, FG Forrest a.s. 2022
 */
@Slf4j
public class ExternalApiServer implements AutoCloseable {

	public static final int PADDING_START_UP = 40;
	private final Undertow rootServer;
	@Getter private final ApiOptions apiOptions;
	private final Map<String, ExternalApiProvider<?>> registeredApiProviders;

	/**
	 * Finds all implementations of {@link ExternalApiProviderRegistrar} using {@link ServiceLoader} from the classpath.
	 */
	@SuppressWarnings("rawtypes")
	public static Collection<ExternalApiProviderRegistrar> gatherExternalApiProviders() {
		return ServiceLoader.load(ExternalApiProviderRegistrar.class)
			.stream()
			.map(Provider::get)
			.collect(Collectors.toList());

	}

	@Nonnull
	public static CertificatePath initCertificate(
		final @Nonnull ApiOptions apiOptions,
		final @Nonnull ServerCertificateManager serverCertificateManager
	) {
		final CertificatePath certificatePath = ServerCertificateManager.getCertificatePath(apiOptions.certificate())
			.orElseThrow(() -> new GenericEvitaInternalError("Either certificate path or its private key path is not set"));
		final File certificateFile = new File(certificatePath.certificate());
		final File certificateKeyFile = new File(certificatePath.privateKey());
		if (apiOptions.certificate().generateAndUseSelfSigned()) {
			final CertificateType[] certificateTypes = apiOptions.endpoints()
				.values()
				.stream()
				.flatMap(
					it -> Stream.of(
							it.isEnabled() && it.isTlsEnabled() ? CertificateType.SERVER : null,
							it.isEnabled() && it.isMtlsEnabled() ? CertificateType.CLIENT : null
						).filter(Objects::nonNull)
				)
				.distinct()
				.toArray(CertificateType[]::new);

			// if no end-point requires any certificate skip generation
			if (ArrayUtils.isEmpty(certificateTypes)) {
				log.info("No end-point is configured to use TLS or mTLS. Skipping certificate generation regardless `generateAndUseSelfSigned` is set to true.");
			} else {
				final Path certificateFolderPath = serverCertificateManager.getCertificateFolderPath();
				if (!certificateFolderPath.toFile().exists()) {
					Assert.isTrue(
						certificateFolderPath.toFile().mkdirs(),
						() -> "Cannot create certificate folder path: `" + certificateFolderPath + "`"
					);
				}
				final File rootCaFile = serverCertificateManager.getRootCaCertificatePath().toFile();
				if (!certificateFile.exists() && !certificateKeyFile.exists() && !rootCaFile.exists()) {
					try {
						serverCertificateManager.generateSelfSignedCertificate(certificateTypes);
					} catch (Exception e) {
						throw new GenericEvitaInternalError(
							"Failed to generate self-signed certificate: " + e.getMessage(),
							"Failed to generate self-signed certificate",
							e
						);
					}
				} else if (!certificateFile.exists() || !certificateKeyFile.exists() || !rootCaFile.exists()) {
					throw new EvitaInvalidUsageException("One of the essential certificate files is missing. Please either " +
						"provide all of these files or delete all files in the configured certificate folder and try again."
					);
				}
			}
		} else {
			if (!certificateFile.exists() || !certificateKeyFile.exists()) {
				throw new GenericEvitaInternalError("Certificate or its private key file does not exist");
			}
		}
		return certificatePath;
	}

	/**
	 * Configures and returns SSLContext for the Undertow.
	 */
	@Nonnull
	private static SSLContext configureSSLContext(
		@Nonnull CertificatePath certificatePath,
		@Nonnull ServerCertificateManager serverCertificateManager
	) {
		final SSLContext sslContext;

		try {
			final CertificateFactory cf = CertificateFactory.getInstance("X.509");
			final Optional<String> rootCaFingerPrint = getRootCaCertificateFingerPrint(serverCertificateManager, cf);
			final KeyManagerFactory keyManagerFactory = createKeyManagerFactory(certificatePath, cf);

			sslContext = SSLContext.getInstance("TLS");
			sslContext.init(keyManagerFactory.getKeyManagers(), null, null);

			rootCaFingerPrint.ifPresent(it -> {
				ConsoleWriter.write(StringUtils.rightPad("Root CA Certificate fingerprint: ", " ", PADDING_START_UP));
				ConsoleWriter.write(it, ConsoleColor.BRIGHT_YELLOW);
				ConsoleWriter.write("\n", ConsoleColor.WHITE);
			});

		} catch (NoSuchAlgorithmException | UnrecoverableKeyException | CertificateException | KeyStoreException |
		         IOException | KeyManagementException e) {
			throw new GenericEvitaInternalError(
				"Error while creating SSL context: " + e.getMessage(),
				"Error while creating SSL context",
				e
			);
		}
		return sslContext;
	}

	/**
	 * Reads root CA certificate from the file if exists.
	 */
	@Nullable
	private static Optional<String> getRootCaCertificateFingerPrint(
		@Nonnull ServerCertificateManager serverCertificateManager,
		@Nonnull CertificateFactory certificateFactory
	) throws IOException, CertificateException, NoSuchAlgorithmException {
		final File rootCaFile = serverCertificateManager.getRootCaCertificatePath().toFile();
		if (rootCaFile.exists()) {
			try (InputStream in = new FileInputStream(rootCaFile)) {
				final String fingerprint = CertificateUtils.getCertificateFingerprint(
					certificateFactory.generateCertificate(in)
				);
				return of(fingerprint);
			}
		} else {
			return empty();
		}
	}

	/**
	 * Reads certificate private key.
	 */
	@Nonnull
	private static PrivateKey loadPrivateKey(@Nonnull CertificatePath certificatePath) {
		final PrivateKey privateKey;
		final File certificatePrivateKey = Path.of(Objects.requireNonNull(certificatePath.privateKey())).toFile();
		Assert.isTrue(certificatePrivateKey.exists(), () -> "Certificate private key file `" + certificatePath.privateKey() + "` doesn't exists!");
		try (PemReader privateKeyReader = new PemReader(new FileReader(certificatePrivateKey))) {
			final PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyReader.readPemObject().getContent());
			final KeyFactory keyFactory = KeyFactory.getInstance("RSA");
			privateKey = keyFactory.generatePrivate(keySpec);
		} catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new EvitaInvalidUsageException(
				"Error while loading stored certificates. Check the server configuration file: " + e.getMessage(),
				"Error while loading stored certificates. Check the server configuration file.",
				e
			);
		}
		return privateKey;
	}

	/**
	 * Creates key manager factory.
	 */
	@Nonnull
	private static KeyManagerFactory createKeyManagerFactory(
		@Nonnull CertificatePath certificatePath,
		@Nonnull CertificateFactory cf
	) throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, UnrecoverableKeyException {
		final PrivateKey privateKey = loadPrivateKey(certificatePath);

		final File certificateFile = Path.of(Objects.requireNonNull(certificatePath.certificate())).toFile();
		final List<? extends Certificate> certificates;
		Assert.isTrue(certificateFile.exists(), () -> "Certificate file `" + certificatePath.certificate() + "` doesn't exists!");
		try (InputStream in = new FileInputStream(certificateFile)) {
			certificates = new ArrayList<>(cf.generateCertificates(in));
			Assert.isTrue(certificateFile.length() > 0, () -> "Certificate file `" + certificatePath.certificate() + "` contains no certificate!");
		}

		final KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
		keyStore.load(null);
		keyStore.setCertificateEntry("cert", certificates.get(0));
		keyStore.setKeyEntry(
			"key",
			privateKey,
			ofNullable(certificatePath.privateKeyPassword()).map(String::toCharArray).orElse(null),
			certificates.toArray(Certificate[]::new)
		);

		final KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagerFactory.init(keyStore, null);
		return keyManagerFactory;
	}

	/**
	 * Registers API providers based on configuration and returns its references.
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static Map<String, ExternalApiProvider<?>> registerApiProviders(
		@Nonnull Evita evita,
		@Nonnull ExternalApiServer externalApiServer,
		@Nonnull ApiOptions apiOptions,
		@SuppressWarnings("rawtypes") @Nonnull Collection<ExternalApiProviderRegistrar> externalApiProviders
	) {
		//noinspection rawtypes
		return (Map) externalApiProviders
			.stream()
			.sorted(Comparator.comparingInt(ExternalApiProviderRegistrar::getOrder))
			.map(registrar -> {
				final AbstractApiConfiguration apiProviderConfiguration = apiOptions.endpoints().get(registrar.getExternalApiCode());
				if (apiProviderConfiguration == null || !apiProviderConfiguration.isEnabled()) {
					return null;
				}

				//noinspection unchecked
				return registrar.register(evita, externalApiServer, apiOptions, apiProviderConfiguration);
			})
			.filter(Objects::nonNull)
			.collect(
				Collectors.toMap(
					it -> it.getCode().toLowerCase(),
					Function.identity(),
					(externalApiProvider, externalApiProvider2) -> {
						throw new GenericEvitaInternalError(
							"Multiple implementations of `" + externalApiProvider.getCode() + "` found on classpath!"
						);
					},
					LinkedHashMap::new
				)
			);
	}

	public ExternalApiServer(
		@Nonnull Evita evita,
		@Nonnull ApiOptions apiOptions
	) {
		this(evita, apiOptions, gatherExternalApiProviders());
	}

	public ExternalApiServer(
		@Nonnull Evita evita,
		@Nonnull ApiOptions apiOptions,
		@SuppressWarnings("rawtypes") @Nonnull Collection<ExternalApiProviderRegistrar> externalApiProviders
	) {
		this.apiOptions = apiOptions;

		final Undertow.Builder rootServerBuilder = Undertow.builder();

		final CertificateSettings certificateSettings = apiOptions.certificate();
		final ServerCertificateManager serverCertificateManager = new ServerCertificateManager(certificateSettings);
		final CertificatePath certificatePath = certificateSettings.generateAndUseSelfSigned() ?
			initCertificate(apiOptions, serverCertificateManager) :
			(
				certificateSettings.custom().certificate() != null && !certificateSettings.custom().certificate().isBlank() &&
					certificateSettings.custom().privateKey() != null && !certificateSettings.custom().privateKey().isBlank() ?
					new CertificatePath(
						certificateSettings.custom().certificate(),
						certificateSettings.custom().privateKey(),
						certificateSettings.custom().privateKeyPassword()
					) : null
			);

		this.registeredApiProviders = registerApiProviders(evita, this, apiOptions, externalApiProviders);
		if (this.registeredApiProviders.isEmpty()) {
			log.info("No external API providers were registered. No server will be created.");
			rootServer = null;
			return;
		}

		configureUndertow(rootServerBuilder, evita, certificatePath, apiOptions, serverCertificateManager);

		this.rootServer = rootServerBuilder.build();
		registeredApiProviders.values().forEach(ExternalApiProvider::afterAllInitialized);
	}

	/**
	 * Returns {@link ExternalApiProvider} by its {@link ExternalApiProvider#getCode()}.
	 */
	@Nullable
	public <T extends ExternalApiProvider<?>> T getExternalApiProviderByCode(@Nonnull String code) {
		//noinspection unchecked
		return (T) this.registeredApiProviders.get(code.toLowerCase());
	}

	/**
	 * Starts this configured HTTP server with registered APIs.
	 */
	public void start() {
		if (rootServer == null) {
			return;
		}

		rootServer.start();
		registeredApiProviders.values().forEach(ExternalApiProvider::afterStart);
	}

	/**
	 * Stops this running HTTP server and its registered APIs.
	 */
	@Override
	public void close() {
		if (rootServer == null) {
			return;
		}

		try {
			registeredApiProviders.values().forEach(ExternalApiProvider::beforeStop);
			rootServer.stop();
			final XnioWorker worker = rootServer.getWorker();
			worker.shutdown();
			try {
				if (!worker.awaitTermination(5000, TimeUnit.MILLISECONDS)) {
					worker.shutdownNow();
				}
			} catch (InterruptedException e) {
				worker.shutdownNow();
				Thread.currentThread().interrupt();
				throw new RuntimeException(e);
			}

			ConsoleWriter.write("External APIs stopped.\n");
		} catch (Exception ex) {
			ConsoleWriter.write("Failed to stop external APIs in dedicated time (5 secs.).\n");
		}
	}

	private void configureUndertow(
		@Nonnull Undertow.Builder undertowBuilder,
		@Nonnull Evita evita,
		@Nullable CertificatePath certificatePath,
		@Nonnull ApiOptions apiOptions,
		@Nonnull ServerCertificateManager serverCertificateManager
	) {
		final EnhancedQueueExecutor executor = evita.getExecutor();
		undertowBuilder
			.setWorker(
				Xnio.getInstance()
					.createWorkerBuilder()
					/*
						We try to use single shared executor instance to avoid CPU death from too many thread pools.
					 */
					.setExternalExecutorService(executor)
					/*
						IO threads represent separate thread pool, this is enforced by Undertow.
					 */
					.setWorkerIoThreads(apiOptions.ioThreads())
					.setWorkerName("Undertow XNIO worker.")
					.build()
			)
			/*
				Use direct byte buffers.
			 */
			.setDirectBuffers(true)
			/*
				We use recommended buffer size.
			 */
			.setBufferSize(16_384)
			/*
				The amount of time a connection can be idle for before it is timed out. An idle connection is a
				connection that has had no data transfer in the idle timeout period. Note that this is a fairly coarse
				grained approach, and small values will cause problems for requests with a long processing time.
				(milliseconds)
			 */
			.setServerOption(UndertowOptions.IDLE_TIMEOUT, apiOptions.idleTimeoutInMillis())
			/*
				How long a request can spend in the parsing phase before it is timed out. This timer is started when
				the first bytes of a request are read, and finishes once all the headers have been parsed.
				(milliseconds)
			 */
			.setServerOption(UndertowOptions.REQUEST_PARSE_TIMEOUT, apiOptions.parseTimeoutInMillis())
			/*
				The amount of time a connection can sit idle without processing a request, before it is closed by
				the server.
				(milliseconds)
			 */
			.setServerOption(UndertowOptions.NO_REQUEST_TIMEOUT, apiOptions.requestTimeoutInMillis())
			/*
			    If this is true then a Connection: keep-alive header will be added to responses, even when it is not strictly required by
			    the specification.
			 */
			.setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, apiOptions.keepAlive())
			/*
				The default maximum size of a request entity. If entity body is larger than this limit then a
				java.io.IOException will be thrown at some point when reading the request (on the first read for fixed
				length requests, when too much data has been read for chunked requests). This value is only the default
				 size, it is possible for a handler to override this for an individual request by calling
				 io.undertow.server.HttpServerExchange.setMaxEntitySize(long size). Defaults to unlimited.
			 */
			.setServerOption(UndertowOptions.MAX_ENTITY_SIZE, apiOptions.maxEntitySizeInBytes());

		final AccessLogReceiver accessLogReceiver = apiOptions.accessLog() ? new Slf4JAccessLogReceiver() : new NoopAccessLogReceiver();

		final Optional<SSLContext> sslContext = certificatePath == null ?
			empty() : of(configureSSLContext(certificatePath, serverCertificateManager));
		final Map<HostKey, PathHandler> undertowSetupHosts = createHashMap(10);
		this.registeredApiProviders
			.entrySet()
			.stream()
			.sorted(Entry.comparingByKey())
			.forEach(
				entry -> {
					final String apiCode = entry.getKey();
					final ExternalApiProvider<?> registeredApiProvider = entry.getValue();
					final AbstractApiConfiguration configuration = apiOptions.endpoints().get(registeredApiProvider.getCode());
					for (HostDefinition host : configuration.getHost()) {
						final HostKey hostKey = new HostKey(host, configuration.isTlsEnabled());
						if (!registeredApiProvider.isManagedByUndertow() || registeredApiProvider.getApiHandler() == null) {
							continue;
						}

						final boolean portCollision = undertowSetupHosts.keySet()
							.stream()
							.anyMatch(it -> it.host().port() == hostKey.host().port() &&
								it.ssl() != hostKey.ssl());
						if (portCollision) {
							throw new EvitaInvalidUsageException("There is ports collision in APIs configuration for `" + host + "`.");
						}

						final PathHandler hostRouter;
						if (undertowSetupHosts.containsKey(hostKey)) {
							hostRouter = undertowSetupHosts.get(hostKey);
						} else {
							hostRouter = Handlers.path();
							undertowSetupHosts.put(hostKey, hostRouter);

							final HttpHandler fallbackHandler = new FallbackExceptionHandler(hostRouter);

							// we want to support GZIP/deflate compression options for large payloads
							// source https://stackoverflow.com/questions/28295752/compressing-undertow-server-responses#28329810
							final EncodingHandler compressionHandler = new EncodingHandler(
								new ContentEncodingRepository()
									.addEncodingHandler("gzip", new GzipEncodingProvider(), 50)
									.addEncodingHandler("deflate", new DeflateEncodingProvider(), 5)
							)
								.setNext(fallbackHandler);

							// we want to log all requests coming into the Undertow server
							final HttpHandler accessLogHandler = new AccessLogHandler(
								compressionHandler,
								accessLogReceiver,
								"combined",
								ExternalApiServer.class.getClassLoader()
							);

							if (configuration.isTlsEnabled()) {
								undertowBuilder.addHttpsListener(
									host.port(), host.host().getHostAddress(),
									sslContext
										.orElseThrow(
											() -> new ExternalApiInternalError(
												"API `" + apiCode + "` has TLS enabled, but server is not configured" +
													" to use either provided server certificate nor to generate its own self-signed one."
											)
										),
									accessLogHandler
								);
							} else {
								undertowBuilder.addHttpListener(host.port(), host.host().getHostAddress(), accessLogHandler);
							}
						}

						Assert.isPremiseValid(
							configuration instanceof ApiWithSpecificPrefix,
							() -> new ExternalApiInternalError("Cannot register path because API `" + apiCode + "` has no prefix specified.")
						);
						hostRouter.addPrefixPath(
							((ApiWithSpecificPrefix) configuration).getPrefix(),
							registeredApiProvider.getApiHandler()
						);
					}
					ConsoleWriter.write(
						StringUtils.rightPad("API `" + apiCode + "` listening on ", " ", PADDING_START_UP)
					);
					final String[] baseUrls = configuration.getBaseUrls(apiOptions.exposedOn());
					for (int i = 0; i < baseUrls.length; i++) {
						final String url = baseUrls[i];
						if (i > 0) {
							ConsoleWriter.write(", ", ConsoleColor.WHITE);
						}
						ConsoleWriter.write(url, ConsoleColor.DARK_BLUE, ConsoleDecoration.UNDERLINE);
					}
					ConsoleWriter.write("\n", ConsoleColor.WHITE);

					if (registeredApiProvider instanceof ExternalApiProviderWithConsoleOutput<?> consoleOutput) {
						consoleOutput.writeToConsole();
					}
				}
			);
	}

	private record HostKey(HostDefinition host, boolean ssl) {
	}
}
