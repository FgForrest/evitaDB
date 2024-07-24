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

package io.evitadb.driver.interceptor;

import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.externalApi.grpc.constants.GrpcHeaders;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * This class is used to intercept client calls prior their sending to the server. If client did set sessionId and sessionType
 * in {@link SessionIdHolder}, then these two values will be added to the call metadata.
 *
 * @author Tomáš Pozler, 2022
 */
public class ClientSessionInterceptor implements ClientInterceptor {
	private final EvitaClientConfiguration configuration;

	public ClientSessionInterceptor(@Nonnull EvitaClientConfiguration configuration) {
		this.configuration = configuration;
	}

	public ClientSessionInterceptor() {
		this.configuration = null;
	}

	/**
	 * This method is intercepting client calls prior their sending to the server. When target method requires a session, then
	 * the requested information set by the client will be checked in {@link SessionIdHolder}. If there is a set sessionId, then
	 * it will be added together with sessionType to the call metadata.
	 *
	 * @param methodDescriptor which contains information about method called
	 * @param callOptions      to get call properties
	 * @param channel          on which the call is made
	 * @return forwarded client call
	 */
	@Override
	public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions, Channel channel) {
		return new ForwardingClientCall.SimpleForwardingClientCall<>(channel.newCall(methodDescriptor, callOptions)) {
			@Override
			public void start(Listener<RespT> listener, Metadata metadata) {
				final String sessionId = SessionIdHolder.getSessionId();
				if (sessionId != null) {
					metadata.put(Metadata.Key.of(GrpcHeaders.SESSION_ID_HEADER, Metadata.ASCII_STRING_MARSHALLER), SessionIdHolder.getSessionId());
				}
				if (configuration != null) {
					metadata.put(Metadata.Key.of(GrpcHeaders.CLIENT_ID_HEADER, Metadata.ASCII_STRING_MARSHALLER), configuration.clientId());
				}
				super.start(listener, metadata);
			}
		};
	}

	/**
	 * Class used by client to set sessionId and sessionType in context. These values are used in server session interceptor
	 * to set session to the call. It is here used for testing purposes, client can be using some kind of similar approach to this
	 * to pass his credentials in form of sessionId to be able to properly use this gRPC API.
	 *
	 * @author Tomáš Pozler, 2022
	 */
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class SessionIdHolder {
		/**
		 * Context that holds current session in thread-local space.
		 */
		private static final ThreadLocal<SessionDescriptor> SESSION_DESCRIPTOR = new ThreadLocal<>();

		/**
		 * Sets sessionId to the context.
		 *
		 * @param catalogName session to set
		 * @param sessionId   session to set
		 */
		public static void setSessionId(String catalogName, String sessionId) {
			SESSION_DESCRIPTOR.set(new SessionDescriptor(catalogName, sessionId));
		}

		/**
		 * Resets information about session.
		 */
		public static void reset() {
			SESSION_DESCRIPTOR.remove();
		}

		/**
		 * Returns sessionId from the context.
		 *
		 * @return sessionId
		 */
		public static String getSessionId() {
			final SessionDescriptor descriptor = SESSION_DESCRIPTOR.get();
			return descriptor == null ? null : SESSION_DESCRIPTOR.get().sessionId();
		}

		/**
		 * Returns catalog name from the context.
		 *
		 * @return sessionType
		 */
		public static String getCatalogName() {
			final SessionDescriptor descriptor = SESSION_DESCRIPTOR.get();
			return descriptor == null ? null : SESSION_DESCRIPTOR.get().catalogName();
		}

		/**
		 * Record that holds information about session.
		 */
		public record SessionDescriptor(@Nonnull String catalogName, @Nonnull String sessionId) {
		}
	}
}
