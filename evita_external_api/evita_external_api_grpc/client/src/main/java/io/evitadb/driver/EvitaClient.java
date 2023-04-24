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

package io.evitadb.driver;

import com.google.protobuf.Empty;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaNameMutation;
import io.evitadb.driver.certificate.ClientCertificateManager;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.driver.exception.EvitaClientNotTerminatedInTimeException;
import io.evitadb.driver.pooling.ChannelPool;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc.EvitaServiceBlockingStub;
import io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse;
import io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsRequest;
import io.evitadb.externalApi.grpc.generated.GrpcDeleteCatalogIfExistsResponse;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse;
import io.evitadb.externalApi.grpc.generated.GrpcTopLevelCatalogSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcUpdateEvitaRequest;
import io.evitadb.externalApi.grpc.interceptor.ClientSessionInterceptor;
import io.evitadb.externalApi.grpc.interceptor.ClientSessionInterceptor.SessionIdHolder;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingTopLevelCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ReflectionLookup;
import io.evitadb.utils.UUIDUtil;
import io.grpc.ManagedChannel;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Optional.ofNullable;

/**
 * The EvitaClient implements {@link EvitaContract} interface and aims to behave identically as if the evitaDB is used
 * as an embedded engine. The purpose is to switch between the client & server setup and the single server setup
 * seamlessly. The client & server implementation takes advantage of gRPC API that is best suited for fast communication
 * between two endpoints if both parties are Java based.
 *
 * The class is thread-safe and can be used from multiple threads to acquire {@link EvitaClientSession} that are not
 * thread-safe.
 *
 * @see EvitaContract
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Slf4j
public class EvitaClient implements EvitaContract {
	private static final SchemaMutationConverter<TopLevelCatalogSchemaMutation, GrpcTopLevelCatalogSchemaMutation> CATALOG_SCHEMA_MUTATION_CONVERTER =
		new DelegatingTopLevelCatalogSchemaMutationConverter();

	@Getter private final EvitaClientConfiguration configuration;
	private final ChannelPool channelPool;
	/**
	 * True if client is active and hasn't yet been closed.
	 */
	private final AtomicBoolean active = new AtomicBoolean(true);
	/**
	 * Reflection lookup is used to speed up reflection operation by memoizing the results for examined classes.
	 */
	private final ReflectionLookup reflectionLookup;
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
	 * A lambda that needs to be invoked upon EvitaClient closing. It goes through all opened {@link EvitaClientSession}
	 * and closes them along with their gRPC channels.
	 */
	private final Runnable terminationCallback;

	/**
	 * Method that is called within the {@link EvitaClientSession} to apply the wanted logic on a channel retrieved
	 * from a channel pool.
	 *
	 * @param evitaServiceBlockingStub function that holds a logic passed by the caller
	 * @param <T>                      return type of the function
	 * @return result of the applied function
	 */
	private <T> T executeWithEvitaService(@Nonnull Function<EvitaServiceBlockingStub, T> evitaServiceBlockingStub) {
		final ManagedChannel managedChannel = this.channelPool.getChannel();
		try {
			return evitaServiceBlockingStub.apply(EvitaServiceGrpc.newBlockingStub(managedChannel));
		} finally {
			this.channelPool.releaseChannel(managedChannel);
		}
	}

	public EvitaClient(@Nonnull EvitaClientConfiguration configuration) {
		this(configuration, null);
	}

	public EvitaClient(
		@Nonnull EvitaClientConfiguration configuration,
		@Nullable Consumer<NettyChannelBuilder> grpcConfigurator
	) {
		this.configuration = configuration;
		final ClientCertificateManager clientCertificateManager = new ClientCertificateManager.Builder()
			.useGeneratedCertificate(configuration.useGeneratedCertificate(), configuration.host(), configuration.systemApiPort())
			.usingTrustedRootCaCertificate(configuration.trustCertificate())
			.mtls(configuration.mtlsEnabled())
			.certificateClientFolderPath(configuration.certificateFolderPath())
			.clientCertificateFilePath(configuration.certificateFileName())
			.clientPrivateKeyFilePath(configuration.certificateKeyFileName())
			.build();

		final NettyChannelBuilder nettyChannelBuilder = NettyChannelBuilder.forAddress(configuration.host(), configuration.port())
			.sslContext(clientCertificateManager.buildClientSslContext())
			.executor(Executors.newCachedThreadPool())
			.defaultLoadBalancingPolicy("round_robin")
			.intercept(new ClientSessionInterceptor());

		ofNullable(grpcConfigurator)
			.ifPresent(it -> it.accept(nettyChannelBuilder));
		this.reflectionLookup = new ReflectionLookup(configuration.reflectionLookupBehaviour());
		this.channelPool = new ChannelPool(nettyChannelBuilder, 10);
		this.terminationCallback = () -> {
			try {
				Assert.isTrue(
					this.channelPool.awaitTermination(configuration.waitForClose(), configuration.waitForCloseUnit()),
					() -> new EvitaClientNotTerminatedInTimeException(configuration.waitForClose(), configuration.waitForCloseUnit())
				);
			} catch (InterruptedException e) {
				// terminated
				Thread.currentThread().interrupt();
			}
		};;
		this.active.set(true);
	}

	@Nonnull
	@Override
	public EvitaSessionContract createReadOnlySession(@Nonnull String catalogName) {
		return createSession(
			new SessionTraits(catalogName)
		);
	}

	@Nonnull
	@Override
	public EvitaSessionContract createReadWriteSession(@Nonnull String catalogName) {
		return createSession(
			new SessionTraits(catalogName, SessionFlags.READ_WRITE)
		);
	}

	@Nonnull
	@Override
	public EvitaSessionContract createSession(@Nonnull SessionTraits traits) {
		assertActive();
		final GrpcEvitaSessionResponse grpcResponse;

		if (traits.isReadWrite()) {
			if (traits.isBinary()) {
				grpcResponse = executeWithEvitaService(evitaService ->
					evitaService.createBinaryReadWriteSession(
						GrpcEvitaSessionRequest.newBuilder()
							.setCatalogName(traits.catalogName())
							.setDryRun(traits.isDryRun())
							.build()
					)
				);
			} else {
				grpcResponse = executeWithEvitaService(evitaService ->
					evitaService.createReadWriteSession(
						GrpcEvitaSessionRequest.newBuilder()
							.setCatalogName(traits.catalogName())
							.setDryRun(traits.isDryRun())
							.build()
					)
				);
			}
		} else {
			if (traits.isBinary()) {
				grpcResponse = executeWithEvitaService(evitaService ->
					evitaService.createBinaryReadOnlySession(
						GrpcEvitaSessionRequest.newBuilder()
							.setCatalogName(traits.catalogName())
							.setDryRun(traits.isDryRun())
							.build()
					)
				);
			} else {
				grpcResponse = executeWithEvitaService(evitaService ->
					evitaService.createReadOnlySession(
						GrpcEvitaSessionRequest.newBuilder()
							.setCatalogName(traits.catalogName())
							.setDryRun(traits.isDryRun())
							.build()
					)
				);
			}
		}
		final EvitaClientSession evitaClientSession = new EvitaClientSession(
			this.reflectionLookup,
			this.entitySchemaCache.computeIfAbsent(
				traits.catalogName(),
				EvitaEntitySchemaCache::new
			),
			this.channelPool,
			traits.catalogName(),
			EvitaEnumConverter.toCatalogState(grpcResponse.getCatalogState()),
			UUIDUtil.uuid(grpcResponse.getSessionId()),
			traits,
			evitaSession -> {
				this.activeSessions.remove(evitaSession.getId());
				ofNullable(traits.onTermination())
					.ifPresent(it -> it.onTermination(evitaSession));
			}
		);
		final EvitaSessionContract newSessionProxy = (EvitaSessionContract) Proxy.newProxyInstance(
			EvitaSessionContract.class.getClassLoader(),
			new Class[]{EvitaSessionContract.class},
			new EvitaSessionProxy(evitaClientSession)
		);

		this.activeSessions.put(evitaClientSession.getId(), newSessionProxy);
		return newSessionProxy;
	}

	@Nonnull
	@Override
	public Optional<EvitaSessionContract> getSessionById(@Nonnull String catalogName, @Nonnull UUID uuid) {
		return ofNullable(this.activeSessions.get(uuid))
			.filter(it -> catalogName.equals(it.getCatalogName()));
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
		final GrpcCatalogNamesResponse grpcResponse = executeWithEvitaService(evitaService -> evitaService.getCatalogNames(Empty.newBuilder().build()));
		return new LinkedHashSet<>(
			grpcResponse.getCatalogNamesList()
		);
	}

	@Nonnull
	@Override
	public CatalogSchemaBuilder defineCatalog(@Nonnull String catalogName) {
		assertActive();

		if (!getCatalogNames().contains(catalogName)) {
			update(new CreateCatalogSchemaMutation(catalogName));
		}
		return queryCatalog(catalogName, EvitaSessionContract::getCatalogSchema)
			.openForWrite();
	}

	@Override
	public void renameCatalog(@Nonnull String catalogName, @Nonnull String newCatalogName) {
		assertActive();
		update(new ModifyCatalogSchemaNameMutation(catalogName, newCatalogName, false));
		this.entitySchemaCache.remove(catalogName);
		this.entitySchemaCache.remove(newCatalogName);
	}

	@Override
	public void replaceCatalog(@Nonnull String catalogNameToBeReplacedWith, @Nonnull String catalogNameToBeReplaced) {
		assertActive();
		update(new ModifyCatalogSchemaNameMutation(catalogNameToBeReplacedWith, catalogNameToBeReplaced, true));
		this.entitySchemaCache.remove(catalogNameToBeReplaced);
		this.entitySchemaCache.remove(catalogNameToBeReplacedWith);
	}

	@Override
	public boolean deleteCatalogIfExists(@Nonnull String catalogName) {
		assertActive();

		final GrpcDeleteCatalogIfExistsRequest request = GrpcDeleteCatalogIfExistsRequest.newBuilder()
			.setCatalogName(catalogName)
			.build();
		final GrpcDeleteCatalogIfExistsResponse grpcResponse = executeWithEvitaService(evitaService -> evitaService.deleteCatalogIfExists(request));
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
			.map(CATALOG_SCHEMA_MUTATION_CONVERTER::convert)
			.toList();

		final GrpcUpdateEvitaRequest request = GrpcUpdateEvitaRequest.newBuilder()
			.addAllSchemaMutations(grpcSchemaMutations)
			.build();
		executeWithEvitaService(evitaService -> evitaService.update(request));
	}

	@Override
	public <T> T queryCatalog(@Nonnull String catalogName, @Nonnull Function<EvitaSessionContract, T> queryLogic, @Nullable SessionFlags... flags) {
		assertActive();
		try (final EvitaSessionContract session = this.createSession(new SessionTraits(catalogName, flags))) {
			return queryLogic.apply(session);
		}
	}

	@Override
	public void queryCatalog(@Nonnull String catalogName, @Nonnull Consumer<EvitaSessionContract> queryLogic, @Nullable SessionFlags... flags) {
		assertActive();
		try (final EvitaSessionContract session = this.createSession(new SessionTraits(catalogName, flags))) {
			queryLogic.accept(session);
		}
	}

	@Override
	public <T> T updateCatalog(@Nonnull String catalogName, @Nonnull Function<EvitaSessionContract, T> updater, @Nullable SessionFlags... flags) {
		assertActive();
		final SessionTraits traits = new SessionTraits(
			catalogName,
			flags == null ?
				new SessionFlags[]{SessionFlags.READ_WRITE} :
				ArrayUtils.insertRecordIntoArray(SessionFlags.READ_WRITE, flags, flags.length)
		);
		try (final EvitaSessionContract session = this.createSession(traits)) {
			return session.execute(updater);
		}
	}

	@Override
	public void updateCatalog(@Nonnull String catalogName, @Nonnull Consumer<EvitaSessionContract> updater, @Nullable SessionFlags... flags) {
		updateCatalog(
			catalogName,
			evitaSession -> {
				updater.accept(evitaSession);
				return null;
			},
			flags
		);
	}

	@Override
	public void close() {
		if (active.compareAndSet(true, false)) {
			this.activeSessions.values().forEach(EvitaSessionContract::close);
			this.activeSessions.clear();
			this.channelPool.shutdown();
			this.terminationCallback.run();
		}
	}

	/**
	 * Verifies this instance is still active.
	 */
	protected void assertActive() {
		if (!active.get()) {
			throw new InstanceTerminatedException("client instance");
		}
	}

	/**
	 * This handler is an infrastructural handler that delegates all calls to {@link #evitaSession}. We'll pay some
	 * performance price by wrapping {@link EvitaClientSession} in a proxy, that uses this error handler (all calls
	 * on session object will be approximately 1.7x less performant -
	 * <a href="http://ordinaryjava.blogspot.com/2008/08/benchmarking-cost-of-dynamic-proxies.html">source</a>) but this
	 * way we can isolate the error handling in one place and avoid cluttering the source code. Graal
	 * supports JDK proxies out-of-the-box so this shouldn't be a problem in the future.
	 */
	@RequiredArgsConstructor
	private static class EvitaSessionProxy implements InvocationHandler {
		private static final Pattern ERROR_MESSAGE_PATTERN = Pattern.compile("(\\w+:\\w+:\\w+): (.*)");
		private final EvitaClientSession evitaSession;

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			// invoke original method on delegate
			try {
				SessionIdHolder.setSessionId(evitaSession.getCatalogName(), evitaSession.getId().toString());
				try {
					return method.invoke(evitaSession, args);
				} catch (InvocationTargetException ex) {
					// handle the error
					final Throwable targetException = ex.getTargetException();
					if (targetException instanceof StatusRuntimeException statusRuntimeException) {
						final Code statusCode = statusRuntimeException.getStatus().getCode();
						final String description = ofNullable(statusRuntimeException.getStatus().getDescription())
							.orElse("No description.");
						if (statusCode == Code.UNAUTHENTICATED) {
							// close session and rethrow
							evitaSession.closeInternally();
							throw new InstanceTerminatedException("session");
						} else if (statusCode == Code.INVALID_ARGUMENT) {
							final Matcher expectedFormat = ERROR_MESSAGE_PATTERN.matcher(description);
							if (expectedFormat.matches()) {
								throw EvitaInvalidUsageException.createExceptionWithErrorCode(
									expectedFormat.group(2), expectedFormat.group(1)
								);
							} else {
								throw new EvitaInvalidUsageException(description);
							}
						} else {
							final Matcher expectedFormat = ERROR_MESSAGE_PATTERN.matcher(description);
							if (expectedFormat.matches()) {
								throw EvitaInternalError.createExceptionWithErrorCode(
									expectedFormat.group(2), expectedFormat.group(1)
								);
							} else {
								throw new EvitaInternalError(description);
							}
						}
					} else if (targetException instanceof EvitaInvalidUsageException) {
						throw (EvitaInvalidUsageException) targetException;
					} else if (targetException instanceof EvitaInternalError) {
						throw (EvitaInternalError) targetException;
					} else {
						log.error("Unexpected internal Evita error occurred: {}", ex.getMessage(), ex);
						throw new EvitaInternalError(
							"Unexpected internal Evita error occurred: " + ex.getMessage(),
							"Unexpected internal Evita error occurred.",
							targetException
						);
					}
				} catch (Throwable ex) {
					log.error("Unexpected system error occurred: {}", ex.getMessage(), ex);
					throw new EvitaInternalError(
						"Unexpected system error occurred: " + ex.getMessage(),
						"Unexpected system error occurred.",
						ex
					);
				}
			} finally {
				SessionIdHolder.reset();
			}
		}
	}

}
