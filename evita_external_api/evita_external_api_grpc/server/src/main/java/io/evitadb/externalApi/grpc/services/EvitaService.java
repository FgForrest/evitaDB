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

package io.evitadb.externalApi.grpc.services;

import com.google.protobuf.Empty;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.requestResponse.cdc.ChangeCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.async.ObservableExecutorServiceWithHardDeadline;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.event.ReadinessEvent;
import io.evitadb.externalApi.event.ReadinessEvent.Prospective;
import io.evitadb.externalApi.event.ReadinessEvent.Result;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.constants.GrpcHeaders;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateResponse.Builder;
import io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingTopLevelCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.services.interceptors.GlobalExceptionHandlerInterceptor;
import io.evitadb.externalApi.grpc.services.interceptors.ServerSessionInterceptor;
import io.evitadb.externalApi.trace.ExternalApiTracingContextProvider;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.evitadb.utils.UUIDUtil;
import io.grpc.Metadata;
import io.grpc.stub.CallStreamObserver;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.FlowAdapters;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toCaptureContent;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcCatalogState;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcCommitBehavior;

/**
 * This service contains methods that could be called by gRPC clients on {@link EvitaContract}.
 *
 * @author Tomáš Pozler, 2022
 */
@Slf4j
public class EvitaService extends EvitaServiceGrpc.EvitaServiceImplBase {

	/**
	 * Instance of Evita upon which will be executed service calls
	 */
	@Nonnull private final Evita evita;
	/**
	 * Tracing context to be used for gRPC calls.
	 */
	@Nonnull private final ExternalApiTracingContext<Metadata> context;

	/**
	 * Builds array of {@link SessionFlags} based on session type and rollback transaction flag.
	 *
	 * @param sessionType          type of the session
	 * @param rollbackTransactions if true, all transaction will be rolled back on session close
	 * @return built array of {@link SessionFlags}
	 */
	@Nullable
	private static SessionFlags[] getSessionFlags(GrpcSessionType sessionType, boolean rollbackTransactions) {
		final List<SessionFlags> flags = new ArrayList<>(3);
		if (rollbackTransactions) {
			flags.add(SessionFlags.DRY_RUN);
		}
		if (sessionType == GrpcSessionType.READ_WRITE || sessionType == GrpcSessionType.BINARY_READ_WRITE) {
			flags.add(SessionFlags.READ_WRITE);
		}
		if (sessionType == GrpcSessionType.BINARY_READ_ONLY || sessionType == GrpcSessionType.BINARY_READ_WRITE) {
			flags.add(SessionFlags.BINARY);
		}
		return flags.isEmpty() ? null : flags.toArray(new SessionFlags[0]);
	}

	/**
	 * Executes entire lambda function within the scope of a tracing context.
	 *
	 * @param context  tracing context to be used
	 * @param lambda   lambda function to be executed
	 * @param executor executor service to be used as a carrier for a lambda function
	 */
	public static void executeWithClientContext(
		@Nonnull Runnable lambda,
		@Nonnull ObservableExecutorServiceWithHardDeadline executor,
		@Nonnull StreamObserver<?> responseObserver,
		@Nonnull ExternalApiTracingContext<Metadata> context
	) {
		// Retrieve the deadline from the context
		final long requestTimeoutMillis = ServiceRequestContext.current().requestTimeoutMillis();
		final Metadata metadata = ServerSessionInterceptor.METADATA.get();
		final String methodName = GrpcHeaders.getGrpcTraceTaskNameWithMethodName(metadata);
		final Runnable theMethod =
			() -> executor.execute(
				executor.createTask(
					methodName,
					() -> {
						try {
							lambda.run();
						} catch (RuntimeException exception) {
							// Delegate exception handling to GlobalExceptionHandlerInterceptor
							GlobalExceptionHandlerInterceptor.sendErrorToClient(exception, responseObserver);
						}
					},
					requestTimeoutMillis
				)
			);

		context
			.executeWithinBlock(
				methodName,
				metadata,
				theMethod
			);
	}

	public EvitaService(
		@Nonnull Evita evita,
		@Nonnull HeaderOptions headers
	) {
		this.evita = evita;
		this.context = ExternalApiTracingContextProvider.getContext(headers);
	}

	/**
	 * Method is used to check readiness of the gRPC API.
	 *
	 * @param request          empty message
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void isReady(Empty request, StreamObserver<GrpcReadyResponse> responseObserver) {
		new ReadinessEvent(GrpcProvider.CODE, Prospective.SERVER).finish(Result.READY);
		responseObserver.onNext(GrpcReadyResponse.newBuilder().setReady(true).build());
		responseObserver.onCompleted();
	}

	/**
	 * Method is used to create read only session by calling {@link Evita#createSession(SessionTraits)}.
	 *
	 * @param request          request containing session type and session id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void createReadOnlySession(GrpcEvitaSessionRequest request, StreamObserver<GrpcEvitaSessionResponse> responseObserver) {
		createSessionAndBuildResponse(responseObserver, request.getCatalogName(), GrpcSessionType.READ_ONLY, request.getDryRun());
	}

	/**
	 * Method is used to create read-write session by calling {@link Evita#createSession(SessionTraits)}.
	 *
	 * @param request          request containing session type and session id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void createReadWriteSession(GrpcEvitaSessionRequest request, StreamObserver<GrpcEvitaSessionResponse> responseObserver) {
		createSessionAndBuildResponse(responseObserver, request.getCatalogName(), GrpcSessionType.READ_WRITE, request.getDryRun());
	}

	/**
	 * Method is used to create read-only session which will return data in binary format by calling {@link Evita#createSession(SessionTraits)}.
	 *
	 * @param request          request containing session type and session id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void createBinaryReadOnlySession(GrpcEvitaSessionRequest request, StreamObserver<GrpcEvitaSessionResponse> responseObserver) {
		createSessionAndBuildResponse(responseObserver, request.getCatalogName(), GrpcSessionType.BINARY_READ_ONLY, request.getDryRun());
	}

	/**
	 * Method is used to create read-write session which will return data in binary format by calling {@link Evita#createSession(SessionTraits)}.
	 *
	 * @param request          request containing session type and session id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void createBinaryReadWriteSession(GrpcEvitaSessionRequest request, StreamObserver<GrpcEvitaSessionResponse> responseObserver) {
		createSessionAndBuildResponse(responseObserver, request.getCatalogName(), GrpcSessionType.BINARY_READ_WRITE, request.getDryRun());
	}

	/**
	 * Method is used to terminate existing session.
	 *
	 * @param request          request containing catalog name and session id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void terminateSession(GrpcEvitaSessionTerminationRequest request, StreamObserver<GrpcEvitaSessionTerminationResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				final boolean terminated = this.evita.getSessionById(UUIDUtil.uuid(request.getSessionId()))
					.map(session -> {
						this.evita.terminateSession(session);
						return true;
					})
					.orElse(false);

				responseObserver.onNext(GrpcEvitaSessionTerminationResponse.newBuilder()
					.setTerminated(terminated)
					.build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Returns all names of catalogs stored in Evita.
	 *
	 * @param request          empty message
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#getCatalogNames() (String)
	 */
	@Override
	public void getCatalogNames(Empty request, StreamObserver<GrpcCatalogNamesResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				responseObserver.onNext(GrpcCatalogNamesResponse.newBuilder()
					.addAllCatalogNames(this.evita.getCatalogNames())
					.build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Retrieves the state of a specified catalog and sends it back to the client through the provided response observer.
	 *
	 * @param request           The gRPC request containing the catalog name for which the state is requested.
	 * @param responseObserver  The stream observer used to send the response containing the catalog state back to the client.
	 */
	@Override
	public void getCatalogState(GrpcGetCatalogStateRequest request, StreamObserver<GrpcGetCatalogStateResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				final Builder builder = GrpcGetCatalogStateResponse.newBuilder();
				this.evita.getCatalogState(request.getCatalogName())
					.ifPresent(catalogState -> builder.setCatalogState(toGrpcCatalogState(catalogState)));
				responseObserver.onNext(builder.build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Creates new catalog with a name specified in a request.
	 *
	 * @param request          containing name of the catalog to be created
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#defineCatalog(String)
	 */
	@Override
	public void defineCatalog(GrpcDefineCatalogRequest request, StreamObserver<GrpcDefineCatalogResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				this.evita.defineCatalog(request.getCatalogName());
				responseObserver.onNext(GrpcDefineCatalogResponse.newBuilder().setSuccess(true).build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Deletes catalog with a name specified in a request.
	 *
	 * @param request          containing name of the catalog to be deleted
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#deleteCatalogIfExists(String)
	 */
	@Override
	public void deleteCatalogIfExists(GrpcDeleteCatalogIfExistsRequest
		                                  request, StreamObserver<GrpcDeleteCatalogIfExistsResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				boolean success = this.evita.deleteCatalogIfExists(request.getCatalogName());
				responseObserver.onNext(GrpcDeleteCatalogIfExistsResponse.newBuilder().setSuccess(success).build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Applies catalog mutation affecting entire catalog.
	 */
	@Override
	public void update(GrpcUpdateEvitaRequest request, StreamObserver<Empty> responseObserver) {
		executeWithClientContext(
			() -> {
				final TopLevelCatalogSchemaMutation[] schemaMutations = request.getSchemaMutationsList()
					.stream()
					.map(DelegatingTopLevelCatalogSchemaMutationConverter.INSTANCE::convert)
					.toArray(TopLevelCatalogSchemaMutation[]::new);

				this.evita.update(schemaMutations);
				responseObserver.onNext(Empty.getDefaultInstance());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Renames existing catalog to a name specified in a request.
	 *
	 * @param request          containing names of the catalogs involved
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#renameCatalog(String, String)
	 */
	@Override
	public void renameCatalog(GrpcRenameCatalogRequest request, StreamObserver<GrpcRenameCatalogResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				this.evita.renameCatalog(request.getCatalogName(), request.getNewCatalogName());
				responseObserver.onNext(GrpcRenameCatalogResponse.newBuilder().setSuccess(true).build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Replaces existing catalog with a different existing catalog and its contents.
	 *
	 * @param request          containing names of the catalogs involved
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#replaceCatalog(String, String)
	 */
	@Override
	public void replaceCatalog(GrpcReplaceCatalogRequest request, StreamObserver<GrpcReplaceCatalogResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				this.evita.replaceCatalog(request.getCatalogNameToBeReplacedWith(), request.getCatalogNameToBeReplaced());
				responseObserver.onNext(GrpcReplaceCatalogResponse.newBuilder().setSuccess(true).build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * This method is used to create session and build a {@link GrpcEvitaSessionResponse} object.
	 *
	 * @param responseObserver     observer on which errors might be thrown and result returned
	 * @param catalogName          name of the catalog on which should be session created
	 * @param sessionType          type of the session
	 * @param rollbackTransactions if true, all transaction will be rolled back on session close
	 */
	private void createSessionAndBuildResponse(
		@Nonnull StreamObserver<GrpcEvitaSessionResponse> responseObserver,
		@Nonnull String catalogName,
		@Nonnull GrpcSessionType sessionType,
		boolean rollbackTransactions
	) {
		executeWithClientContext(
			() -> {
				final SessionFlags[] flags = getSessionFlags(sessionType, rollbackTransactions);
				final EvitaSessionContract session = this.evita.createSession(new SessionTraits(catalogName, flags));
				responseObserver.onNext(GrpcEvitaSessionResponse.newBuilder()
					.setCatalogId(session.getCatalogId().toString())
					.setSessionId(session.getId().toString())
					.setCatalogState(toGrpcCatalogState(session.getCatalogState()))
					.setCommitBehaviour(toGrpcCommitBehavior(session.getCommitBehavior()))
					.setSessionType(sessionType)
					.build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * TODO JNO - revisit and document?
	 * @param request
	 * @param responseObserver
	 */
	@Override
	public void registerSystemChangeCapture(GrpcRegisterSystemChangeCaptureRequest request, StreamObserver<GrpcRegisterSystemChangeCaptureResponse> responseObserver) {
		final Publisher<ChangeSystemCapture> publisher = this.evita.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(toCaptureContent(request.getContent()))
		);

		final ServerCallStreamObserver<GrpcRegisterSystemChangeCaptureResponse> observer =
			((ServerCallStreamObserver<GrpcRegisterSystemChangeCaptureResponse>) responseObserver);

		publisher.subscribe(new ChangeSystemCOnversionSubsriber(observer));
	}

	/**
	 * TODO JNO - document
	 * @param <C>
	 * @param <G>
	 */
	private static abstract class ConversionSubscriber<C extends ChangeCapture, G> implements Subscriber<C> {

		private final Subscriber<G> delegate;

		public ConversionSubscriber(@Nonnull CallStreamObserver<G> downstream) {
			final RxSubscriber<G> bridge = new RxSubscriber<>(__ -> __);
			bridge.subscribe(downstream);
			this.delegate = FlowAdapters.toFlowSubscriber(bridge);
		}

		@Override
		public void onSubscribe(Subscription subscription) {
			this.delegate.onSubscribe(subscription);
		}

		@Override
		public void onNext(C item) {
			this.delegate.onNext(convertCapture(item));
		}

		@Override
		public void onError(Throwable throwable) {
			this.delegate.onError(throwable);
		}

		@Override
		public void onComplete() {
			this.delegate.onComplete();
		}

		/**
		 * Converts internal incoming capture into gRPC equivalent to be send to client.
		 * @param capture internal incoming capture
		 * @return converted outgoing gRPC capture
		 */
		@Nonnull
		protected abstract G convertCapture(@Nonnull C capture);
	}

	/* todo jno - rename and document */
	private static class ChangeSystemCOnversionSubsriber extends ConversionSubscriber<ChangeSystemCapture, GrpcRegisterSystemChangeCaptureResponse> {

		public ChangeSystemCOnversionSubsriber(@Nonnull CallStreamObserver<GrpcRegisterSystemChangeCaptureResponse> downstream) {
			super(downstream);
		}

		@Nonnull
		@Override
		protected GrpcRegisterSystemChangeCaptureResponse convertCapture(@Nonnull ChangeSystemCapture capture) {
			return GrpcRegisterSystemChangeCaptureResponse.newBuilder()
				.setCapture(ChangeCaptureConverter.toGrpcChangeSystemCapture(capture))
				.build();
		}
	}

}
