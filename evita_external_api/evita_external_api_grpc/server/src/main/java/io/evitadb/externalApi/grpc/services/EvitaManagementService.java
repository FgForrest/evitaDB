/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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
import io.evitadb.api.EvitaManagementContract;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.core.Evita;
import io.evitadb.core.EvitaManagement;
import io.evitadb.core.file.ExportFileService;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.externalApi.grpc.constants.GrpcHeaders;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse.Builder;
import io.evitadb.externalApi.grpc.services.interceptors.ServerSessionInterceptor;
import io.evitadb.externalApi.trace.ExternalApiTracingContextProvider;
import io.evitadb.utils.Assert;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcOffsetDateTime;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcTaskStatus;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toUuid;

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
	 * Direct reference to {@link EvitaManagement} instance.
	 */
	@Nonnull private final EvitaManagement management;

	/**
	 * Executes entire lambda function within the scope of a tracing context.
	 */
	private static void executeWithClientContext(@Nonnull Runnable lambda) {
		final Metadata metadata = ServerSessionInterceptor.METADATA.get();
		ExternalApiTracingContextProvider.getContext()
			.executeWithinBlock(
				GrpcHeaders.getGrpcTraceTaskNameWithMethodName(metadata),
				metadata,
				lambda
			);
	}

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

	public EvitaManagementService(@Nonnull Evita evita) {
		this.evita = evita;
		this.management = evita.management();
	}

	/**
	 * Retrieves the server status.
	 *
	 * @param request          the request for server status
	 * @param responseObserver the observer for receiving the server status response
	 */
	@Override
	public void serverStatus(Empty request, StreamObserver<GrpcEvitaServerStatusResponse> responseObserver) {
		executeWithClientContext(() -> {
			final SystemStatus systemStatus = management.getSystemStatus();
			responseObserver.onNext(
				GrpcEvitaServerStatusResponse
					.newBuilder()
					.setVersion(systemStatus.version())
					.setStartedAt(toGrpcOffsetDateTime(systemStatus.startedAt()))
					.setUptime(systemStatus.uptime().toSeconds())
					.setInstanceId(systemStatus.instanceId())
					.setCatalogsCorrupted(systemStatus.catalogsCorrupted())
					.setCatalogsOk(systemStatus.catalogsOk())
					.build()
			);
			responseObserver.onCompleted();
		});
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
				final Path workDirectory = evita.getConfiguration().transaction().transactionWorkDirectory();
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
							responseObserver.onError(t);
						}
					}

					@Override
					public void onCompleted() {
						try {
							outputStream.close();
							Assert.isPremiseValid(catalogNameToRestore != null, "Catalog name to restore must be provided.");
							final Task<?, Void> restorationTask = management.restoreCatalog(
								catalogNameToRestore,
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
							responseObserver.onError(e);
							deleteFileIfExists(finalBackupFilePath, "restore");
						}
					}
				};
			} catch (IOException e) {
				responseObserver.onError(e);
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
	 * Restores catalog from a file that is already stored on the server and managed by {@link ExportFileService}.
	 *
	 * @param request          containing name of the catalog to be restored and the file id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void restoreCatalogFromServerFile(GrpcRestoreCatalogFromServerFileRequest request, StreamObserver<GrpcRestoreCatalogResponse> responseObserver) {
		final Task<?, Void> restorationTask = management.restoreCatalog(
			request.getCatalogName(), toUuid(request.getFileId())
		);
		responseObserver.onNext(
			GrpcRestoreCatalogResponse.newBuilder()
				.setTask(toGrpcTaskStatus(restorationTask.getStatus()))
				.build()
		);
		responseObserver.onCompleted();
	}

	/**
	 * Method is used to list asynchronous job statuses.
	 */
	@Override
	public void listTaskStatuses(GrpcTaskStatusesRequest request, StreamObserver<GrpcTaskStatusesResponse> responseObserver) {
		final PaginatedList<TaskStatus<?, ?>> taskStatuses = management.listTaskStatuses(
			request.getPageNumber(),
			request.getPageSize()
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
	}

	/**
	 * Retrieves single task status by its unique UUID.
	 */
	@Override
	public void getTaskStatus(GrpcTaskStatusRequest request, StreamObserver<GrpcTaskStatusResponse> responseObserver) {
		management.getTaskStatus(toUuid(request.getTaskId()))
			.ifPresent(
				it -> responseObserver.onNext(GrpcTaskStatusResponse.newBuilder()
					.setTaskStatus(toGrpcTaskStatus(it))
					.build()
				)
			);

		responseObserver.onCompleted();
	}

	/**
	 * Retrieves statuses of specified tasks by their unique UUIDs.
	 */
	@Override
	public void getTaskStatuses(GrpcSpecifiedTaskStatusesRequest request, StreamObserver<GrpcSpecifiedTaskStatusesResponse> responseObserver) {
		final GrpcSpecifiedTaskStatusesResponse.Builder builder = GrpcSpecifiedTaskStatusesResponse.newBuilder();
		management.getTaskStatuses(request.getTaskIdsList().stream().map(EvitaDataTypesConverter::toUuid).toArray(UUID[]::new))
			.forEach(status -> builder.addTaskStatus(toGrpcTaskStatus(status)));
		responseObserver.onNext(builder.build());
		responseObserver.onCompleted();
	}

	/**
	 * Cancels single task execution by its unique UUID and returns a success flag if the task was successfully found
	 * and canceled.
	 */
	@Override
	public void cancelTask(GrpcCancelTaskRequest request, StreamObserver<GrpcCancelTaskResponse> responseObserver) {
		final boolean canceled = management.cancelTask(
			toUuid(request.getTaskId())
		);
		responseObserver.onNext(
			GrpcCancelTaskResponse.newBuilder()
				.setSuccess(canceled)
				.build()
		);
		responseObserver.onCompleted();
	}

	/**
	 * Method returns paginated list of files, that are available for fetching / downloading to the client.
	 */
	@Override
	public void listFilesToFetch(GrpcFilesToFetchRequest request, StreamObserver<GrpcFilesToFetchResponse> responseObserver) {
		final PaginatedList<FileForFetch> filesToFetch = management.listFilesToFetch(
			request.getPageNumber(),
			request.getPageSize(),
			request.hasOrigin() ? request.getOrigin().getValue() : null
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
	}

	/**
	 * Method returns file to fetch by its unique UUID.
	 *
	 * @param request          request containing file id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getFileToFetch(GrpcFileToFetchRequest request, StreamObserver<GrpcFileToFetchResponse> responseObserver) {
		management.getFileToFetch(toUuid(request.getFileId()))
			.ifPresentOrElse(
				file -> responseObserver.onNext(
					GrpcFileToFetchResponse.newBuilder()
						.setFileToFetch(EvitaDataTypesConverter.toGrpcFile(file))
						.build()
				),
				() -> responseObserver.onError(
					new FileForFetchNotFoundException(toUuid(request.getFileId()))
				)
			);

		responseObserver.onCompleted();
	}

	/**
	 * Method streams contents of the single file identified by its unique UUID to the client.
	 */
	@Override
	public void fetchFile(GrpcFetchFileRequest request, StreamObserver<GrpcFetchFileResponse> responseObserver) {
		final UUID fileId = toUuid(request.getFileId());
		final Optional<FileForFetch> fileToFetch = management.getFileToFetch(fileId);
		if (fileToFetch.isEmpty()) {
			responseObserver.onError(new FileForFetchNotFoundException(fileId));
		} else {
			try (
				final InputStream inputStream = management.fetchFile(
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
	}

	/**
	 * Method is used to delete file from the server by its id.
	 *
	 * @param request          request containing file id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void deleteFile(GrpcDeleteFileToFetchRequest request, StreamObserver<GrpcDeleteFileToFetchResponse> responseObserver) {
		final UUID fileId = toUuid(request.getFileId());
		try {
			management.deleteFile(fileId);
			responseObserver.onNext(GrpcDeleteFileToFetchResponse.newBuilder().setSuccess(true).build());
		} catch (FileForFetchNotFoundException ex) {
			responseObserver.onNext(GrpcDeleteFileToFetchResponse.newBuilder().setSuccess(false).build());
		}
		responseObserver.onCompleted();
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
