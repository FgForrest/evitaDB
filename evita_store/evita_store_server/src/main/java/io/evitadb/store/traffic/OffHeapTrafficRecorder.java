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

package io.evitadb.store.traffic;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.TrafficRecordingReader;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.trafficRecording.EntityEnrichmentContainer;
import io.evitadb.api.requestResponse.trafficRecording.EntityFetchContainer;
import io.evitadb.api.requestResponse.trafficRecording.MutationContainer;
import io.evitadb.api.requestResponse.trafficRecording.QueryContainer;
import io.evitadb.api.requestResponse.trafficRecording.SessionCloseContainer;
import io.evitadb.api.requestResponse.trafficRecording.SessionStartContainer;
import io.evitadb.api.requestResponse.trafficRecording.SourceQueryContainer;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.core.async.DelayedAsyncTask;
import io.evitadb.core.async.Scheduler;
import io.evitadb.core.file.ExportFileService;
import io.evitadb.core.traffic.TrafficRecorder;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.offsetIndex.stream.RandomAccessFileInputStream;
import io.evitadb.store.query.QuerySerializationKryoConfigurer;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.traffic.event.TrafficRecorderStatisticsEvent;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.io.Serial;
import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.PrimitiveIterator.OfInt;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Implementation of the {@link TrafficRecorder} that stores traffic data in off-heap memory in different memory blocks
 * assigned to each session according to their sizes. When the session is finished, all the memory blocks are written to
 * the disk buffer and the memory is freed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class OffHeapTrafficRecorder implements TrafficRecorder, TrafficRecordingReader, Closeable {
	/**
	 * Constant that defines the duration of inactivity after which the disk buffer index is released.
	 */
	private static final long INDEX_INACTIVITY_DURATION = 600_000L;
	/**
	 * Size of a single memory slot used for storing queries and mutations.
	 */
	private final int blockSizeBytes;
	/**
	 * Pool of Kryo instances used for serialization of traffic data.
	 */
	private final Pool<Kryo> offHeapTrafficRecorderKryoPool = new Pool<>(true, true) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(
				WalKryoConfigurer.INSTANCE
					.andThen(QuerySerializationKryoConfigurer.INSTANCE)
					.andThen(TrafficRecordingSerializationKryoConfigurer.INSTANCE)
			);
		}
	};
	/**
	 * Private final variable to store a reference to a ByteBuffer object.
	 * The AtomicReference class is used to provide thread-safe access to the memoryBlock.
	 */
	private final AtomicReference<ByteBuffer> memoryBlock = new AtomicReference<>();
	/**
	 * Map contains all tracked sessions and their traffic data indexed by session ID.
	 */
	private final Map<UUID, SessionTraffic> trackedSessionsIndex = new ConcurrentHashMap<>(256);
	/**
	 * Queue of all sessions that were finished and are waiting to be written to disk buffer.
	 */
	private final Queue<SessionTraffic> finalizedSessions = new ConcurrentLinkedQueue<>();
	/**
	 * Counter of records used in sampling calculation.
	 */
	private final AtomicLong recordedRecords = new AtomicLong();
	/**
	 * Counter of missed records due to memory shortage or sampling.
	 */
	private final AtomicLong missedRecords = new AtomicLong();
	/**
	 * Counter of dropped sessions due to memory shortage.
	 */
	private final AtomicLong droppedSessions = new AtomicLong();
	/**
	 * Counter of created sessions.
	 */
	private final AtomicLong createdSessions = new AtomicLong();
	/**
	 * Counter of finished sessions.
	 */
	private final AtomicLong finishedSessions = new AtomicLong();
	/**
	 * Pool of byte arrays used for storing output data and reading input data.
	 */
	private Pool<byte[]> copyBufferPool;
	/**
	 * Reference to the export file service used for creating temporary files and creating export files.
	 */
	private ExportFileService exportFileService;
	/**
	 * Queue contains indexes of free blocks available for usage.
	 */
	private Queue<Integer> freeBlocks;
	/**
	 * Ring buffer used for storing traffic data when they are completed in the memory buffer.
	 */
	private DiskRingBuffer diskBuffer;
	/**
	 * The name of the catalog this traffic recorder is associated with.
	 */
	private String catalogName;
	/**
	 * Sampling percentage used to determine how many records are stored in the memory buffer from 0 to 99.
	 * Zero means that all records are stored, 99 means that almost no records are stored.
	 */
	private int samplingPercentage;
	/**
	 * Contains reference to the asynchronous task executor that clears finalized session memory blocks and writes
	 * them to disk buffer.
	 */
	private DelayedAsyncTask freeMemoryTask;
	/**
	 * Last time when the data from the {@link #diskBuffer} was read.
	 */
	private long lastRead = -1;

	public OffHeapTrafficRecorder() {
		this(16_384);
	}

	public OffHeapTrafficRecorder(int blockSizeBytes) {
		this.blockSizeBytes = blockSizeBytes;
	}

	@Override
	public void init(
		@Nonnull String catalogName,
		@Nonnull ExportFileService exportFileService,
		@Nonnull Scheduler scheduler,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TrafficRecordingOptions recordingOptions
	) {
		this.catalogName = catalogName;
		this.exportFileService = exportFileService;
		this.samplingPercentage = recordingOptions.trafficSamplingPercentage();

		final long trafficMemoryBufferSizeInBytes = recordingOptions.trafficMemoryBufferSizeInBytes();
		Assert.isPremiseValid(
			trafficMemoryBufferSizeInBytes > 0,
			"Traffic memory buffer size must be greater than 0."
		);
		// align the buffer size to be divisible by 16KB page size
		final int capacity = (int) (trafficMemoryBufferSizeInBytes - (trafficMemoryBufferSizeInBytes % this.blockSizeBytes));
		this.memoryBlock.set(ByteBuffer.allocateDirect(capacity));
		final int blockCount = capacity / this.blockSizeBytes;
		// initialize free blocks queue, all blocks are free at the beginning
		this.freeBlocks = new ArrayBlockingQueue<>(blockCount, true);
		// initialize observable outputs for each memory block
		for (int i = 0; i < blockCount; i++) {
			this.freeBlocks.offer(i);
		}
		// create ring buffer on disk
		this.diskBuffer = new DiskRingBuffer(
			exportFileService.createManagedTempFile("traffic-recording-buffer.bin"),
			recordingOptions.trafficDiskBufferSizeInBytes()
		);

		this.freeMemoryTask = new DelayedAsyncTask(
			this.catalogName, "Traffic recorder - memory buffer cleanup", scheduler,
			this::freeMemory, 60, TimeUnit.SECONDS, 10
		);

		this.copyBufferPool = new Pool<>(true, true) {
			@Override
			protected byte[] create() {
				return new byte[storageOptions.outputBufferSize()];
			}
		};
	}

	@Override
	public void createSession(@Nonnull UUID sessionId, long catalogVersion, @Nonnull OffsetDateTime created) {
		final long recorded = this.recordedRecords.get();
		final long missed = this.missedRecords.get();
		final boolean record = ((double) recorded / (double) (recorded + missed) * 100.0) < this.samplingPercentage;

		if (record) {
			final SessionTraffic sessionTraffic = new SessionTraffic(
				sessionId,
				catalogVersion,
				created,
				this.copyBufferPool.obtain(),
				this::prepareStorageBlock
			);
			try {
				final StorageRecord<SessionStartContainer> sessionStartRecord = new StorageRecord<>(
					this.offHeapTrafficRecorderKryoPool.obtain(),
					sessionTraffic.getObservableOutput(),
					0L,
					false,
					new SessionStartContainer(sessionId, sessionTraffic.nextRecordingId(), catalogVersion, created)
				);
				Assert.isPremiseValid(sessionStartRecord.fileLocation() != null, "Location must not be null.");
				this.createdSessions.incrementAndGet();
				this.trackedSessionsIndex.put(sessionId, sessionTraffic);
			} catch (MemoryNotAvailableException ex) {
				this.copyBufferPool.free(ex.getWriteBuffer());
				this.droppedSessions.incrementAndGet();
				this.missedRecords.incrementAndGet();
			}
		} else {
			this.missedRecords.incrementAndGet();
		}
	}

	@Override
	public void closeSession(@Nonnull UUID sessionId) {
		final SessionTraffic sessionTraffic = this.trackedSessionsIndex.remove(sessionId);
		if (sessionTraffic != null && !sessionTraffic.isFinished()) {
			final byte[] bufferToReturn = sessionTraffic.finish();

			try {
				final StorageRecord<SessionCloseContainer> sessionCloseRecord = new StorageRecord<>(
					this.offHeapTrafficRecorderKryoPool.obtain(),
					sessionTraffic.getObservableOutput(),
					0L,
					true,
					new SessionCloseContainer(
						sessionId,
						sessionTraffic.nextRecordingId(),
						sessionTraffic.getCatalogVersion(),
						sessionTraffic.getCreated(),
						sessionTraffic.getDurationInMillis(),
						sessionTraffic.getFetchCount(),
						sessionTraffic.getBytesFetchedTotal(),
						sessionTraffic.getRecordCount(),
						sessionTraffic.getRecordsMissedOut(),
						sessionTraffic.getQueryCount(),
						sessionTraffic.getEntityFetchCount(),
						sessionTraffic.getMutationCount()
					)
				);
				Assert.isPremiseValid(sessionCloseRecord.fileLocation() != null, "Location must not be null.");
			} catch (MemoryNotAvailableException ex) {
				this.droppedSessions.incrementAndGet();
				this.missedRecords.incrementAndGet();
			} finally {
				this.copyBufferPool.free(bufferToReturn);
				this.finishedSessions.incrementAndGet();
				this.finalizedSessions.offer(sessionTraffic);
				this.freeMemoryTask.schedule();
			}
		} else {
			this.missedRecords.incrementAndGet();
		}
	}

	@Override
	public void recordQuery(
		@Nonnull UUID sessionId,
		@Nonnull Query query,
		@Nonnull Label[] labels,
		@Nonnull OffsetDateTime now,
		int totalRecordCount,
		int ioFetchCount,
		int ioFetchedSizeBytes,
		@Nonnull int... primaryKeys
	) {
		record(
			this.trackedSessionsIndex.get(sessionId),
			sessionTraffic -> new QueryContainer(
				sessionId,
				sessionTraffic.nextRecordingId(),
				query,
				labels.length == 0 ?
					QueryContainer.Label.EMPTY_LABELS :
					Arrays.stream(labels)
						.map(label -> new QueryContainer.Label(label.getLabelName(), label.getLabelValue()))
						.toArray(QueryContainer.Label[]::new),
				now, (int) (System.currentTimeMillis() - now.toInstant().toEpochMilli()),
				totalRecordCount, ioFetchCount, ioFetchedSizeBytes, primaryKeys
			)
		);
	}

	@Override
	public void recordFetch(
		@Nonnull UUID sessionId,
		@Nonnull Query query,
		@Nonnull OffsetDateTime now,
		int ioFetchCount,
		int ioFetchedSizeBytes,
		int primaryKey
	) {
		record(
			this.trackedSessionsIndex.get(sessionId),
			sessionTraffic -> new EntityFetchContainer(
				sessionId,
				sessionTraffic.nextRecordingId(),
				query,
				now,
				(int) (System.currentTimeMillis() - now.toInstant().toEpochMilli()),
				ioFetchCount, ioFetchedSizeBytes, primaryKey
			)
		);
	}

	@Override
	public void recordEnrichment(
		@Nonnull UUID sessionId,
		@Nonnull Query query,
		@Nonnull OffsetDateTime now,
		int ioFetchCount,
		int ioFetchedSizeBytes,
		int primaryKey
	) {
		record(
			this.trackedSessionsIndex.get(sessionId),
			sessionTraffic -> new EntityEnrichmentContainer(
				sessionId,
				sessionTraffic.nextRecordingId(),
				query,
				now,
				(int) (System.currentTimeMillis() - now.toInstant().toEpochMilli()),
				ioFetchCount, ioFetchedSizeBytes, primaryKey
			)
		);
	}

	@Override
	public void recordMutation(
		@Nonnull UUID sessionId,
		@Nonnull OffsetDateTime now,
		@Nonnull Mutation mutation
	) {
		record(
			this.trackedSessionsIndex.get(sessionId),
			sessionTraffic -> new MutationContainer(
				sessionId,
				sessionTraffic.nextRecordingId(),
				now,
				(int) (System.currentTimeMillis() - now.toInstant().toEpochMilli()),
				mutation
			)
		);
	}

	@Override
	public void setupSourceQuery(
		@Nonnull UUID sessionId,
		@Nonnull UUID sourceQueryId,
		@Nonnull OffsetDateTime now,
		@Nonnull String sourceQuery,
		@Nonnull String queryType
	) {
		record(
			this.trackedSessionsIndex.get(sessionId),
			sessionTraffic
				-> {
				sessionTraffic.setupSourceQuery(sourceQueryId, now);
				return new SourceQueryContainer(
					sessionId,
					sessionTraffic.nextRecordingId(),
					sourceQueryId,
					now,
					sourceQuery,
					queryType
				);
			}
		);
	}

	@Override
	public void closeSourceQuery(
		@Nonnull UUID sessionId,
		@Nonnull UUID sourceQueryId
	) {
		record(
			this.trackedSessionsIndex.get(sessionId),
			sessionTraffic -> sessionTraffic.closeSourceQuery(sourceQueryId)
		);
	}

	@Nonnull
	@Override
	public Stream<TrafficRecording> getRecordings(@Nonnull TrafficRecordingCaptureRequest request) throws TemporalDataNotAvailableException {
		this.lastRead = System.currentTimeMillis();
		return this.diskBuffer.getSessionRecordsStream(
			request,
			this::readTrafficRecord
		);
	}

	@Override
	public void close() throws IOException {
		this.memoryBlock.set(null);
		this.trackedSessionsIndex.clear();
		this.diskBuffer.close(filePath -> this.exportFileService.purgeManagedTempFile(filePath));
	}

	/**
	 * Reads a traffic record from a specified file position.
	 *
	 * @param filePosition the position within the file to read the traffic record from
	 * @return a {@code StorageRecord} containing the traffic recording
	 */
	@Nonnull
	private StorageRecord<TrafficRecording> readTrafficRecord(long filePosition) {
		final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
			new RandomAccessFileInputStream(this.diskBuffer.getDiskBufferFile()),
			this.copyBufferPool.obtain()
		);
		input.resetToPosition(filePosition);
		return StorageRecord.read(
			this.offHeapTrafficRecorderKryoPool.obtain(),
			input,
			fl -> TrafficRecording.class
		);
	}

	/**
	 * Records traffic data for a specific session. The method tracks traffic statistics for an
	 * active session and stores records if the sampling conditions are met and if resources are
	 * available. If the session is not found, finished, or sampling conditions are not met,
	 * a missed record is counted. In cases where memory is not available, the write buffer is
	 * released and a missed record is incremented.
	 *
	 * @param sessionTraffic   the session traffic object containing the data to be recorded
	 * @param containerFactory the traffic recording container object containing the data to be recorded
	 *                         and its associated metadata
	 */
	private <T extends TrafficRecording> void record(@Nullable SessionTraffic sessionTraffic, @Nonnull Function<SessionTraffic, T> containerFactory) {
		if (sessionTraffic != null && !sessionTraffic.isFinished()) {
			try {
				final T container = containerFactory.apply(sessionTraffic);
				final StorageRecord<T> storageRecord = new StorageRecord<>(
					this.offHeapTrafficRecorderKryoPool.obtain(),
					sessionTraffic.getObservableOutput(),
					0L,
					false,
					container
				);
				Assert.isPremiseValid(storageRecord.fileLocation() != null, "Location must not be null.");
				sessionTraffic.registerRecording(container);
				this.recordedRecords.incrementAndGet();
			} catch (MemoryNotAvailableException ex) {
				this.copyBufferPool.free(ex.getWriteBuffer());
				this.missedRecords.incrementAndGet();
			}
		} else {
			if (sessionTraffic != null) {
				sessionTraffic.registerRecordMissedOut();
			}
			this.missedRecords.incrementAndGet();
		}
	}

	/**
	 * Prepares and returns a storage block for use. This method retrieves a free block ID
	 * from the pool of available blocks. If a free block ID is found, it creates a
	 * NumberedByteBuffer corresponding to that block ID, adjusting the memory slice
	 * accordingly to fit the block size. If no free block ID is available,
	 * a MemoryNotAvailableException is thrown to indicate that no storage slots are free.
	 *
	 * @return a NumberedByteBuffer corresponding to the allocated storage block, including
	 * its ID and a ByteBuffer slice adjusted to the block's size.
	 * @throws MemoryNotAvailableException if no storage slots are available.
	 */
	@Nonnull
	private NumberedByteBuffer prepareStorageBlock() {
		final Integer freeBlockId = this.freeBlocks.poll();
		if (freeBlockId == null) {
			throw MemoryNotAvailableException.NO_SLOT_FREE;
		} else {
			return new NumberedByteBuffer(
				freeBlockId,
				this.memoryBlock.get()
					.slice(freeBlockId * blockSizeBytes, blockSizeBytes)
			);
		}
	}

	/**
	 * Frees up memory blocks that have been allocated to finalized sessions. It processes each
	 * session to calculate the total size of blocks used, appends the session data to a disk buffer,
	 * and then marks those blocks as free for future use. The method also publishes statistical
	 * information about traffic sessions.
	 *
	 * @return Always returns -1 as a placeholder for future implementations or changes.
	 */
	private long freeMemory() {
		final ByteBuffer memoryByteBuffer = this.memoryBlock.get();
		do {
			final SessionTraffic finalizedSession = this.finalizedSessions.poll();
			if (finalizedSession != null) {
				int totalSize = 0;
				final OfInt memoryBlockIds = finalizedSession.getMemoryBlockIds();
				while (memoryBlockIds.hasNext()) {
					memoryBlockIds.nextInt();
					totalSize += memoryBlockIds.hasNext() ?
						this.blockSizeBytes : finalizedSession.getCurrentByteBufferPosition();
				}

				final SessionLocation sessionLocation = this.diskBuffer.appendSession(totalSize);
				final OfInt memoryBlockIdsToFree = finalizedSession.getMemoryBlockIds();
				while (memoryBlockIdsToFree.hasNext()) {
					final int freeBlock = memoryBlockIdsToFree.nextInt();
					this.diskBuffer.append(
						sessionLocation,
						memoryByteBuffer.slice(
							freeBlock * this.blockSizeBytes,
							// the last block may not be fully occupied
							memoryBlockIdsToFree.hasNext() ?
								this.blockSizeBytes : finalizedSession.getCurrentByteBufferPosition()
						)
					);
					this.freeBlocks.offer(freeBlock);
				}
				this.diskBuffer.sessionWritten(
					sessionLocation,
					finalizedSession.getSessionId(),
					finalizedSession.getCreated(),
					finalizedSession.getDurationInMillis(),
					finalizedSession.getRecordingTypes(),
					finalizedSession.getFetchCount(),
					finalizedSession.getBytesFetchedTotal()
				);
			}
		} while (!this.finalizedSessions.isEmpty());

		// publish statistic information
		new TrafficRecorderStatisticsEvent(
			this.catalogName,
			this.missedRecords.get(),
			this.droppedSessions.get(),
			this.createdSessions.get(),
			this.finishedSessions.get()
		).commit();

		// if the disk buffer wasn't read for a long time, we can purge it
		if (this.lastRead > 0 && System.currentTimeMillis() - this.lastRead > INDEX_INACTIVITY_DURATION) {
			this.diskBuffer.releaseIndex();
		}

		return -1L;
	}

	/**
	 * A record representing a numbered byte buffer. This record pairs a unique
	 * integer identifier with a non-null ByteBuffer instance.
	 *
	 * @param number an integer representing the unique identifier for the buffer
	 * @param buffer a non-null ByteBuffer instance containing the data
	 */
	public record NumberedByteBuffer(
		int number,
		@Nonnull ByteBuffer buffer
	) {
	}

	/**
	 * Exception thrown when there is insufficient memory available or no free slot
	 * in the memory buffer for processing a request or operation.
	 *
	 * This exception is a specific type of internal error encountered within the
	 * Evita system and is used to indicate that memory allocation or data insertion
	 * within a buffer was not successful due to constraints.
	 *
	 * The exception provides two static instances:
	 * - NO_SLOT_FREE: indicates no free slot is available in the memory buffer.
	 * - DATA_TOO_LARGE: indicates the data is too large to fit into any available slot in the memory buffer.
	 *
	 * The exception can be constructed with a specific message or can carry the
	 * context of a failed operation with an associated buffer state.
	 */
	public static class MemoryNotAvailableException extends EvitaInternalError {
		public static final MemoryNotAvailableException NO_SLOT_FREE = new MemoryNotAvailableException("No free slot in memory buffer!");
		public static final MemoryNotAvailableException DATA_TOO_LARGE = new MemoryNotAvailableException("No free slot in memory buffer!");

		@Serial private static final long serialVersionUID = 567086221625997669L;
		@Getter private final byte[] writeBuffer;

		MemoryNotAvailableException(@Nonnull String message) {
			super(message);
			this.writeBuffer = null;
		}

		public MemoryNotAvailableException(
			@Nonnull byte[] writeBuffer,
			@Nonnull MemoryNotAvailableException originException
		) {
			super(originException.getPrivateMessage(), originException.getPublicMessage());
			this.writeBuffer = writeBuffer;
		}
	}

}
