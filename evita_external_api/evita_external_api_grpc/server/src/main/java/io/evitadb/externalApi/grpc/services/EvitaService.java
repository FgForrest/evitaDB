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
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureSubscription;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.MakeCatalogAliveMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.executor.ObservableExecutorServiceWithHardDeadline;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.event.ReadinessEvent;
import io.evitadb.externalApi.event.ReadinessEvent.Prospective;
import io.evitadb.externalApi.event.ReadinessEvent.Result;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.constants.GrpcHeaders;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcGetCatalogStateResponse.Builder;
import io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingEngineMutationConverter;
import io.evitadb.externalApi.grpc.services.interceptors.GlobalExceptionHandlerInterceptor;
import io.evitadb.externalApi.grpc.services.interceptors.ServerSessionInterceptor;
import io.evitadb.externalApi.trace.ExternalApiTracingContextProvider;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.evitadb.utils.UUIDUtil;
import io.grpc.Metadata;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.function.IntConsumer;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toCaptureContent;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcCatalogState;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcCommitBehavior;
import static java.util.Optional.ofNullable;

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
				final String catalogName = request.getCatalogName();
				this.evita.getCatalogInstance(catalogName)
					.ifPresentOrElse(
						catalog ->
							this.evita.deleteCatalogIfExistsWithProgress(catalogName)
					                     .ifPresentOrElse(
							voidProgress -> voidProgress.onCompletion()
						                            .whenComplete(
									(__, throwable) -> {
										if (throwable == null) {
											responseObserver.onNext(GrpcDeleteCatalogIfExistsResponse.newBuilder().setSuccess(true).build());
										} else {
											responseObserver.onError(throwable);
										}
										responseObserver.onCompleted();
									}
								),
								() -> {
									responseObserver.onNext(GrpcDeleteCatalogIfExistsResponse.newBuilder().setSuccess(false).build());
									responseObserver.onCompleted();
								}
							),
						() -> {
							responseObserver.onNext(GrpcDeleteCatalogIfExistsResponse.newBuilder().setSuccess(false).build());
							responseObserver.onCompleted();
						}
					);
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
	public void applyMutation(
		GrpcApplyMutationRequest request,
		StreamObserver<GrpcApplyMutationResponse> responseObserver
	) {
		executeWithClientContext(
			() -> {
				final EngineMutation<?> engineMutation = DelegatingEngineMutationConverter.INSTANCE.convert(request.getMutation());
				if (engineMutation == null) {
					responseObserver.onError(new EvitaInvalidUsageException("Mutation is not set in the request!"));
					responseObserver.onCompleted();
				} else {
					this.evita.applyMutation(engineMutation)
					          .onCompletion()
					          .whenComplete(
								  (__, throwable) -> {
									  if (throwable == null) {
										  responseObserver.onNext(GrpcApplyMutationResponse.newBuilder().build());
									  } else {
										  responseObserver.onError(throwable);
									  }
									  responseObserver.onCompleted();
								  }
					          );
				}
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Applies catalog mutation affecting entire catalog and send progress updates to the client.
	 */
	@Override
	public void applyMutationWithProgress(
		GrpcApplyMutationRequest request,
		StreamObserver<GrpcApplyMutationWithProgressResponse> responseObserver
	) {
		final ApplyMutationProgressConsumer progressObserver = new ApplyMutationProgressConsumer(responseObserver);
		final CompletableFuture<Progress<?>> applyMutationProgressRef = new CompletableFuture<>();
		setOnCancelListener((ServerCallStreamObserver<?>) responseObserver, applyMutationProgressRef, progressObserver);

		executeWithClientContext(
			() -> {
				final EngineMutation<?> engineMutation = DelegatingEngineMutationConverter.INSTANCE.convert(request.getMutation());
				if (engineMutation == null) {
					final EvitaInvalidUsageException ex = new EvitaInvalidUsageException("Mutation is not set in the request!");
					applyMutationProgressRef.completeExceptionally(ex);
					responseObserver.onError(ex);
					responseObserver.onCompleted();
				} else {
					try {
						final Progress<?> applyMutationProgress = this.evita.applyMutation(
							engineMutation,
							progressObserver
						);
						applyMutationProgressRef.complete(applyMutationProgress);
						waitForFinish(responseObserver, applyMutationProgress);
					} catch (RuntimeException e) {
						applyMutationProgressRef.completeExceptionally(e);
						throw e;
					}
				}
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
			() -> this.evita.renameCatalogWithProgress(request.getCatalogName(), request.getNewCatalogName())
		                .onCompletion()
		                .whenComplete(
					(commitVersions, throwable) -> {
						if (throwable == null) {
							responseObserver.onNext(GrpcRenameCatalogResponse.newBuilder().setSuccess(true).build());
						} else {
							responseObserver.onError(throwable);
						}
						responseObserver.onCompleted();
					}
				),
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Renames existing catalog to a name specified in a request with progress tracking.
	 *
	 * @param request          containing names of the catalogs involved
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#renameCatalogWithProgress(String, String)
	 */
	@Override
	public void renameCatalogWithProgress(
		GrpcRenameCatalogRequest request,
		StreamObserver<GrpcApplyMutationWithProgressResponse> responseObserver
	) {
		final ApplyMutationProgressConsumer progressObserver = new ApplyMutationProgressConsumer(responseObserver);
		final CompletableFuture<Progress<?>> applyMutationProgressRef = new CompletableFuture<>();
		setOnCancelListener((ServerCallStreamObserver<?>) responseObserver, applyMutationProgressRef, progressObserver);
		executeWithClientContext(
			() -> {
				try {
					final Progress<CommitVersions> renameCatalogProgress = this.evita.renameCatalogWithProgress(
						request.getCatalogName(),
						request.getNewCatalogName()
					);
					applyMutationProgressRef.complete(renameCatalogProgress);
					waitForFinish(responseObserver, renameCatalogProgress);
				} catch (RuntimeException e) {
					applyMutationProgressRef.completeExceptionally(e);
					throw e;
				}
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
			() -> this.evita.replaceCatalogWithProgress(request.getCatalogNameToBeReplacedWith(), request.getCatalogNameToBeReplaced())
		                .onCompletion()
		                .whenComplete(
					(commitVersions, throwable) -> {
						if (throwable == null) {
							responseObserver.onNext(GrpcReplaceCatalogResponse.newBuilder().setSuccess(true).build());
						} else {
							responseObserver.onError(throwable);
						}
						responseObserver.onCompleted();
					}
				),
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Replaces existing catalog with a different existing catalog and its contents with progress tracking.
	 *
	 * @param request          containing names of the catalogs involved
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#replaceCatalogWithProgress(String, String)
	 */
	@Override
	public void replaceCatalogWithProgress(
		GrpcReplaceCatalogRequest request,
		StreamObserver<GrpcApplyMutationWithProgressResponse> responseObserver
	) {
		final ApplyMutationProgressConsumer progressObserver = new ApplyMutationProgressConsumer(responseObserver);
		final CompletableFuture<Progress<?>> applyMutationProgressRef = new CompletableFuture<>();
		setOnCancelListener((ServerCallStreamObserver<?>) responseObserver, applyMutationProgressRef, progressObserver);
		executeWithClientContext(
			() -> {
				try {
					final Progress<CommitVersions> replaceCatalogProgress = this.evita.replaceCatalogWithProgress(
						request.getCatalogNameToBeReplacedWith(),
						request.getCatalogNameToBeReplaced()
					);
					applyMutationProgressRef.complete(replaceCatalogProgress);
					waitForFinish(responseObserver, replaceCatalogProgress);
				} catch (RuntimeException e) {
					applyMutationProgressRef.completeExceptionally(e);
					throw e;
				}
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
	 * Registers a system change capture by subscribing to a publisher that emits change events.
	 *
	 * @param request          The gRPC request containing information about the system change capture,
	 *                         including optional version and index parameters and content specification.
	 * @param responseObserver The stream observer used to send responses back to the client, which
	 *                         includes the serialized captured change events.
	 */
	@Override
	public void registerSystemChangeCapture(
		GrpcRegisterSystemChangeCaptureRequest request,
		StreamObserver<GrpcRegisterSystemChangeCaptureResponse> responseObserver
	) {
		final CompletableFuture<Subscription> subscriptionFuture = new CompletableFuture<>();
		((ServerCallStreamObserver<GrpcRegisterSystemChangeCaptureResponse>)responseObserver).setOnCancelHandler(
			() -> {
				try {
					subscriptionFuture.get().cancel();
				} catch (Exception e) {
					log.debug("Failed to remove progress listener on cancel", e);
				}
			}
		);

		executeWithClientContext(
			() -> {
				try {
					this.evita.registerSystemChangeCapture(
						new ChangeSystemCaptureRequest(
							request.hasSinceVersion() ? request.getSinceVersion().getValue() : null,
							request.hasSinceIndex() ? request.getSinceIndex().getValue() : null,
							toCaptureContent(request.getContent())
						)
					).subscribe(
						new ChangeSystemCaptureSubscriber(responseObserver, subscriptionFuture)
					);
				} catch (RuntimeException e) {
					subscriptionFuture.completeExceptionally(e);
					throw e;
				}
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Makes a catalog alive.
	 *
	 * @param request          containing name of the catalog to be made alive
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#makeCatalogAlive(String)
	 */
	@Override
	public void makeCatalogAlive(
		GrpcMakeCatalogAliveRequest request,
		StreamObserver<GrpcMakeCatalogAliveResponse> responseObserver
	) {
		executeWithClientContext(
			() -> {
				this.evita.makeCatalogAlive(request.getCatalogName());
				responseObserver.onNext(GrpcMakeCatalogAliveResponse.newBuilder().setSuccess(true).build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Makes a catalog alive with progress tracking.
	 *
	 * @param request          containing name of the catalog to be made alive
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#makeCatalogAliveWithProgress(String)
	 */
	@Override
	public void makeCatalogAliveWithProgress(
		GrpcMakeCatalogAliveRequest request,
		StreamObserver<GrpcApplyMutationWithProgressResponse> responseObserver
	) {
		final ApplyMutationProgressConsumer progressObserver = new ApplyMutationProgressConsumer(responseObserver);
		final CompletableFuture<Progress<?>> applyMutationProgressRef = new CompletableFuture<>();
		setOnCancelListener((ServerCallStreamObserver<?>) responseObserver, applyMutationProgressRef, progressObserver);
		executeWithClientContext(
			() -> {
				try {
					final Progress<?> applyMutationProgress = this.evita.applyMutation(
						new MakeCatalogAliveMutation(request.getCatalogName()),
						progressObserver
					);
					applyMutationProgressRef.complete(applyMutationProgress);
					waitForFinish(responseObserver, applyMutationProgress);
				} catch (RuntimeException e) {
					applyMutationProgressRef.completeExceptionally(e);
					throw e;
				}
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Duplicates a catalog.
	 *
	 * @param request          containing name of the source catalog and new catalog name
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#duplicateCatalog(String, String)
	 */
	@Override
	public void duplicateCatalog(
		GrpcDuplicateCatalogRequest request,
		StreamObserver<GrpcDuplicateCatalogResponse> responseObserver
	) {
		executeWithClientContext(
			() -> {
				this.evita.duplicateCatalog(request.getCatalogName(), request.getNewCatalogName());
				responseObserver.onNext(GrpcDuplicateCatalogResponse.newBuilder().setSuccess(true).build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Duplicates a catalog with progress tracking.
	 *
	 * @param request          containing name of the source catalog and new catalog name
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#duplicateCatalogWithProgress(String, String)
	 */
	@Override
	public void duplicateCatalogWithProgress(
		GrpcDuplicateCatalogRequest request,
		StreamObserver<GrpcApplyMutationWithProgressResponse> responseObserver
	) {
		final ApplyMutationProgressConsumer progressObserver = new ApplyMutationProgressConsumer(responseObserver);
		final CompletableFuture<Progress<?>> applyMutationProgressRef = new CompletableFuture<>();
		setOnCancelListener((ServerCallStreamObserver<?>) responseObserver, applyMutationProgressRef, progressObserver);

		executeWithClientContext(
			() -> {
				try {
					final Progress<?> applyMutationProgress = this.evita.applyMutation(
						new DuplicateCatalogMutation(request.getCatalogName(), request.getNewCatalogName()),
						progressObserver
					);
					applyMutationProgressRef.complete(applyMutationProgress);
					waitForFinish(responseObserver, applyMutationProgress);
				} catch (RuntimeException e) {
					applyMutationProgressRef.completeExceptionally(e);
					throw e;
				}
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Makes a catalog mutable.
	 *
	 * @param request          containing name of the catalog to be made mutable
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#makeCatalogMutable(String)
	 */
	@Override
	public void makeCatalogMutable(GrpcMakeCatalogMutableRequest request, StreamObserver<GrpcMakeCatalogMutableResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				this.evita.makeCatalogMutable(request.getCatalogName());
				responseObserver.onNext(GrpcMakeCatalogMutableResponse.newBuilder().setSuccess(true).build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Makes a catalog mutable with progress tracking.
	 *
	 * @param request          containing name of the catalog to be made mutable
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#makeCatalogMutableWithProgress(String)
	 */
	@Override
	public void makeCatalogMutableWithProgress(GrpcMakeCatalogMutableRequest request, StreamObserver<GrpcApplyMutationWithProgressResponse> responseObserver) {
		final ApplyMutationProgressConsumer progressObserver = new ApplyMutationProgressConsumer(responseObserver);
		final CompletableFuture<Progress<?>> applyMutationProgressRef = new CompletableFuture<>();
		setOnCancelListener((ServerCallStreamObserver<?>) responseObserver, applyMutationProgressRef, progressObserver);
		executeWithClientContext(
			() -> {
				try {
					final Progress<?> applyMutationProgress = this.evita.applyMutation(
						new SetCatalogMutabilityMutation(request.getCatalogName(), true),
						progressObserver
					);
					applyMutationProgressRef.complete(applyMutationProgress);
					waitForFinish(responseObserver, applyMutationProgress);
				} catch (RuntimeException e) {
					applyMutationProgressRef.completeExceptionally(e);
					throw e;
				}
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Makes a catalog immutable.
	 *
	 * @param request          containing name of the catalog to be made immutable
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#makeCatalogImmutable(String)
	 */
	@Override
	public void makeCatalogImmutable(GrpcMakeCatalogImmutableRequest request, StreamObserver<GrpcMakeCatalogImmutableResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				this.evita.makeCatalogImmutable(request.getCatalogName());
				responseObserver.onNext(GrpcMakeCatalogImmutableResponse.newBuilder().setSuccess(true).build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Makes a catalog immutable with progress tracking.
	 *
	 * @param request          containing name of the catalog to be made immutable
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#makeCatalogImmutableWithProgress(String)
	 */
	@Override
	public void makeCatalogImmutableWithProgress(GrpcMakeCatalogImmutableRequest request, StreamObserver<GrpcApplyMutationWithProgressResponse> responseObserver) {
		final ApplyMutationProgressConsumer progressObserver = new ApplyMutationProgressConsumer(responseObserver);
		final CompletableFuture<Progress<?>> applyMutationProgressRef = new CompletableFuture<>();
		setOnCancelListener((ServerCallStreamObserver<?>) responseObserver, applyMutationProgressRef, progressObserver);
		executeWithClientContext(
			() -> {
				try {
					final Progress<?> applyMutationProgress = this.evita.applyMutation(
						new SetCatalogMutabilityMutation(request.getCatalogName(), false),
						progressObserver
					);
					applyMutationProgressRef.complete(applyMutationProgress);
					waitForFinish(responseObserver, applyMutationProgress);
				} catch (RuntimeException e) {
					applyMutationProgressRef.completeExceptionally(e);
					throw e;
				}
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Activates a catalog.
	 *
	 * @param request          containing name of the catalog to be activated
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#activateCatalog(String)
	 */
	@Override
	public void activateCatalog(GrpcActivateCatalogRequest request, StreamObserver<GrpcActivateCatalogResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				this.evita.activateCatalog(request.getCatalogName());
				responseObserver.onNext(GrpcActivateCatalogResponse.newBuilder().setSuccess(true).build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Activates a catalog with progress tracking.
	 *
	 * @param request          containing name of the catalog to be activated
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#activateCatalogWithProgress(String)
	 */
	@Override
	public void activateCatalogWithProgress(GrpcActivateCatalogRequest request, StreamObserver<GrpcApplyMutationWithProgressResponse> responseObserver) {
		final ApplyMutationProgressConsumer progressObserver = new ApplyMutationProgressConsumer(responseObserver);
		final CompletableFuture<Progress<?>> applyMutationProgressRef = new CompletableFuture<>();
		setOnCancelListener((ServerCallStreamObserver<?>) responseObserver, applyMutationProgressRef, progressObserver);

		executeWithClientContext(
			() -> {
				try {
					final Progress<?> applyMutationProgress = this.evita.applyMutation(
						new SetCatalogStateMutation(request.getCatalogName(), true),
						progressObserver
					);
					applyMutationProgressRef.complete(applyMutationProgress);
					waitForFinish(responseObserver, applyMutationProgress);
				} catch (RuntimeException e) {
					applyMutationProgressRef.completeExceptionally(e);
					throw e;
				}
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Deactivates a catalog.
	 *
	 * @param request          containing name of the catalog to be deactivated
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#deactivateCatalog(String)
	 */
	@Override
	public void deactivateCatalog(GrpcDeactivateCatalogRequest request, StreamObserver<GrpcDeactivateCatalogResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				this.evita.deactivateCatalog(request.getCatalogName());
				responseObserver.onNext(GrpcDeactivateCatalogResponse.newBuilder().setSuccess(true).build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Deactivates a catalog with progress tracking.
	 *
	 * @param request          containing name of the catalog to be deactivated
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#deactivateCatalogWithProgress(String)
	 */
	@Override
	public void deactivateCatalogWithProgress(GrpcDeactivateCatalogRequest request, StreamObserver<GrpcApplyMutationWithProgressResponse> responseObserver) {
		final ApplyMutationProgressConsumer progressObserver = new ApplyMutationProgressConsumer(responseObserver);
		final CompletableFuture<Progress<?>> applyMutationProgressRef = new CompletableFuture<>();
		setOnCancelListener((ServerCallStreamObserver<?>) responseObserver, applyMutationProgressRef, progressObserver);

		executeWithClientContext(
			() -> {
				try {
					final Progress<?> applyMutationProgress = this.evita.applyMutation(
						new SetCatalogStateMutation(request.getCatalogName(), false),
						progressObserver
					);
					applyMutationProgressRef.complete(applyMutationProgress);
					waitForFinish(responseObserver, applyMutationProgress);
				} catch (RuntimeException e) {
					applyMutationProgressRef.completeExceptionally(e);
					throw e;
				}
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Returns the progress of the execution of a long-running engine mutation operation.
	 */
	@Override
	public void getProgress(GrpcGetProgressRequest request, StreamObserver<GrpcGetProgressResponse> responseObserver) {
		final String catalogName = request.getCatalogName();
		final GetProgressConsumer progressObserver = new GetProgressConsumer(catalogName, responseObserver);
		final CompletableFuture<Progress<?>> applyMutationProgressRef = new CompletableFuture<>();
		setOnCancelListener((ServerCallStreamObserver<?>) responseObserver, applyMutationProgressRef, progressObserver);

		executeWithClientContext(
			() -> {
				final Optional<Progress<?>> progress = ofNullable(catalogName)
					.flatMap(this.evita::getEngineMutationProgress);

				if (progress.isEmpty()) {
					applyMutationProgressRef.completeExceptionally(new RuntimeException("No progress found for catalog: " + catalogName));
					responseObserver.onNext(
						GrpcGetProgressResponse
							.newBuilder()
							.setFound(false)
							.build()
					);
					responseObserver.onCompleted();
				} else {
					final Progress<?> theProgress = progress.get();
					theProgress.addProgressListener(progressObserver);
					applyMutationProgressRef.complete(theProgress);

					theProgress
						.onCompletion()
						.whenComplete(
							(result, throwable) -> {
								if (throwable == null) {
									final GrpcGetProgressResponse.Builder builder = GrpcGetProgressResponse
										.newBuilder()
										.setFound(true)
										.setCatalogName(catalogName)
										.setProgressInPercent(Int32Value.of(100));
									if (result instanceof CommitVersions commitVersions) {
										builder
											.setCatalogVersion(Int64Value.of(commitVersions.catalogVersion()))
											.setCatalogSchemaVersion(Int32Value.of(commitVersions.catalogSchemaVersion()));
									}
									responseObserver.onNext(builder.build());
								} else {
									responseObserver.onError(throwable);
								}
								responseObserver.onCompleted();
							})
						.toCompletableFuture()
						.join();
				}
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Sets an onCancel listener for the provided ServerCallStreamObserver that
	 * removes the progress listener on cancellation.
	 *
	 * @param responseObserver the ServerCallStreamObserver to attach the cancel handler to. Must not be null.
	 * @param applyMutationProgressRef a CompletableFuture containing the Progress object from which the progress listener will be removed. Must not be null.
	 * @param progressObserver an IntConsumer representing the progress listener to be removed upon cancellation. Must not be null.
	 */
	private static void setOnCancelListener(
		@Nonnull ServerCallStreamObserver<?> responseObserver,
		@Nonnull CompletableFuture<Progress<?>> applyMutationProgressRef,
		@Nonnull IntConsumer progressObserver
	) {
		responseObserver.setOnCancelHandler(
			() -> {
				try {
					applyMutationProgressRef.get().removeProgressListener(progressObserver);
				} catch (Exception e) {
					log.debug("Failed to remove progress listener on cancel", e);
				}
			}
		);
	}

	/**
	 * Waits for the completion of the given mutation progress and sends the appropriate response
	 * to the provided gRPC response observer. If the mutation completes successfully, it sends
	 * a success response with progress and, if applicable, version details. If an error occurs,
	 * it sends the error to the response observer.
	 *
	 * @param responseObserver the gRPC response observer used to send the progress or error responses
	 * @param applyMutationProgress the progress tracker for the mutation operation to monitor
	 */
	private static void waitForFinish(
		@Nonnull StreamObserver<GrpcApplyMutationWithProgressResponse> responseObserver,
		@Nonnull Progress<?> applyMutationProgress
	) {
		applyMutationProgress
			.onCompletion()
			.whenComplete(
				(result, throwable) -> {
					if (throwable == null) {
						final GrpcApplyMutationWithProgressResponse.Builder responseBuilder = GrpcApplyMutationWithProgressResponse
							.newBuilder()
							.setProgressInPercent(100);
						if (result instanceof CommitVersions commitVersions) {
							responseBuilder
								.setCatalogVersion(Int64Value.of(commitVersions.catalogVersion()))
								.setCatalogSchemaVersion(Int32Value.of(commitVersions.catalogSchemaVersion()));
						}
						responseObserver.onNext(responseBuilder.build());
					} else {
						responseObserver.onError(throwable);
					}
					responseObserver.onCompleted();
				})
			.toCompletableFuture()
			.join();
	}

	/**
	 * A private static class implementing the {@link Subscriber} interface to handle
	 * system change capture subscriptions. This class coordinates the receipt of change events,
	 * processes them, and forwards the results to a response observer.
	 *
	 * It is specifically designed for managing a subscription lifecycle and handling events
	 * of type {@link ChangeSystemCapture} within the context of gRPC communication.
	 */
	@RequiredArgsConstructor
	private static class ChangeSystemCaptureSubscriber implements Subscriber<ChangeSystemCapture> {
		private final StreamObserver<GrpcRegisterSystemChangeCaptureResponse> responseObserver;
		private final CompletableFuture<Subscription> subscriptionFuture;
		private Subscription subscription;

		@Override
		public void onSubscribe(Subscription subscription) {
			this.subscription = subscription;
			this.subscriptionFuture.complete(subscription);

			final GrpcRegisterSystemChangeCaptureResponse.Builder response = GrpcRegisterSystemChangeCaptureResponse
				.newBuilder();
			if (subscription instanceof ChangeCaptureSubscription ccs) {
				response.setUuid(EvitaDataTypesConverter.toGrpcUuid(ccs.getSubscriptionId()));
			}
			this.responseObserver.onNext(
				response
					.setResponseType(GrpcCaptureResponseType.ACKNOWLEDGEMENT)
					.build()
			);
			subscription.request(1);
		}

		@Override
		public void onNext(ChangeSystemCapture item) {
			this.responseObserver.onNext(
				GrpcRegisterSystemChangeCaptureResponse
					.newBuilder()
					.setCapture(ChangeCaptureConverter.toGrpcChangeSystemCapture(item))
					.setResponseType(GrpcCaptureResponseType.CHANGE)
					.build()
			);
			this.subscription.request(1);
		}

		@Override
		public void onError(Throwable throwable) {
			this.subscriptionFuture.completeExceptionally(throwable);
			this.responseObserver.onError(throwable);
		}

		@Override
		public void onComplete() {
			this.responseObserver.onCompleted();
		}
	}

	/**
	 * A consumer to track and report the progress of applying mutations.
	 * This class implements the {@link IntConsumer} interface and is used to send progress updates
	 * to a {@link StreamObserver} in a controlled manner.
	 *
	 * Progress is measured as a percentage, and updates are throttled to occur only if:
	 * - The new progress (percentage) is greater than the last reported progress.
	 * - At least 1 second has passed since the last update.
	 */
	private static class ApplyMutationProgressConsumer implements IntConsumer {
		private final StreamObserver<GrpcApplyMutationWithProgressResponse> responseObserver;
		private int percentDone = -1;
		private long lastUpdate;

		public ApplyMutationProgressConsumer(StreamObserver<GrpcApplyMutationWithProgressResponse> responseObserver) {
			this.responseObserver = responseObserver;
		}

		@Override
		public void accept(int percentDoneCurrently) {
			if (percentDoneCurrently > this.percentDone && this.lastUpdate + 1000 < System.currentTimeMillis()) {
				this.percentDone = percentDoneCurrently;
				final GrpcApplyMutationWithProgressResponse response =
					GrpcApplyMutationWithProgressResponse.newBuilder()
					                                     .setProgressInPercent(this.percentDone)
					                                     .build();
				this.responseObserver.onNext(response);
				this.lastUpdate = System.currentTimeMillis();
			}
		}
	}

	/**
	 * A consumer implementation that processes progress updates and communicates
	 * them back to a client through a provided stream observer.
	 *
	 * <ul>
	 *   <li>This class tracks the progress percentage and sends updates to the client
	 *       only when the progress increases and at least one second has elapsed
	 *       since the last update.</li>
	 *   <li>Each progress update is encapsulated in a {@link GrpcGetProgressResponse}
	 *       object and sent to the client via the {@link StreamObserver}.</li>
	 * </ul>
	 */
	private static class GetProgressConsumer implements IntConsumer {
		private final String catalogName;
		private final StreamObserver<GrpcGetProgressResponse> responseObserver;
		private int percentDone;
		private long lastUpdate;

		public GetProgressConsumer(@Nonnull String catalogName, @Nonnull StreamObserver<GrpcGetProgressResponse> responseObserver) {
			this.catalogName = catalogName;
			this.responseObserver = responseObserver;
			this.percentDone = 0;
			this.lastUpdate = System.currentTimeMillis();
		}

		@Override
		public void accept(int percentDoneCurrently) {
			if (percentDoneCurrently > this.percentDone && this.lastUpdate + 1000 < System.currentTimeMillis()) {
				this.percentDone = percentDoneCurrently;
				final GrpcGetProgressResponse response =
					GrpcGetProgressResponse
						.newBuilder()
						.setFound(true)
						.setCatalogName(this.catalogName)
						.setProgressInPercent(Int32Value.of(this.percentDone))
						.build();
				this.responseObserver.onNext(response);
				this.lastUpdate = System.currentTimeMillis();
			}
		}
	}
}
