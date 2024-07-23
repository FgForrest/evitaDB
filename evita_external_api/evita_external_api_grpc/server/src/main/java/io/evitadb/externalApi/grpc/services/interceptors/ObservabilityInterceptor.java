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

package io.evitadb.externalApi.grpc.services.interceptors;

import io.evitadb.externalApi.grpc.metric.event.ProcedureCalledEvent;
import io.evitadb.externalApi.grpc.metric.event.ProcedureCalledEvent.InitiatorType;
import io.evitadb.externalApi.grpc.metric.event.ProcedureCalledEvent.ResponseState;
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
 * @author Lukáš Hornych, 2023
 */
@Slf4j
@RequiredArgsConstructor
public class ObservabilityInterceptor implements ServerInterceptor {


	@Override
	public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
	                                                  Metadata headers,
	                                                  ServerCallHandler<ReqT, RespT> next) {
		final MethodDescriptor<ReqT, RespT> methodDescriptor = call.getMethodDescriptor();
		final String catalogName = ServerSessionInterceptor.CATALOG_NAME.get();
		final ProcedureCalledEvent event = new ProcedureCalledEvent(
			catalogName,
			methodDescriptor.getServiceName(),
			methodDescriptor.getBareMethodName(),
			methodDescriptor.getType()
		);
		final ObservabilityServerCall<ReqT, RespT> loggingServerCall = new ObservabilityServerCall<>(call, event);
		return new ObservabilityListener<>(
			next.startCall(loggingServerCall, headers), event
		);
	}

	/**
	 * Observability server call that logs access log messages and fires gRPC procedure called event.
	 * @param <M>
	 * @param <R>
	 */
	private static class ObservabilityServerCall<M, R> extends ServerCall<M, R> {
		private final ServerCall<M, R> serverCall;
		private final ProcedureCalledEvent event;

		protected ObservabilityServerCall(
			@Nonnull ServerCall<M, R> serverCall,
			@Nonnull ProcedureCalledEvent event
		) {
			this.serverCall = serverCall;
			this.event = event;
		}

		@Override
		public void close(Status status, Metadata trailers) {
			this.event.finish().commit();
			this.serverCall.close(status, trailers);
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
		public boolean isCancelled() {
			return serverCall.isCancelled();
		}

		@Override
		public MethodDescriptor<M, R> getMethodDescriptor() {
			return this.serverCall.getMethodDescriptor();
		}

	}

	/**
	 * Observability listener that changes the properties of the gRPC procedure called event.
	 * @param <R>
	 */
	private static class ObservabilityListener<R> extends ForwardingServerCallListener<R> {
		private final ServerCall.Listener<R> delegate;
		private final ProcedureCalledEvent event;

		ObservabilityListener(
			@Nonnull ServerCall.Listener<R> delegate,
			@Nonnull ProcedureCalledEvent event
		) {
			this.delegate = delegate;
			this.event = event;
		}

		@Override
		protected ServerCall.Listener<R> delegate() {
			return this.delegate;
		}

		@Override
		public void onHalfClose() {
			try {
				super.onHalfClose();
			} catch (RuntimeException ex) {
				event.setResponseState(ResponseState.ERROR);
				throw ex;
			}
		}

		@Override
		public void onCancel() {
			event.setResponseState(ResponseState.CANCELED);
			super.onCancel();
		}

		@Override
		public void onMessage(R request) {
			if (event.streamsRequests() || event.unaryCall()) {
				event.setInitiator(InitiatorType.CLIENT);
			}
			super.onMessage(request);
		}

	}

}
