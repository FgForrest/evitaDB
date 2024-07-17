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

import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.core.Evita;
import io.evitadb.core.EvitaInternalSessionContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.configuration.TlsMode;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.UUIDUtil;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.InternalMetadata;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.externalApi.grpc.constants.GrpcHeaders.CATALOG_NAME_HEADER;
import static io.evitadb.externalApi.grpc.constants.GrpcHeaders.METADATA_HEADER;
import static io.evitadb.externalApi.grpc.constants.GrpcHeaders.METHOD_NAME_HEADER;
import static io.evitadb.externalApi.grpc.constants.GrpcHeaders.SESSION_ID_HEADER;

/**
 * This class is used to intercept calls to gRPC services by setting a session to
 * the call. If no session is specified by its type and sessionId on a non-opened endpoint, then an unauthenticated
 * status will be returned to the client.
 *
 * @author Tomáš Pozler, 2022
 */
@RequiredArgsConstructor
public class ServerSessionInterceptor implements ServerInterceptor {
	private static final Set<String> ENDPOINTS_NOT_REQUIRING_SESSION = CollectionUtils.createHashSet(32);
	static {
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaService/CreateReadOnlySession");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaService/CreateReadWriteSession");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaService/CreateBinaryReadOnlySession");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaService/CreateBinaryReadWriteSession");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaService/TerminateSession");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaService/GetCatalogNames");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaService/DefineCatalog");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaService/RenameCatalog");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaService/ReplaceCatalog");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaService/Update");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaService/DeleteCatalogIfExists");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaManagementService/ServerStatus");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaManagementService/RestoreCatalog");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaManagementService/RestoreCatalogFromServerFile");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaManagementService/ListTaskStatuses");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaManagementService/GetTaskStatus");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaManagementService/GetTaskStatuses");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaManagementService/CancelTask");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaManagementService/ListFilesToFetch");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaManagementService/GetFileToFetch");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaManagementService/FetchFile");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaManagementService/DeleteFile");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaManagementService/GetCatalogStatistics");
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaManagementService/GetConfiguration");

		// might be already closed, same behaviour as server session
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaSessionService/Close");
		// might be already closed, same behaviour as server session
		ENDPOINTS_NOT_REQUIRING_SESSION.add("io.evitadb.externalApi.grpc.generated.EvitaSessionService/GoLiveAndClose");
	}

	/**
	 * Context that holds current {@link EvitaSessionContract} session.
	 */
	public static final Context.Key<EvitaInternalSessionContract> SESSION = Context.key(SESSION_ID_HEADER);
	public static final Context.Key<String> CATALOG_NAME = Context.key(CATALOG_NAME_HEADER);
	public static final Context.Key<Metadata> METADATA = Context.key(METADATA_HEADER);

	/**
	 * Reference to the {@link EvitaContract} instance.
	 */
	private final Evita evita;

	/**
	 * Flag indicating whether TLS is enabled.
	 */
	private final TlsMode tlsMode;

	/**
	 * This method is intercepting calls to gRPC services. If client provided session type and sessionId in metadata, an attempt
	 * for getting matching session will occur. If session is not found and is required by endpoint, then
	 * unauthenticated status will be returned to the client.
	 * If session is found or endpoint doesn't need one, the requested method will be executed within context with the
	 * found session set as the context session (if required and found).
	 *
	 * @param serverCall        original call
	 * @param metadata          metadata of the call
	 * @param serverCallHandler handler of the call
	 * @return server call handler or unauthenticated status if session is not found
	 */
	@Override
	public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> serverCall, Metadata metadata, ServerCallHandler<ReqT, RespT> serverCallHandler) {
		if (serverCall.getMethodDescriptor().getServiceName().equals("grpc.reflection.v1alpha.ServerReflection")) {
			return serverCallHandler.startCall(serverCall, metadata);
		}
		if (tlsMode != TlsMode.RELAXED) {
			final String scheme = metadata.get(InternalMetadata.keyOf(":scheme", Metadata.ASCII_STRING_MARSHALLER));
			if ("https".equals(scheme) && tlsMode == TlsMode.FORCE_NO_TLS) {
				final Status status = Status.UNAUTHENTICATED
					.withCause(new EvitaInvalidUsageException("TLS is not required for this endpoint."))
					.withDescription("TLS is not required for this endpoint.");
				serverCall.close(status, metadata);
				return new ServerCall.Listener<>() {};
			}
			if ("http".equals(scheme) && tlsMode == TlsMode.FORCE_TLS) {
				final Status status = Status.UNAUTHENTICATED
					.withCause(new EvitaInvalidUsageException("TLS is required for this endpoint."))
					.withDescription("TLS is required for this endpoint.");
				serverCall.close(status, metadata);
				return new ServerCall.Listener<>() {};
			}
		}

		final Metadata.Key<String> catalogNameMetadata = Metadata.Key.of(CATALOG_NAME_HEADER, Metadata.ASCII_STRING_MARSHALLER);
		final Metadata.Key<String> sessionMetadata = Metadata.Key.of(SESSION_ID_HEADER, Metadata.ASCII_STRING_MARSHALLER);
		final String catalogName = metadata.get(catalogNameMetadata);
		final String sessionId = metadata.get(sessionMetadata);
		final Optional<EvitaInternalSessionContract> activeSession = resolveActiveSession(catalogName, sessionId);
		if (activeSession.isEmpty() && isEndpointRequiresSession(serverCall)) {
			final Status status = Status.UNAUTHENTICATED
				.withCause(new EvitaInvalidUsageException("Your session is either not set or is not active."))
				.withDescription("Your session (catalog: "+ catalogName + ", session id: " + sessionId + ") is either not set or is not active.");
			serverCall.close(status, metadata);
			return new ServerCall.Listener<>() {};
		}

		metadata.put(Metadata.Key.of(METHOD_NAME_HEADER, Metadata.ASCII_STRING_MARSHALLER), serverCall.getMethodDescriptor().getBareMethodName());

		Context context = Context.current();

		if (activeSession.isPresent()) {
			context = context.withValue(SESSION, activeSession.get());
		}
		context = context.withValue(METADATA, metadata).withValue(CATALOG_NAME, catalogName);
		return Contexts.interceptCall(context, serverCall, metadata, serverCallHandler);
	}

	@Nonnull
	private Optional<EvitaInternalSessionContract> resolveActiveSession(@Nullable String catalogName, @Nullable String sessionId) {
		if (catalogName == null && sessionId == null) {
			return Optional.empty();
		}
		Assert.notNull(catalogName, "Both `catalogName` and `sessionId` must be specified to identify session.");
		Assert.notNull(sessionId, "Both `catalogName` and `sessionId` must be specified to identify session.");

		return evita.getSessionById(catalogName, UUIDUtil.uuid(sessionId))
			.map(session -> {
				if (!session.isActive()) {
					return null;
				}
				if (!(session instanceof EvitaInternalSessionContract)) {
					return null;
				}
				return (EvitaInternalSessionContract) session;
			});
	}

	private static <ReqT, RespT> boolean isEndpointRequiresSession(@Nonnull ServerCall<ReqT, RespT> serverCall) {
		return !ENDPOINTS_NOT_REQUIRING_SESSION.contains(serverCall.getMethodDescriptor().getFullMethodName());
	}

}
