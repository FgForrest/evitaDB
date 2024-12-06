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
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.async.DelayedAsyncTask;
import io.evitadb.core.async.Scheduler;
import io.evitadb.core.file.ExportFileService;
import io.evitadb.core.traffic.TrafficRecorder;
import io.evitadb.core.traffic.TrafficRecording;
import io.evitadb.core.traffic.TrafficRecordingCaptureRequest;
import io.evitadb.core.traffic.TrafficRecordingReader;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.offsetIndex.stream.RandomAccessFileInputStream;
import io.evitadb.store.query.QuerySerializationKryoConfigurer;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.traffic.data.MutationContainer;
import io.evitadb.store.traffic.data.QueryContainer;
import io.evitadb.store.traffic.data.RecordEnrichmentContainer;
import io.evitadb.store.traffic.data.RecordFetchContainer;
import io.evitadb.store.traffic.data.SessionLocation;
import io.evitadb.store.traffic.data.SessionStartContainer;
import io.evitadb.store.traffic.event.TrafficRecorderStatisticsEvent;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.io.IOException;
import java.io.Serial;
import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.PrimitiveIterator.OfInt;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class OffHeapTrafficRecorder implements TrafficRecorder, TrafficRecordingReader, Closeable {
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
	 * Pool of byte arrays used for storing output data.
	 */
	private Pool<byte[]> outputBufferPool;
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
	 * Contains reference to the asynchronous task executor that clears finalized session memory blocks and writes
	 * them to disk buffer.
	 */
	private DelayedAsyncTask freeMemoryTask;

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
		@Nonnull ServerOptions serverOptions
	) {
		this.catalogName = catalogName;
		this.exportFileService = exportFileService;
		this.samplingPercentage = serverOptions.trafficSamplingPercentage();

		final long trafficMemoryBufferSizeInBytes = serverOptions.trafficMemoryBufferSizeInBytes();
		Assert.isPremiseValid(
			trafficMemoryBufferSizeInBytes > 0,
			"Traffic memory buffer size must be greater than 0."
		);
		// align the buffer size to be divisible by 16KB page size
		final int capacity = (int) (trafficMemoryBufferSizeInBytes - (trafficMemoryBufferSizeInBytes % this.blockSizeBytes));
		this.memoryBlock.set(ByteBuffer.allocateDirect(capacity));
		final int blockCount = capacity / blockSizeBytes;
		// initialize free blocks queue, all blocks are free at the beginning
		this.freeBlocks = new ArrayBlockingQueue<>(blockCount, true);
		// initialize observable outputs for each memory block
		for (int i = 0; i < blockCount; i++) {
			this.freeBlocks.offer(i);
		}
		// create ring buffer on disk
		this.diskBuffer = new DiskRingBuffer(
			exportFileService.createManagedTempFile("traffic-recording-buffer.bin"),
			serverOptions.trafficDiskBufferSizeInBytes()
		);

		this.freeMemoryTask = new DelayedAsyncTask(
			this.catalogName, "Traffic recorder - memory buffer cleanup", scheduler,
			this::freeMemory, 60, TimeUnit.SECONDS, 10
		);

		this.outputBufferPool = new Pool<>(true, true) {
			@Override
			protected byte[] create() {
				return new byte[storageOptions.outputBufferSize()];
			}
		};
	}

	@Override
	public void createSession(@Nonnull UUID sessionId, long catalogVersion, @Nonnull OffsetDateTime created) {
		final SessionTraffic sessionTraffic = new SessionTraffic(
			sessionId,
			catalogVersion,
			created,
			outputBufferPool.obtain(),
			this::prepareStorageBlock
		);
		try {
			final StorageRecord<SessionStartContainer> queryContainerStorageRecord = new StorageRecord<>(
				offHeapTrafficRecorderKryoPool.obtain(),
				sessionTraffic.getObservableOutput(),
				0L,
				false,
				new SessionStartContainer(sessionId, catalogVersion, created)
			);
			Assert.isPremiseValid(queryContainerStorageRecord.fileLocation() != null, "Location must not be null.");
			this.createdSessions.incrementAndGet();
			this.trackedSessionsIndex.put(sessionId, sessionTraffic);
		} catch (MemoryNotAvailableException ex) {
			outputBufferPool.free(ex.getWriteBuffer());
			this.droppedSessions.incrementAndGet();
			this.missedRecords.incrementAndGet();
		}
	}

	@Override
	public void closeSession(@Nonnull UUID sessionId) {
		final SessionTraffic sessionTraffic = this.trackedSessionsIndex.remove(sessionId);
		if (sessionTraffic != null && !sessionTraffic.isFinished()) {
			outputBufferPool.free(sessionTraffic.finish());
			this.finishedSessions.incrementAndGet();
			this.finalizedSessions.offer(sessionTraffic);
			this.freeMemoryTask.schedule();
		} else {
			this.missedRecords.incrementAndGet();
		}
	}

	@Override
	public void recordQuery(
		@Nonnull UUID sessionId,
		@Nonnull Query query,
		@Nonnull OffsetDateTime now,
		int totalRecordCount,
		int ioFetchCount,
		int ioFetchedSizeBytes,
		@Nonnull int... primaryKeys
	) {
		record(
			sessionId,
			new QueryContainer(
				sessionId, query, now, (int) (System.currentTimeMillis() - now.toInstant().toEpochMilli()),
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
			sessionId,
			new RecordFetchContainer(
				sessionId, query, now, (int) (System.currentTimeMillis() - now.toInstant().toEpochMilli()),
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
			sessionId,
			new RecordEnrichmentContainer(
				sessionId, query, now, (int) (System.currentTimeMillis() - now.toInstant().toEpochMilli()),
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
			sessionId,
			new MutationContainer(
				sessionId, now, (int) (System.currentTimeMillis() - now.toInstant().toEpochMilli()), mutation
			)
		);
	}

	@Nonnull
	@Override
	public Stream<TrafficRecording> getRecordings(@Nonnull TrafficRecordingCaptureRequest request) throws TemporalDataNotAvailableException {
		return this.diskBuffer.getSessionRecordsStream(
			request,
			this::readTrafficRecord
		);
	}

	@Nonnull
	private StorageRecord<TrafficRecording> readTrafficRecord(long filePosition) {
		final ObservableInput<RandomAccessFileInputStream> input = null;
		input.resetToPosition(filePosition);
		return StorageRecord.read(
			this.offHeapTrafficRecorderKryoPool.obtain(),
			input,
			fl -> TrafficRecording.class
		);
	}

	@Override
	public void close() throws IOException {
		this.memoryBlock.set(null);
		this.trackedSessionsIndex.clear();
		this.diskBuffer.close(filePath -> this.exportFileService.purgeManagedTempFile(filePath));
	}

	private <T extends TrafficRecording> void record(@Nonnull UUID sessionId, @Nonnull T container) {
		final SessionTraffic sessionTraffic = this.trackedSessionsIndex.get(sessionId);
		if (sessionTraffic != null && !sessionTraffic.isFinished() &&
			(this.samplingPercentage == 0 || this.samplingPercentage <= ThreadLocalRandom.current().nextInt(100))) {
			try {
				final StorageRecord<T> storageRecord = new StorageRecord<>(
					this.offHeapTrafficRecorderKryoPool.obtain(),
					sessionTraffic.getObservableOutput(),
					0L,
					false,
					container
				);
				Assert.isPremiseValid(storageRecord.fileLocation() != null, "Location must not be null.");
				sessionTraffic.registerRecording(container.type(), container.ioFetchedSizeBytes());
			} catch (MemoryNotAvailableException ex) {
				this.outputBufferPool.free(ex.getWriteBuffer());
				this.missedRecords.incrementAndGet();
			}
		} else {
			this.missedRecords.incrementAndGet();
		}
	}

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
	 * Drops finished session from the memory to free up space and returns next free block ID. Method tries to release
	 * 30% of the allocated memory.
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
					this::readTrafficRecord
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

		return -1L;
	}

	/**
	 * TODO JNO - document me
	 */
	public record NumberedByteBuffer(
		int number,
		@Nonnull ByteBuffer buffer
	) {
	}

	/**
	 * TODO JNO - document me
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
