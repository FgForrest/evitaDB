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

package io.evitadb.externalApi.grpc.services.interceptors;

import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.core.session.EvitaInternalSessionContract;
import io.evitadb.externalApi.event.ResponseStatus;
import io.evitadb.externalApi.grpc.metric.event.AbstractProcedureCalledEvent;
import io.evitadb.externalApi.grpc.metric.event.AbstractProcedureCalledEvent.InitiatorType;
import io.evitadb.externalApi.grpc.metric.event.EvitaProcedureCalledEvent;
import io.evitadb.externalApi.grpc.metric.event.SessionProcedureCalledEvent;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Logs access log messages to Slf4J logger marked with `ACCESS_LOG` and `GRPC_ACCESS_LOG`.
 *
 * <p>
 * It tries to replicate the <a href="http://fileformats.archiveteam.org/wiki/Combined_Log_Format">combined log format</a>,
 * specifically it logs following data:
 * <pre>
 *     hostname - - [date] "methodType method protocol" statusCode "-" "userAgent"
 * </pre>
 * Currently, we don't support following data:
 * <ul>
 *     <li>identity of client</li>
 *     <li>username</li>
 *     <li>response size</li>
 *     <li>referrer</li>
 * </ul>
 *
 * <p>
 * Inspired by https://stackoverflow.com/a/56999548.
 *
 * **The interceptor has a second responsibility: the request start.** Every seam through which control passes
 * downstream - `startCall` and each listener callback - is wrapped in a scope that records the start of the request
 * being served in the MDC, so that anything logged underneath it can report how far into the request it happened.
 * A seam left unwrapped costs that on every line logged under the callback **and** on every task submitted to an
 * evitaDB executor from it, because the executor snapshots the MDC as the task is constructed rather than as it
 * runs. A contributor overriding a further listener callback therefore has to wrap it too.
 *
 * Guarded by `ObservabilityInterceptorRequestStartTest`.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
@RequiredArgsConstructor
public class ObservabilityInterceptor implements ServerInterceptor {


	@Override
	public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
	                                                  Metadata headers,
	                                                  ServerCallHandler<ReqT, RespT> next) {
		final MethodDescriptor<ReqT, RespT> methodDescriptor = call.getMethodDescriptor();
		final Optional<EvitaInternalSessionContract> session = Optional.ofNullable(ServerSessionInterceptor.SESSION.get());
		final AbstractProcedureCalledEvent event = session
			.map(
				it -> (AbstractProcedureCalledEvent) new SessionProcedureCalledEvent(
					it.getCatalogName(),
					methodDescriptor.getServiceName(),
					methodDescriptor.getBareMethodName(),
					methodDescriptor.getType()
				)
			)
			.orElseGet(
				() -> new EvitaProcedureCalledEvent(
					methodDescriptor.getServiceName(),
					methodDescriptor.getBareMethodName(),
					methodDescriptor.getType()
				)
			);
		final ObservabilityServerCall<ReqT, RespT> loggingServerCall = new ObservabilityServerCall<>(call, event);
		// `startCall` is where a client-streaming or bidi service method body actually executes - grpc-java invokes
		// the user method before it constructs the listener - so the request start has to be recorded around it and
		// not only around the listener callbacks below. It is also read here once and handed to the listener,
		// because this is the one site Armeria is guaranteed to have pushed the request context at.
		final String requestStart = ExternalApiTracingContext.currentRequestStart();
		return TracingContext.executeWithRequestStart(
			requestStart,
			() -> new ObservabilityListener<>(
				next.startCall(loggingServerCall, headers), event, requestStart
			)
		);
	}

	/**
	 * Observability server call that logs access log messages and fires gRPC procedure called event.
	 *
	 * It must extend {@link SimpleForwardingServerCall} rather than {@link ServerCall} directly: several
	 * {@link ServerCall} methods are non-abstract and carry defaults that silently disagree with the
	 * transport. {@link ServerCall#isReady()} is the dangerous one — it returns an unconditional `true`,
	 * so a hand-rolled decorator that forgets to override it reports every transport as writable and
	 * disables any {@code isReady()}-driven flow control in the service method underneath it. This is
	 * the only {@link ServerCall} decorator in evitaDB's interceptor chain - the other two wrap the
	 * *listener* - so it is what a service's {@code ServerCallStreamObserver} actually queries, and
	 * what {@code GlobalExceptionHandlerInterceptor} sees when it decides whether it may still close a
	 * failing call. {@link ServerCall#setOnReadyThreshold(int)}, {@link ServerCall#getAttributes()},
	 * {@link ServerCall#getAuthority()}, {@link ServerCall#getSecurityLevel()},
	 * {@link ServerCall#setCompression(String)} and {@link ServerCall#setMessageCompression(boolean)}
	 * have the same problem in a less visible way. Forwarding by default and overriding only what
	 * genuinely adds behaviour keeps the decorator correct as gRPC adds methods.
	 *
	 * Guarded by `ObservabilityInterceptorReadinessTest`.
	 */
	private static class ObservabilityServerCall<M, R> extends SimpleForwardingServerCall<M, R> {
		private final AbstractProcedureCalledEvent event;

		protected ObservabilityServerCall(
			@Nonnull ServerCall<M, R> serverCall,
			@Nonnull AbstractProcedureCalledEvent event
		) {
			super(serverCall);
			this.event = event;
		}

		@Override
		public void sendMessage(R message) {
			if (this.event.streamsResponses()) {
				this.event.setInitiator(InitiatorType.SERVER);
			}
			super.sendMessage(message);
		}

		@Override
		public void close(Status status, Metadata trailers) {
			this.event.finish().commit();
			super.close(status, trailers);
		}

	}

	/**
	 * Observability listener that changes the properties of the gRPC procedure called event, and re-establishes the
	 * start of the request being served around every callback it delegates - including the ones it has nothing else
	 * to add to.
	 */
	private static class ObservabilityListener<R> extends ForwardingServerCallListener<R> {
		private final ServerCall.Listener<R> delegate;
		private final AbstractProcedureCalledEvent event;
		/**
		 * The start of the request this call belongs to, as it was readable when the call was intercepted, or null
		 * when no request context was current then. A listener serves exactly one call, so this value stays correct
		 * for the whole of its life - which is what lets the callbacks below report it without asking Armeria again.
		 */
		@Nullable private final String requestStart;

		ObservabilityListener(
			@Nonnull ServerCall.Listener<R> delegate,
			@Nonnull AbstractProcedureCalledEvent event,
			@Nullable String requestStart
		) {
			this.delegate = delegate;
			this.event = event;
			this.requestStart = requestStart;
		}

		/**
		 * Records the start of the request being served in the MDC for the duration of {@code lambda}, so that
		 * anything logged underneath it - and any task submitted to an evitaDB executor from underneath it, which
		 * snapshots the MDC as it is constructed - can report how far into the request it happened.
		 *
		 * The value captured when the call was intercepted is preferred over reading Armeria again, because it is
		 * the same value and it is available even where Armeria has not pushed the request context: `onReady` is
		 * invoked without it, and `startCall` itself runs without it once a method is served on the blocking task
		 * executor. Only when nothing was captured is the live context consulted, and when that yields nothing
		 * either the MDC is left exactly as it was - these scopes nest, and a listener callback runs inside the
		 * same request as the `startCall` that preceded it.
		 *
		 * @param lambda the work to run with the request start recorded
		 */
		private void withRequestStart(@Nonnull Runnable lambda) {
			TracingContext.executeWithRequestStart(
				this.requestStart == null ? ExternalApiTracingContext.currentRequestStart() : this.requestStart,
				() -> {
					lambda.run();
					return null;
				}
			);
		}

		@Override
		public void onHalfClose() {
			withRequestStart(() -> {
				try {
					super.onHalfClose();
				} catch (RuntimeException ex) {
					this.event.setGrpcResponseStatus(ResponseStatus.ERROR);
					throw ex;
				}
			});
		}

		@Override
		public void onCancel() {
			this.event.setGrpcResponseStatus(ResponseStatus.CANCELLED);
			withRequestStart(() -> super.onCancel());
		}

		/**
		 * Delegates unchanged. The override exists **only** to open the request-start scope around the delegate: a
		 * call being torn down still logs, and those lines belong to the request that is ending. It deliberately adds
		 * no other behaviour, so it must not be mistaken for a removable no-op forward.
		 */
		@Override
		public void onComplete() {
			withRequestStart(() -> super.onComplete());
		}

		@Override
		protected ServerCall.Listener<R> delegate() {
			return this.delegate;
		}

		@Override
		public void onMessage(R request) {
			if (this.event.streamsRequests() || this.event.unaryCall()) {
				this.event.setInitiator(InitiatorType.CLIENT);
			}
			withRequestStart(() -> super.onMessage(request));
		}

		/**
		 * Delegates unchanged, and like {@code onComplete()} exists only to open the request-start scope - but this
		 * is the callback that most needs it: Armeria invokes `onReady` **without** pushing the request context,
		 * unlike the other four, so the value captured when the call was intercepted is the only source of a request
		 * start here. Without the wrapper, everything a flow-controlled producer logs from `onReady` loses its
		 * duration. It deliberately adds no other behaviour.
		 */
		@Override
		public void onReady() {
			withRequestStart(() -> super.onReady());
		}

	}

}
