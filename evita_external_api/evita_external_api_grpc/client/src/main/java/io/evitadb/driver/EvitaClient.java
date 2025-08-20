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

package io.evitadb.driver;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.Empty;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import io.evitadb.api.CatalogState;
import io.evitadb.api.CommitProgress;
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaManagementContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.driver.exception.EvitaClientServerCallException;
import io.evitadb.driver.exception.EvitaClientTimedOutException;
import io.evitadb.driver.exception.IncompatibleClientException;
import io.evitadb.driver.interceptor.ClientSessionInterceptor;
import io.evitadb.driver.trace.ClientTracingContext;
import io.evitadb.driver.trace.ClientTracingContextProvider;
import io.evitadb.driver.trace.DefaultClientTracingContext;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.InvalidEvitaVersionException;
import io.evitadb.externalApi.grpc.certificate.ClientCertificateManager;
import io.evitadb.externalApi.grpc.certificate.ClientCertificateManager.Builder;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc.EvitaServiceFutureStub;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingTopLevelCatalogSchemaMutationConverter;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CertificateUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ReflectionLookup;
import io.evitadb.utils.UUIDUtil;
import io.evitadb.utils.VersionUtils;
import io.evitadb.utils.VersionUtils.SemVer;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Optional.ofNullable;

/**
 * The EvitaClient implements {@link EvitaContract} interface and aims to behave identically as if the evitaDB is used
 * as an embedded engine. The purpose is to switch between the client & server setup and the single server setup
 * seamlessly. The client implementation takes advantage of gRPC API that is best suited for fast communication between
 * two endpoints if both parties are Java based.
 *
 * The class is thread-safe and can be used from multiple threads to acquire {@link EvitaClientSession} that are not
 * thread-safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @see EvitaContract
 */
@ThreadSafe
@Slf4j
public class EvitaClient implements EvitaContract {

	static final Pattern ERROR_MESSAGE_PATTERN = Pattern.compile("(\\w+:\\w+:\\w+): (.*)");
	/**
	 * Client call timeout.
	 */
	final ThreadLocal<LinkedList<Timeout>> timeout;
	/**
	 * Created evita service stub that returns futures.
	 */
	private final EvitaServiceFutureStub evitaServiceFutureStub;
	/**
	 * The configuration of the evitaDB client.
	 */
	@Getter private final EvitaClientConfiguration configuration;
	/**
	 * True if client is active and hasn't yet been closed.
	 */
	private final AtomicBoolean active = new AtomicBoolean(true);
	/**
	 * Reflection lookup is used to speed up reflection operation by memoizing the results for examined classes.
	 */
	@Getter private final ReflectionLookup reflectionLookup;
	/**
	 * Index of the {@link EntitySchemaContract} cache. See {@link EvitaEntitySchemaCache} for more information.
	 * The key in index is the catalog name.
	 */
	private final Map<String, EvitaEntitySchemaCache> entitySchemaCache = new ConcurrentHashMap<>(8);
	/**
	 * Index of the opened and active {@link EvitaClientSession} indexed by their unique {@link UUID}
	 */
	private final Map<UUID, EvitaSessionContract> activeSessions = CollectionUtils.createConcurrentHashMap(16);
	/**
	 * Executor service used for asynchronous operations.
	 */
	private final ExecutorService executor;
	/**
	 * Client manager.
	 */
	private final ClientFactory clientFactory;
	/**
	 * Builder for creating the gRPC client.
	 */
	private final GrpcClientBuilder grpcClientBuilder;
	/**
	 * Client implementation of management service.
	 */
	private final EvitaClientManagement management;

	/**
	 * Transforms the given Throwable into a RuntimeException based on its type.
	 *
	 * @param ex                The original exception to be transformed. Must not be null.
	 * @param onUnauthenticated A runnable to be executed if the exception indicates an unauthenticated status. Must not be null.
	 * @return A corresponding RuntimeException based on the type of the original exception.
	 */
	@Nonnull
	public static RuntimeException transformException(
		@Nonnull Throwable ex,
		@Nonnull Runnable onUnauthenticated
	) {
		if (ex instanceof StatusRuntimeException statusRuntimeException) {
			return transformStatusRuntimeException(statusRuntimeException, onUnauthenticated);
		} else if (ex instanceof EvitaInvalidUsageException invalidUsageException) {
			return invalidUsageException;
		} else if (ex instanceof EvitaInternalError evitaInternalError) {
			return evitaInternalError;
		} else {
			log.error("Unexpected internal Evita error occurred: {}", ex.getMessage(), ex);
			return new EvitaClientServerCallException(
				"Unexpected internal Evita error occurred.",
				ex
			);
		}
	}

	@Nonnull
	private static ClientTracingContext getClientTracingContext(@Nonnull EvitaClientConfiguration configuration) {
		final ClientTracingContext context = ClientTracingContextProvider.getContext();
		final Object openTelemetryInstance = configuration.openTelemetryInstance();
		if (openTelemetryInstance != null && context instanceof DefaultClientTracingContext) {
			throw new EvitaInvalidUsageException(
				"OpenTelemetry instance is set, but tracing context is not configured!"
			);
		}
		return context;
	}

	/**
	 * Handles a {@link StatusRuntimeException} by checking the status code and performing appropriate actions.
	 *
	 * @param statusRuntimeException the {@link StatusRuntimeException} to handle
	 * @param onUnauthenticated      the action to perform when the status code is {@link Code#UNAUTHENTICATED}
	 */
	@Nonnull
	private static RuntimeException transformStatusRuntimeException(
		@Nonnull StatusRuntimeException statusRuntimeException,
		@Nonnull Runnable onUnauthenticated
	) {
		final Code statusCode = statusRuntimeException.getStatus().getCode();
		final String description = ofNullable(statusRuntimeException.getStatus().getDescription())
			.map(it -> statusCode.name() + ": " + it)
			.orElseGet(statusCode::name);
		if (statusCode == Code.UNAUTHENTICATED) {
			onUnauthenticated.run();
			return new InstanceTerminatedException("session");
		} else if (statusCode == Code.INVALID_ARGUMENT || statusCode == Code.PERMISSION_DENIED) {
			final Matcher expectedFormat = ERROR_MESSAGE_PATTERN.matcher(description);
			if (expectedFormat.matches()) {
				return EvitaInvalidUsageException.createExceptionWithErrorCode(
					expectedFormat.group(2), expectedFormat.group(1)
				);
			} else {
				return new EvitaInvalidUsageException(description);
			}
		} else {
			final Matcher expectedFormat = ERROR_MESSAGE_PATTERN.matcher(description);
			if (expectedFormat.matches()) {
				return GenericEvitaInternalError.createExceptionWithErrorCode(
					expectedFormat.group(2), expectedFormat.group(1)
				);
			} else {
				return new GenericEvitaInternalError(description);
			}
		}
	}

	public EvitaClient(@Nonnull EvitaClientConfiguration configuration) {
		this(configuration, null);
	}

	public EvitaClient(
		@Nonnull EvitaClientConfiguration configuration,
		@Nullable Consumer<GrpcClientBuilder> grpcConfigurator
	) {
		this.configuration = configuration;
		ClientFactoryBuilder clientFactoryBuilder = ClientFactory.builder()
			.workerGroup(Runtime.getRuntime().availableProcessors())
			.idleTimeoutMillis(TimeUnit.MILLISECONDS.convert(configuration.timeout(), configuration.timeoutUnit()))
			.pingIntervalMillis(1000);

		final String uriScheme;
		if (configuration.tlsEnabled()) {
			uriScheme = "https";

			final Builder certificateBuilder = new Builder()
				.useGeneratedCertificate(configuration.useGeneratedCertificate(), configuration.host(), configuration.systemApiPort())
				.usingTrustedServerCertificate(configuration.trustCertificate())
				.trustStorePassword(configuration.trustStorePassword())
				.mtls(configuration.mtlsEnabled())
				.clientCertificateFilePath(configuration.certificateFileName())
				.clientPrivateKeyFilePath(configuration.certificateKeyFileName())
				.clientPrivateKeyPassword(configuration.certificateKeyPassword());
			if (configuration.certificateFolderPath() != null) {
				certificateBuilder.certificateClientFolderPath(configuration.certificateFolderPath());
			}
			if (configuration.serverCertificatePath() != null) {
				certificateBuilder.serverCertificateFilePath(configuration.serverCertificatePath());
			}
			final ClientCertificateManager clientCertificateManager = certificateBuilder.build();

			clientFactoryBuilder = clientCertificateManager.buildClientSslContext((certificateType, certificate) -> {
					try {
						switch (certificateType) {
							case SERVER ->
								log.info("Server's certificate fingerprint: {}", CertificateUtils.getCertificateFingerprint(certificate));
							case CLIENT ->
								log.info("Client's certificate fingerprint: {}", CertificateUtils.getCertificateFingerprint(certificate));
						}
					} catch (NoSuchAlgorithmException | CertificateEncodingException e) {
						throw new GenericEvitaInternalError(
							"Failed to get certificate fingerprint.",
							"Failed to get certificate fingerprint: " + e.getMessage(),
							e
						);
					}
				},
				clientFactoryBuilder
			);
		} else {
			uriScheme = "http";
		}

		this.executor = Executors.newCachedThreadPool();
		this.clientFactory = clientFactoryBuilder.build();

		SemVer clientVersion;
		try {
			clientVersion = SemVer.fromString(getVersion());
		} catch (InvalidEvitaVersionException e) {
			clientVersion = null;
		}

		final GrpcClientBuilder grpcClientBuilder = GrpcClients.builder(uriScheme + "://" + configuration.host() + ":" + configuration.port() + "/")
			.factory(this.clientFactory)
			.serializationFormat(GrpcSerializationFormats.PROTO)
			.intercept(new ClientSessionInterceptor(configuration.clientId(), clientVersion));

		if (configuration.retry()) {
			grpcClientBuilder.decorator(
				RetryingClient.builder(
					RetryRule.of(
						RetryRule.builder().onTimeoutException().thenBackoff(),
						RetryRule.builder().onStatus(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT, HttpStatus.UNKNOWN).thenBackoff(),
						RetryRule.builder().onStatus(HttpStatus.TOO_MANY_REQUESTS).thenNoRetry()
					)
				)
					.useRetryAfter(true)
					.newDecorator()
			);
		}

		final ClientTracingContext context = getClientTracingContext(configuration);
		if (configuration.openTelemetryInstance() != null) {
			context.setOpenTelemetry(configuration.openTelemetryInstance());
		}

		ofNullable(grpcConfigurator).ifPresent(it -> it.accept(grpcClientBuilder));
		this.grpcClientBuilder = grpcClientBuilder;
		this.evitaServiceFutureStub = grpcClientBuilder.build(EvitaServiceFutureStub.class);
		this.reflectionLookup = new ReflectionLookup(configuration.reflectionLookupBehaviour());
		this.timeout = ThreadLocal.withInitial(() -> {
			final LinkedList<Timeout> timeouts = new LinkedList<>();
			timeouts.add(new Timeout(configuration.timeout(), configuration.timeoutUnit()));
			return timeouts;
		});
		this.management = new EvitaClientManagement(this, this.grpcClientBuilder);
		this.active.set(true);

		try {
			if (clientVersion == null) {
				log.warn("Client version `{}` is not a valid semantic version. Aborting version check, this situation may lead to compatibility issues.", getVersion());
				return;
			}

			final SystemStatus systemStatus = this.management().getSystemStatus();
			final SemVer serverVersion;

			try {
				serverVersion = SemVer.fromString(systemStatus.version());
			} catch (InvalidEvitaVersionException e) {
				log.warn("Server version `{}` is not a valid semantic version. Aborting version check, this situation may lead to compatibility issues.", systemStatus.version());
				return;
			}

			final int comparisonResult = SemVer.compare(clientVersion, serverVersion);
			if (comparisonResult < 0) {
				log.warn(
					"Client version {} is lower than the server version {}. " +
						"It may not represent a compatibility issue, but it is recommended to update " +
						"the client to the latest version.",
					clientVersion,
					serverVersion
				);
			} else if (comparisonResult > 0) {
				if (clientVersion.snapshot() || serverVersion.snapshot()) {
					log.warn(
						"Client version `{}` is higher than server version `{}`. " +
							"This situation might lead to compatibility issues, but there is SNAPSHOT version involved " +
							"and some kind of testing is probably happening.",
						clientVersion,
						serverVersion
					);
				} else {
					throw new IncompatibleClientException(
						"Client version `" + clientVersion + "` is higher than the server version `" + serverVersion + "`. " +
							"This situation will probably lead to compatibility issues. Please update the server to " +
							"the latest version.",
						"Incompatible client version!"
					);
				}
			}
		} catch (IncompatibleClientException ex) {
			throw ex;
		} catch (Exception ex) {
			log.error("Failed to connect to the evitaDB server. Please check the connection settings.", ex);
		}
	}

	@Override
	public boolean isActive() {
		return this.active.get();
	}

	@Nonnull
	@Override
	public EvitaClientSession createSession(@Nonnull SessionTraits traits) {
		assertActive();
		final GrpcEvitaSessionResponse grpcResponse;

		final GrpcEvitaSessionRequest.Builder sessionBuilder = GrpcEvitaSessionRequest.newBuilder()
			.setCatalogName(traits.catalogName())
			.setDryRun(traits.isDryRun());

		if (traits.isReadWrite()) {
			if (traits.commitBehaviour() != null) {
				sessionBuilder.setCommitBehavior(EvitaEnumConverter.toGrpcCommitBehavior(traits.commitBehaviour()));
			}
			if (traits.isBinary()) {
				grpcResponse = executeWithEvitaService(
					evitaService -> evitaService.createBinaryReadWriteSession(
						sessionBuilder.build()
					)
				);
			} else {
				grpcResponse = executeWithEvitaService(
					evitaService -> evitaService.createReadWriteSession(
						sessionBuilder.build()
					)
				);
			}
		} else {
			if (traits.isBinary()) {
				grpcResponse = executeWithEvitaService(
					evitaService -> evitaService.createBinaryReadOnlySession(
						sessionBuilder.build()
					)
				);
			} else {
				grpcResponse = executeWithEvitaService(
					evitaService -> evitaService.createReadOnlySession(
						sessionBuilder.build()
					)
				);
			}
		}
		final EvitaClientSession evitaClientSession = new EvitaClientSession(
			this,
			this.management,
			this.entitySchemaCache.computeIfAbsent(
				traits.catalogName(),
				catalogName -> new EvitaEntitySchemaCache(catalogName, this.reflectionLookup)
			),
			this.grpcClientBuilder,
			traits.catalogName(),
			EvitaEnumConverter.toCatalogState(grpcResponse.getCatalogState()),
			ofNullable(grpcResponse.getCatalogId())
				.filter(it -> !it.isBlank())
				.map(UUIDUtil::uuid)
				.orElseGet(UUIDUtil::randomUUID),
			UUIDUtil.uuid(grpcResponse.getSessionId()),
			EvitaEnumConverter.toCommitBehavior(grpcResponse.getCommitBehaviour()),
			traits,
			evitaSession -> {
				this.activeSessions.remove(evitaSession.getId());
				ofNullable(traits.onTermination())
					.ifPresent(it -> it.onTermination(evitaSession));
			},
			Objects.requireNonNull(this.timeout.get().peek())
		);

		this.activeSessions.put(evitaClientSession.getId(), evitaClientSession);
		return evitaClientSession;
	}

	@Nonnull
	@Override
	public Optional<EvitaSessionContract> getSessionById(@Nonnull UUID uuid) {
		return ofNullable(this.activeSessions.get(uuid));
	}

	@Override
	public void terminateSession(@Nonnull EvitaSessionContract session) {
		assertActive();
		if (session instanceof EvitaClientSession evitaClientSession) {
			evitaClientSession.close();
		} else {
			throw new EvitaInvalidUsageException(
				"Passed session is expected to be `EvitaClientSession`, but it is not (" + session.getClass().getSimpleName() + ")!"
			);
		}
	}

	@Nonnull
	@Override
	public Set<String> getCatalogNames() {
		assertActive();
		final GrpcCatalogNamesResponse grpcResponse = executeWithEvitaService(
			evitaService -> evitaService.getCatalogNames(Empty.newBuilder().build())
		);
		return new LinkedHashSet<>(
			grpcResponse.getCatalogNamesList()
		);
	}

	@Nonnull
	@Override
	public Optional<CatalogState> getCatalogState(@Nonnull String catalogName) {
		assertActive();
		final GrpcGetCatalogStateResponse grpcResponse = executeWithEvitaService(
			evitaService -> evitaService.getCatalogState(GrpcGetCatalogStateRequest.newBuilder().setCatalogName(catalogName).build())
		);
		return grpcResponse.hasCatalogState() ?
			Optional.of(EvitaEnumConverter.toCatalogState(grpcResponse.getCatalogState())) :
			Optional.empty();
	}

	@Nonnull
	@Override
	public CatalogSchemaBuilder defineCatalog(@Nonnull String catalogName) {
		assertActive();
		if (!getCatalogNames().contains(catalogName)) {
			update(new CreateCatalogSchemaMutation(catalogName));
		}
		return queryCatalog(
			catalogName,
			session -> {
				return ((EvitaClientSession) session).getCatalogSchema(this);
			}
		).openForWrite();
	}

	@Override
	public void renameCatalog(@Nonnull String catalogName, @Nonnull String newCatalogName) {
		assertActive();
		final GrpcRenameCatalogRequest request = GrpcRenameCatalogRequest.newBuilder()
			.setCatalogName(catalogName)
			.setNewCatalogName(newCatalogName)
			.build();
		final GrpcRenameCatalogResponse grpcResponse = executeWithEvitaService(
			evitaService -> evitaService.renameCatalog(request)
		);
		final boolean success = grpcResponse.getSuccess();
		if (success) {
			this.entitySchemaCache.remove(catalogName);
			this.entitySchemaCache.remove(newCatalogName);
		}
	}

	@Override
	public void replaceCatalog(@Nonnull String catalogNameToBeReplacedWith, @Nonnull String catalogNameToBeReplaced) {
		assertActive();
		final GrpcReplaceCatalogRequest request = GrpcReplaceCatalogRequest.newBuilder()
			.setCatalogNameToBeReplacedWith(catalogNameToBeReplacedWith)
			.setCatalogNameToBeReplaced(catalogNameToBeReplaced)
			.build();

		final GrpcReplaceCatalogResponse grpcResponse = executeWithEvitaService(
			evitaService -> evitaService.replaceCatalog(request)
		);
		final boolean success = grpcResponse.getSuccess();
		if (success) {
			this.entitySchemaCache.remove(catalogNameToBeReplaced);
			this.entitySchemaCache.remove(catalogNameToBeReplacedWith);
		}
	}

	@Override
	public boolean deleteCatalogIfExists(@Nonnull String catalogName) {
		assertActive();

		final GrpcDeleteCatalogIfExistsRequest request = GrpcDeleteCatalogIfExistsRequest.newBuilder()
			.setCatalogName(catalogName)
			.build();

		final GrpcDeleteCatalogIfExistsResponse grpcResponse = executeWithEvitaService(
			evitaService -> evitaService.deleteCatalogIfExists(request)
		);
		final boolean success = grpcResponse.getSuccess();
		if (success) {
			this.entitySchemaCache.remove(catalogName);
		}
		return success;
	}

	@Override
	public void update(@Nonnull TopLevelCatalogSchemaMutation... catalogMutations) {
		assertActive();

		final List<GrpcTopLevelCatalogSchemaMutation> grpcSchemaMutations = Arrays.stream(catalogMutations)
			.map(DelegatingTopLevelCatalogSchemaMutationConverter.INSTANCE::convert)
			.toList();

		final GrpcUpdateEvitaRequest request = GrpcUpdateEvitaRequest.newBuilder()
			.addAllSchemaMutations(grpcSchemaMutations)
			.build();

		executeWithEvitaService(
			evitaService -> evitaService.update(request)
		);
	}

	@Override
	public <T> T queryCatalog(@Nonnull String catalogName, @Nonnull Function<EvitaSessionContract, T> queryLogic, @Nullable SessionFlags... flags) {
		assertActive();
		try (final EvitaSessionContract session = this.createSession(new SessionTraits(catalogName, flags))) {
			return queryLogic.apply(session);
		}
	}

	@Override
	public void queryCatalog(@Nonnull String
		                         catalogName, @Nonnull Consumer<EvitaSessionContract> queryLogic, @Nullable SessionFlags... flags) {
		assertActive();
		try (final EvitaSessionContract session = this.createSession(new SessionTraits(catalogName, flags))) {
			queryLogic.accept(session);
		}
	}

	@Nonnull
	@Override
	public <T> CompletableFuture<T> queryCatalogAsync(@Nonnull String catalogName, @Nonnull Function<EvitaSessionContract, T> queryLogic, @Nullable SessionFlags... flags) {
		return CompletableFuture.supplyAsync(
			() -> {
				assertActive();
				try (final EvitaSessionContract session = this.createSession(new SessionTraits(catalogName, flags))) {
					return queryLogic.apply(session);
				}
			},
			this.executor
		);
	}

	@Override
	public <T> T updateCatalog(
		@Nonnull String catalogName,
		@Nonnull Function<EvitaSessionContract, T> updater,
		@Nonnull CommitBehavior commitBehaviour,
		@Nullable SessionFlags... flags
	) {
		assertActive();
		final SessionTraits traits = new SessionTraits(
			catalogName,
			commitBehaviour,
			flags == null ?
				new SessionFlags[]{SessionFlags.READ_WRITE} :
				ArrayUtils.insertRecordIntoArrayOnIndex(SessionFlags.READ_WRITE, flags, flags.length)
		);
		try (final EvitaSessionContract session = this.createSession(traits)) {
			return updater.apply(session);
		}
	}

	@Nonnull
	@Override
	public <T> CompletionStage<T> updateCatalogAsync(
		@Nonnull String catalogName,
		@Nonnull Function<EvitaSessionContract, T> updater,
		@Nonnull CommitBehavior commitBehaviour,
		@Nullable SessionFlags... flags
	) {
		assertActive();
		final SessionTraits traits = new SessionTraits(
			catalogName,
			commitBehaviour,
			flags == null ?
				new SessionFlags[]{SessionFlags.READ_WRITE} :
				ArrayUtils.insertRecordIntoArrayOnIndex(SessionFlags.READ_WRITE, flags, flags.length)
		);
		final EvitaSessionContract session = this.createSession(traits);
		final CompletionStage<CommitVersions> closeFuture;
		final T resultValue;
		try {
			resultValue = updater.apply(session);
		} finally {
			closeFuture = session.closeNow(commitBehaviour);
		}

		// join the transaction future and return
		final CompletableFuture<T> result = new CompletableFuture<>();
		closeFuture.whenComplete((txId, ex) -> {
			if (ex != null) {
				result.completeExceptionally(ex);
			} else {
				result.complete(resultValue);
			}
		});
		return result;
	}

	@Override
	public void updateCatalog(@Nonnull String catalogName, @Nonnull Consumer<EvitaSessionContract> updater, @Nonnull CommitBehavior commitBehaviour, @Nullable SessionFlags... flags) {
		assertActive();
		final SessionTraits traits = new SessionTraits(
			catalogName,
			commitBehaviour,
			flags == null ?
				new SessionFlags[]{SessionFlags.READ_WRITE} :
				ArrayUtils.insertRecordIntoArrayOnIndex(SessionFlags.READ_WRITE, flags, flags.length)
		);
		try (final EvitaSessionContract session = this.createSession(traits)) {
			updater.accept(session);
		}
	}

	@Nonnull
	@Override
	public CommitProgress updateCatalogAsync(
		@Nonnull String catalogName,
		@Nonnull Consumer<EvitaSessionContract> updater,
		@Nonnull CommitBehavior commitBehaviour,
		@Nullable SessionFlags... flags
	) throws TransactionException {
		assertActive();
		final SessionTraits traits = new SessionTraits(
			catalogName,
			commitBehaviour,
			flags == null ?
				new SessionFlags[]{SessionFlags.READ_WRITE} :
				ArrayUtils.insertRecordIntoArrayOnIndex(SessionFlags.READ_WRITE, flags, flags.length)
		);
		final EvitaSessionContract session = this.createSession(traits);
		final CommitProgress commitProgress;
		try {
			updater.accept(session);
		} finally {
			commitProgress = session.closeNowWithProgress();
		}

		return commitProgress;
	}

	@Nonnull
	@Override
	public EvitaManagementContract management() {
		return this.management;
	}

	@Override
	public void close() {
		if (this.active.compareAndSet(true, false)) {
			this.activeSessions.values().forEach(EvitaSessionContract::close);
			this.activeSessions.clear();
			this.management.close();
			this.clientFactory.close();
		}
	}

	/**
	 * Retrieves the version number of the evitaDB client.
	 *
	 * @return The version number as a string.
	 */
	@Nonnull
	public String getVersion() {
		return VersionUtils.readVersion();
	}

	/**
	 * Method executes lambda using specified timeout for the call ignoring the defaults specified
	 * in {@link EvitaClientConfiguration#timeout()}.
	 *
	 * @param lambda  logic to be executed
	 * @param timeout timeout value
	 * @param unit    time unit of the timeout
	 */
	@SuppressWarnings("unused")
	public void executeWithExtendedTimeout(@Nonnull Runnable lambda, long timeout, @Nonnull TimeUnit unit) {
		final LinkedList<Timeout> callTimeouts = this.timeout.get();
		try {
			callTimeouts.push(new Timeout(timeout, unit));
			lambda.run();
		} finally {
			callTimeouts.pop();
		}
	}

	/**
	 * Method executes lambda using specified timeout for the call ignoring the defaults specified
	 * in {@link EvitaClientConfiguration#timeout()}.
	 *
	 * @param lambda  logic to be executed
	 * @param timeout timeout value
	 * @param unit    time unit of the timeout
	 * @param <T>     type of the result
	 * @return result of the lambda
	 */
	@SuppressWarnings("unused")
	public <T> T executeWithExtendedTimeout(@Nonnull Supplier<T> lambda, long timeout, @Nonnull TimeUnit unit) {
		final LinkedList<Timeout> callTimeouts = this.timeout.get();
		try {
			callTimeouts.push(new Timeout(timeout, unit));
			return lambda.get();
		} finally {
			callTimeouts.pop();
		}
	}

	/**
	 * Verifies this instance is still active.
	 */
	protected void assertActive() {
		if (!this.active.get()) {
			throw new InstanceTerminatedException("client instance");
		}
	}

	/**
	 * Method that is called within the {@link EvitaClientSession} to apply the wanted logic on a channel retrieved
	 * from a channel pool.
	 *
	 * @param lambda function that holds a logic passed by the caller
	 * @param <T>    return type of the function
	 * @return result of the applied function
	 */
	private <T> T executeWithEvitaService(
		@Nonnull AsyncCallFunction<EvitaServiceFutureStub, ListenableFuture<T>> lambda
	) {
		final Timeout timeout = Objects.requireNonNull(this.timeout.get().peek());
		try {
			return lambda.apply(this.evitaServiceFutureStub.withDeadlineAfter(timeout.timeout(), timeout.timeoutUnit()))
				.get(timeout.timeout(), timeout.timeoutUnit());
		} catch (ExecutionException e) {
			throw EvitaClient.transformException(
				e.getCause() == null ? e : e.getCause(),
				() -> {
				}
			);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new EvitaClientServerCallException("Server call interrupted.", e);
		} catch (TimeoutException e) {
			throw new EvitaClientTimedOutException(
				timeout.timeout(), timeout.timeoutUnit()
			);
		}
	}

}
