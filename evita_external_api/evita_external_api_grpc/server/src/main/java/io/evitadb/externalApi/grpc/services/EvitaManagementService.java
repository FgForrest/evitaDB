/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import io.evitadb.api.CatalogStatistics;
import io.evitadb.api.EvitaManagementContract;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.exception.ReadOnlyException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.observability.ReadinessState;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.core.Evita;
import io.evitadb.core.EvitaManagement;
import io.evitadb.core.file.ExportFileService;
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
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.trace.ExternalApiTracingContextProvider;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.evitadb.store.spi.CatalogPersistenceServiceFactory.FileIdCarrier;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import io.evitadb.utils.ClassifierUtils.Keyword;
import io.evitadb.utils.UUIDUtil;
import io.grpc.Metadata;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcOffsetDateTime;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcTaskStatus;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcUuid;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toUuid;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcHealthProblem;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcReadinessState;
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

	public EvitaManagementService(@Nonnull Evita evita, @Nonnull ExternalApiServer externalApiServer, HeaderOptions headers) {
		this.evita = evita;
		this.externalApiServer = externalApiServer;
		this.management = evita.management();
		this.context = ExternalApiTracingContextProvider.getContext(headers);
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
					.setUptime(systemStatus.uptime().toSeconds())
					.setInstanceId(systemStatus.instanceId())
					.setCatalogsCorrupted(systemStatus.catalogsCorrupted())
					.setCatalogsOk(systemStatus.catalogsOk())
					.setReadiness(toGrpcReadinessState(readiness.map(Readiness::state).orElse(ReadinessState.UNKNOWN)))
					.setReadOnly(evita.getConfiguration().server().readOnly());

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
						new ReadOnlyException()
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
		Path backupFilePath = null;
		try {
			try {
				final Path workDirectory = this.evita.getConfiguration().transaction().transactionWorkDirectory();
				if (!workDirectory.toFile().exists()) {
					Assert.isTrue(workDirectory.toFile().mkdirs(), "Failed to create work directory for catalog restore.");
				}
				backupFilePath = Files.createTempFile(workDirectory, "catalog_backup_for_restore-", ".zip");
				final Path finalBackupFilePath = backupFilePath;
				@SuppressWarnings("resource") final OutputStream outputStream = Files.newOutputStream(finalBackupFilePath, StandardOpenOption.APPEND);
				final AtomicLong bytesRead = new AtomicLong(0);

				return new StreamObserver<>() {
					private String catalogNameToRestore;

					@Override
					public void onNext(GrpcRestoreCatalogRequest request) {
						this.catalogNameToRestore = request.getCatalogName();
						try {
							final ByteString backupFile = request.getBackupFile();
							backupFile.writeTo(outputStream);
							bytesRead.addAndGet(backupFile.size());
						} catch (IOException e) {
							throw new UnexpectedIOException(
								"Failed to write backup file to temporary file.",
								"Failed to write backup file to temporary file.",
								e
							);
						}
					}

					@Override
					public void onError(Throwable t) {
						try {
							outputStream.close();
						} catch (IOException e) {
							log.error("Failed to close output stream for backup file: {}", finalBackupFilePath, e);
						} finally {
							deleteFileIfExists(finalBackupFilePath, "restore");
							sendErrorToClient(t, responseObserver);
						}
					}

					@Override
					public void onCompleted() {
						try {
							outputStream.close();
							Assert.isPremiseValid(this.catalogNameToRestore != null, "Catalog name to restore must be provided.");
							final Task<?, Void> restorationTask = EvitaManagementService.this.management.restoreCatalog(
								this.catalogNameToRestore,
								Files.size(finalBackupFilePath),
								Files.newInputStream(finalBackupFilePath, StandardOpenOption.READ)
							);
							responseObserver.onNext(
								GrpcRestoreCatalogResponse.newBuilder()
									.setTask(toGrpcTaskStatus(restorationTask.getStatus()))
									.setRead(bytesRead.get())
									.build()
							);
							responseObserver.onCompleted();
						} catch (Exception e) {
							deleteFileIfExists(finalBackupFilePath, "restore");
							sendErrorToClient(e, responseObserver);
						}
					}
				};
			} catch (IOException e) {
				sendErrorToClient(e, responseObserver);
				throw e;
			}
		} catch (Exception e) {
			if (backupFilePath != null) {
				deleteFileIfExists(backupFilePath, "restore");
			}
			return new NoopStreamObserver<>();
		}
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
					final Path workDirectory = evita.getConfiguration().transaction().transactionWorkDirectory();
					final String catalogNameToRestore = request.getCatalogName();
					Assert.isPremiseValid(catalogNameToRestore != null, "Catalog name to restore must be provided.");

					final ServerTask<?, ?> restorationTask;
					final Path backupFilePath;
					if (fileId == null) {
						if (!workDirectory.toFile().exists()) {
							Assert.isTrue(workDirectory.toFile().mkdirs(), "Failed to create work directory for catalog restore.");
						}

						fileId = UUIDUtil.randomUUID();
						backupFilePath = management.exportFileService().createTempFile(fileId + ".zip");
						restorationTask = management.createRestorationTask(
							catalogNameToRestore,
							fileId,
							backupFilePath,
							request.getTotalSizeInBytes(),
							true
						);
						this.management.registerWaitingTask(restorationTask);
					} else {
						backupFilePath = management.exportFileService().getTempFile(fileId + ".zip");
						restorationTask = this.management.getWaitingTask(createRestoreTaskFindPredicate(fileId))
							.orElseThrow(() -> new UnexpectedIOException("Task not found for file: " + backupFilePath, "Task not found for file id!"));
					}

					try (final OutputStream outputStream = Files.newOutputStream(backupFilePath, StandardOpenOption.APPEND)) {
						final ByteString backupFile = request.getBackupFile();
						backupFile.writeTo(outputStream);
					}

					// we've reached the expected size of the file
					final long actualSize = Files.size(backupFilePath);
					if (actualSize == request.getTotalSizeInBytes()) {
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

					if (actualSize > totalSizeInBytes) {
						deleteFileIfExists(backupFilePath, "restore");
						throw new UnexpectedIOException(
							"Backup file size exceeds the expected size.",
							"Backup file size exceeds the expected size (expected " + totalSizeInBytes + ", actual " + actualSize + " Bytes)."
						);
					}
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
	 * Restores catalog from a file that is already stored on the server and managed by {@link ExportFileService}.
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
	 */
	@Override
	public void fetchFile(GrpcFetchFileRequest request, StreamObserver<GrpcFetchFileResponse> responseObserver) {
		executeWithClientContext(
			() -> {
				final UUID fileId = toUuid(request.getFileId());
				final Optional<FileForFetch> fileToFetch = this.management.getFileToFetch(fileId);
				if (fileToFetch.isEmpty()) {
					sendErrorToClient(new FileForFetchNotFoundException(fileId), responseObserver);
				} else {
					try (
						final InputStream inputStream = this.management.fetchFile(
							fileId
						)
					) {
						//noinspection CheckForOutOfMemoryOnLargeArrayAllocation
						byte[] buffer = new byte[65_536];
						int bytesRead;
						while ((bytesRead = inputStream.read(buffer)) != -1) {
							GrpcFetchFileResponse response = GrpcFetchFileResponse.newBuilder()
								.setFileContents(ByteString.copyFrom(buffer, 0, bytesRead))
								.setTotalSizeInBytes(fileToFetch.get().totalSizeInBytes())
								.build();
							responseObserver.onNext(response);
						}
					} catch (IOException e) {
						throw new UnexpectedIOException(
							"Failed to fetch the designated file: " + e.getMessage(),
							"Failed to fetch the designated file.",
							e
						);
					}
					responseObserver.onCompleted();
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
	 * No-op implementation of StreamObserver. Used in case the proper observer could not be created.
	 */
	private static class NoopStreamObserver<V> implements StreamObserver<V> {

		@Override
		public void onNext(V value) {
		}

		@Override
		public void onError(Throwable t) {
		}

		@Override
		public void onCompleted() {
		}
	}

}
