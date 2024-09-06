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

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.encoding.DecodingService;
import com.linecorp.armeria.server.encoding.EncodingService;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.server.logging.LoggingService;
import io.evitadb.api.requestResponse.data.DevelopmentConstants;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.certificate.ServerCertificateManager;
import io.evitadb.externalApi.certificate.ServerCertificateManager.CertificateType;
import io.evitadb.externalApi.configuration.AbstractApiConfiguration;
import io.evitadb.externalApi.configuration.ApiConfigurationWithMutualTls;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.ApiWithSpecificPrefix;
import io.evitadb.externalApi.configuration.CertificatePath;
import io.evitadb.externalApi.configuration.CertificateSettings;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.configuration.MtlsConfiguration;
import io.evitadb.externalApi.configuration.TlsMode;
import io.evitadb.externalApi.http.ExternalApiProvider.HttpServiceDefinition;
import io.evitadb.externalApi.http.ExternalApiProvider.PathHandlingMode;
import io.evitadb.externalApi.utils.path.PathHandlingService;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CertificateUtils;
import io.evitadb.utils.ConsoleWriter;
import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import io.evitadb.utils.ConsoleWriter.ConsoleDecoration;
import io.evitadb.utils.StringUtils;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.ClientAuth;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.io.pem.PemReader;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.KeyManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.KeyFactory;
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
import java.time.Duration;
import java.util.*;
import java.util.ServiceLoader.Provider;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * HTTP server of external APIs. It is responsible for starting HTTP server with all configured external API providers
 * (GraphQL, REST, ...).
 * It uses Armeria {@link Server} server under the hood and uses {@link ExternalApiProviderRegistrar} for registering
 * each external API provider to be run on this HTTP server.
 *
 * @author Lukáš Hornych, FG Forrest a.s. 2022
 */
@Slf4j
public class ExternalApiServer implements AutoCloseable {

	public static final int PADDING_START_UP = 40;
	private final Server server;
	@Getter private final ApiOptions apiOptions;
	private final Map<String, ExternalApiProvider<?>> registeredApiProviders;
	private CompletableFuture<Void> stopFuture;

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
						it.isEnabled() && it.getTlsMode() != TlsMode.FORCE_NO_TLS ? CertificateType.SERVER : null,
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

			getRootCaCertificateFingerPrint(serverCertificateManager)
				.ifPresent(it -> {
					ConsoleWriter.write(StringUtils.rightPad("Root CA Certificate fingerprint: ", " ", PADDING_START_UP));
					ConsoleWriter.write(it, ConsoleColor.BRIGHT_YELLOW);
					ConsoleWriter.write("\n", ConsoleColor.WHITE);
				});

		} else {
			if (!certificateFile.exists() || !certificateKeyFile.exists()) {
				throw new GenericEvitaInternalError("Certificate or its private key file does not exist");
			}
		}
		return certificatePath;
	}

	/**
	 * Reads root CA certificate from the file if exists.
	 */
	@Nullable
	private static Optional<String> getRootCaCertificateFingerPrint(
		@Nonnull ServerCertificateManager serverCertificateManager
	) {
		final File rootCaFile = serverCertificateManager.getRootCaCertificatePath().toFile();
		if (rootCaFile.exists()) {
			try (InputStream in = new FileInputStream(rootCaFile)) {
				final CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
				final String fingerprint = CertificateUtils.getCertificateFingerprint(
					certificateFactory.generateCertificate(in)
				);
				return of(fingerprint);
			} catch (IOException e) {
				throw new GenericEvitaInternalError(
					"Failed to read root CA certificate: " + e.getMessage(),
					"Failed to read root CA certificate.",
					e
				);
			} catch (NoSuchAlgorithmException | CertificateException e) {
				throw new GenericEvitaInternalError(
					"Failed to generate certificate fingerprint: " + e.getMessage(),
					"Failed to generate certificate fingerprint.",
					e
				);
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
		@Nonnull CertificatePath certificatePath
	) {
		try {
			final PrivateKey privateKey = loadPrivateKey(certificatePath);

			final File certificateFile = Path.of(Objects.requireNonNull(certificatePath.certificate())).toFile();
			final List<? extends Certificate> certificates;
			Assert.isTrue(certificateFile.exists(), () -> "Certificate file `" + certificatePath.certificate() + "` doesn't exists!");
			try (InputStream in = new FileInputStream(certificateFile)) {
				final CertificateFactory cf = CertificateFactory.getInstance("X.509");
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
		} catch (CertificateException | UnrecoverableKeyException | NoSuchAlgorithmException | KeyStoreException |
		         IOException e) {
			throw new GenericEvitaInternalError(
				"Failed to create key manager factory with provided certificate and private key: " + e.getMessage(),
				"Failed to create key manager factory with provided certificate and private key.",
				e
			);
		}
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

	/**
	 * Customizes TLS settings for the server. Sets up a mutual TLS verification if requested.
	 */
	private static void customizeTls(
		@Nonnull ServerBuilder virtualHostBuilder,
		@Nonnull ApiOptions apiOptions,
		@Nullable MtlsConfiguration mtlsConfiguration
	) {
		virtualHostBuilder.tlsCustomizer(
			customizer -> {
				try {
					if (apiOptions.certificate().generateAndUseSelfSigned()) {
						customizer.trustManager(
							apiOptions.certificate().getFolderPath()
								.resolve(CertificateUtils.getGeneratedRootCaCertificateFileName())
								.toFile()
						);
					}
					if (mtlsConfiguration != null && Boolean.TRUE.equals(mtlsConfiguration.enabled())) {
						customizer.clientAuth(ClientAuth.REQUIRE);
						final CertificateFactory cf = CertificateFactory.getInstance("X.509");
						for (String clientCert : mtlsConfiguration.allowedClientCertificatePaths()) {
							customizer.trustManager(new FileInputStream(clientCert));
							try (InputStream in = new FileInputStream(clientCert)) {
								log.info("Whitelisted client's certificate fingerprint: {}", CertificateUtils.getCertificateFingerprint(cf.generateCertificate(in)));
							}
						}
					} else {
						customizer.clientAuth(ClientAuth.OPTIONAL);
					}
				} catch (Exception e) {
					throw new GenericEvitaInternalError(
						"Failed to create gRPC server credentials with provided certificate and private key: " + e.getMessage(),
						"Failed to create gRPC server credentials with provided certificate and private key.",
						e
					);
				}
			});
	}

	/**
	 * Prints information about API endpoint to the server console output.
	 *
	 * @param apiOptions            API endpoint options
	 * @param registeredApiProvider registered API provider
	 */
	private static void logStatusToConsole(
		@Nonnull ApiOptions apiOptions,
		@Nonnull ExternalApiProvider<?> registeredApiProvider
	) {
		final AbstractApiConfiguration configuration = registeredApiProvider.getConfiguration();
		ConsoleWriter.write(
			StringUtils.rightPad("API `" + registeredApiProvider.getCode() + "` listening on ", " ", PADDING_START_UP)
		);
		final String[] baseUrls = configuration.getBaseUrls();
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

		final ServerBuilder serverBuilder = Server.builder();
		this.registeredApiProviders = registerApiProviders(
			evita, this, apiOptions, externalApiProviders
		);
		if (this.registeredApiProviders.isEmpty()) {
			log.info("No external API providers were registered. No server will be created.");
			server = null;
			return;
		}

		try {
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
			configureArmeria(evita, serverBuilder, certificatePath, apiOptions);
		} catch (CertificateException | UnrecoverableKeyException | KeyStoreException | IOException |
		         NoSuchAlgorithmException e) {
			throw new GenericEvitaInternalError(
				"Failed to configure Armeria server with provided certificate and private key: " + e.getMessage(),
				"Failed to configure Armeria server with provided certificate and private key.",
				e
			);
		}

		this.server = serverBuilder.build();
		this.registeredApiProviders.values().forEach(ExternalApiProvider::afterAllInitialized);
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
		if (server == null) {
			return;
		}

		server.closeOnJvmShutdown();
		try {
			server.start().get();
			registeredApiProviders.values().forEach(ExternalApiProvider::afterStart);
		} catch (InterruptedException | ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Stops this running HTTP server and its registered APIs.
	 */
	@Override
	public void close() {
		try {
			closeAsynchronously().get(30, TimeUnit.SECONDS);
		} catch (Exception ex) {
			ConsoleWriter.write("Failed to stop external APIs in dedicated time (30 secs.).\n");
		}
	}

	/**
	 * Stops this running HTTP server and its registered APIs.
	 */
	@Nonnull
	public CompletableFuture<Void> closeAsynchronously() {
		if (this.stopFuture == null) {
			if (this.server == null) {
				this.stopFuture = CompletableFuture.completedFuture(null);
			} else {
				this.registeredApiProviders.values().forEach(ExternalApiProvider::beforeStop);
				final long start = System.nanoTime();
				this.stopFuture = this.server.stop()
					.thenAccept(
						unused -> ConsoleWriter.write(
							"External APIs stopped in " + StringUtils.formatPreciseNano(System.nanoTime() - start) + ".\n")
					);
			}
		}
		return this.stopFuture;
	}

	/**
	 * Configures Armeria server with all registered external API providers.
	 *
	 * @param serverBuilder   - Armeria server builder
	 * @param certificatePath - certificate path
	 * @param apiOptions      - API configuration options
	 * @throws CertificateException      when certificate cannot be created
	 * @throws UnrecoverableKeyException when key cannot be recovered
	 * @throws KeyStoreException         when keystore cannot be created or accessed
	 * @throws IOException               when IO operation fails
	 * @throws NoSuchAlgorithmException  when algorithm for creating cipher keys is not found
	 */
	private void configureArmeria(
		@Nonnull Evita evita,
		@Nonnull ServerBuilder serverBuilder,
		@Nonnull CertificatePath certificatePath,
		@Nonnull ApiOptions apiOptions
	) throws CertificateException, UnrecoverableKeyException, KeyStoreException, IOException, NoSuchAlgorithmException {
		// in tests we don't wait for shutdowns
		final boolean gracefulShutdown = !"true".equals(System.getProperty(DevelopmentConstants.TEST_RUN));
		// we share worker group both for I/O and service processing, all computations are done in separate executors
		// and having different worker groups for I/O and service processing only slows down the server
		// due to context switching between threads and branch misses
		final EventLoopGroup workerGroup = EventLoopGroups.newEventLoopGroup(apiOptions.workerGroupThreadsAsInt());
		serverBuilder
			.blockingTaskExecutor(evita.getServiceExecutor(), gracefulShutdown)
			.childChannelOption(ChannelOption.SO_REUSEADDR, true)
			.childChannelOption(ChannelOption.SO_KEEPALIVE, apiOptions.keepAlive())
			.decorator(DecodingService.newDecorator())
			.decorator(EncodingService.builder()
				.encodableContentTypes(
					MediaType.PLAIN_TEXT,
					MediaType.PLAIN_TEXT_UTF_8,
					MediaType.GRAPHQL,
					MediaType.GRAPHQL_RESPONSE_JSON,
					MediaType.JSON,
					MediaType.JSON_UTF_8
				)
				.newDecorator()
			)
			.errorHandler(LoggingServerErrorHandler.INSTANCE)
			.gracefulShutdownTimeout(gracefulShutdown ? Duration.ofSeconds(1) : Duration.ZERO, gracefulShutdown ? Duration.ofSeconds(1) : Duration.ZERO)
			.idleTimeoutMillis(apiOptions.idleTimeoutInMillis())
			.requestTimeoutMillis(apiOptions.requestTimeoutInMillis())
			.serviceWorkerGroup(workerGroup, gracefulShutdown)
			.maxRequestLength(apiOptions.maxEntitySizeInBytes())
			.verboseResponses(false)
			.workerGroup(workerGroup, gracefulShutdown);

		if (apiOptions.accessLog()) {
			serverBuilder
				.accessLogWriter(AccessLogWriter.combined(), gracefulShutdown)
				/* remote IP, remote host, remote logname, remote user, timestamp, request line, status code, length, header: Referer, header: User-Agent */
				.accessLogFormat("%a %h %l %u %t %r %s %b %{Referer}i %{User-Agent}i");
		}

		final List<FixedPathService> fixedPathHandlingServices = new LinkedList<>();
		// objects lazily initialized on first use
		PathHandlingService dynamicPathHandlingService = null;
		MtlsConfiguration mtlsConfiguration = null;

		// list of proxy provider configurations
		final AbstractApiConfiguration[] proxyConfigs = registeredApiProviders.values()
			.stream()
			.filter(ProxyingEndpointProvider.class::isInstance)
			.map(it -> apiOptions.endpoints().get(it.getCode()))
			.filter(AbstractApiConfiguration::isEnabled)
			.toArray(AbstractApiConfiguration[]::new);

		// for each API provider do
		for (ExternalApiProvider<?> registeredApiProvider : registeredApiProviders.values()) {
			final AbstractApiConfiguration configuration = apiOptions.endpoints().get(registeredApiProvider.getCode());
			// ok, only for those that are actually enabled ;)
			if (configuration.isEnabled()) {
				// if the API allows MTLS, retrieve its configuration
				if (configuration instanceof ApiConfigurationWithMutualTls apiWithMutualTls) {
					/* actually only gRPC API allows mTLS */
					Assert.isPremiseValid(
						mtlsConfiguration == null || mtlsConfiguration.equals(apiWithMutualTls.getMtlsConfiguration()),
						"Multiple APIs with different mutual TLS configuration found. Only one API can have mutual TLS enabled."
					);
					mtlsConfiguration = apiWithMutualTls.getMtlsConfiguration();
				}

				for (HostDefinition host : configuration.getHost()) {
					// if the host allows non-TLS interface, set it up
					final TlsMode tlsMode = configuration.getTlsMode();
					if (tlsMode == TlsMode.FORCE_NO_TLS || tlsMode == TlsMode.RELAXED) {
						if (host.localhost()) {
							serverBuilder.port(host.port(), SessionProtocol.HTTP, SessionProtocol.PROXY);
						} else {
							serverBuilder.port(new InetSocketAddress(host.host(), host.port()), SessionProtocol.HTTP, SessionProtocol.PROXY);
						}
					}
					// if the host allows TLS interface, set it up
					if (tlsMode == TlsMode.FORCE_TLS || tlsMode == TlsMode.RELAXED) {
						if (host.localhost()) {
							serverBuilder.port(host.port(), SessionProtocol.HTTPS, SessionProtocol.PROXY);
						} else {
							serverBuilder.port(new InetSocketAddress(host.host(), host.port()), SessionProtocol.HTTPS, SessionProtocol.PROXY);
						}
					}

					// now provide implementation for the host services
					for (HttpServiceDefinition httpServiceDefinition : registeredApiProvider.getHttpServiceDefinitions()) {
						final String basePath = configuration instanceof ApiWithSpecificPrefix apiWithSpecificPrefix ?
							apiWithSpecificPrefix.getPrefix() : "";

						// calculate base path of the service
						final String servicePath = "/" + basePath +
							ofNullable(httpServiceDefinition)
								.map(HttpServiceDefinition::path)
								.map(it -> !it.isEmpty() && it.charAt(0) == '/' ? it.substring(1) : it)
								.orElse("");

						// decorate the service with security decorator
						final HttpService service = httpServiceDefinition.service().decorate(
							new HttpServiceSecurityDecorator(
								ArrayUtils.mergeArrays(
									new AbstractApiConfiguration[]{configuration},
									proxyConfigs
								)
							)
						);
						if (httpServiceDefinition.pathHandlingMode() == PathHandlingMode.FIXED_PATH_HANDLING) {
							// if the service knows by itself how to route requests (HttpServiceWithRoutes), collect it
							// for later registration
							fixedPathHandlingServices.add(
								new FixedPathService(servicePath, service)
							);
						} else {
							// else use path handling service to route requests to the service's own custom router
							if (dynamicPathHandlingService == null) {
								dynamicPathHandlingService = new PathHandlingService();
							}
							dynamicPathHandlingService.addPrefixPath(servicePath, service);
						}
					}
				}
				logStatusToConsole(apiOptions, registeredApiProvider);
			}
		}

		// initialize and customize TLS settings if at least one endpoint requires / allows TLS protocol
		if (apiOptions.atLeastOneEndpointRequiresTls()) {
			final KeyManagerFactory keyFactory = createKeyManagerFactory(certificatePath);
			// configure TLS
			serverBuilder.tls(keyFactory);
			// customize TLS settings
			customizeTls(serverBuilder, apiOptions, mtlsConfiguration);
		}

		// we need to process APIs with PathHandlingMode.FIXED_PATH_HANDLING first,
		// because the DYNAMIC_PATH_HANDLING catches all other requests on the same route
		for (FixedPathService fixedPathHandlingService : fixedPathHandlingServices) {
			serverBuilder.serviceUnder(fixedPathHandlingService.servicePath(), fixedPathHandlingService.service());
		}

		// if path handling service is set, use it for all requests
		if (dynamicPathHandlingService != null) {
			serverBuilder.service("glob:/**", dynamicPathHandlingService)
				.decorator(LoggingService.newDecorator());
		}
	}

	/**
	 * Fixed path services must be initialized prior to dynamic path handling service.
	 *
	 * @param servicePath fixed path of the service
	 * @param service     service to be handled
	 */
	private record FixedPathService(
		@Nonnull String servicePath,
		@Nonnull HttpService service
	) {
	}

}
