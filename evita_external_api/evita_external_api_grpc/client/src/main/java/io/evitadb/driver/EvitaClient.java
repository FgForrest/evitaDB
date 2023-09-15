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
import io.evitadb.api.requestResponse.cdc.ChangeCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateCatalogSchemaMutation;
import io.evitadb.driver.cdc.ClientRxPublisher;
import io.evitadb.driver.certificate.ClientCertificateManager;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.driver.exception.EvitaClientNotTerminatedInTimeException;
import io.evitadb.driver.pooling.ChannelPool;
import io.evitadb.driver.service.ChannelSupplier;
import io.evitadb.driver.service.PooledChannelSupplier;
import io.evitadb.driver.service.SharedChannelSupplier;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.dataType.ChangeCatalogCaptureConverter;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc.EvitaServiceBlockingStub;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc.EvitaServiceStub;
import io.evitadb.externalApi.grpc.interceptor.ClientSessionInterceptor;
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
import org.reactivestreams.FlowAdapters;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @see EvitaContract
 */
@ThreadSafe
@Slf4j
public class EvitaClient implements EvitaContract {
	static final Pattern ERROR_MESSAGE_PATTERN = Pattern.compile("(\\w+:\\w+:\\w+): (.*)");

	private static final SchemaMutationConverter<TopLevelCatalogSchemaMutation, GrpcTopLevelCatalogSchemaMutation> CATALOG_SCHEMA_MUTATION_CONVERTER =
		new DelegatingTopLevelCatalogSchemaMutationConverter();

	/**
	 * The configuration of the evitaDB client.
	 */
	@Getter private final EvitaClientConfiguration configuration;
	@Getter private final ThreadPoolExecutor executor;
	/**
	 * The channel pool is used to manage the gRPC channels. The channels are created lazily and are reused for
	 * subsequent requests. The channel pool is thread-safe.
	 */
	private final ChannelPool channelPool;
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
	 * A lambda that needs to be invoked upon EvitaClient closing. It goes through all opened {@link EvitaClientSession}
	 * and closes them along with their gRPC channels.
	 */
	private final Runnable terminationCallback;

	private final ManagedChannel cdcChannel;

	// todo jno: reimplement to publishers
//	private final List<ClientSubscription> activeSubscriptions = new ArrayList<>(8);

	/**
	 * Method that is called within the {@link EvitaClientSession} to apply the wanted blocking logic on a channel retrieved
	 * from a channel pool.
	 *
	 * @param logic function that holds a logic passed by the caller
	 * @param <T>   return type of the function
	 * @return result of the applied function
	 */
	private <T> T executeWithBlockingEvitaService(@Nonnull Function<EvitaServiceBlockingStub, T> logic) {
		return executeWithEvitaService(
			new PooledChannelSupplier(this.channelPool),
			EvitaServiceGrpc::newBlockingStub,
			logic
		);
	}

	/**
	 * Method that is called within the {@link EvitaClientSession} to apply the wanted streaming logic on the shared
	 * streaming channel.
	 *
	 * @param logic function that holds a logic passed by the caller
	 */
	private void executeWithStreamingEvitaService(@Nonnull Consumer<EvitaServiceStub> logic) {
		executeWithEvitaService(
			new SharedChannelSupplier(this.cdcChannel),
			EvitaServiceGrpc::newStub,
			stub -> {
				logic.accept(stub);
				return null;
			}
		);
	}

	/**
	 * Method that is called within the {@link EvitaClientSession} to apply the wanted logic on a channel retrieved
	 * from a channel pool.
	 *
	 * @param logic function that holds a logic passed by the caller
	 * @param <T>                      return type of the function
	 * @return result of the applied function
	 */
	private <S, T> T executeWithEvitaService(@Nonnull ChannelSupplier channelSupplier,
	                                         @Nonnull Function<ManagedChannel, S> stubBuilder,
	                                         @Nonnull Function<S, T> logic) {
		return executeWithClientAndRequestId(
			configuration.clientId(),
			UUIDUtil.randomUUID().toString(),
			() -> {
				final ManagedChannel managedChannel = channelSupplier.getChannel();
				try {
					return logic.apply(stubBuilder.apply(managedChannel));
				} catch (StatusRuntimeException statusRuntimeException) {
					final Code statusCode = statusRuntimeException.getStatus().getCode();
					final String description = ofNullable(statusRuntimeException.getStatus().getDescription())
						.orElse("No description.");
					if (statusCode == Code.INVALID_ARGUMENT) {
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
				} catch (EvitaInvalidUsageException | EvitaInternalError evitaError) {
					throw evitaError;
				} catch (Throwable e) {
					log.error("Unexpected internal Evita error occurred: {}", e.getMessage(), e);
					throw new EvitaInternalError(
						"Unexpected internal Evita error occurred: " + e.getMessage(),
						"Unexpected internal Evita error occurred.",
						e
					);
				} finally {
					channelSupplier.releaseChannel();
				}
			}
		);
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
		this.cdcChannel = nettyChannelBuilder.build();
		this.terminationCallback = () -> {
			try {
				Assert.isTrue(
					this.channelPool.awaitTermination(configuration.waitForClose(), configuration.waitForCloseUnit()),
					() -> new EvitaClientNotTerminatedInTimeException(configuration.waitForClose(), configuration.waitForCloseUnit())
				);
				Assert.isTrue(
					this.cdcChannel.awaitTermination(configuration.waitForClose(), configuration.waitForCloseUnit()),
					() -> new EvitaClientNotTerminatedInTimeException(configuration.waitForClose(), configuration.waitForCloseUnit())
				);
			} catch (InterruptedException e) {
				// terminated
				Thread.currentThread().interrupt();
			}
		};
		this.active.set(true);
		this.executor = new ThreadPoolExecutor(4, 16, 1000, TimeUnit.HOURS, new LinkedBlockingQueue<>());
		this.executor.prestartAllCoreThreads();
	}

	@Override
	public ChangeCapturePublisher<ChangeSystemCapture> registerSystemChangeCapture(@Nonnull ChangeSystemCaptureRequest request) {
		// todo jno: use this as an inspiration for the implementation of the catalog changes
//		final ClientChangeSystemCaptureProcessor clientPublisher = new ClientChangeSystemCaptureProcessor(executor);

//		final ClientRxPublisher<GrpcRegisterSystemChangeCaptureResponse> publisher = new ClientRxPublisher<>(null, null);

		// todo lho proper queue
		final ClientRxPublisher<GrpcRegisterSystemChangeCaptureResponse> publisher = new ClientRxPublisher<>(new LinkedBlockingQueue<>());
		final ChangeSystemConversionPublisher changeSystemConversionPublisher = new ChangeSystemConversionPublisher(publisher);

		executeWithStreamingEvitaService(stub ->
			stub.registerSystemChangeCapture(
				GrpcRegisterSystemChangeCaptureRequest.newBuilder()
					.setContent(EvitaEnumConverter.toGrpcCaptureContent(request.content()))
					.build(),
				publisher
			)
		);
		return changeSystemConversionPublisher;
	}

	private static abstract class ConversionPublisher<C extends ChangeCapture, G> implements ChangeCapturePublisher<C> {

		private final ClientRxPublisher<G> delegate;

		public ConversionPublisher(ClientRxPublisher<G> delegate) {
			this.delegate = delegate;
		}

		@Override
		public void close() {
			delegate.cancel();
		}

		@Override
		public void subscribe(Subscriber<? super C> subscriber) {
			delegate.subscribe(FlowAdapters.toSubscriber(createConversionSubscriber(subscriber)));
		}

		@Nullable
		protected abstract ConversionSubscriber<C, G> createConversionSubscriber(@Nullable Subscriber<? super C> subscriber);
	}

	private static class ChangeSystemConversionPublisher extends ConversionPublisher<ChangeSystemCapture, GrpcRegisterSystemChangeCaptureResponse> {

		public ChangeSystemConversionPublisher(ClientRxPublisher<GrpcRegisterSystemChangeCaptureResponse> delegate) {
			super(delegate);
		}

		@Nullable
		@Override
		protected ConversionSubscriber<ChangeSystemCapture, GrpcRegisterSystemChangeCaptureResponse> createConversionSubscriber(@Nullable Subscriber<? super ChangeSystemCapture> subscriber) {
			return new ChangeSystemConversionSubscriber(subscriber);
		}
	}

	private static class ChangeSystemConversionSubscriber extends ConversionSubscriber<ChangeSystemCapture, GrpcRegisterSystemChangeCaptureResponse> {

		public ChangeSystemConversionSubscriber(@Nonnull Subscriber<? super ChangeSystemCapture> delegate) {
			super(delegate);
		}

		@Nullable
		@Override
		protected ChangeSystemCapture convertCapture(@Nullable GrpcRegisterSystemChangeCaptureResponse item) {
			return ChangeCatalogCaptureConverter.toChangeSystemCapture(item.getCapture());
		}
	}

	@RequiredArgsConstructor
	private static abstract class ConversionSubscriber<C, G> implements Subscriber<G> {

		@Nonnull private final Subscriber<? super C> delegate;

		@Override
		public void onSubscribe(Subscription subscription) {
			delegate.onSubscribe(subscription);
		}

		@Override
		public void onNext(G item) {
			delegate.onNext(convertCapture(item));
		}

		@Override
		public void onError(Throwable throwable) {
			delegate.onError(throwable);
		}

		@Override
		public void onComplete() {
			delegate.onComplete();
		}

		@Nullable
		protected abstract C convertCapture(@Nullable G item);
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
				grpcResponse = executeWithBlockingEvitaService(evitaService ->
					evitaService.createBinaryReadWriteSession(
						GrpcEvitaSessionRequest.newBuilder()
							.setCatalogName(traits.catalogName())
							.setDryRun(traits.isDryRun())
							.build()
					)
				);
			} else {
				grpcResponse = executeWithBlockingEvitaService(evitaService ->
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
				grpcResponse = executeWithBlockingEvitaService(evitaService ->
					evitaService.createBinaryReadOnlySession(
						GrpcEvitaSessionRequest.newBuilder()
							.setCatalogName(traits.catalogName())
							.setDryRun(traits.isDryRun())
							.build()
					)
				);
			} else {
				grpcResponse = executeWithBlockingEvitaService(evitaService ->
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
			this,
			this.entitySchemaCache.computeIfAbsent(
				traits.catalogName(),
				catalogName -> new EvitaEntitySchemaCache(catalogName, this.reflectionLookup)
			),
			this.channelPool,
			this.cdcChannel,
			this.executor,
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

		this.activeSessions.put(evitaClientSession.getId(), evitaClientSession);
		return evitaClientSession;
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
		final GrpcCatalogNamesResponse grpcResponse = executeWithBlockingEvitaService(evitaService -> evitaService.getCatalogNames(Empty.newBuilder().build()));
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
		final GrpcRenameCatalogResponse grpcResponse = executeWithBlockingEvitaService(evitaService -> evitaService.renameCatalog(request));
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
		final GrpcReplaceCatalogResponse grpcResponse = executeWithBlockingEvitaService(evitaService -> evitaService.replaceCatalog(request));
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
		final GrpcDeleteCatalogIfExistsResponse grpcResponse = executeWithBlockingEvitaService(evitaService -> evitaService.deleteCatalogIfExists(request));
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
		executeWithBlockingEvitaService(evitaService -> evitaService.update(request));
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
			// todo jno: reimplement to publishers
//			this.activeSubscriptions.forEach(ClientSubscription::cancel);
			this.cdcChannel.shutdownNow();
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

}
