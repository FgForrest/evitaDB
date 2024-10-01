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

package io.evitadb.driver;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.StringValue;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import io.evitadb.api.CatalogStatistics;
import io.evitadb.api.EvitaManagementContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.EvitaManagementServiceGrpc.EvitaManagementServiceFutureStub;
import io.evitadb.externalApi.grpc.generated.EvitaManagementServiceGrpc.EvitaManagementServiceStub;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest.Builder;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.driver.EvitaClient.executeWithEvitaService;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcUuid;

/**
 * Client implementation of {@link EvitaManagementContract}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class EvitaClientManagement implements EvitaManagementContract, Closeable {
	/**
	 * Evita client used for communication with the server side.
	 */
	private final EvitaClient evitaClient;
	/**
	 * Client task tracker is used to track the tasks and their status.
	 */
	private final ClientTaskTracker clientTaskTracker;
	/**
	 * Created evita service stub.
	 */
	private final EvitaManagementServiceStub evitaManagementServiceStub;
	/**
	 * Created evita service stub that returns futures.
	 */
	private final EvitaManagementServiceFutureStub evitaManagementServiceFutureStub;

	public EvitaClientManagement(@Nonnull EvitaClient evitaClient, @Nonnull GrpcClientBuilder grpcClientBuilder) {
		this.evitaClient = evitaClient;
		this.clientTaskTracker = new ClientTaskTracker(
			this,
			evitaClient.getConfiguration().trackedTaskLimit(),
			2000
		);
		this.evitaManagementServiceStub = grpcClientBuilder.build(EvitaManagementServiceStub.class);
		this.evitaManagementServiceFutureStub = grpcClientBuilder.build(EvitaManagementServiceFutureStub.class);
	}

	@Nonnull
	@Override
	public CatalogStatistics[] getCatalogStatistics() {
		this.evitaClient.assertActive();

		final GrpcEvitaCatalogStatisticsResponse response = executeWithEvitaService(
			this.evitaManagementServiceFutureStub,
			evitaService -> {
				final Timeout timeoutToUse = this.evitaClient.timeout.get().peek();
				return evitaService.getCatalogStatistics(Empty.newBuilder().build())
					.get(timeoutToUse.timeout(), timeoutToUse.timeoutUnit());
			}
		);

		return response.getCatalogStatisticsList()
			.stream()
			.map(EvitaDataTypesConverter::toCatalogStatistics)
			.toArray(CatalogStatistics[]::new);
	}

	@Nonnull
	@Override
	public CompletableFuture<FileForFetch> backupCatalog(
		@Nonnull String catalogName,
		@Nullable OffsetDateTime pastMoment,
		boolean includingWAL
	) throws TemporalDataNotAvailableException {
		this.evitaClient.assertActive();
		try (final EvitaSessionContract session = this.evitaClient.createReadWriteSession(catalogName)) {
			final Task<?, FileForFetch> resultTask = session.backupCatalog(pastMoment, includingWAL);
			return resultTask.getFutureResult();
		}
	}

	@Nonnull
	@Override
	public Task<?, Void> restoreCatalog(
		@Nonnull String catalogName,
		long totalBytesExpected,
		@Nonnull InputStream inputStream
	) throws UnexpectedIOException {
		this.evitaClient.assertActive();

		return executeWithEvitaService(
			this.evitaManagementServiceStub,
			evitaService -> {
				final CompletableFuture<TaskStatus<?, ?>> result = new CompletableFuture<>();
				final AtomicLong bytesSent = new AtomicLong(0);
				final AtomicReference<TaskStatus<?, ?>> taskStatus = new AtomicReference<>();
				final StreamObserver<GrpcRestoreCatalogRequest> requestObserver = evitaService.restoreCatalog(
					new StreamObserver<>() {
						final AtomicLong bytesReceived = new AtomicLong(0);

						@Override
						public void onNext(GrpcRestoreCatalogResponse value) {
							bytesReceived.accumulateAndGet(value.getRead(), Math::max);
							if (value.hasTask()) {
								taskStatus.set(EvitaDataTypesConverter.toTaskStatus(value.getTask()));
							}
						}

						@Override
						public void onError(Throwable t) {
							log.error("Error occurred during catalog restoration: {}", t.getMessage(), t);
							result.completeExceptionally(t);
						}

						@Override
						public void onCompleted() {
							if (bytesSent.get() == bytesReceived.get()) {
								result.complete(taskStatus.get());
							} else {
								result.completeExceptionally(
									new UnexpectedIOException(
										"Number of bytes sent and received during catalog restoration does not match (sent " + bytesSent.get() + ", received " + bytesReceived.get() + ")!",
										"Number of bytes sent and received during catalog restoration does not match!"
									)
								);
							}
						}
					}
				);

				// Send data in chunks
				final ByteBuffer buffer = ByteBuffer.allocate(65_536);
				try (inputStream) {
					while (inputStream.available() > 0) {
						final int read = inputStream.read(buffer.array());
						if (read == -1) {
							requestObserver.onCompleted();
						}
						buffer.limit(read);
						requestObserver.onNext(
							GrpcRestoreCatalogRequest.newBuilder()
								.setCatalogName(catalogName)
								.setBackupFile(ByteString.copyFrom(buffer))
								.build()
						);
						buffer.clear();
						bytesSent.addAndGet(read);
					}

					requestObserver.onCompleted();
				} catch (IOException e) {
					requestObserver.onError(e);
					throw new RuntimeException(e);
				}

				//noinspection unchecked
				return (Task<?, Void>) clientTaskTracker.createTask(
					Objects.requireNonNull(result.get())
				);
			}
		);
	}

	@Nonnull
	@Override
	public Task<?, Void> restoreCatalog(@Nonnull String catalogName, @Nonnull UUID fileId) throws FileForFetchNotFoundException {
		this.evitaClient.assertActive();

		final GrpcRestoreCatalogResponse response = executeWithEvitaService(
			this.evitaManagementServiceFutureStub,
			evitaService -> {
				final Timeout timeoutToUse = this.evitaClient.timeout.get().peek();
				return evitaService.restoreCatalogFromServerFile(
					GrpcRestoreCatalogFromServerFileRequest.newBuilder()
						.setFileId(toGrpcUuid(fileId))
						.setCatalogName(catalogName)
						.build()
				).get(timeoutToUse.timeout(), timeoutToUse.timeoutUnit());
			}
		);

		//noinspection unchecked
		return (Task<?, Void>) this.clientTaskTracker.createTask(
			EvitaDataTypesConverter.toTaskStatus(response.getTask())
		);
	}

	@Nonnull
	@Override
	public PaginatedList<TaskStatus<?, ?>> listTaskStatuses(
		int page, int pageSize,
		@Nullable String[] taskType,
		@Nonnull TaskSimplifiedState... states
	) {
		this.evitaClient.assertActive();

		final GrpcTaskStatusesResponse response = executeWithEvitaService(
			this.evitaManagementServiceFutureStub,
			evitaService -> {
				final Timeout timeoutToUse = this.evitaClient.timeout.get().peek();
				final GrpcTaskStatusesRequest.Builder builder = GrpcTaskStatusesRequest.newBuilder()
					.setPageNumber(page)
					.setPageSize(pageSize);
				if (taskType != null) {
					for (String theTaskType : taskType) {
						builder.addTaskType(StringValue.of(theTaskType));
					}
				}
				for (TaskSimplifiedState state : states) {
					builder.addSimplifiedState(EvitaEnumConverter.toGrpcSimplifiedStatus(state));
				}
				return evitaService.listTaskStatuses(builder.build())
					.get(timeoutToUse.timeout(), timeoutToUse.timeoutUnit());
			}
		);

		return new PaginatedList<>(
			response.getPageNumber(),
			response.getPageSize(),
			response.getTotalNumberOfRecords(),
			response.getTaskStatusList()
				.stream()
				.map(EvitaDataTypesConverter::toTaskStatus)
				.collect(Collectors.toCollection(ArrayList::new))
		);
	}

	@Nonnull
	@Override
	public Optional<TaskStatus<?, ?>> getTaskStatus(@Nonnull UUID jobId) {
		this.evitaClient.assertActive();

		final GrpcTaskStatusResponse response = executeWithEvitaService(
			this.evitaManagementServiceFutureStub,
			evitaService -> {
				final Timeout timeoutToUse = this.evitaClient.timeout.get().peek();
				return evitaService.getTaskStatus(
					GrpcTaskStatusRequest.newBuilder()
						.setTaskId(toGrpcUuid(jobId))
						.build()
				).get(timeoutToUse.timeout(), timeoutToUse.timeoutUnit());
			}
		);

		return response.hasTaskStatus() ?
			Optional.of(EvitaDataTypesConverter.toTaskStatus(response.getTaskStatus())) : Optional.empty();
	}

	@Nonnull
	@Override
	public Collection<TaskStatus<?, ?>> getTaskStatuses(@Nonnull UUID... jobId) {
		this.evitaClient.assertActive();

		final GrpcSpecifiedTaskStatusesResponse response = executeWithEvitaService(
			this.evitaManagementServiceFutureStub,
			evitaService -> {
				final Timeout timeoutToUse = this.evitaClient.timeout.get().peek();
				final Builder builder = GrpcSpecifiedTaskStatusesRequest.newBuilder();
				for (UUID id : jobId) {
					builder.addTaskIds(toGrpcUuid(id));
				}
				return evitaService.getTaskStatuses(
					builder.build()
				).get(timeoutToUse.timeout(), timeoutToUse.timeoutUnit());
			}
		);

		return response.getTaskStatusList()
			.stream()
			.map(EvitaDataTypesConverter::toTaskStatus)
			.collect(Collectors.toCollection(ArrayList::new));
	}

	@Override
	public boolean cancelTask(@Nonnull UUID jobId) {
		this.evitaClient.assertActive();

		final GrpcCancelTaskResponse response = executeWithEvitaService(
			this.evitaManagementServiceFutureStub,
			evitaService -> {
				final Timeout timeoutToUse = this.evitaClient.timeout.get().peek();
				return evitaService.cancelTask(
					GrpcCancelTaskRequest.newBuilder()
						.setTaskId(toGrpcUuid(jobId))
						.build()
				).get(timeoutToUse.timeout(), timeoutToUse.timeoutUnit());
			}
		);

		return response.getSuccess();
	}

	@Nonnull
	@Override
	public PaginatedList<FileForFetch> listFilesToFetch(int page, int pageSize, @Nullable String origin) {
		this.evitaClient.assertActive();

		final GrpcFilesToFetchResponse response = executeWithEvitaService(
			this.evitaManagementServiceFutureStub,
			evitaService -> {
				final Timeout timeoutToUse = this.evitaClient.timeout.get().peek();
				return evitaService.listFilesToFetch(
					GrpcFilesToFetchRequest.newBuilder()
						.setPageNumber(page)
						.setPageSize(pageSize)
						.build()
				).get(timeoutToUse.timeout(), timeoutToUse.timeoutUnit());
			}
		);

		return new PaginatedList<>(
			response.getPageNumber(),
			response.getPageSize(),
			response.getTotalNumberOfRecords(),
			response.getFilesToFetchList()
				.stream()
				.map(EvitaDataTypesConverter::toFileForFetch)
				.collect(Collectors.toCollection(ArrayList::new))
		);
	}

	@Nonnull
	@Override
	public Optional<FileForFetch> getFileToFetch(@Nonnull UUID fileId) {
		this.evitaClient.assertActive();

		final GrpcFileToFetchResponse response = executeWithEvitaService(
			this.evitaManagementServiceFutureStub,
			evitaService -> {
				final Timeout timeoutToUse = this.evitaClient.timeout.get().peek();
				return evitaService.getFileToFetch(
					GrpcFileToFetchRequest.newBuilder()
						.setFileId(toGrpcUuid(fileId))
						.build()
				).get(timeoutToUse.timeout(), timeoutToUse.timeoutUnit());
			}
		);

		return response.hasFileToFetch() ?
			Optional.of(EvitaDataTypesConverter.toFileForFetch(response.getFileToFetch())) : Optional.empty();
	}

	@Nonnull
	@Override
	public InputStream fetchFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException, UnexpectedIOException {
		this.evitaClient.assertActive();
		try {
			// Create a temporary file
			Path tempFile = Files.createTempFile("downloadedFile", ".tmp");
			CompletableFuture<Void> downloadFuture = new CompletableFuture<>();

			// Download the file asynchronously
			executeWithEvitaService(
				this.evitaManagementServiceStub,
				evitaService -> {
					evitaService.fetchFile(
						GrpcFetchFileRequest.newBuilder().setFileId(toGrpcUuid(fileId)).build(),
						new StreamObserver<>() {
							@Override
							public void onNext(GrpcFetchFileResponse response) {
								try {
									// Write chunks to the temporary file
									Files.write(tempFile, response.getFileContents().toByteArray(), StandardOpenOption.APPEND);
								} catch (IOException e) {
									onError(e);
								}
							}

							@Override
							public void onError(Throwable t) {
								downloadFuture.completeExceptionally(t);
							}

							@Override
							public void onCompleted() {
								downloadFuture.complete(null);
							}
						}
					);
					return null;
				}
			);

			// Wait for the download to complete
			downloadFuture.join();

			// Return an InputStream for the temporary file
			return new FileInputStream(tempFile.toFile()) {
				@Override
				public void close() throws IOException {
					super.close();
					// Cleanup - delete the temporary file after reading
					Files.deleteIfExists(tempFile);
				}
			};
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to create temporary file or write to it: " + e.getMessage(),
				"Failed to create temporary file or write to it",
				e
			);
		}
	}

	@Override
	public void deleteFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException {
		this.evitaClient.assertActive();

		final GrpcDeleteFileToFetchResponse response = executeWithEvitaService(
			this.evitaManagementServiceFutureStub,
			evitaService -> {
				final Timeout timeoutToUse = this.evitaClient.timeout.get().peek();
				return evitaService.deleteFile(
					GrpcDeleteFileToFetchRequest.newBuilder()
						.setFileId(toGrpcUuid(fileId))
						.build()
				).get(timeoutToUse.timeout(), timeoutToUse.timeoutUnit());
			}
		);

		if (!response.getSuccess()) {
			throw new FileForFetchNotFoundException(fileId);
		}
	}

	@Nonnull
	@Override
	public SystemStatus getSystemStatus() {
		this.evitaClient.assertActive();

		return executeWithEvitaService(
			this.evitaManagementServiceFutureStub,
			evitaService -> {
				final Timeout timeoutToUse = this.evitaClient.timeout.get().peek();
				final GrpcEvitaServerStatusResponse response = evitaService.serverStatus(Empty.newBuilder().build())
					.get(timeoutToUse.timeout(), timeoutToUse.timeoutUnit());
				return new SystemStatus(
					response.getVersion(),
					EvitaDataTypesConverter.toOffsetDateTime(response.getStartedAt()),
					Duration.of(response.getUptime(), ChronoUnit.SECONDS),
					response.getInstanceId(),
					response.getCatalogsCorrupted(),
					response.getCatalogsOk()
				);
			}
		);
	}

	@Nonnull
	@Override
	public String getConfiguration() {
		this.evitaClient.assertActive();

		return executeWithEvitaService(
			this.evitaManagementServiceFutureStub,
			evitaService -> {
				final Timeout timeoutToUse = this.evitaClient.timeout.get().peek();
				final GrpcEvitaConfigurationResponse response = evitaService.getConfiguration(Empty.newBuilder().build())
					.get(timeoutToUse.timeout(), timeoutToUse.timeoutUnit());
				return response.getConfiguration();
			}
		);
	}

	@Override
	public void close() {
		this.clientTaskTracker.close();
	}

	/**
	 * Creates a new client task. If the task is not yet completed (finished or failed), it is added to the queue of
	 * tracked tasks and its status is updated in the background, so that the {@link Task#getFutureResult()} is completed
	 * when the task is finished.
	 *
	 * @param taskStatus the status of the task to be tracked
	 * @return the client task that is tracking the status of the task
	 * @param <S> the type of the settings of the task
	 * @param <T> the type of the result of the task
	 */
	@Nonnull
	public <S, T> ClientTask<S, T> createTask(@Nonnull TaskStatus<S, T> taskStatus) {
		return this.clientTaskTracker.createTask(taskStatus);
	}

}
