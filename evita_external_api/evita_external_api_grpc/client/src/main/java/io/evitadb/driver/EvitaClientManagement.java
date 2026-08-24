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

package io.evitadb.driver;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.google.protobuf.Empty;
import com.google.protobuf.StringValue;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import io.evitadb.driver.EvitaClientChannel.TimeoutTier;
import io.evitadb.api.EvitaManagementContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.system.EngineSettings;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.api.statistics.CatalogStatistics;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.IndexDetail;
import io.evitadb.api.statistics.EntityCollectionStatistics;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.driver.exception.EvitaClientServerCallException;
import io.evitadb.driver.exception.EvitaClientTimedOutException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.EvitaManagementServiceGrpc.EvitaManagementServiceFutureStub;
import io.evitadb.externalApi.grpc.generated.EvitaManagementServiceGrpc.EvitaManagementServiceStub;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest.Builder;
import io.evitadb.externalApi.grpc.requestResponse.CatalogStatisticsConverter;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.ConflictResolutionConverter;
import io.evitadb.function.Functions;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcUuid;

/**
 * Client implementation of {@link EvitaManagementContract}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class EvitaClientManagement implements EvitaManagementContract, Closeable {
	/**
	 * Size of a single chunk uploaded by {@link #restoreCatalog(String, long, InputStream)}.
	 *
	 * Each chunk travels as its own unary request, so the binding constraint is the server's
	 * `maxRequestLength` - evitaDB wires that to `api.maxEntitySizeInBytes`, whose default is 2 MB.
	 * 512 KB leaves generous room for the protobuf envelope underneath that default while cutting the
	 * number of round trips eightfold against the 64 KB the client-streaming upload used. Nothing here
	 * can discover the server's actual setting, so an operator who lowers `maxEntitySizeInBytes` below
	 * this value has to raise it back above the chunk size.
	 */
	private static final int RESTORE_CHUNK_SIZE = 524_288;

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
	/**
	 * Stub used to upload a catalog backup chunk by chunk.
	 *
	 * Deliberately built on the **streaming** channel even though `RestoreCatalogUnary` is a unary call.
	 * The unary channel carries the retry decorator, and with `retry` enabled that rule set replays on
	 * timeouts and on 503/504/UNKNOWN. Replaying a chunk would append it to the uploaded archive a second
	 * time - the server notices the overshoot and fails the restore, so the damage is a spurious failure
	 * rather than a corrupted catalog, but appending is not idempotent and has no business running on a
	 * channel that replays.
	 */
	private final EvitaManagementServiceFutureStub evitaManagementServiceUploadStub;
	/**
	 * Deadline tier of {@link #evitaManagementServiceStub}, taken from the channel it was built on.
	 */
	private final TimeoutTier streamingStubTier;
	/**
	 * Deadline tier of {@link #evitaManagementServiceFutureStub}, taken from the channel it was built on.
	 */
	private final TimeoutTier unaryStubTier;
	/**
	 * Deadline tier of {@link #evitaManagementServiceUploadStub}, taken from the channel it was built on.
	 */
	private final TimeoutTier uploadStubTier;

	/**
	 * Creates the management facade.
	 *
	 * The two channels are **not** interchangeable: {@link EvitaClientChannel.Streaming} carries no retry
	 * decorator, because a retrying client freezes the response-timeout budget at call start and would cap
	 * long-lived management streams (backup, restore, task progress). Building
	 * {@link EvitaManagementServiceStub} from the unary channel would reintroduce that cap — which is why the
	 * two are distinct types and the mistake no longer compiles. See issue #1388.
	 *
	 * @param evitaClient      the owning client
	 * @param unaryChannel     channel for unary stubs, carrying the retry decorator
	 * @param streamingChannel channel for streaming stubs, deliberately *without* the retry decorator
	 */
	public EvitaClientManagement(
		@Nonnull EvitaClient evitaClient,
		@Nonnull EvitaClientChannel.Unary unaryChannel,
		@Nonnull EvitaClientChannel.Streaming streamingChannel
	) {
		this.evitaClient = evitaClient;
		this.clientTaskTracker = new ClientTaskTracker(
			this,
			evitaClient.getConfiguration().trackedTaskLimit(),
			2000
		);
		this.evitaManagementServiceStub = streamingChannel.stub(EvitaManagementServiceStub.class);
		this.evitaManagementServiceFutureStub = unaryChannel.stub(EvitaManagementServiceFutureStub.class);
		this.evitaManagementServiceUploadStub = streamingChannel.stub(EvitaManagementServiceFutureStub.class);
		// each stub is budgeted from the tier its own channel carries, paired here - the one place where
		// both are in scope - so that no method below can pick a tier at all, let alone the wrong one
		this.streamingStubTier = streamingChannel.timeoutTier();
		this.unaryStubTier = unaryChannel.timeoutTier();
		this.uploadStubTier = streamingChannel.timeoutTier();
	}

	@Nonnull
	@Override
	public CompletableFuture<FileForFetch> backupCatalog(
		@Nonnull String catalogName,
		@Nullable OffsetDateTime pastMoment,
		@Nullable Long catalogVersion,
		boolean includingWAL
	) throws TemporalDataNotAvailableException {
		this.evitaClient.assertActive();
		try (final EvitaSessionContract session = this.evitaClient.createReadWriteSession(catalogName)) {
			final Task<?, FileForFetch> resultTask = session.backupCatalog(pastMoment, catalogVersion, includingWAL);
			return resultTask.getFutureResult();
		}
	}

	@Nonnull
	@Override
	public CompletableFuture<FileForFetch> fullBackupCatalog(@Nonnull String catalogName) {
		this.evitaClient.assertActive();
		try (final EvitaSessionContract session = this.evitaClient.createReadWriteSession(catalogName)) {
			final Task<?, FileForFetch> resultTask = session.fullBackupCatalog();
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

		// Uploaded through the chunked unary RPC rather than the client-streaming one, deliberately.
		// A client-streaming upload is a *single* HTTP request, so the server's `maxRequestLength` -
		// which evitaDB wires to `api.maxEntitySizeInBytes`, 2 MB by default - caps the whole backup;
		// anything larger died part-way through with a bare RESOURCE_EXHAUSTED, which made the streaming
		// variant unusable for a catalog of any real size. With the unary variant every chunk is its own
		// request, so only the chunk has to fit under that limit. The server accumulates the chunks into
		// one temporary file keyed by the `fileId` it echoes back in the first response, and submits the
		// restoration task once the accumulated size reaches `totalBytesExpected`.
		final byte[] buffer = new byte[RESTORE_CHUNK_SIZE];
		GrpcTaskStatus restorationTask = null;
		GrpcUuid uploadFileId = null;
		long bytesSent = 0L;
		try (inputStream) {
			int bytesRead;
			while ((bytesRead = inputStream.read(buffer)) != -1) {
				final GrpcRestoreCatalogUnaryRequest.Builder requestBuilder = GrpcRestoreCatalogUnaryRequest
					.newBuilder()
					.setCatalogName(catalogName)
					.setTotalSizeInBytes(totalBytesExpected)
					.setBackupFile(ByteString.copyFrom(buffer, 0, bytesRead));
				// every chunk but the first names the upload it belongs to
				if (uploadFileId != null) {
					requestBuilder.setFileId(uploadFileId);
				}
				final GrpcRestoreCatalogUnaryRequest request = requestBuilder.build();
				final GrpcRestoreCatalogUnaryResponse response = executeWithEvitaUploadService(
					evitaService -> evitaService.restoreCatalogUnary(request)
				);
				restorationTask = response.getTask();
				if (uploadFileId == null) {
					// the response's own `fileId` is the documented handle for the upload - the id on the
					// task's file descriptor is not populated on the first chunk
					uploadFileId = response.getFileId();
				}
				bytesSent += bytesRead;
			}
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to read the backup file being restored: " + e.getMessage(),
				"Failed to read the backup file being restored.",
				e
			);
		}

		if (restorationTask == null) {
			throw new UnexpectedIOException(
				"The backup file being restored contains no data.",
				"The backup file being restored contains no data."
			);
		}
		// the server submits the restoration task only once the uploaded size reaches the announced one,
		// so a mismatch here would otherwise hand back a task that is never going to run
		if (bytesSent != totalBytesExpected) {
			throw new UnexpectedIOException(
				"Number of bytes uploaded during catalog restoration does not match the announced size " +
					"(announced " + totalBytesExpected + ", uploaded " + bytesSent + ")!",
				"Number of bytes uploaded during catalog restoration does not match the announced size!"
			);
		}

		//noinspection unchecked
		return (Task<?, Void>) this.clientTaskTracker.createTask(
			EvitaDataTypesConverter.toTaskStatus(restorationTask)
		);
	}

	@Nonnull
	@Override
	public Task<?, Void> restoreCatalog(@Nonnull String catalogName, @Nonnull UUID fileId) throws FileForFetchNotFoundException {
		this.evitaClient.assertActive();

		final GrpcRestoreCatalogFromServerFileRequest request = GrpcRestoreCatalogFromServerFileRequest.newBuilder()
			.setFileId(toGrpcUuid(fileId))
			.setCatalogName(catalogName)
			.build();
		final GrpcRestoreCatalogResponse response = executeWithEvitaService(
			evitaService -> evitaService.restoreCatalogFromServerFile(request)
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
		final GrpcTaskStatusesRequest request = builder.build();

		final GrpcTaskStatusesResponse response = executeWithEvitaService(
			evitaService -> evitaService.listTaskStatuses(request)
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

		final GrpcTaskStatusRequest request = GrpcTaskStatusRequest.newBuilder()
			.setTaskId(toGrpcUuid(jobId))
			.build();
		final GrpcTaskStatusResponse response = executeWithEvitaService(
			evitaService -> evitaService.getTaskStatus(request)
		);

		return response.hasTaskStatus() ?
			Optional.of(EvitaDataTypesConverter.toTaskStatus(response.getTaskStatus())) : Optional.empty();
	}

	@Nonnull
	@Override
	public Collection<TaskStatus<?, ?>> getTaskStatuses(@Nonnull UUID... jobId) {
		this.evitaClient.assertActive();

		final Builder builder = GrpcSpecifiedTaskStatusesRequest.newBuilder();
		for (UUID id : jobId) {
			builder.addTaskIds(toGrpcUuid(id));
		}
		final GrpcSpecifiedTaskStatusesRequest request = builder.build();
		final GrpcSpecifiedTaskStatusesResponse response = executeWithEvitaService(
			evitaService -> evitaService.getTaskStatuses(request)
		);

		return response.getTaskStatusList()
			.stream()
			.map(EvitaDataTypesConverter::toTaskStatus)
			.collect(Collectors.toCollection(ArrayList::new));
	}

	@Override
	public boolean cancelTask(@Nonnull UUID jobId) {
		this.evitaClient.assertActive();

		final GrpcCancelTaskRequest request = GrpcCancelTaskRequest.newBuilder()
			.setTaskId(toGrpcUuid(jobId))
			.build();
		final GrpcCancelTaskResponse response = executeWithEvitaService(
			evitaService -> evitaService.cancelTask(
				request
			)
		);

		return response.getSuccess();
	}

	@Nonnull
	@Override
	public PaginatedList<FileForFetch> listFilesToFetch(int page, int pageSize, @Nonnull Set<String> origin) {
		this.evitaClient.assertActive();

		final GrpcFilesToFetchRequest.Builder requestBuilder = GrpcFilesToFetchRequest.newBuilder()
			.setPageNumber(page)
			.setPageSize(pageSize);
		for (String theOrigin : origin) {
			requestBuilder.addOrigin(StringValue.of(theOrigin));
		}

		final GrpcFilesToFetchResponse response = executeWithEvitaService(
			evitaService -> evitaService.listFilesToFetch(requestBuilder.build())
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

		final GrpcFileToFetchRequest request = GrpcFileToFetchRequest.newBuilder()
			.setFileId(toGrpcUuid(fileId))
			.build();
		final GrpcFileToFetchResponse response = executeWithEvitaService(
			evitaService -> evitaService.getFileToFetch(request)
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
			// A download is deadlined per message, not per call - see `TimeoutTier#PER_MESSAGE`. Both
			// clocks below are driven from this one stamp: the transport's response timeout, re-armed in
			// `onNext`, and the caller's own wait, which is recomputed from it rather than fixed.
			final Timeout stallTimeout = this.evitaClient.resolveTimeout(this.streamingStubTier);
			final AtomicLong lastProgressNanos = new AtomicLong(System.nanoTime());

			// Download the file asynchronously
			executeWithEvitaBlockingService(
				evitaService -> {
					evitaService.fetchFile(
						GrpcFetchFileRequest.newBuilder().setFileId(toGrpcUuid(fileId)).build(),
						new StreamObserver<>() {
							@Override
							public void onNext(GrpcFetchFileResponse response) {
								try {
									// Write chunks to the temporary file
									Files.write(tempFile, response.getFileContents().toByteArray(), StandardOpenOption.APPEND);
									lastProgressNanos.set(System.nanoTime());
									// Roll the transport deadline forward: without this the response
									// timeout bounds the *whole* download, so a backup simply larger than
									// the link is fast enough to move in one window can never arrive, no
									// matter how healthily it is progressing. Same re-arm the session's
									// streaming observers do.
									ClientRequestContext.current().setResponseTimeout(
										TimeoutMode.SET_FROM_NOW,
										Duration.of(
											stallTimeout.timeout(),
											stallTimeout.timeoutUnit().toChronoUnit()
										)
									);
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

			// Wait for the download to stop making progress, rather than for it to fit in a fixed budget.
			// A plain `get(stallTimeout)` here would reintroduce the whole-call cap the tier and the
			// per-message re-arm above just removed - it would simply cap the download at a larger number.
			try {
				awaitDownloadProgress(downloadFuture, lastProgressNanos, stallTimeout);
			} catch (TimeoutException e) {
				downloadFuture.cancel(true);
				throw new EvitaClientTimedOutException(stallTimeout.timeout(), stallTimeout.timeoutUnit());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new EvitaClientServerCallException("File download interrupted.", e);
			} catch (ExecutionException e) {
				throw EvitaClient.transformException(
					e.getCause() == null ? e : e.getCause(),
					Functions.noOpRunnable()
				);
			}

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

		final GrpcDeleteFileToFetchRequest request = GrpcDeleteFileToFetchRequest.newBuilder()
			.setFileId(toGrpcUuid(fileId))
			.build();
		final GrpcDeleteFileToFetchResponse response = executeWithEvitaService(
			evitaService -> evitaService.deleteFile(request)
		);

		if (!response.getSuccess()) {
			throw new FileForFetchNotFoundException(fileId);
		}
	}

	@Nonnull
	@Override
	public SystemStatus getSystemStatus() {
		this.evitaClient.assertActive();

		final GrpcEvitaServerStatusResponse response = executeWithEvitaService(
			evitaService -> evitaService.serverStatus(Empty.newBuilder().build())
		);

		return new SystemStatus(
			response.getVersion(),
			EvitaDataTypesConverter.toOffsetDateTime(response.getStartedAt()),
			response.getEngineVersion(),
			EvitaDataTypesConverter.toOffsetDateTime(response.getIntroducedAt()),
			Duration.of(response.getUptime(), ChronoUnit.SECONDS),
			response.getInstanceId(),
			response.getCatalogsCorrupted(),
			response.getCatalogsActive(),
			response.getCatalogsInactive()
		);
	}

	@Nonnull
	@Override
	public String getConfiguration() {
		this.evitaClient.assertActive();

		final GrpcEvitaConfigurationResponse response = executeWithEvitaService(
			evitaService -> evitaService.getConfiguration(Empty.newBuilder().build())
		);

		return response.getConfiguration();
	}

	@Nonnull
	@Override
	public EngineSettings getEngineSettings() {
		this.evitaClient.assertActive();

		final GrpcEvitaEngineSettingsResponse response = executeWithEvitaService(
			evitaService -> evitaService.getEngineSettings(Empty.newBuilder().build())
		);

		// an absent conflict resolution would silently decode to the zero enum value - i.e. "no
		// conflict detection at all", the most permissive reading - so refuse it instead of guessing
		if (!response.hasConflictResolution()) {
			throw new GenericEvitaInternalError(
				"Server returned engine settings without the conflict resolution."
			);
		}

		return new EngineSettings(
			ConflictResolutionConverter.toConflictResolution(response.getConflictResolution()),
			response.getTimeTravelEnabled(),
			response.getChangeDataCaptureEnabled(),
			response.getTrafficRecordingEnabled(),
			response.getQueryCacheEnabled()
		);
	}

	@Nonnull
	@Override
	public CatalogStatistics getCatalogStatistics(
		@Nonnull String catalogName,
		@Nonnull Set<CatalogStatisticsComponent> components
	) {
		this.evitaClient.assertActive();

		final GrpcCatalogStatisticsSnapshotResponse response = executeWithEvitaService(
			evitaService -> evitaService.getCatalogStatisticsSnapshot(
				GrpcCatalogStatisticsSnapshotRequest.newBuilder()
					.setCatalogName(catalogName)
					.addAllComponents(CatalogStatisticsConverter.toGrpcComponents(components))
					.build()
			)
		);

		// an absent envelope is not an empty catalog. Unwrapping it would yield a statistics object with a blank name,
		// version 0 and no component statuses at all - which reads as "everything you asked for, and it was all zero"
		// rather than "the server sent nothing", and is precisely the silent success the component model exists to
		// rule out at the level of the individual component
		if (!response.hasCatalogStatistics()) {
			throw new GenericEvitaInternalError(
				"Server returned no catalog statistics for catalog `" + catalogName + "`."
			);
		}
		return CatalogStatisticsConverter.toCatalogStatistics(response.getCatalogStatistics());
	}

	@Nonnull
	@Override
	public Collection<CatalogStatistics> getAllCatalogStatistics(
		@Nonnull Set<CatalogStatisticsComponent> components
	) {
		this.evitaClient.assertActive();

		final GrpcAllCatalogStatisticsSnapshotResponse response = executeWithEvitaService(
			evitaService -> evitaService.getAllCatalogStatisticsSnapshots(
				GrpcAllCatalogStatisticsSnapshotRequest.newBuilder()
					.addAllComponents(CatalogStatisticsConverter.toGrpcComponents(components))
					.build()
			)
		);

		final List<GrpcCatalogStatisticsSnapshot> snapshots = response.getCatalogStatisticsList();
		final List<CatalogStatistics> statistics = new ArrayList<>(snapshots.size());
		for (final GrpcCatalogStatisticsSnapshot snapshot : snapshots) {
			// the server already ordered them by catalog name - re-sorting here would only risk disagreeing with it
			statistics.add(CatalogStatisticsConverter.toCatalogStatistics(snapshot));
		}
		return statistics;
	}

	@Nonnull
	@Override
	public EntityCollectionStatistics getEntityCollectionStatistics(
		@Nonnull String catalogName,
		@Nonnull String entityType,
		@Nonnull Set<CatalogStatisticsComponent> components
	) {
		this.evitaClient.assertActive();

		final GrpcEntityCollectionStatisticsSnapshotResponse response = executeWithEvitaService(
			evitaService -> evitaService.getEntityCollectionStatisticsSnapshot(
				GrpcEntityCollectionStatisticsSnapshotRequest.newBuilder()
					.setCatalogName(catalogName)
					.setEntityType(entityType)
					.addAllComponents(CatalogStatisticsConverter.toGrpcComponents(components))
					.build()
			)
		);

		if (!response.hasEntityCollectionStatistics()) {
			throw new GenericEvitaInternalError(
				"Server returned no statistics for collection `" + entityType + "` of catalog `" + catalogName + "`."
			);
		}
		return CatalogStatisticsConverter.toEntityCollectionStatistics(
			response.getEntityCollectionStatistics()
		);
	}

	@Nonnull
	@Override
	public IndexBrowseResult browseIndexes(
		@Nonnull String catalogName,
		@Nullable String entityType,
		@Nonnull IndexBrowseCriteria criteria
	) {
		this.evitaClient.assertActive();

		final GrpcIndexBrowseRequest request = CatalogStatisticsConverter.toGrpcIndexBrowseRequest(
			catalogName, entityType, criteria
		);
		final GrpcIndexBrowseResponse response = executeWithEvitaService(
			evitaService -> evitaService.browseIndexes(request)
		);

		return CatalogStatisticsConverter.toIndexBrowseResult(response);
	}

	@Nonnull
	@Override
	public IndexDetail getIndexDetail(
		@Nonnull String catalogName,
		@Nullable String entityType,
		int indexPrimaryKey
	) {
		this.evitaClient.assertActive();

		final GrpcIndexDetailRequest.Builder requestBuilder = GrpcIndexDetailRequest.newBuilder()
			.setCatalogName(catalogName)
			.setIndexPrimaryKey(indexPrimaryKey);
		// unset addresses the catalog's own index of that handle; an empty string would name a collection that cannot
		// exist, which is why absence travels as an unset wrapper rather than as a sentinel
		if (entityType != null) {
			requestBuilder.setEntityType(StringValue.of(entityType));
		}
		final GrpcIndexDetailRequest request = requestBuilder.build();
		final GrpcIndexDetailResponse response = executeWithEvitaService(
			evitaService -> evitaService.getIndexDetail(request)
		);

		if (!response.hasIndexDetail()) {
			throw new GenericEvitaInternalError(
				"Server returned no detail for index `" + indexPrimaryKey + "` of catalog `" + catalogName + "`."
			);
		}
		return CatalogStatisticsConverter.toIndexDetail(response.getIndexDetail());
	}

	@Nonnull
	@Override
	public List<SchemaCapabilityUsageStatistics> listCapabilityUsage(
		@Nonnull String catalogName,
		@Nullable String entityType
	) {
		this.evitaClient.assertActive();

		final GrpcSchemaCapabilityUsageRequest.Builder requestBuilder = GrpcSchemaCapabilityUsageRequest.newBuilder()
			.setCatalogName(catalogName);
		// unset reports what the catalog schema declares itself; an empty string would name a collection that cannot
		// exist, which is why absence travels as an unset wrapper rather than as a sentinel
		if (entityType != null) {
			requestBuilder.setEntityType(StringValue.of(entityType));
		}
		final GrpcSchemaCapabilityUsageRequest request = requestBuilder.build();
		final GrpcSchemaCapabilityUsageResponse response = executeWithEvitaService(
			evitaService -> evitaService.listSchemaCapabilityUsage(request)
		);

		return CatalogStatisticsConverter.toSchemaCapabilityUsages(response);
	}

	@Override
	public void close() {
		this.clientTaskTracker.close();
	}

	/**
	 * Waits for a streamed download to finish, giving up only once it has made **no progress** for
	 * `stallTimeout` - not once it has taken `stallTimeout` in total.
	 *
	 * The distinction is the whole point of {@link TimeoutTier#PER_MESSAGE}: a download's duration is a
	 * function of the file's size and the link's speed, neither of which the client knows, so any fixed
	 * budget is a guess that a large enough backup will always lose. What can be bounded is silence.
	 *
	 * Waiting in recomputed slices, rather than simply blocking forever and trusting the transport to
	 * fail the future, is deliberate: it keeps a caller-side bound on a hang that never produces a
	 * terminal event. A spurious timeout is recoverable, a permanently parked application thread is not.
	 *
	 * @param downloadFuture    future completed by the stream's terminal event
	 * @param lastProgressNanos `System.nanoTime()` stamp of the most recently received message, updated
	 *                          by the observer as the download proceeds
	 * @param stallTimeout      how long the stream may stay silent before it is considered dead
	 * @throws TimeoutException     when nothing arrived for the whole `stallTimeout`
	 * @throws InterruptedException when the waiting thread is interrupted
	 * @throws ExecutionException   when the download itself failed
	 */
	private static void awaitDownloadProgress(
		@Nonnull CompletableFuture<Void> downloadFuture,
		@Nonnull AtomicLong lastProgressNanos,
		@Nonnull Timeout stallTimeout
	) throws TimeoutException, InterruptedException, ExecutionException {
		final long stallNanos = stallTimeout.timeoutUnit().toNanos(stallTimeout.timeout());
		while (true) {
			final long remainingNanos = stallNanos - (System.nanoTime() - lastProgressNanos.get());
			if (remainingNanos <= 0L) {
				throw new TimeoutException();
			}
			try {
				downloadFuture.get(remainingNanos, TimeUnit.NANOSECONDS);
				return;
			} catch (TimeoutException e) {
				// the window elapsed - but a message may have landed while we waited, which moves
				// `lastProgressNanos` and makes the recomputed window positive again. Only a genuinely
				// silent stream leaves it non-positive and exits above.
			}
		}
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

	/**
	 * Method that is called within the {@link EvitaClientSession} to apply the wanted logic on a channel retrieved
	 * from a channel pool.
	 *
	 * @param lambda function that holds a logic passed by the caller
	 * @param <T>    return type of the function
	 * @return result of the applied function
	 */
	private <T> T executeWithEvitaBlockingService(
		@Nonnull AsyncCallFunction<EvitaManagementServiceStub, T> lambda
	) {
		final Timeout timeout = this.evitaClient.resolveTimeout(this.streamingStubTier);
		try {
			return lambda.apply(
				this.evitaManagementServiceStub.withDeadlineAfter(timeout.timeout(), timeout.timeoutUnit())
			);
		} catch (ExecutionException e) {
			throw EvitaClient.transformException(
				e.getCause() == null ? e : e.getCause(),
				Functions.noOpRunnable()
			);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new EvitaClientServerCallException("Server call interrupted.", e);
		} catch (TimeoutException e) {
			throw new EvitaClientTimedOutException(
				timeout.timeout(), timeout.timeoutUnit()
			);
		}
	}

	/**
	 * Method that is called within the {@link EvitaClientSession} to apply the wanted logic on a channel retrieved
	 * from a channel pool.
	 *
	 * @param lambda function that holds a logic passed by the caller
	 * @param <T>    return type of the function
	 * @return result of the applied function
	 */
	private <T> T executeWithEvitaService(
		@Nonnull AsyncCallFunction<EvitaManagementServiceFutureStub, ListenableFuture<T>> lambda
	) {
		return executeWithStub(this.evitaManagementServiceFutureStub, this.unaryStubTier, lambda);
	}

	/**
	 * Runs a unary call on the non-retrying {@link #evitaManagementServiceUploadStub} - see that field
	 * for why an append must not travel on a channel that replays.
	 *
	 * @param lambda function that holds a logic passed by the caller
	 * @param <T>    return type of the function
	 * @return result of the applied function
	 */
	private <T> T executeWithEvitaUploadService(
		@Nonnull AsyncCallFunction<EvitaManagementServiceFutureStub, ListenableFuture<T>> lambda
	) {
		return executeWithStub(this.evitaManagementServiceUploadStub, this.uploadStubTier, lambda);
	}

	/**
	 * Applies the passed logic on the given stub under the timeout its channel's tier resolves to.
	 *
	 * @param stub   stub to issue the call on
	 * @param tier   deadline tier of that stub, i.e. the one its channel carries
	 * @param lambda function that holds a logic passed by the caller
	 * @param <T>    return type of the function
	 * @return result of the applied function
	 */
	private <T> T executeWithStub(
		@Nonnull EvitaManagementServiceFutureStub stub,
		@Nonnull TimeoutTier tier,
		@Nonnull AsyncCallFunction<EvitaManagementServiceFutureStub, ListenableFuture<T>> lambda
	) {
		final Timeout timeout = this.evitaClient.resolveTimeout(tier);
		try {
			return lambda.apply(stub.withDeadlineAfter(timeout.timeout(), timeout.timeoutUnit()))
				.get(timeout.timeout(), timeout.timeoutUnit());
		} catch (ExecutionException e) {
			throw EvitaClient.transformException(
				e.getCause() == null ? e : e.getCause(),
				Functions.noOpRunnable()
			);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new EvitaClientServerCallException("Server call interrupted.", e);
		} catch (TimeoutException e) {
			throw new EvitaClientTimedOutException(
				timeout.timeout(), timeout.timeoutUnit()
			);
		}
	}

}
