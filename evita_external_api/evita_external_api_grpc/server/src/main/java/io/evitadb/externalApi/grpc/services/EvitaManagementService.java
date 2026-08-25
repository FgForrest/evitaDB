/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.StringValue;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.CatalogStatistics;
import io.evitadb.api.EvitaManagementContract;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.exception.ReadOnlyException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.observability.ReadinessState;
import io.evitadb.api.requestResponse.system.EngineSettings;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.core.Evita;
import io.evitadb.core.management.EvitaManagement;
import io.evitadb.core.management.FileManagementService;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.externalApi.api.system.ProbesProvider.ApiState;
import io.evitadb.externalApi.api.system.ProbesProvider.Readiness;
import io.evitadb.externalApi.configuration.AbstractApiOptions;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse.Builder;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.utils.GrpcOutboundGate;
import io.evitadb.externalApi.grpc.utils.GrpcTimeoutUtil;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.trace.ExternalApiTracingContextProvider;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceServiceFactory.FileIdCarrier;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import io.evitadb.utils.ClassifierUtils.Keyword;
import io.evitadb.utils.UUIDUtil;
import io.grpc.Metadata;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcOffsetDateTime;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcTaskStatus;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcUuid;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toUuid;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcHealthProblem;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcReadinessState;
import static io.evitadb.externalApi.grpc.requestResponse.schema.ConflictResolutionConverter.toGrpcConflictResolution;
import static io.evitadb.externalApi.grpc.services.EvitaService.executeWithClientContext;
import static io.evitadb.externalApi.grpc.services.interceptors.GlobalExceptionHandlerInterceptor.sendErrorToClient;
import static java.util.Optional.ofNullable;

/**
 * This service contains methods that could be called by gRPC clients on {@link EvitaManagementContract}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class EvitaManagementService extends EvitaManagementServiceGrpc.EvitaManagementServiceImplBase {
	/**
	 * Size of a single chunk streamed by {@link #fetchFile(GrpcFetchFileRequest, StreamObserver)}.
	 *
	 * The value is a direct consequence of the readiness gating that loop performs. Armeria reports a
	 * server call as ready only while `pendingMessages == 0`, so a gated producer keeps exactly one
	 * message in flight and every chunk costs a full event-loop round trip - at the original 64 KB that
	 * is over ten thousand sequential round trips for a large backup, and it would make downloads for
	 * clients that keep up measurably slower than the unbounded loop it replaces. 1 MB restores the
	 * throughput while keeping the worst-case in-flight footprint at a couple of megabytes instead of
	 * the whole file.
	 *
	 * The binding ceiling is the receiving side's maximum inbound message size: gRPC-Java defaults to
	 * 4 MB and Armeria's gRPC client to no limit at all, so 1 MB is safe for every client - and chunk
	 * size is not part of the wire contract anyway, since `totalSizeInBytes` carries the whole file
	 * size on every message.
	 */
	private static final int FETCH_FILE_CHUNK_SIZE = 1_048_576;

	/**
	 * Instance of Evita upon which will be executed service calls
	 */
	@Nonnull private final Evita evita;
	/**
	 * Tracing context to be used for gRPC calls.
	 */
	@Nonnull private final ExternalApiTracingContext<Metadata> context;
	/**
	 * Instance of {@link ExternalApiServer} that is used to handle HTTP requests - for the sake of checking the status.
	 */
	@Nonnull private final ExternalApiServer externalApiServer;
	/**
	 * Direct reference to {@link EvitaManagement} instance.
	 */
	@Nonnull private final EvitaManagement management;

	/**
	 * Deletes temporary file if it exists.
	 *
	 * @param backupFilePath path to the file to be deleted
	 */
	private static void deleteFileIfExists(@Nullable Path backupFilePath, @Nonnull String purpose) {
		if (backupFilePath != null) {
			try {
				Files.deleteIfExists(backupFilePath);
			} catch (IOException e) {
				log.error("Failed to delete temporary " + purpose + " file: {}", backupFilePath, e);
			}
		}
	}

	/**
	 * Creates a predicate to find a restore task based on a specific file ID.
	 *
	 * @param theFileId the UUID of the file to match against the file ID carrier within the server task.
	 * @return a predicate that evaluates true if the server task's file ID matches the provided file ID.
	 */
	@Nonnull
	private static Predicate<ServerTask<?, ?>> createRestoreTaskFindPredicate(@Nonnull UUID theFileId) {
		return serverTask -> serverTask.getStatus().settings() instanceof FileIdCarrier fileIdCarrier &&
			fileIdCarrier.fileId().equals(theFileId);
	}

	/**
	 * How long a streamed response may make no progress before it is abandoned -
	 * `api.endpoints.gRPC.streamingRequestTimeoutInMillis`.
	 * Handed to every {@link GrpcOutboundGate} this service attaches.
	 */
	private final long streamingRequestTimeoutInMillis;

	public EvitaManagementService(
		@Nonnull Evita evita,
		@Nonnull ExternalApiServer externalApiServer,
		HeaderOptions headers,
		long streamingRequestTimeoutInMillis
	) {
		this.streamingRequestTimeoutInMillis = streamingRequestTimeoutInMillis;
		this.evita = evita;
		this.externalApiServer = externalApiServer;
		this.management = evita.management();
		this.context = ExternalApiTracingContextProvider.getContext(Metadata.class, headers);
	}

	/**
	 * Retrieves the server status.
	 *
	 * @param request          the request for server status
	 * @param responseObserver the observer for receiving the server status response
	 */
	@Override
	public void serverStatus(Empty request, StreamObserver<GrpcEvitaServerStatusResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				final SystemStatus systemStatus = this.management.getSystemStatus();

				final String[] enabledApiEndpoints = this.externalApiServer.getApiOptions().getEnabledApiEndpoints();
				final Optional<Readiness> readiness = this.externalApiServer.getProbeProviders().stream()
					.findFirst()
					.map(it -> it.getReadiness(this.evita, this.externalApiServer, enabledApiEndpoints));

				final GrpcEvitaServerStatusResponse.Builder responseBuilder = GrpcEvitaServerStatusResponse
					.newBuilder()
					.setVersion(systemStatus.version())
					.setStartedAt(toGrpcOffsetDateTime(systemStatus.startedAt()))
					.setEngineVersion(systemStatus.engineVersion())
					.setIntroducedAt(toGrpcOffsetDateTime(systemStatus.introducedAt()))
					.setUptime(systemStatus.uptime().toSeconds())
					.setInstanceId(systemStatus.instanceId())
					.setCatalogsCorrupted(systemStatus.catalogsCorrupted())
					.setCatalogsOk(systemStatus.catalogsActive())
					.setCatalogsActive(systemStatus.catalogsActive())
					.setCatalogsInactive(systemStatus.catalogsInactive())
					.setReadiness(toGrpcReadinessState(readiness.map(Readiness::state).orElse(ReadinessState.UNKNOWN)))
					.setReadOnly(this.evita.getConfiguration().server().readOnly());

				this.externalApiServer.getProbeProviders().stream()
					.flatMap(probe -> probe.getHealthProblems(this.evita, this.externalApiServer, enabledApiEndpoints).stream())
					.distinct()
					.forEach(problem -> responseBuilder.addHealthProblems(toGrpcHealthProblem(problem)));

				final Set<String> enabledApiEndpointsSet = Set.of(enabledApiEndpoints);
				ExternalApiServer.gatherExternalApiProviders()
					.forEach(apiRegistrar -> {
						final GrpcApiStatus.Builder apiBuilder = GrpcApiStatus.newBuilder()
							.setEnabled(enabledApiEndpointsSet.contains(apiRegistrar.getExternalApiCode()))
							.setReady(
								readiness.map(it -> Arrays.stream(it.apiStates())
									.filter(apiState -> apiState.apiCode().equals(apiRegistrar.getExternalApiCode()))
									.anyMatch(ApiState::isReady)
								).orElse(false)
							);

						final Optional<ExternalApiProvider<?>> externalApiProviderByCode = ofNullable(this.externalApiServer.getExternalApiProviderByCode(apiRegistrar.getExternalApiCode()));
						externalApiProviderByCode
							.ifPresent(provider -> {
								final AbstractApiOptions configuration = provider.getConfiguration();
								Arrays.stream(configuration.getBaseUrls())
									.forEach(apiBuilder::addBaseUrl);

								provider.getKeyEndPoints()
									.forEach(
										(key, value) -> {
											final GrpcEndpoint.Builder endpointBuilder = GrpcEndpoint.newBuilder()
												.setName(key);
											for (String url : value) {
												endpointBuilder.addUrl(url);
											}
											apiBuilder.addEndpoints(
												endpointBuilder.build()
											);
										}
									);
							});

						responseBuilder.putApi(
							apiRegistrar.getExternalApiCode(),
							apiBuilder.build()
						);
					});

				responseObserver.onNext(responseBuilder.build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Retrieves server configuration.
	 *
	 * @param request          the request for configuration
	 * @param responseObserver the observer for receiving the configuration response
	 */
	@Override
	public void getConfiguration(Empty request, StreamObserver<GrpcEvitaConfigurationResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				/* TOBEDONE JNO #25 - handle differently */
				if (this.evita.getConfiguration().server().readOnly()) {
					responseObserver.onError(
						ReadOnlyException.engineReadOnly()
					);
				} else {
					responseObserver.onNext(
						GrpcEvitaConfigurationResponse.newBuilder()
							.setConfiguration(this.management.getConfiguration())
							.build()
					);
					responseObserver.onCompleted();
				}
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Retrieves the curated subset of the engine configuration that is safe to expose to any client.
	 *
	 * Contrary to {@link #getConfiguration(Empty, StreamObserver)} this method is intentionally
	 * **not** gated on the read-only mode of the engine - it exposes no sensitive values, and
	 * clients need it to interpret the server's behaviour (most notably its conflict resolution)
	 * regardless of the mode the engine was booted in.
	 *
	 * @param request          the request for engine settings
	 * @param responseObserver the observer for receiving the engine settings response
	 */
	@Override
	public void getEngineSettings(Empty request, StreamObserver<GrpcEvitaEngineSettingsResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				final EngineSettings engineSettings = this.management.getEngineSettings();
				responseObserver.onNext(
					GrpcEvitaEngineSettingsResponse.newBuilder()
						.setConflictResolution(
							toGrpcConflictResolution(engineSettings.conflictResolution())
						)
						.setTimeTravelEnabled(engineSettings.timeTravelEnabled())
						.setChangeDataCaptureEnabled(engineSettings.changeDataCaptureEnabled())
						.setTrafficRecordingEnabled(engineSettings.trafficRecordingEnabled())
						.setQueryCacheEnabled(engineSettings.queryCacheEnabled())
						.build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Retrieves catalog statistics from the server.
	 *
	 * @param request          the request for catalog statistics
	 * @param responseObserver the observer for receiving the catalog statistics response
	 */
	@Override
	public void getCatalogStatistics(Empty request, StreamObserver<GrpcEvitaCatalogStatisticsResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				final CatalogStatistics[] catalogStatistics = this.management.getCatalogStatistics();
				responseObserver.onNext(
					GrpcEvitaCatalogStatisticsResponse.newBuilder()
						.addAllCatalogStatistics(
							Arrays.stream(catalogStatistics)
								.map(EvitaDataTypesConverter::toGrpcCatalogStatistics)
								.toList()
						)
						.build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Restores catalog from uploaded backup binary file into a new catalog.
	 *
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaManagementContract#restoreCatalog(String, long, InputStream)
	 */
	@Override
	public StreamObserver<GrpcRestoreCatalogRequest> restoreCatalog(StreamObserver<GrpcRestoreCatalogResponse> responseObserver) {
		final ServerCallStreamObserver<GrpcRestoreCatalogResponse> serverCallObserver =
			(ServerCallStreamObserver<GrpcRestoreCatalogResponse>) responseObserver;
		// Inbound demand has to become explicit here. gRPC auto-requests the next message only after
		// `onMessage` returns, so the inline blocking write this method used to perform *was* the
		// throttle that kept a client from outrunning the disk - an accidental one, paid for by doing
		// file IO on the Armeria event loop. Moving the write onto a worker removes that side effect,
		// so the demand it used to provide is now issued by hand: one message at a time, the next one
		// requested only once the previous chunk is on disk.
		//
		// Both calls must happen before this method returns - gRPC freezes the observer immediately
		// afterwards and rejects any later attempt to change the flow-control mode.
		serverCallObserver.disableAutoRequest();
		serverCallObserver.request(1);

		return new RestoreCatalogUploadObserver(
			serverCallObserver,
			ServiceRequestContext.current(),
			this.evita.getConfiguration().transaction().transactionWorkDirectory(),
			this.management,
			this.evita.getRequestExecutor(),
			this.streamingRequestTimeoutInMillis
		);
	}

	/**
	 * Restores catalog from uploaded backup binary file into a new catalog.
	 * Unary variant of {@link #restoreCatalog(StreamObserver)}
	 *
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaManagementContract#restoreCatalog(String, long, InputStream)
	 */
	@Override
	public void restoreCatalogUnary(GrpcRestoreCatalogUnaryRequest request, StreamObserver<GrpcRestoreCatalogUnaryResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				UUID fileId = request.hasFileId() ? toUuid(request.getFileId()) : null;
				final long totalSizeInBytes = request.getTotalSizeInBytes();

				try {
					final Path workDirectory = this.evita.getConfiguration().transaction().transactionWorkDirectory();
					final String catalogNameToRestore = request.getCatalogName();
					Assert.isPremiseValid(catalogNameToRestore != null, "Catalog name to restore must be provided.");

					final ServerTask<?, ?> restorationTask;
					final Path backupFilePath;
					if (fileId == null) {
						if (!workDirectory.toFile().exists()) {
							Assert.isTrue(workDirectory.toFile().mkdirs(), "Failed to create work directory for catalog restore.");
						}

						fileId = UUIDUtil.randomUUID();
						backupFilePath = this.management.fileManagementService().createTempFile(fileId + ".zip");
						restorationTask = this.management.createRestorationTask(
							catalogNameToRestore,
							fileId,
							backupFilePath,
							request.getTotalSizeInBytes(),
							true
						);
						// The archive assembled here has no other owner. `createTempFile` deliberately does
						// not reserve the file (that is `createManagedTempFile`), nothing sweeps the work
						// directory, and the restore step - which deletes it once it runs, because
						// `deleteAfterRestore` is set - only runs for an upload that actually completed. An
						// upload that stops half way would therefore leave an archive the size of a catalog
						// behind for the lifetime of the process.
						//
						// A chunked upload has no half-close to hang that cleanup on, so the task's future
						// carries it: it completes on every terminal outcome, including the scheduler's purge
						// of tasks left waiting for a precondition for longer than ten minutes
						// (`Scheduler#purgeFinishedAndLongWaitingTasks`). That purge is what catches the cases
						// no protocol-level signal ever reaches us for - a client that was killed, crashed, or
						// simply lost the network mid-upload.
						final Path uploadedFilePath = backupFilePath;
						restorationTask.getFutureResult().whenComplete(
							(result, exception) -> {
								if (exception != null) {
									deleteFileIfExists(uploadedFilePath, "restore");
								}
							}
						);
						this.management.registerWaitingTask(restorationTask);
					} else {
						backupFilePath = this.management.fileManagementService().getTempFile(fileId + ".zip");
						restorationTask = this.management.getWaitingTask(createRestoreTaskFindPredicate(fileId))
							.orElseThrow(() -> new UnexpectedIOException("Task not found for file: " + backupFilePath, "Task not found for file id!"));
					}

					try (final OutputStream outputStream = Files.newOutputStream(backupFilePath, StandardOpenOption.APPEND)) {
						final ByteString backupFile = request.getBackupFile();
						backupFile.writeTo(outputStream);
					}

					final long actualSize = Files.size(backupFilePath);

					// An over-long upload has to be rejected *before* the client is told the chunk was
					// accepted. Reporting success first and throwing afterwards left the error being written
					// into an already-completed observer - so the client saw the upload succeed - while the
					// archive was deleted underneath a task that stayed registered, making the next chunk fail
					// on `getTempFile`'s existence check instead of on the size that was actually wrong.
					//
					// Cancelling the task is what discards the archive: its completion hook owns that file.
					if (actualSize > totalSizeInBytes) {
						this.management.cancelTask(restorationTask.getStatus().taskId());
						throw new UnexpectedIOException(
							"Backup file size exceeds the expected size.",
							"Backup file size exceeds the expected size (expected " + totalSizeInBytes +
								", actual " + actualSize + " Bytes)."
						);
					}

					// we've reached the expected size of the file
					if (actualSize == totalSizeInBytes) {
						this.management.submitWaitingTask(createRestoreTaskFindPredicate(fileId));
					}

					responseObserver.onNext(
						GrpcRestoreCatalogUnaryResponse.newBuilder()
							.setTask(toGrpcTaskStatus(restorationTask.getStatus()))
							.setRead(actualSize)
							.setFileId(toGrpcUuid(fileId))
							.build()
					);
					responseObserver.onCompleted();
				} catch (IOException e) {
					throw new UnexpectedIOException(
						"Failed to store data to the designated file: " + e.getMessage(),
						"Failed to store data to the designated file.",
						e
					);
				}
			},
			this.evita.getTransactionExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Restores catalog from a file that is already stored on the server and managed by {@link FileManagementService}.
	 *
	 * @param request          containing name of the catalog to be restored and the file id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void restoreCatalogFromServerFile(GrpcRestoreCatalogFromServerFileRequest request, StreamObserver<GrpcRestoreCatalogResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				final Task<?, Void> restorationTask = this.management.restoreCatalog(
					request.getCatalogName(), toUuid(request.getFileId())
				);
				responseObserver.onNext(
					GrpcRestoreCatalogResponse.newBuilder()
						.setTask(toGrpcTaskStatus(restorationTask.getStatus()))
						.build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Method is used to list asynchronous job statuses.
	 */
	@Override
	public void listTaskStatuses(GrpcTaskStatusesRequest request, StreamObserver<GrpcTaskStatusesResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				final PaginatedList<TaskStatus<?, ?>> taskStatuses = this.management.listTaskStatuses(
					request.getPageNumber(),
					request.getPageSize(),
					request.getTaskTypeList()
						.stream()
						.map(StringValue::getValue)
						.toArray(String[]::new),
					request.getSimplifiedStateList()
						.stream()
						.map(EvitaEnumConverter::toSimplifiedStatus)
						.toArray(TaskSimplifiedState[]::new)
				);
				final Builder builder = GrpcTaskStatusesResponse.newBuilder();
				taskStatuses.getData()
					.stream()
					.map(EvitaDataTypesConverter::toGrpcTaskStatus)
					.forEach(builder::addTaskStatus);
				responseObserver.onNext(
					builder.setPageNumber(taskStatuses.getPageNumber())
						.setPageSize(taskStatuses.getPageSize())
						.setTotalNumberOfRecords(taskStatuses.getTotalRecordCount())
						.build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Retrieves single task status by its unique UUID.
	 */
	@Override
	public void getTaskStatus(GrpcTaskStatusRequest request, StreamObserver<GrpcTaskStatusResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				this.management.getTaskStatus(toUuid(request.getTaskId()))
					.ifPresent(
						it -> responseObserver.onNext(GrpcTaskStatusResponse.newBuilder()
							.setTaskStatus(toGrpcTaskStatus(it))
							.build()
						)
					);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Retrieves statuses of specified tasks by their unique UUIDs.
	 */
	@Override
	public void getTaskStatuses(GrpcSpecifiedTaskStatusesRequest request, StreamObserver<GrpcSpecifiedTaskStatusesResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				final GrpcSpecifiedTaskStatusesResponse.Builder builder = GrpcSpecifiedTaskStatusesResponse.newBuilder();
				this.management.getTaskStatuses(request.getTaskIdsList().stream().map(EvitaDataTypesConverter::toUuid).toArray(UUID[]::new))
					.forEach(status -> builder.addTaskStatus(toGrpcTaskStatus(status)));
				responseObserver.onNext(builder.build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Cancels single task execution by its unique UUID and returns a success flag if the task was successfully found
	 * and canceled.
	 */
	@Override
	public void cancelTask(GrpcCancelTaskRequest request, StreamObserver<GrpcCancelTaskResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				final boolean canceled = this.management.cancelTask(
					toUuid(request.getTaskId())
				);
				responseObserver.onNext(
					GrpcCancelTaskResponse.newBuilder()
						.setSuccess(canceled)
						.build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Method returns paginated list of files, that are available for fetching / downloading to the client.
	 */
	@Override
	public void listFilesToFetch(GrpcFilesToFetchRequest request, StreamObserver<GrpcFilesToFetchResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				final PaginatedList<FileForFetch> filesToFetch = this.management.listFilesToFetch(
					request.getPageNumber(),
					request.getPageSize(),
					request.getOriginList()
						.stream()
						.map(StringValue::getValue)
						.collect(Collectors.toSet())
				);

				final GrpcFilesToFetchResponse.Builder builder = GrpcFilesToFetchResponse.newBuilder();
				filesToFetch.stream()
					.map(EvitaDataTypesConverter::toGrpcFile)
					.forEach(builder::addFilesToFetch);
				responseObserver.onNext(
					builder
						.setPageNumber(filesToFetch.getPageNumber())
						.setPageSize(filesToFetch.getPageSize())
						.setTotalNumberOfRecords(filesToFetch.getTotalRecordCount())
						.build()
				);

				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Method returns file to fetch by its unique UUID.
	 *
	 * @param request          request containing file id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getFileToFetch(GrpcFileToFetchRequest request, StreamObserver<GrpcFileToFetchResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				this.management.getFileToFetch(toUuid(request.getFileId()))
					.ifPresentOrElse(
						file -> responseObserver.onNext(
							GrpcFileToFetchResponse.newBuilder()
								.setFileToFetch(EvitaDataTypesConverter.toGrpcFile(file))
								.build()
						),
						() -> sendErrorToClient(
							new FileForFetchNotFoundException(toUuid(request.getFileId())), responseObserver
						)
					);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Method streams contents of the single file identified by its unique UUID to the client.
	 *
	 * The producing loop is gated on transport readiness, so the file is streamed at the speed the
	 * client actually consumes it. Without the gate the loop pushes the whole file into Armeria's
	 * unbounded outbound queue at disk speed, which for a large backup over a slow link exhausts the
	 * direct-memory allocator and kills the RPC with a bare `UNKNOWN` part-way through.
	 */
	@Override
	public void fetchFile(GrpcFetchFileRequest request, StreamObserver<GrpcFetchFileResponse> responseObserver) {
		// the gate has to be wired up here, on the thread that invoked the service method and before it
		// returns - gRPC freezes handler registration afterwards, and the loop below runs on a worker
		final GrpcOutboundGate outboundGate = GrpcOutboundGate.attach(
			(ServerCallStreamObserver<GrpcFetchFileResponse>) responseObserver,
			"fetchFile", null, this.streamingRequestTimeoutInMillis
		);
		executeWithClientContext(
			() -> {
				final UUID fileId = toUuid(request.getFileId());
				final Optional<FileForFetch> fileToFetch = this.management.getFileToFetch(fileId);
				if (fileToFetch.isEmpty()) {
					sendErrorToClient(new FileForFetchNotFoundException(fileId), responseObserver);
				} else {
					final long totalSizeInBytes = fileToFetch.get().totalSizeInBytes();
					try (
						final InputStream inputStream = this.management.fetchFile(
							fileId
						)
					) {
						//noinspection CheckForOutOfMemoryOnLargeArrayAllocation
						final byte[] buffer = new byte[FETCH_FILE_CHUNK_SIZE];
						int bytesRead;
						while ((bytesRead = inputStream.read(buffer)) != -1) {
							if (!outboundGate.awaitWritable()) {
								// the client is gone - abandon the transfer without completing the
								// stream, since completing it would claim a file that never arrived
								log.debug("Client of `fetchFile` disconnected, abandoning transfer of {}.", fileId);
								return;
							}
							final GrpcFetchFileResponse response = GrpcFetchFileResponse.newBuilder()
								.setFileContents(ByteString.copyFrom(buffer, 0, bytesRead))
								.setTotalSizeInBytes(totalSizeInBytes)
								.build();
							// no re-arm of the Armeria deadline here: `awaitWritable` above already granted
							// this message its window - see `GrpcOutboundGate#grantNextMessageWindow`. The
							// deadline has to roll on a stream, because gating on readiness ties this
							// handler's lifetime to how fast the *client* consumes, and a healthy download
							// of a large file over a slow link outlives any fixed budget.
							responseObserver.onNext(response);
						}
						// the final chunk's window is the only one still standing - give the half-close,
						// and whatever is still queued behind it, a full budget of their own
						outboundGate.grantCompletionWindow();
						responseObserver.onCompleted();
					} catch (IOException e) {
						throw new UnexpectedIOException(
							"Failed to fetch the designated file: " + e.getMessage(),
							"Failed to fetch the designated file.",
							e
						);
					}
				}
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Method is used to delete file from the server by its id.
	 *
	 * @param request          request containing file id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void deleteFile(GrpcDeleteFileToFetchRequest request, StreamObserver<GrpcDeleteFileToFetchResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				final UUID fileId = toUuid(request.getFileId());
				try {
					this.management.deleteFile(fileId);
					responseObserver.onNext(GrpcDeleteFileToFetchResponse.newBuilder().setSuccess(true).build());
				} catch (FileForFetchNotFoundException ex) {
					responseObserver.onNext(GrpcDeleteFileToFetchResponse.newBuilder().setSuccess(false).build());
				}
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Returns list of reserved keywords from {@link io.evitadb.utils.ClassifierUtils}.
	 *
	 * @param request          the request for reserved keywords
	 * @param responseObserver the observer for receiving the reserved keywords response
	 */
	@Override
	public void listReservedKeywords(Empty request, StreamObserver<GrpcReservedKeywordsResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				final GrpcReservedKeywordsResponse.Builder responseBuilder = GrpcReservedKeywordsResponse.newBuilder();
				for (Entry<ClassifierType, Set<Keyword>> entry : ClassifierUtils.getNormalizedReservedKeywords().entrySet()) {
					final GrpcClassifierType grpcClassifierType = EvitaEnumConverter.toGrpcClassifierType(entry.getKey());
					for (Keyword keyword : entry.getValue()) {
						responseBuilder.addKeywords(
							GrpcReservedKeyword.newBuilder()
								.setClassifierType(grpcClassifierType)
								.setClassifier(keyword.classifier())
								.addAllWords(Arrays.asList(keyword.words()))
								.build()
						);
					}
				}
				responseObserver.onNext(
					responseBuilder.build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.context
		);
	}

	/**
	 * Piece of file work that may fail with an {@link IOException}.
	 */
	@FunctionalInterface
	private interface UploadStep {

		void run() throws IOException;

	}

	/**
	 * Receives an uploaded catalog backup, writes it to a temporary file and submits the restoration
	 * task once the client half-closes.
	 *
	 * All three observer callbacks are invoked on the Armeria event loop, and none of them may block
	 * there - a 690 MB restore is thousands of blocking write syscalls, and the event loop is shared
	 * by every other connection the server is serving. Every step that touches the file system is
	 * therefore appended to {@link #uploadChain}, a single {@link CompletableFuture} chain per call:
	 * it hands the work to a worker thread while keeping the chunks strictly ordered, which is the one
	 * property a corrupted ZIP would be the symptom of losing.
	 *
	 * The chain is also what makes ordering independent of the executor's scheduling. Routing the
	 * whole handler to Armeria's blocking task executor (the `@Blocking` annotation) would be a
	 * one-line alternative, but `AbstractServerCall` dispatches each message as a separate task onto a
	 * *shared* pool, so ordering would rest on the undocumented assumption that no second message is
	 * ever dispatched before the first task returns. Here ordering is explicit.
	 *
	 * **The chain and the demand protocol are redundant, deliberately.** `disableAutoRequest()` plus a
	 * `request(1)` issued from inside each *completed* write step already means at most one chunk is in
	 * flight, which orders the writes on its own and additionally withholds half-close until the last
	 * write has landed. Removing either mechanism alone therefore changes nothing observable - measured,
	 * not assumed: `LongRunningGrpcRestoreCatalogUploadTest#shouldRestoreCatalogUploadedThroughClientStreamingRpc`
	 * stays green against both single-mechanism counterfactuals. Do not read that as licence to delete
	 * one. Neither is covered by a test that fails when it goes, so the redundancy is the only thing
	 * standing between a plausible-looking simplification and a silently corrupted archive.
	 */
	static final class RestoreCatalogUploadObserver implements StreamObserver<GrpcRestoreCatalogRequest> {
		/**
		 * Size of the write buffer wrapped around the temporary file. Writes larger than the buffer
		 * bypass it, so this only matters for clients that upload in small chunks - which the previous
		 * unbuffered stream turned into one syscall each.
		 */
		private static final int UPLOAD_BUFFER_SIZE = 65_536;

		private final ServerCallStreamObserver<GrpcRestoreCatalogResponse> responseObserver;
		private final ServiceRequestContext serviceContext;
		/**
		 * Directory the assembled archive is written into. Resolved once at construction rather than per
		 * chunk - it is a configuration read, and doing it here is what lets this observer be driven from
		 * a test without an engine behind it.
		 */
		private final Path workDirectory;
		private final EvitaManagement management;
		private final Executor uploadExecutor;
		/**
		 * The call's configured request timeout, captured once at construction - i.e. before the first
		 * chunk, and therefore before any re-arm has rewritten the stored budget. Re-reading it from the
		 * context per chunk instead would compound it; see
		 * {@link GrpcTimeoutUtil#captureRequestTimeoutMillis(ServiceRequestContext)}.
		 */
		private final long configuredRequestTimeoutMillis;
		private final AtomicLong bytesRead = new AtomicLong(0);
		/**
		 * Guards the single terminal response - the client must be told the outcome exactly once, no
		 * matter which of the failure paths gets there first.
		 */
		private final AtomicBoolean terminated = new AtomicBoolean();
		/**
		 * Serialises every file operation of this call. Mutated only from the observer callbacks,
		 * which gRPC delivers serially on the event loop.
		 */
		private CompletableFuture<Void> uploadChain = CompletableFuture.completedFuture(null);
		/**
		 * Written on the event loop, read from the worker running the chain - the chain's own
		 * completion machinery orders the two, `volatile` states that intent rather than relying on it.
		 */
		private volatile String catalogNameToRestore;
		private volatile Path backupFilePath;
		@Nullable private volatile OutputStream outputStream;

		RestoreCatalogUploadObserver(
			@Nonnull ServerCallStreamObserver<GrpcRestoreCatalogResponse> responseObserver,
			@Nonnull ServiceRequestContext serviceContext,
			@Nonnull Path workDirectory,
			@Nonnull EvitaManagement management,
			@Nonnull Executor uploadExecutor,
			long streamingRequestTimeoutInMillis
		) {
			this.responseObserver = responseObserver;
			this.serviceContext = serviceContext;
			this.workDirectory = workDirectory;
			this.management = management;
			this.uploadExecutor = uploadExecutor;
			this.configuredRequestTimeoutMillis = GrpcTimeoutUtil.resolveStreamingBudgetMillis(
				serviceContext, streamingRequestTimeoutInMillis
			);
		}

		@Override
		public void onNext(GrpcRestoreCatalogRequest request) {
			this.catalogNameToRestore = request.getCatalogName();
			final ByteString backupFile = request.getBackupFile();
			appendToChain(
				() -> {
					openBackupFileIfNeeded();
					backupFile.writeTo(this.outputStream);
					this.bytesRead.addAndGet(backupFile.size());
					GrpcTimeoutUtil.reArmRequestTimeoutIfEnabled(
						this.serviceContext, this.configuredRequestTimeoutMillis
					);
					// Only now may the client send the next chunk - raising demand from inside the
					// *completed* write step is what keeps at most one chunk in flight and therefore
					// what orders the writes. Safe to call straight from this worker: Armeria's
					// `StreamingServerCall.request(int)` marshals onto the call's event loop itself
					// when the caller is not already on it, so the upstream subscription it touches
					// stays event-loop-confined without help from here.
					this.responseObserver.request(1);
				}
			);
		}

		@Override
		public void onError(Throwable t) {
			// the client aborted mid-upload - let whatever is already in flight finish before the
			// partial file is closed and discarded, so no worker is writing into a closed stream
			this.uploadChain.whenComplete(
				(ignored, throwable) -> handOffCleanupToUploadExecutor(() -> failUpload(t))
			);
		}

		@Override
		public void onCompleted() {
			appendToChain(this::submitRestoration);
		}

		/**
		 * Appends a file operation to this call's chain. A step that fails reports the failure to the
		 * client once and then poisons the rest of the chain, so nothing downstream - the restoration
		 * submission above all - ever runs against a half-written file.
		 *
		 * The hand-off to {@link #uploadExecutor} is made by hand rather than by `thenRunAsync`, because
		 * the request pool is bounded and rejects work once its queue fills
		 * (`EvitaRejectingExecutorHandler` throws). Letting `CompletableFuture` schedule the step loses
		 * that rejection in two different ways, and the second one is worse than a lost error:
		 *
		 * - while the previous step is still running, the rejection surfaces on *that* worker, inside
		 *   `CompletableFuture#postComplete`, with no relation to this call - the stage simply never
		 *   completes and the client waits out its deadline;
		 * - once the previous step has completed, `thenRunAsync` throws on the calling thread *before*
		 *   `this.uploadChain` is reassigned, so the chain is left looking healthy. The next chunk then
		 *   appends successfully, {@link #openBackupFileIfNeeded()} finds a null stream and reopens the
		 *   temporary file in `APPEND` mode - leaking a second archive on the very path being handled.
		 *
		 * Completing `next` exceptionally in both cases is what keeps the chain poisoned, and therefore
		 * what stops that reopen.
		 *
		 * @param step the operation to append
		 */
		private void appendToChain(@Nonnull UploadStep step) {
			final CompletableFuture<Void> previous = this.uploadChain;
			final CompletableFuture<Void> next = new CompletableFuture<>();
			this.uploadChain = next;
			// deliberately `whenComplete` and not `whenCompleteAsync`: this stage only *submits* work, so
			// running it inline (or on whichever worker completed the previous step) costs nothing and,
			// crucially, cannot itself be rejected
			previous.whenComplete(
				(ignored, previousFailure) -> {
					if (previousFailure != null) {
						// a poisoned chain stays poisoned - nothing may run against a discarded file
						next.completeExceptionally(previousFailure);
						return;
					}
					handOffToUploadExecutor(
						() -> {
							try {
								step.run();
								next.complete(null);
							} catch (Exception ex) {
								failUpload(ex);
								next.completeExceptionally(ex);
							}
						},
						next
					);
				}
			);
		}

		/**
		 * Hands a step to {@link #uploadExecutor}, falling back to running it on the calling thread when
		 * the pool refuses it.
		 *
		 * The fallback puts a `close` and an `unlink` on whichever thread got here - the event loop, in
		 * the worst case - which is exactly what this observer exists to avoid. It is still the right
		 * trade: the alternative to a few microseconds of blocking on an already-degraded server is a
		 * part-uploaded archive, up to the full size of a catalog, left in the work directory for the
		 * lifetime of the process. Nothing sweeps that directory.
		 *
		 * @param step             the work to run on the upload executor
		 * @param rejectionOutcome poisoned with the rejection when the pool refuses the hand-off, so the
		 *                         chain cannot continue past a step that never ran
		 */
		private void handOffToUploadExecutor(
			@Nonnull Runnable step,
			@Nonnull CompletableFuture<Void> rejectionOutcome
		) {
			try {
				this.uploadExecutor.execute(step);
			} catch (RejectedExecutionException ex) {
				failUpload(ex);
				rejectionOutcome.completeExceptionally(ex);
			}
		}

		/**
		 * Hands terminal cleanup to {@link #uploadExecutor}, running it on the calling thread when the
		 * pool refuses it. Unlike {@link #handOffToUploadExecutor(Runnable, CompletableFuture)} there is
		 * no stage left to poison - the call is already ending - so the rejection only has to not
		 * prevent the cleanup from happening at all.
		 *
		 * @param cleanup the cleanup to run; idempotent, so running it on either thread is equivalent
		 */
		private void handOffCleanupToUploadExecutor(@Nonnull Runnable cleanup) {
			try {
				this.uploadExecutor.execute(cleanup);
			} catch (RejectedExecutionException ex) {
				cleanup.run();
			}
		}

		/**
		 * Creates the temporary file on first use. Deferred to the first chunk on purpose: creating it
		 * eagerly would put `mkdirs` and `createTempFile` back on the event loop, which is exactly what
		 * this observer exists to avoid.
		 */
		private void openBackupFileIfNeeded() throws IOException {
			if (this.outputStream != null) {
				return;
			}
			if (!this.workDirectory.toFile().exists()) {
				Assert.isTrue(
					this.workDirectory.toFile().mkdirs(), "Failed to create work directory for catalog restore."
				);
			}
			this.backupFilePath = Files.createTempFile(
				this.workDirectory, "catalog_backup_for_restore-", ".zip"
			);
			this.outputStream = new BufferedOutputStream(
				Files.newOutputStream(this.backupFilePath, StandardOpenOption.APPEND), UPLOAD_BUFFER_SIZE
			);
		}

		/**
		 * Finalises the upload and hands the assembled file to the restoration task.
		 */
		private void submitRestoration() throws IOException {
			Assert.isPremiseValid(this.catalogNameToRestore != null, "Catalog name to restore must be provided.");
			Assert.isPremiseValid(this.backupFilePath != null, "No backup file contents were uploaded.");
			final OutputStream theOutputStream = this.outputStream;
			Assert.isPremiseValid(theOutputStream != null, "Output stream has already been closed.");
			theOutputStream.close();
			this.outputStream = null;
			final Task<?, Void> restorationTask = this.management.restoreCatalog(
				this.catalogNameToRestore,
				Files.size(this.backupFilePath),
				Files.newInputStream(this.backupFilePath, StandardOpenOption.READ)
			);
			if (this.terminated.compareAndSet(false, true)) {
				this.responseObserver.onNext(
					GrpcRestoreCatalogResponse.newBuilder()
						.setTask(toGrpcTaskStatus(restorationTask.getStatus()))
						.setRead(this.bytesRead.get())
						.build()
				);
				this.responseObserver.onCompleted();
			}
		}

		/**
		 * Reports a failed upload to the client and discards the partial file. Idempotent.
		 *
		 * @param exception the failure to report
		 */
		private void failUpload(@Nonnull Throwable exception) {
			closeOutputStream();
			deleteFileIfExists(this.backupFilePath, "restore");
			if (this.terminated.compareAndSet(false, true)) {
				sendErrorToClient(exception, this.responseObserver);
			}
		}

		/**
		 * Closes the temporary file, tolerating both "never opened" and "already closed".
		 */
		private void closeOutputStream() {
			final OutputStream stream = this.outputStream;
			if (stream == null) {
				return;
			}
			this.outputStream = null;
			try {
				stream.close();
			} catch (IOException e) {
				log.error("Failed to close output stream for backup file: {}", this.backupFilePath, e);
			}
		}

	}

}
