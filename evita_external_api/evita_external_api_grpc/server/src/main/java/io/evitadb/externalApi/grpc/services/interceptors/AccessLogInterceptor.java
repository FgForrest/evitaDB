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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.grpc.services.interceptors;

import io.evitadb.externalApi.log.AccessLogMarker;
import io.grpc.Attributes;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import javax.annotation.Nonnull;
import java.net.InetSocketAddress;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

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
public class AccessLogInterceptor implements ServerInterceptor {

	private static final DateTimeFormatter ACCESS_LOG_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z");
	/**
	 * Marks gRPC's access log messages to differentiate them from access log messages from other web servers.
	 */
	private static final Marker GRPC_ACCESS_LOG_MARKER = MarkerFactory.getMarker("GRPC_ACCESS_LOG");

	private static final Attributes.Key<String> USER_AGENT = Attributes.Key.create("user-agent");


	@Override
	public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
	                                                  Metadata headers,
	                                                  ServerCallHandler<ReqT, RespT> next) {
		final LoggingServerCall<ReqT, RespT> loggingServerCall = new LoggingServerCall<>(call);
		final ServerCall.Listener<ReqT> listener = next.startCall(loggingServerCall, headers);
		return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {};
	}

	private static class LoggingServerCall<M, R> extends ServerCall<M, R> {

		private final ServerCall<M, R> serverCall;

		protected LoggingServerCall(ServerCall<M, R> serverCall) {
			this.serverCall = serverCall;
		}

		@Override
		public void close(Status status, Metadata trailers) {
			log.atInfo()
				.addMarker(AccessLogMarker.getInstance())
				.addMarker(GRPC_ACCESS_LOG_MARKER)
				.log(constructLogMessage(serverCall, status));
			serverCall.close(status, trailers);
		}

		@Override
		public void request(int numMessages) {
			serverCall.request(numMessages);
		}

		@Override
		public void sendHeaders(Metadata headers) {
			serverCall.sendHeaders(headers);
		}

		@Override
		public void sendMessage(R message) {
			serverCall.sendMessage(message);
		}

		@Override
		public boolean isCancelled() {
			return serverCall.isCancelled();
		}

		@Override
		public MethodDescriptor<M, R> getMethodDescriptor() {
			return serverCall.getMethodDescriptor();
		}

		@Nonnull
		private <ReqT, RespT> String constructLogMessage(@Nonnull ServerCall<ReqT, RespT> call, @Nonnull Status status) {
			final String clientIP = ((InetSocketAddress) call.getAttributes()
				.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR)).getAddress().getHostName();
			final MethodType requestMethodType = call.getMethodDescriptor().getType();
			final String requestMethod = call.getMethodDescriptor().getFullMethodName();
			// gRPC Java supports only HTTP/2 protocol now and doesn't expose actual used protocol
			final String protocol = "HTTP/2";
			final int statusCode = status.getCode().value();
			// Currently, we cannot determine response size because gRPC API doesn't expose status code and response object
			// in a single place. We can either send status or response size, but not both. We opt for status code as it
			// is more important.
			// final int responseSize = 0;

			final String userAgent = call.getAttributes().get(USER_AGENT);

			return String.format(
				"%s - - [%s] \"%s %s %s\" %d - \"-\" \"%s\"",
				clientIP,
				OffsetDateTime.now().format(ACCESS_LOG_DATE_FORMAT),
				requestMethodType,
				requestMethod,
				protocol,
				statusCode,
				userAgent == null || userAgent.isEmpty() ? "-" : userAgent
			);
		}
	}
}
