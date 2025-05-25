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

package io.evitadb.externalApi.grpc.services.interceptors;

import io.evitadb.core.EvitaInternalSessionContract;
import io.evitadb.externalApi.grpc.metric.event.AbstractProcedureCalledEvent;
import io.evitadb.externalApi.grpc.metric.event.AbstractProcedureCalledEvent.InitiatorType;
import io.evitadb.externalApi.grpc.metric.event.AbstractProcedureCalledEvent.ResponseState;
import io.evitadb.externalApi.grpc.metric.event.EvitaProcedureCalledEvent;
import io.evitadb.externalApi.grpc.metric.event.SessionProcedureCalledEvent;
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
		return new ObservabilityListener<>(
			next.startCall(loggingServerCall, headers), event
		);
	}

	/**
	 * Observability server call that logs access log messages and fires gRPC procedure called event.
	 */
	private static class ObservabilityServerCall<M, R> extends ServerCall<M, R> {
		private final ServerCall<M, R> serverCall;
		private final AbstractProcedureCalledEvent event;

		protected ObservabilityServerCall(
			@Nonnull ServerCall<M, R> serverCall,
			@Nonnull AbstractProcedureCalledEvent event
		) {
			this.serverCall = serverCall;
			this.event = event;
		}

		@Override
		public void request(int numMessages) {
			this.serverCall.request(numMessages);
		}

		@Override
		public void sendHeaders(Metadata headers) {
			this.serverCall.sendHeaders(headers);
		}

		@Override
		public void sendMessage(R message) {
			if (this.event.streamsResponses()) {
				this.event.setInitiator(InitiatorType.SERVER);
			}
			this.serverCall.sendMessage(message);
		}

		@Override
		public void close(Status status, Metadata trailers) {
			this.event.finish().commit();
			this.serverCall.close(status, trailers);
		}

		@Override
		public boolean isCancelled() {
			return this.serverCall.isCancelled();
		}

		@Override
		public MethodDescriptor<M, R> getMethodDescriptor() {
			return this.serverCall.getMethodDescriptor();
		}

	}

	/**
	 * Observability listener that changes the properties of the gRPC procedure called event.
	 */
	private static class ObservabilityListener<R> extends ForwardingServerCallListener<R> {
		private final ServerCall.Listener<R> delegate;
		private final AbstractProcedureCalledEvent event;

		ObservabilityListener(
			@Nonnull ServerCall.Listener<R> delegate,
			@Nonnull AbstractProcedureCalledEvent event
		) {
			this.delegate = delegate;
			this.event = event;
		}

		@Override
		public void onHalfClose() {
			try {
				super.onHalfClose();
			} catch (RuntimeException ex) {
				this.event.setGrpcResponseStatus(ResponseState.ERROR);
				throw ex;
			}
		}

		@Override
		public void onCancel() {
			this.event.setGrpcResponseStatus(ResponseState.CANCELED);
			super.onCancel();
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
			super.onMessage(request);
		}

	}

}
