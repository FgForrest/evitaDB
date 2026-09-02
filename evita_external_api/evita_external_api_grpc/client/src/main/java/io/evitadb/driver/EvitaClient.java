/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.util.EventLoopGroupBuilder;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.common.util.TimeoutMode;
import io.evitadb.api.CatalogState;
import io.evitadb.api.CommitProgress;
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaManagementContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureRequest;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.data.DevelopmentConstants;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.api.requestResponse.progress.ProgressRecord;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.MakeCatalogAliveMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.driver.cdc.ClientChangeCapturePublisher;
import io.evitadb.driver.cdc.ClientChangeSystemCaptureProcessor;
import io.evitadb.driver.config.ClientConnectionOptions;
import io.evitadb.driver.EvitaClientChannel.TimeoutTier;
import io.evitadb.driver.config.ClientTimeoutOptions;
import io.evitadb.driver.config.ClientTlsOptions;
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
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc.EvitaServiceStub;
import io.evitadb.externalApi.grpc.generated.EvitaSessionServiceGrpc.EvitaSessionServiceStub;
import io.evitadb.externalApi.grpc.generated.GrpcApplyMutationRequest;
import io.evitadb.externalApi.grpc.generated.GrpcApplyMutationWithProgressResponse;
import io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse;
import io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateRequest;
import io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateResponse;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingEngineMutationConverter;
import io.evitadb.function.Functions;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CertificateUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ExceptionUtils;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.ReflectionLookup;
import io.evitadb.utils.UUIDUtil;
import io.evitadb.utils.VersionUtils;
import io.evitadb.utils.VersionUtils.SemVer;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.time.Duration;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
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
	 * Counter for naming threads created by the client executor.
	 */
	private static final AtomicInteger CLIENT_THREAD_COUNTER = new AtomicInteger();
	/**
	 * Numbers the threads of the lazily created capture callback executor, so a thread dump names them
	 * unambiguously.
	 */
	private static final AtomicInteger CDC_CALLBACK_THREAD_COUNTER = new AtomicInteger();
	/**
	 * How long {@link #close()} waits for already-dispatched capture callbacks - in practice the terminal
	 * `onError` / `onComplete` notifications - to run before abandoning them. Long enough for a notification
	 * that merely returns, short enough that a consumer blocking in its own callback cannot hold the close open.
	 */
	private static final long CDC_CALLBACK_DRAIN_TIMEOUT_MS = 5_000L;
	/**
	 * Client call timeout.
	 *
	 * The bottom of the stack is the configured {@link ClientTimeoutOptions#timeout()} - the *whole-call*
	 * tier. Anything above it was pushed by {@link #executeWithExtendedTimeout} and is an explicit
	 * caller override. Prefer {@link #resolveTimeout(TimeoutTier)} over reading this directly: peeking
	 * at it hands every call the whole-call budget, which is wrong for a stream and is precisely the bug
	 * {@link TimeoutTier} was introduced to end.
	 */
	final ThreadLocal<LinkedList<Timeout>> timeout;
	/**
	 * Created evita service stub that returns futures.
	 */
	private final EvitaServiceFutureStub evitaServiceFutureStub;
	/**
	 * Created evita service stub that returns streaming calls.
	 */
	private final EvitaServiceStub evitaServiceStub;
	/**
	 * Asynchronous stub bound to the {@link #cdcClientFactory dedicated CDC channel}, used exclusively for
	 * system-level change capture streams.
	 */
	private final EvitaServiceStub evitaServiceCdcStub;
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
	 * Index of the opened and active {@link ClientChangeCapturePublisher} indexed by their unique
	 * {@link CapturePublisherKey} — either a {@link CatalogBoundCaptureKey} (for catalog-level captures)
	 * or a {@link SystemCaptureKey} (for system-level captures).
	 */
	final Map<CapturePublisherKey, ClientChangeCapturePublisher<?, ?, ?>> activePublishers = CollectionUtils.createConcurrentHashMap(
		16);
	/**
	 * Executor service used for asynchronous operations.
	 *
	 * Sized by `ThreadPoolOptions` and guarded by {@link EvitaClientRejectingExecutorHandler}: once all
	 * threads are busy **and** the bounded backlog is full, submission throws
	 * {@link io.evitadb.driver.exception.EvitaClientPoolSaturatedException} rather than running the task on
	 * the submitting thread. Every submission site must therefore tolerate a rejection.
	 */
	private final ExecutorService executor;
	/**
	 * Executor carrying **every consumer-facing change data capture callback** — `Flow.Subscriber#onNext`,
	 * `#onError`, `#onComplete`, {@link io.evitadb.driver.cdc.HeartBeatSensor#onHeartBeat} and a closeable
	 * delegate's `close`, plus the driver-internal capture teardown that runs alongside them.
	 *
	 * Deliberately **not** {@link #executor the shared client pool}. This is the same isolation principle the
	 * {@link #cdcClientFactory dedicated CDC client factory} applies one layer down, for the same reason: a burst
	 * of ordinary `queryCatalogAsync` work must not be able to starve capture delivery, and a consumer callback
	 * that blocks must not be able to starve ordinary work. Sharing the pool is what made issue #1387 reachable
	 * at all — saturation there was what triggered the rejection that `CallerRunsPolicy` then ran on the event
	 * loop.
	 *
	 * **Created lazily**, on the first capture subscription opened by this client (see
	 * {@link #cdcCallbackExecutor()}). A client that never subscribes to a capture stream never allocates it, and
	 * `allowCoreThreadTimeOut` reclaims its threads once captures go quiet, so an idle subscription costs no
	 * threads either.
	 *
	 * Sized by the same `ThreadPoolOptions` as the shared pool and guarded by the same
	 * {@link EvitaClientRejectingExecutorHandler}. When it refuses, the affected subscription is **terminated**
	 * with {@link io.evitadb.driver.exception.EvitaClientPoolSaturatedException} rather than rescued onto an
	 * ad-hoc thread — see {@link io.evitadb.driver.cdc.CdcCallbackDispatcher}.
	 */
	private final AtomicReference<ExecutorService> cdcCallbackExecutor = new AtomicReference<>();
	/**
	 * Client manager.
	 */
	private final ClientFactory clientFactory;
	/**
	 * Client manager dedicated to long-lived change data capture streams. It owns a small event loop group of
	 * its own, so CDC traffic lands on a different connection — and a different I/O thread — than ordinary
	 * request/response calls. A capture callback that stalls therefore cannot stall unrelated calls.
	 */
	private final ClientFactory cdcClientFactory;
	/**
	 * The ordinary request/response channel. Carries the {@link RetryingClient} decorator and therefore backs
	 * **unary stubs only** — see {@link #streamingChannel} for why streaming must not use it.
	 */
	private final EvitaClientChannel.Unary unaryChannel;
	/**
	 * The channel backing **streaming** calls. Shares the {@link #clientFactory main connection} with
	 * {@link #unaryChannel} and differs from it in exactly one respect: it carries no {@link RetryingClient}
	 * decorator.
	 *
	 * That difference is load-bearing, not cosmetic. `AbstractRetryingClient` freezes the call's response-timeout
	 * budget at call start, so the driver's per-message re-arm can no longer move it and a long-lived stream dies
	 * on a deadline it appears to be beating. See
	 * {@link #createGrpcClientBuilder(String, ClientFactory, RetryRule, Duration, ClientConnectionOptions, SemVer,
	 * Consumer)}
	 * and issue #1388.
	 */
	private final EvitaClientChannel.Streaming streamingChannel;
	/**
	 * The channel bound to the {@link #cdcClientFactory dedicated CDC client factory}.
	 *
	 * Note that `registerChangeCatalogCapture` is a *session-bound* call, so routing it here puts it on
	 * a different connection from the rest of its session's calls, and HTTP/2 guarantees ordering only within
	 * a connection. This is safe only because
	 * {@link io.evitadb.driver.cdc.ClientChangeCaptureSubscriber#awaitAcknowledgement()} gates the call — see
	 * {@link EvitaClientSession#registerChangeCatalogCapture} for the full invariant.
	 *
	 * Carries no {@link RetryingClient} decorator either: capture streams are the longest-lived streams the driver
	 * opens, so the frozen-budget problem described on {@link #streamingChannel} applies to them most of all.
	 */
	private final EvitaClientChannel.Cdc cdcChannel;
	/**
	 * Session-scoped capture stub bound to the {@link #cdcChannel}, shared by **every** {@link EvitaClientSession}
	 * this client opens.
	 *
	 * It is built once rather than per session because it carries no session identity of its own: the session id
	 * travels in the gRPC metadata, put there per call by
	 * {@link io.evitadb.driver.interceptor.ClientSessionInterceptor} from the `SessionIdHolder` thread local. Since
	 * sessions are created per `queryCatalog(...)` call, building it per session would put a stub construction on
	 * a hot path for the benefit of `registerChangeCatalogCapture` alone, which most sessions never call.
	 */
	private final EvitaSessionServiceStub evitaSessionServiceCdcStub;
	/**
	 * Client implementation of management service.
	 */
	private final EvitaClientManagement management;
	/**
	 * Contains reference to the proxy factory that is used to create proxies for the entities.
	 */
	@Getter private final ProxyFactory proxyFactory;
	/**
	 * Callback that will be called when session is created.
	 */
	private final Consumer<EvitaSessionContract> onSessionCreationCallback;
	/**
	 * Callback that will be called when session is closed.
	 */
	private final Consumer<EvitaSessionContract> onSessionTerminationCallback;
	/**
	 * Duration to extend the response timeout for each received message.
	 * This helps keep the streaming connection alive as long as messages are being received.
	 */
	private final Duration streamingTimeout;

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

	/**
	 * Classifies a throwable as a TRANSPORT-level failure — one where the gRPC connection did not deliver the
	 * call's outcome, as opposed to a business error the server deliberately returned over a healthy connection.
	 * A transport failure is any of: a {@link StatusRuntimeException} with status {@link Code#CANCELLED},
	 * {@link Code#UNAVAILABLE} or {@link Code#DEADLINE_EXCEEDED}, or a cause chain carrying Armeria's
	 * {@link ClosedSessionException} / {@link ClosedStreamException}. The whole cause chain is inspected because
	 * the transport signal is frequently wrapped (e.g. a {@link StatusRuntimeException} caused by a
	 * {@link ClosedSessionException}).
	 *
	 * When a session call fails this way the server-side invocation is orphaned and the outcome is indeterminate
	 * for the client, which is why the caller must treat the session as lost rather than retrying blindly.
	 *
	 * @param throwable the throwable to classify (typically unwrapped from an {@link ExecutionException})
	 * @return true if the failure happened at the transport level
	 */
	public static boolean isTransportFailure(@Nonnull Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof final StatusRuntimeException statusRuntimeException) {
				final Code code = statusRuntimeException.getStatus().getCode();
				if (code == Code.CANCELLED || code == Code.UNAVAILABLE || code == Code.DEADLINE_EXCEEDED) {
					return true;
				}
			}
			// ClosedSessionException (a connection-level close) is a final subclass of ClosedStreamException,
			// so this single check classifies both stream- and session-level Armeria closures as transport failures
			if (current instanceof ClosedStreamException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	/**
	 * Builds the {@link RetryRule} installed on every driver instance's gRPC client. An `onUnprocessed()` rule is
	 * always active, regardless of {@code retryEnabled}: Armeria raises {@link UnprocessedRequestException} only
	 * when it is certain a request never reached the server (a refused connection, or a GOAWAY received before the
	 * request's stream was accepted), so replaying it can never duplicate an already-applied mutation. When
	 * {@code retryEnabled} is {@code true}, the broader rule set is layered on top — timeouts, `503`/`504`/`UNKNOWN`
	 * statuses and `429` back-off — which can also match a request the server already processed (e.g. a mutation
	 * whose response was lost to a transport abort), so that behaviour stays opt-in.
	 *
	 * @param retryEnabled whether the broader, potentially-duplicating retry rule set should be active
	 * @return the {@link RetryRule} to install on the gRPC client
	 */
	@Nonnull
	static RetryRule createRetryRule(boolean retryEnabled) {
		final RetryRule alwaysSafeUnprocessedRetry = RetryRule.builder().onUnprocessed().thenBackoff();
		if (!retryEnabled) {
			return alwaysSafeUnprocessedRetry;
		}
		return RetryRule.of(
			alwaysSafeUnprocessedRetry,
			RetryRule.builder().onTimeoutException().thenBackoff(),
			RetryRule.builder()
				.onStatus(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT, HttpStatus.UNKNOWN)
				.thenBackoff(),
			RetryRule.builder().onStatus(HttpStatus.TOO_MANY_REQUESTS).thenNoRetry()
		);
	}

	/**
	 * Builds the event loop group backing the {@link #cdcClientFactory dedicated CDC channel}.
	 *
	 * Deliberately a single thread. The isolation comes from the group being *distinct*, not from its size:
	 * Armeria assigns one event loop per endpoint (`DefaultEventLoopScheduler.DEFAULT_MAX_NUM_EVENT_LOOPS` is
	 * `1`) and the driver talks to exactly one endpoint, so any further threads here would never be used.
	 * The point is not throughput but *separation* — CDC frames are read by a thread that carries no ordinary
	 * request/response traffic, so a capture stream that misbehaves degrades captures only.
	 *
	 * Because that one thread serves every capture stream on this client, nothing that blocks may run on it —
	 * see {@link io.evitadb.driver.cdc.ClientChangeCaptureSubscriber#awaitAcknowledgement()}.
	 *
	 * @return a dedicated event loop group for change data capture streams
	 */
	@Nonnull
	private static EventLoopGroup createCdcWorkerGroup() {
		final EventLoopGroupBuilder builder = EventLoopGroups
			.builder()
			.numThreads(1)
			.threadFactory(
				ThreadFactories.builder("evita-client-cdc-eventloop").daemon(true).eventLoop(true).build()
			);
		// in tests we don't want to wait for graceful shutdown (mirrors the main worker group)
		if (DevelopmentConstants.isTestRun()) {
			builder.gracefulShutdown(Duration.ofMillis(0), Duration.ofMillis(0));
		}
		return builder.build();
	}

	/**
	 * Builds a {@link GrpcClientBuilder} bound to the given {@link ClientFactory}. Called three times - for the
	 * ordinary unary request/response channel, for the streaming stubs sharing that same connection, and for the
	 * {@link #cdcClientFactory dedicated CDC channel} - so that all of them carry an identical interceptor stack
	 * and differ only in the connection, the event loop, and whether retries are installed.
	 *
	 * **`retryRule` must be NULL for any builder whose stubs issue streaming calls.**
	 * {@link com.linecorp.armeria.client.retry.AbstractRetryingClient} snapshots `ctx.responseTimeoutMillis()`
	 * into an immutable per-call budget the moment the call starts, and runs each attempt in a *derived* request
	 * context whose timeout it overwrites from that frozen budget. The driver re-arms the response timeout on
	 * every streamed message (`ClientRequestContext.current().setResponseTimeout(SET_FROM_NOW, ...)`) precisely
	 * because a long-lived stream cannot know its total duration up front - but `current()` is the **root**
	 * context, so the re-arm cannot reach the derived scheduler that actually cancels the call. Decorating a
	 * streaming stub therefore caps it from call start no matter how much progress is streaming. See issue #1388.
	 *
	 * Nothing is lost by the omission: the always-on rule is `onUnprocessed()` only, and a server-streaming call
	 * that has already begun emitting messages is by construction not unprocessed, so there is never anything
	 * safe to replay.
	 *
	 * @param uri               target URI of the evitaDB server, including the scheme
	 * @param clientFactory     factory (and therefore connection pool and event loop group) to bind to
	 * @param retryRule         retry rule to install, or NULL to install no retry decorator at all - which is
	 *                          mandatory for builders backing streaming stubs (see above)
	 * @param responseTimeout   response timeout to seed the channel with, or NULL to leave Armeria's 15 s default
	 *                          in place. **Defence in depth, not the fix for #1388** - `ArmeriaClientCall#start`
	 *                          maps the gRPC deadline onto the Armeria response timeout before the decorator
	 *                          chain runs, and every streaming call site here applies `withDeadlineAfter`, so on
	 *                          those paths this value is overwritten anyway. Measured: disabling it changes
	 *                          nothing (see the ADR's *Verification*). It is kept so that a future call site
	 *                          which forgets the deadline still gets a sane window rather than 15 s.
	 * @param streaming         `true` for a channel carrying server-streaming calls, which lifts Armeria's
	 *                          10 MiB total-response-length cap - see the call site for why that cap is
	 *                          meaningless on a stream and fatal for large file downloads
	 * @param connectionOptions connection options providing the client id reported to the server
	 * @param clientVersion     semantic version of this client, or NULL when it could not be parsed
	 * @param grpcConfigurator  optional caller-supplied customization applied last, so it can override defaults
	 * @return the configured gRPC client builder
	 */
	@Nonnull
	private static GrpcClientBuilder createGrpcClientBuilder(
		@Nonnull String uri,
		@Nonnull ClientFactory clientFactory,
		@Nullable RetryRule retryRule,
		@Nullable Duration responseTimeout,
		boolean streaming,
		@Nonnull ClientConnectionOptions connectionOptions,
		@Nullable SemVer clientVersion,
		@Nullable Consumer<GrpcClientBuilder> grpcConfigurator
	) {
		final GrpcClientBuilder grpcClientBuilder = GrpcClients
			.builder(uri)
			.factory(clientFactory)
			.serializationFormat(GrpcSerializationFormats.PROTO)
			.intercept(new ClientSessionInterceptor(connectionOptions.clientId(), clientVersion));

		// Installed on unary channels only: requests Armeria can prove never reached the server are safe to replay
		// regardless of the `retry` flag (see createRetryRule); the broader, potentially-duplicating rule set stays
		// opt-in. Streaming channels pass NULL - see the method contract above.
		if (retryRule != null) {
			grpcClientBuilder.decorator(
				RetryingClient.builder(retryRule)
					.useRetryAfter(true)
					.newDecorator()
			);
		}
		if (responseTimeout != null) {
			grpcClientBuilder.responseTimeout(responseTimeout);
		}
		if (streaming) {
			// Armeria caps a response at 10 MiB by default, and the cap counts the *entire* HTTP body -
			// which for a server-streaming call is every message added together, not the largest one.
			// That is a sane guard on a unary reply and a hard ceiling on a stream: `fetchFile` could not
			// download a backup larger than 10 MiB at all, dying part-way through with
			// RESOURCE_EXHAUSTED. A stream's total length is not a meaningful safety bound - what needs
			// bounding is how much is in flight at once, which is the server's job (see
			// `GrpcOutboundGate`) - so the cap is lifted here and left in place for unary calls.
			grpcClientBuilder.maxResponseLength(0);
		}

		ofNullable(grpcConfigurator).ifPresent(it -> it.accept(grpcClientBuilder));
		return grpcClientBuilder;
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
	 * The server writes the status description as `errorCode + ": " + publicMessage` (see
	 * `GlobalExceptionHandlerInterceptor#createErrorStatus` on the server side), so {@link #ERROR_MESSAGE_PATTERN}
	 * has to be matched against the description **exactly as it arrived**. Prepending the status name first - as this
	 * method used to - makes the anchored `(\w+:\w+:\w+)` group unmatchable, because `\w` does not cover the space
	 * that follows `INTERNAL:`. The effect was that no error code was ever recovered from a gRPC status and every
	 * server error reached the caller re-coded against a line of this class instead.
	 *
	 * The status name is therefore only prepended on the fallback path, where the description carries no code and the
	 * name is the sole classification available.
	 *
	 * Package-private rather than private so `EvitaClientErrorTransformationTest` can drive it directly; it needs no
	 * server, and standing up one to assert on a regex would only obscure what is being tested.
	 *
	 * @param statusRuntimeException the {@link StatusRuntimeException} to handle
	 * @param onUnauthenticated      the action to perform when the status code is {@link Code#UNAUTHENTICATED}
	 * @return the exception to be raised towards the caller
	 */
	@Nonnull
	static RuntimeException transformStatusRuntimeException(
		@Nonnull StatusRuntimeException statusRuntimeException,
		@Nonnull Runnable onUnauthenticated
	) {
		final Code statusCode = statusRuntimeException.getStatus().getCode();
		final String rawDescription = statusRuntimeException.getStatus().getDescription();
		// matched against the untouched description; an absent description cannot carry a code, and the empty string
		// never matches the pattern, so it needs no separate branch
		final Matcher expectedFormat = ERROR_MESSAGE_PATTERN.matcher(rawDescription == null ? "" : rawDescription);
		final boolean codeRecovered = expectedFormat.matches();
		if (statusCode == Code.UNAUTHENTICATED) {
			onUnauthenticated.run();
			return new InstanceTerminatedException("session");
		} else if (statusCode == Code.INVALID_ARGUMENT || statusCode == Code.PERMISSION_DENIED) {
			return codeRecovered ?
				EvitaInvalidUsageException.createExceptionWithErrorCode(
					expectedFormat.group(2), expectedFormat.group(1)
				) :
				new EvitaInvalidUsageException(describeUncoded(statusCode, rawDescription));
		} else {
			return codeRecovered ?
				GenericEvitaInternalError.createExceptionWithErrorCode(
					expectedFormat.group(2), expectedFormat.group(1)
				) :
				new GenericEvitaInternalError(describeUncoded(statusCode, rawDescription));
		}
	}

	/**
	 * Builds the message for a status whose description carries no evitaDB error code - a status raised by gRPC
	 * itself, or by an interceptor that never saw an evitaDB exception. The status name is prepended because it is
	 * the only classification such a message has; a description that does carry a code keeps the server's own public
	 * text verbatim instead.
	 *
	 * @param statusCode     the code of the status being transformed
	 * @param rawDescription the status description exactly as received, may be `null`
	 * @return the message to construct the client-side exception with
	 */
	@Nonnull
	private static String describeUncoded(@Nonnull Code statusCode, @Nullable String rawDescription) {
		return ofNullable(rawDescription)
			.map(it -> statusCode.name() + ": " + it)
			.orElseGet(statusCode::name);
	}

	public EvitaClient(
		@Nonnull EvitaClientConfiguration configuration
	) {
		this(configuration, null, null, null);
	}

	public EvitaClient(
		@Nonnull EvitaClientConfiguration configuration,
		@Nullable Consumer<EvitaSessionContract> onSessionCreationCallback,
		@Nullable Consumer<EvitaSessionContract> onSessionTerminationCallback
	) {
		this(configuration, null, onSessionCreationCallback, onSessionTerminationCallback);
	}

	/**
	 * Creates a client with a caller-supplied customization of the gRPC client builders.
	 *
	 * **`grpcConfigurator` is invoked once per channel — three times, not once** (unary, streaming and
	 * change data capture), and it is applied **last**, so it overrides everything the driver configured.
	 * Two consequences follow:
	 *
	 * 1. A configurator with side effects (registering a metric, appending to a collection) runs three times.
	 * 2. A configurator that installs a `RetryingClient` decorator or sets `responseTimeout` applies it to the
	 *    streaming and capture channels too, which reintroduces issue #1388 from outside the driver — the
	 *    retry layer freezes a stream's response-timeout budget at call start, so the driver's per-message
	 *    re-arm can no longer move it. Configure retries on {@link EvitaClientConfiguration#retry()} instead.
	 *
	 * @param configuration     the client configuration
	 * @param grpcConfigurator  optional customization applied to each of the three channel builders
	 */
	public EvitaClient(
		@Nonnull EvitaClientConfiguration configuration,
		@Nullable Consumer<GrpcClientBuilder> grpcConfigurator
	) {
		this(configuration, grpcConfigurator, null, null);
	}

	/**
	 * Creates a client with a caller-supplied builder customization and session lifecycle callbacks.
	 *
	 * See {@link #EvitaClient(EvitaClientConfiguration, Consumer)} for the contract `grpcConfigurator` has
	 * to respect - in particular that it runs once per channel and must not install a retry decorator.
	 *
	 * @param configuration               the client configuration
	 * @param grpcConfigurator            optional customization applied to each of the three channel builders
	 * @param onSessionCreationCallback    invoked when a session is opened
	 * @param onSessionTerminationCallback invoked when a session is closed
	 */
	public EvitaClient(
		@Nonnull EvitaClientConfiguration configuration,
		@Nullable Consumer<GrpcClientBuilder> grpcConfigurator,
		@Nullable Consumer<EvitaSessionContract> onSessionCreationCallback,
		@Nullable Consumer<EvitaSessionContract> onSessionTerminationCallback
	) {
		this.configuration = configuration;
		final ClientTimeoutOptions clientTimeouts = this.configuration.timeouts();
		this.streamingTimeout = Duration.of(
			clientTimeouts.streamingTimeout(),
			clientTimeouts.streamingTimeoutUnit().toChronoUnit()
		);
		this.onSessionCreationCallback = onSessionCreationCallback == null
			? Functions.noOpConsumer()
			: onSessionCreationCallback;
		this.onSessionTerminationCallback = onSessionTerminationCallback == null
			? Functions.noOpConsumer()
			: onSessionTerminationCallback;

		// in tests we don't want to wait for graceful shutdown, so we set it to 0,
		// but in production we want to wait a bit to let all requests finish properly
		final EventLoopGroup workerGroup;
		if (DevelopmentConstants.isTestRun()) {
			workerGroup = EventLoopGroups
				.builder()
				.numThreads(Runtime.getRuntime().availableProcessors())
				.gracefulShutdown(Duration.ofMillis(0), Duration.ofMillis(0))
				.build();
		} else {
			workerGroup = EventLoopGroups
				.builder()
				.numThreads(Runtime.getRuntime().availableProcessors())
				.build();
		}

		// The connection idle timeout is a dedicated knob, deliberately decoupled from the per-call timeout:
		// a short request deadline must not tear down the pooled HTTP/2 connection between calls.
		final int idleTimeoutMillis = configuration.connection().idleTimeoutMillis();
		final int pingIntervalMillis = configuration.connection().pingIntervalMillis();
		// Armeria silently disables the keep-alive ping when the (floored) ping interval is not strictly below
		// a positive connection idle timeout (see ClientFactoryBuilder ping/idle reconciliation: the ping is
		// dropped when idle > 0 && ping > 0 && max(ping, 1000) >= idle; 1000 ms is Armeria's minimum ping). An
		// idle timeout of 0 means "never idle out" and keeps the ping active. Warn loudly so an operator who
		// configured a ping expecting a watchdog is not left with a silently inert one.
		if (idleTimeoutMillis > 0 && pingIntervalMillis > 0
			&& Math.max(pingIntervalMillis, 1000) >= idleTimeoutMillis) {
			log.warn(
				"Client keep-alive ping ({} ms) is not below the connection idle timeout ({} ms); Armeria will " +
					"disable the ping. Lower the ping interval (ClientConnectionOptions.pingIntervalMillis) or " +
					"raise the connection idle timeout (ClientConnectionOptions.idleTimeoutMillis) to keep the " +
					"ping active.",
				pingIntervalMillis, idleTimeoutMillis
			);
		}
		// keepAliveOnPing = true makes acknowledged keep-alive pings count as connection activity, so a healthy
		// connection is never reaped by the idle timeout — set explicitly rather than riding Armeria's global
		// Flags.defaultClientKeepAliveOnPing (false by default, and flippable by a system property).
		ClientFactoryBuilder clientFactoryBuilder = ClientFactory
			.builder()
			.workerGroup(workerGroup, true)
			.idleTimeoutMillis(idleTimeoutMillis, true)
			.pingIntervalMillis(pingIntervalMillis);

		final String uriScheme;
		final ClientTlsOptions tlsOptions = configuration.tls();
		final ClientConnectionOptions connectionOptions = configuration.connection();
		if (tlsOptions.tlsEnabled()) {
			uriScheme = "https";

			final Builder certificateBuilder = new Builder()
				.useGeneratedCertificate(
					tlsOptions.useGeneratedCertificate(),
					connectionOptions.host(),
					connectionOptions.systemApiPort()
				)
				.usingTrustedServerCertificate(tlsOptions.trustCertificate())
				.trustStorePassword(tlsOptions.trustStorePassword())
				.mtls(tlsOptions.mtlsEnabled())
				.clientCertificateFilePath(tlsOptions.certificateFileName())
				.clientPrivateKeyFilePath(tlsOptions.certificateKeyFileName())
				.clientPrivateKeyPassword(tlsOptions.certificateKeyPassword());
			if (tlsOptions.certificateFolderPath() != null) {
				certificateBuilder.certificateClientFolderPath(tlsOptions.certificateFolderPath());
			}
			if (tlsOptions.serverCertificatePath() != null) {
				certificateBuilder.serverCertificateFilePath(tlsOptions.serverCertificatePath());
			}
			final ClientCertificateManager clientCertificateManager = certificateBuilder.build();

			clientFactoryBuilder = clientCertificateManager.buildClientSslContext(
				(certificateType, certificate) -> {
					try {
						switch (certificateType) {
							case SERVER -> log.info(
								"Server's certificate fingerprint: {}",
								CertificateUtils.getCertificateFingerprint(certificate)
							);
							case CLIENT -> log.info(
								"Client's certificate fingerprint: {}",
								CertificateUtils.getCertificateFingerprint(certificate)
							);
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

		final ThreadPoolOptions threadPoolOptions = configuration.threadPool();
		this.executor = new ThreadPoolExecutor(
			threadPoolOptions.minThreadCount(),
			threadPoolOptions.maxThreadCount(),
			60L, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>(threadPoolOptions.queueSize()),
			r -> {
				final Thread thread = new Thread(r, "evita-client-" + CLIENT_THREAD_COUNTER.incrementAndGet());
				thread.setDaemon(true);
				if (thread.getPriority() != threadPoolOptions.threadPriority()) {
					thread.setPriority(threadPoolOptions.threadPriority());
				}
				return thread;
			},
			// Fail the submission fast once the bounded backlog is exhausted. `CallerRunsPolicy` must never be
			// used here: the driver does not control who submits, and when the submitter is an Armeria event
			// loop, "backpressure" becomes driver work executed on the single thread that reads the connection
			// — which then deadlocks the transport if that work waits for an inbound message. See
			// EvitaClientRejectingExecutorHandler.
			new EvitaClientRejectingExecutorHandler(
				threadPoolOptions.maxThreadCount(),
				threadPoolOptions.queueSize()
			)
		);
		this.clientFactory = clientFactoryBuilder.build();
		// Long-lived change-data-capture streams get their own ClientFactory, and therefore their own
		// connection, so that a stalled capture callback can never stall unrelated request/response traffic.
		// The dedicated event loop group makes the assignment deterministic instead of leaving it to whichever
		// loop Armeria's scheduler happens to pick — a plain client concentrates everything on a single loop
		// (`DefaultEventLoopScheduler.DEFAULT_MAX_NUM_EVENT_LOOPS` is 1, and `HttpChannelPool` is instantiated
		// per event loop), so without this split one blocked CDC callback takes the whole client down.
		// `build()` snapshots the builder's options, so re-pointing the worker group here does not disturb the
		// factory built above.
		this.cdcClientFactory = clientFactoryBuilder
			.workerGroup(createCdcWorkerGroup(), true)
			.build();

		SemVer clientVersion;
		try {
			clientVersion = SemVer.fromString(getVersion());
		} catch (InvalidEvitaVersionException e) {
			clientVersion = null;
		}

		final ClientTracingContext context = getClientTracingContext(configuration);
		if (configuration.openTelemetryInstance() != null) {
			context.setOpenTelemetry(configuration.openTelemetryInstance());
		}

		final String uri = uriScheme + "://" + connectionOptions.host() + ":" + connectionOptions.port() + "/";
		// Unary calls retry; streaming calls must not be decorated at all, or the retry layer freezes their
		// response-timeout deadline at call start and caps every stream at 15 s (issue #1388).
		this.unaryChannel = new EvitaClientChannel.Unary(
			createGrpcClientBuilder(
				uri, this.clientFactory, createRetryRule(configuration.retry()), null, false,
				connectionOptions, clientVersion, grpcConfigurator
			)
		);
		this.streamingChannel = new EvitaClientChannel.Streaming(
			createGrpcClientBuilder(
				uri, this.clientFactory, null, this.streamingTimeout, true,
				connectionOptions, clientVersion, grpcConfigurator
			)
		);
		this.cdcChannel = new EvitaClientChannel.Cdc(
			createGrpcClientBuilder(
				uri, this.cdcClientFactory, null, this.streamingTimeout, true,
				connectionOptions, clientVersion, grpcConfigurator
			)
		);
		this.evitaServiceFutureStub = this.unaryChannel.stub(EvitaServiceFutureStub.class);
		this.evitaServiceStub = this.streamingChannel.stub(EvitaServiceStub.class);
		this.evitaServiceCdcStub = this.cdcChannel.stub(EvitaServiceStub.class);
		this.evitaSessionServiceCdcStub = this.cdcChannel.stub(EvitaSessionServiceStub.class);
		this.reflectionLookup = new ReflectionLookup(configuration.reflectionLookupBehaviour());
		this.timeout = ThreadLocal.withInitial(() -> {
			final LinkedList<Timeout> timeouts = new LinkedList<>();
			timeouts.add(new Timeout(clientTimeouts.timeout(), clientTimeouts.timeoutUnit()));
			return timeouts;
		});
		this.management = new EvitaClientManagement(this, this.unaryChannel, this.streamingChannel);
		this.proxyFactory = ProxyFactory.createInstance(this.reflectionLookup);
		this.active.set(true);

		try {
			if (clientVersion == null) {
				log.warn(
					"Client version `{}` is not a valid semantic version. Aborting version check, this situation may lead to compatibility issues.",
					getVersion()
				);
				return;
			}

			final SystemStatus systemStatus = this.management().getSystemStatus();
			final SemVer serverVersion;

			try {
				serverVersion = SemVer.fromString(systemStatus.version());
			} catch (InvalidEvitaVersionException e) {
				log.warn(
					"Server version `{}` is not a valid semantic version. Aborting version check, this situation may lead to compatibility issues.",
					systemStatus.version()
				);
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

		final GrpcEvitaSessionRequest.Builder sessionBuilder = GrpcEvitaSessionRequest
			.newBuilder()
			.setCatalogName(
				traits.catalogName())
			.setDryRun(traits.isDryRun());

		if (traits.isReadWrite()) {
			if (traits.commitBehaviour() != null) {
				sessionBuilder.setCommitBehavior(EvitaEnumConverter.toGrpcCommitBehavior(traits.commitBehaviour()));
			}
			if (traits.isBinary()) {
				grpcResponse = executeWithEvitaFutureService(
					evitaService -> evitaService.createBinaryReadWriteSession(
						sessionBuilder.build()
					)
				);
			} else {
				grpcResponse = executeWithEvitaFutureService(
					evitaService -> evitaService.createReadWriteSession(
						sessionBuilder.build()
					)
				);
			}
		} else {
			if (traits.isBinary()) {
				grpcResponse = executeWithEvitaFutureService(
					evitaService -> evitaService.createBinaryReadOnlySession(
						sessionBuilder.build()
					)
				);
			} else {
				grpcResponse = executeWithEvitaFutureService(
					evitaService -> evitaService.createReadOnlySession(
						sessionBuilder.build()
					)
				);
			}
		}
		final EvitaClientSession evitaClientSession = new EvitaClientSession(
			this,
			this.management,
			this.proxyFactory,
			this.entitySchemaCache.computeIfAbsent(
				traits.catalogName(),
				EvitaEntitySchemaCache::new
			),
			this.unaryChannel,
			this.streamingChannel,
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
				this.onSessionTerminationCallback.accept(evitaSession);
			},
			Objects.requireNonNull(this.timeout.get().peek())
		);

		this.activeSessions.put(evitaClientSession.getId(), evitaClientSession);
		this.onSessionCreationCallback.accept(evitaClientSession);
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
				"Passed session is expected to be `EvitaClientSession`, but it is not (" + session.getClass()
				                                                                                  .getSimpleName() + ")!"
			);
		}
	}

	@Nonnull
	@Override
	public Set<String> getCatalogNames() {
		assertActive();
		final GrpcCatalogNamesResponse grpcResponse = executeWithEvitaFutureService(
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
		final GrpcGetCatalogStateResponse grpcResponse = executeWithEvitaFutureService(
			evitaService -> evitaService.getCatalogState(
				GrpcGetCatalogStateRequest.newBuilder().setCatalogName(catalogName).build())
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
			ExceptionUtils.unwrapCompletionException(
				() -> {
					applyMutation(new CreateCatalogSchemaMutation(catalogName))
						.onCompletion()
						.toCompletableFuture()
						.join();
					return null;
				}
			);
		}
		return queryCatalog(
			catalogName,
			session -> {
				return ((EvitaClientSession) session).getCatalogSchema(this);
			}
		).openForWrite();
	}

	@Nonnull
	@Override
	public Progress<CommitVersions> makeCatalogAliveWithProgress(@Nonnull String catalogName) {
		assertActive();
		if (getCatalogState(catalogName).map(it -> it == CatalogState.WARMING_UP).orElse(false)) {
			return applyMutation(new MakeCatalogAliveMutation(catalogName));
		} else {
			throw new InvalidMutationException(
				"Catalog `" + catalogName + "` is not in WARMING_UP state, so it cannot be made alive!"
			);
		}
	}

	@Nonnull
	@Override
	public Progress<Void> duplicateCatalogWithProgress(@Nonnull String catalogName, @Nonnull String newCatalogName) {
		assertActive();
		return applyMutation(
			new DuplicateCatalogMutation(catalogName, newCatalogName)
		);
	}

	@Nonnull
	@Override
	public Progress<Void> activateCatalogWithProgress(@Nonnull String catalogName) {
		assertActive();
		if (getCatalogState(catalogName).map(it -> it == CatalogState.INACTIVE).orElse(false)) {
			return applyMutation(new SetCatalogStateMutation(catalogName, true));
		} else {
			throw new InvalidMutationException(
				"Catalog `" + catalogName + "` is not in INACTIVE state, so it cannot be activated!"
			);
		}
	}

	@Nonnull
	@Override
	public Progress<Void> deactivateCatalogWithProgress(@Nonnull String catalogName) {
		assertActive();
		if (getCatalogState(catalogName).map(CatalogState::isActive).orElse(false)) {
			return applyMutation(new SetCatalogStateMutation(catalogName, false));
		} else {
			throw new InvalidMutationException(
				"Catalog `" + catalogName + "` is not in WARMING_UP or ALIVE state, so it cannot be deactivated!"
			);
		}
	}

	@Nonnull
	@Override
	public Progress<Void> makeCatalogMutableWithProgress(@Nonnull String catalogName) {
		assertActive();
		return applyMutation(new SetCatalogMutabilityMutation(catalogName, true));
	}

	@Nonnull
	@Override
	public Progress<Void> makeCatalogImmutableWithProgress(@Nonnull String catalogName) {
		assertActive();
		return applyMutation(new SetCatalogMutabilityMutation(catalogName, false));
	}

	@Nonnull
	@Override
	public Progress<CommitVersions> renameCatalogWithProgress(@Nonnull String catalogName, @Nonnull String newCatalogName) {
		assertActive();
		return applyMutation(
			new ModifyCatalogSchemaNameMutation(catalogName, newCatalogName, false),
			progress -> {
				if (progress == 100) {
					this.entitySchemaCache.remove(catalogName);
					this.entitySchemaCache.remove(newCatalogName);
					// server has already closed sessions bound to the old catalog name;
					// drop our stale references so callers do not leak them
					evictLocalSessionsForCatalog(catalogName);
				}
			}
		);
	}

	@Nonnull
	@Override
	public Progress<CommitVersions> replaceCatalogWithProgress(@Nonnull String catalogNameToBeReplacedWith, @Nonnull String catalogNameToBeReplaced) {
		assertActive();
		return applyMutation(
			new ModifyCatalogSchemaNameMutation(catalogNameToBeReplacedWith, catalogNameToBeReplaced, true),
			progress -> {
				if (progress == 100) {
					this.entitySchemaCache.remove(catalogNameToBeReplaced);
					this.entitySchemaCache.remove(catalogNameToBeReplacedWith);
					// both names become unusable on the server — the source catalog is gone
					// (it has been renamed onto the target), and sessions opened against the
					// replaced catalog have been terminated by the replace operation
					evictLocalSessionsForCatalog(catalogNameToBeReplaced);
					evictLocalSessionsForCatalog(catalogNameToBeReplacedWith);
				}
			}
		);
	}

	@Nonnull
	@Override
	public Optional<Progress<Void>> deleteCatalogIfExistsWithProgress(@Nonnull String catalogName) {
		assertActive();
		if (getCatalogNames().contains(catalogName)) {
			return Optional.of(
				applyMutation(
					new RemoveCatalogSchemaMutation(catalogName),
					progress -> {
						if (progress == 100) {
							this.entitySchemaCache.remove(catalogName);
							// server has already closed sessions bound to the removed catalog;
							// drop our stale references so callers do not leak them
							evictLocalSessionsForCatalog(catalogName);
						}
					}
				)
			);
		} else {
			return Optional.empty();
		}
	}

	@Nonnull
	@Override
	public <T> Progress<T> applyMutation(
		@Nonnull EngineMutation<T> engineMutation, @Nullable IntConsumer progressObserver) {
		assertActive();

		DelegatingEngineMutationConverter.INSTANCE.convert(engineMutation);

		final GrpcApplyMutationRequest request = GrpcApplyMutationRequest
			.newBuilder()
			.setMutation(DelegatingEngineMutationConverter.INSTANCE.convert(engineMutation))
			.build();

		final ClientTimeoutOptions clientTimeouts = this.configuration.timeouts();
		final Duration streamingTimeout = Duration.of(
			clientTimeouts.streamingTimeout(),
			clientTimeouts.streamingTimeoutUnit().toChronoUnit()
		);

		//noinspection unchecked
		return Objects.requireNonNull(
			executeWithStreamingEvitaService(
				evitaService -> {
					@SuppressWarnings("rawtypes") final ProgressRecord applyMutationProgress = new ProgressRecord(
						"Applying mutation `" + engineMutation + "`",
						progressObserver
					);

					final StreamObserver<GrpcApplyMutationWithProgressResponse> observer = new StreamObserver<>() {
						private long catalogVersion = -1;
						private int catalogSchemaVersion = -1;

						@Override
						public void onNext(GrpcApplyMutationWithProgressResponse grpcResponse) {
							applyMutationProgress.updatePercentCompleted(
								grpcResponse.getProgressInPercent()
							);

							if (grpcResponse.hasCatalogVersion()) {
								this.catalogVersion = grpcResponse.getCatalogVersion().getValue();
							}
							if (grpcResponse.hasCatalogSchemaVersion()) {
								this.catalogSchemaVersion = grpcResponse.getCatalogSchemaVersion().getValue();
							}

							// restart the response deadline from now so a silent stream unblocks the caller
							// within `streamingTimeout` of the last event, regardless of how many have arrived
							ClientRequestContext.current().setResponseTimeout(TimeoutMode.SET_FROM_NOW, streamingTimeout);

							if (progressObserver != null) {
								progressObserver.accept(grpcResponse.getProgressInPercent());
							}
						}

						@Override
						public void onError(Throwable throwable) {
							applyMutationProgress.completeExceptionally(throwable);
						}

						@SuppressWarnings("unchecked")
						@Override
						public void onCompleted() {
							if (this.catalogVersion > -1 && this.catalogSchemaVersion > -1) {
								applyMutationProgress.complete(
									new CommitVersions(this.catalogVersion, this.catalogSchemaVersion)
								);

								if (engineMutation instanceof TopLevelCatalogMutation<T> tlcm) {
									ofNullable(EvitaClient.this.entitySchemaCache.get(tlcm.getCatalogName()))
										.ifPresent(
											it -> it.updateLastKnownCatalogVersion(
												this.catalogVersion, this.catalogSchemaVersion
											)
										);
								}
							} else {
								applyMutationProgress.complete(null);
							}
						}
					};

					evitaService.applyMutationWithProgress(
						request, observer
					);

					return applyMutationProgress;
				}
			)
		);
	}

	@Override
	public <T> T queryCatalog(
		@Nonnull String catalogName, @Nonnull Function<EvitaSessionContract, T> queryLogic,
		@Nullable SessionFlags... flags
	) {
		assertActive();
		try (final EvitaSessionContract session = this.createSession(new SessionTraits(catalogName, flags))) {
			return queryLogic.apply(session);
		}
	}

	@Override
	public void queryCatalog(
		@Nonnull String
			catalogName, @Nonnull Consumer<EvitaSessionContract> queryLogic, @Nullable SessionFlags... flags
	) {
		assertActive();
		try (final EvitaSessionContract session = this.createSession(new SessionTraits(catalogName, flags))) {
			queryLogic.accept(session);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * The work is submitted to the shared client pool, which is bounded and fails fast. When the pool is
	 * saturated (or the client is closing) `CompletableFuture.supplyAsync` propagates the refusal
	 * **synchronously** — this method throws instead of returning a future that later completes
	 * exceptionally. That matches the embedded implementation, whose own rejecting handler throws from
	 * `Evita#queryCatalogAsync` the same way.
	 *
	 * @throws io.evitadb.exception.EvitaInvalidUsageException if the client thread pool cannot accept the task.
	 *         The concrete type is `io.evitadb.driver.exception.EvitaClientPoolSaturatedException`, but that
	 *         package is not exported by the driver's `module-info.java`, so consumers on the module path can
	 *         only name the supertype - catch that unless you are on the class path.
	 */
	@Nonnull
	@Override
	public <T> CompletableFuture<T> queryCatalogAsync(
		@Nonnull String catalogName, @Nonnull Function<EvitaSessionContract, T> queryLogic,
		@Nullable SessionFlags... flags
	) {
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
		try (final EvitaClientSession session = this.createSession(traits)) {
			// run the updater within the session's root transaction frame so individual mutations run nested — a
			// single caught mutation failure doesn't discard the surrounding transaction (1:1 with embedded)
			return session.execute(updater);
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
		final EvitaClientSession session = this.createSession(traits);
		final CompletionStage<CommitVersions> closeFuture;
		final T resultValue;
		try {
			// run the updater within the session's root transaction frame (1:1 with embedded — see updateCatalog)
			resultValue = session.execute(updater);
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
	public void updateCatalog(
		@Nonnull String catalogName, @Nonnull Consumer<EvitaSessionContract> updater,
		@Nonnull CommitBehavior commitBehaviour, @Nullable SessionFlags... flags
	) {
		assertActive();
		final SessionTraits traits = new SessionTraits(
			catalogName,
			commitBehaviour,
			flags == null ?
				new SessionFlags[]{SessionFlags.READ_WRITE} :
				ArrayUtils.insertRecordIntoArrayOnIndex(SessionFlags.READ_WRITE, flags, flags.length)
		);
		try (final EvitaClientSession session = this.createSession(traits)) {
			// run the updater within the session's root transaction frame (1:1 with embedded — see updateCatalog)
			session.execute(updater);
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
		final EvitaClientSession session = this.createSession(traits);
		final CommitProgress commitProgress;
		try {
			// run the updater within the session's root transaction frame (1:1 with embedded — see updateCatalog)
			session.execute(updater);
		} finally {
			commitProgress = session.closeNowWithProgress();
		}

		return commitProgress;
	}

	@Nonnull
	@Override
	public ChangeCapturePublisher<ChangeSystemCapture> registerSystemChangeCapture(
		@Nonnull ChangeSystemCaptureRequest request
	) {
		final SystemCaptureKey key = new SystemCaptureKey(request);
		//noinspection unchecked
		return (ChangeCapturePublisher<ChangeSystemCapture>) this.activePublishers.compute(
			key,
			(theKey, existingInstance) ->
				existingInstance == null || existingInstance.isClosed() ?
					new ClientChangeSystemCaptureProcessor(
						this.configuration.changeCaptureQueueSize(),
						this.streamingTimeout,
						// capture callbacks get their own executor - never the shared client pool
						cdcCallbackExecutor(),
						subscriber -> executeWithStreamingEvitaCdcService(
							evitaService -> {
								evitaService.registerSystemChangeCapture(
									ChangeCaptureConverter.toGrpcChangeSystemCaptureRequest(request),
									subscriber
								);
								return null;
							}
							),
						publisher -> this.activePublishers.remove(key, publisher)
					) : existingInstance
		);
	}

	@Nonnull
	@Override
	public EvitaManagementContract management() {
		return this.management;
	}

	/**
	 * Returns the executor carrying this client's change data capture callbacks, creating it on first use.
	 *
	 * Lazily created because most clients never open a capture stream, and an eagerly built pool would charge
	 * every one of them for a feature they do not use. `allowCoreThreadTimeOut` is enabled so that even a client
	 * that *did* subscribe drops back to zero threads once captures go quiet.
	 *
	 * Deliberately **not** public: the return value is a live handle on which `shutdown`/`shutdownNow` would
	 * silently break every future capture subscription on this client and race {@link #close()}'s own
	 * drain-then-shutdown sequence. Only {@link EvitaClientSession} needs it, and it lives in this package.
	 *
	 * @return the capture callback executor, never NULL
	 */
	@Nonnull
	ExecutorService cdcCallbackExecutor() {
		final ExecutorService existing = this.cdcCallbackExecutor.get();
		if (existing != null) {
			return existing;
		}
		// constructing a ThreadPoolExecutor starts no threads, so a lost race costs an object, not a thread
		final ThreadPoolOptions threadPoolOptions = this.configuration.threadPool();
		final ThreadPoolExecutor created = new ThreadPoolExecutor(
			threadPoolOptions.maxThreadCount(),
			threadPoolOptions.maxThreadCount(),
			60L, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>(threadPoolOptions.queueSize()),
			runnable -> {
				final Thread thread = new CdcCallbackThread(
					runnable, "evita-client-cdc-callback-" + CDC_CALLBACK_THREAD_COUNTER.incrementAndGet()
				);
				thread.setDaemon(true);
				if (thread.getPriority() != threadPoolOptions.threadPriority()) {
					thread.setPriority(threadPoolOptions.threadPriority());
				}
				return thread;
			},
			new EvitaClientRejectingExecutorHandler(
				threadPoolOptions.maxThreadCount(),
				threadPoolOptions.queueSize()
			)
		);
		created.allowCoreThreadTimeOut(true);
		if (this.cdcCallbackExecutor.compareAndSet(null, created)) {
			return created;
		}
		return this.cdcCallbackExecutor.get();
	}

	/**
	 * Returns the capture stub shared by every {@link EvitaClientSession} this client opens - see
	 * {@link #evitaSessionServiceCdcStub} for why it is built once rather than per session.
	 *
	 * @return the session-scoped capture stub bound to the dedicated CDC channel
	 */
	@Nonnull
	EvitaSessionServiceStub sessionCaptureStub() {
		return this.evitaSessionServiceCdcStub;
	}

	/**
	 * {@inheritDoc}
	 *
	 * **Order matters here.** Closing the publishers dispatches every still-live subscription's terminal
	 * notification - `onError` / `onComplete` and the delegate's `close` - onto
	 * {@link #cdcCallbackExecutor() the capture callback executor}. That executor is therefore drained *before*
	 * it is torn down: a consumer that never receives its terminal notification is left believing its
	 * subscription is alive, which is a silent, permanent capture outage. Shutting it down first, or tearing it
	 * down with `shutdownNow()` straight away, would discard exactly those notifications.
	 */
	@Override
	public void close() {
		if (this.active.compareAndSet(true, false)) {
			this.activePublishers.forEach((key, it) -> IOUtils.closeSafely(it::close));
			this.activeSessions.values().forEach(it -> IOUtils.closeSafely(it::close));
			this.activeSessions.clear();
			// let the terminal notifications dispatched just above actually reach the consumer
			drainAndShutdownCdcCallbackExecutor();
			this.executor.shutdownNow();
			IOUtils.closeSafely(
				this.management::close,
				this.clientFactory::close,
				// releases the dedicated CDC event loop group too (registered with shutdownOnClose = true)
				this.cdcClientFactory::close
			);
		}
	}

	/**
	 * Stops the capture callback executor, giving the notifications already queued on it a bounded window to
	 * run first. Queued notifications are never discarded here — only the *waiting* for them is bounded, and
	 * `shutdownNow` is reached solely when that bound expires. No-op when no capture stream was ever opened on
	 * this client.
	 */
	private void drainAndShutdownCdcCallbackExecutor() {
		final ExecutorService captureExecutor = this.cdcCallbackExecutor.get();
		if (captureExecutor == null) {
			// this client never subscribed to a capture stream
			return;
		}
		captureExecutor.shutdown();
		if (Thread.currentThread() instanceof CdcCallbackThread) {
			// `close()` was called *from* a capture callback - a consumer closing the client from its own
			// `onError` handler is an ordinary pattern - so awaiting termination here would wait for the very
			// task that is doing the waiting. Only the *wait* is skipped, not the drain: `shutdown()` above
			// stops new submissions but lets the already-queued notifications run, so they are still delivered
			// once this callback returns. They are simply no longer delivered *before* `close()` returns, which
			// is the correct trade - the consumer that would receive them is the one that asked for the close.
			log.debug("The evitaDB client is being closed from a capture callback; skipping the drain window.");
			return;
		}
		try {
			if (!captureExecutor.awaitTermination(CDC_CALLBACK_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				final List<Runnable> abandoned = captureExecutor.shutdownNow();
				log.warn(
					"Change data capture callbacks did not finish within {} ms while closing the evitaDB " +
						"client; {} pending callback(s) were abandoned and their consumers will not be notified.",
					CDC_CALLBACK_DRAIN_TIMEOUT_MS, abandoned.size()
				);
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			captureExecutor.shutdownNow();
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
	 * Resolves the deadline a call of the passed tier runs under.
	 *
	 * An explicit {@link #executeWithExtendedTimeout} override wins over both tiers: the caller named a
	 * duration for the work inside that lambda, and silently substituting a configured default for it -
	 * in either direction - would defeat the point of the API. Absent an override, the tier decides, so
	 * that a streaming call is budgeted per message rather than per call.
	 *
	 * @param tier which of the configured budgets applies, normally taken from the channel the call's
	 *             stub was built from
	 * @return the timeout to deadline the call with
	 */
	@Nonnull
	Timeout resolveTimeout(@Nonnull TimeoutTier tier) {
		final LinkedList<Timeout> callTimeouts = this.timeout.get();
		// the stack is seeded with exactly one element, so anything beyond that is a caller override
		return callTimeouts.size() > 1 ?
			Objects.requireNonNull(callTimeouts.peek()) :
			tier.resolve(this.configuration.timeouts());
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
	 * Force-closes any `EvitaClientSession` bound to `catalogName` without issuing a server
	 * round-trip. Called after a top-level catalog mutation (delete, rename, replace) initiated
	 * through *this* client completes on the server — at that point the server has already
	 * terminated matching sessions via `closeAllActiveSessionsAndSuspend`, so the only work
	 * left is to evict the now-dead instances from `activeSessions` and flip their
	 * `isActive()` flag so subsequent calls fail fast.
	 *
	 * Only the sessions living in this client instance can be cleaned up this way; sessions
	 * opened by other `EvitaClient` instances (or the server itself deleting a catalog) stay
	 * stale until their next failing RPC.
	 */
	private void evictLocalSessionsForCatalog(@Nonnull String catalogName) {
		for (final EvitaSessionContract session : this.activeSessions.values()) {
			if (catalogName.equals(session.getCatalogName()) && session instanceof EvitaClientSession clientSession) {
				clientSession.terminateLocally();
			}
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
	@Nullable
	private <T> T executeWithStreamingEvitaService(
		@Nonnull AsyncCallFunction<EvitaServiceStub, T> lambda
	) {
		return executeWithStreamingEvitaService(lambda, this.evitaServiceStub);
	}

	/**
	 * Variant of {@link #executeWithStreamingEvitaService(AsyncCallFunction)} that issues the call on the
	 * {@link #cdcClientFactory dedicated CDC channel}, so a long-lived capture stream never shares
	 * a connection - nor an event loop thread - with ordinary request/response traffic.
	 *
	 * @param lambda function that holds a logic passed by the caller
	 * @param <T>    return type of the function
	 * @return result of the applied function
	 */
	@Nullable
	private <T> T executeWithStreamingEvitaCdcService(
		@Nonnull AsyncCallFunction<EvitaServiceStub, T> lambda
	) {
		return executeWithStreamingEvitaService(lambda, this.evitaServiceCdcStub);
	}

	/**
	 * Applies the caller's logic on the given stub with the streaming deadline attached, translating the
	 * transport-level failures into the driver's exception family.
	 *
	 * @param lambda function that holds a logic passed by the caller
	 * @param stub   stub - and therefore channel - the call is issued on
	 * @param <T>    return type of the function
	 * @return result of the applied function
	 */
	@Nullable
	private <T> T executeWithStreamingEvitaService(
		@Nonnull AsyncCallFunction<EvitaServiceStub, T> lambda,
		@Nonnull EvitaServiceStub stub
	) {
		try {
			return lambda.apply(
				stub.withDeadlineAfter(this.streamingTimeout)
			);
		} catch (ExecutionException e) {
			throw EvitaClient.transformException(
				e.getCause() == null ? e : e.getCause(),
				Functions.noOpRunnable()
			);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new EvitaClientServerCallException("Server call interrupted.", e);
		} catch (TimeoutException e) {
			final ClientTimeoutOptions clientTimeouts = this.configuration.timeouts();
			throw new EvitaClientTimedOutException(
				clientTimeouts.streamingTimeout(),
				clientTimeouts.streamingTimeoutUnit()
			);
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
	@Nonnull
	private <T> T executeWithEvitaFutureService(
		@Nonnull AsyncCallFunction<EvitaServiceFutureStub, ListenableFuture<T>> lambda
	) {
		final Timeout timeout = Objects.requireNonNull(this.timeout.get().peek());
		try {
			return Objects.requireNonNull(
				Objects.requireNonNull(
					lambda.apply(
						this.evitaServiceFutureStub.withDeadlineAfter(timeout.timeout(), timeout.timeoutUnit())
					)
				).get(timeout.timeout(), timeout.timeoutUnit())
			);
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

	/**
	 * Key type for the {@link #activePublishers} map, ensuring compile-time safety
	 * while allowing both system-level and catalog-level capture keys.
	 */
	sealed interface CapturePublisherKey
		permits SystemCaptureKey, CatalogBoundCaptureKey {
	}

	/**
	 * Thread type of the {@link #cdcCallbackExecutor() capture callback executor}.
	 *
	 * It exists purely so the driver can recognise "I am running on a capture callback thread" without parsing
	 * thread names - {@link #drainAndShutdownCdcCallbackExecutor()} uses it to avoid waiting for the executor
	 * from inside one of its own tasks, and tests use it to assert that consumer callbacks never run on the
	 * gRPC event loop.
	 */
	public static final class CdcCallbackThread extends Thread {

		/**
		 * Creates a capture callback thread.
		 *
		 * @param target the task the thread runs
		 * @param name   diagnostic thread name
		 */
		CdcCallbackThread(@Nonnull Runnable target, @Nonnull String name) {
			super(target, name);
		}

	}

	/**
	 * Key for system-level CDC publishers in the {@link #activePublishers} map.
	 *
	 * @param request the original system capture request
	 */
	record SystemCaptureKey(
		@Nonnull ChangeSystemCaptureRequest request
	) implements CapturePublisherKey {
	}

	/**
	 * Key for catalog-level CDC publishers in the {@link #activePublishers} map.
	 * Binds a {@link ChangeCaptureRequest} to a specific catalog name, preventing
	 * collisions when two different catalogs issue requests with identical criteria.
	 *
	 * @param catalogName the name of the catalog this capture is bound to
	 * @param request     the original capture request
	 */
	record CatalogBoundCaptureKey(
		@Nonnull String catalogName,
		@Nonnull ChangeCatalogCaptureRequest request
	) implements CapturePublisherKey {
	}

}
