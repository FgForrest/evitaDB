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

package io.evitadb.store.traffic;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.api.exception.IndexNotReady;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.head.Collection;
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
import io.evitadb.api.traffic.LabelIntrospector;
import io.evitadb.api.traffic.TrafficRecordingExporter;
import io.evitadb.api.traffic.TrafficRecordingReader;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.management.FileManagementService;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.trafficRecorder.RandomAccessFileSessionSink;
import io.evitadb.spi.store.catalog.trafficRecorder.SessionSink;
import io.evitadb.spi.store.catalog.trafficRecorder.TrafficRecorder;
import io.evitadb.spi.store.catalog.trafficRecorder.model.SessionLocation;
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.query.QuerySerializationKryoConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.traffic.event.TrafficRecorderStatisticsEvent;
import io.evitadb.store.traffic.serializer.CurrentSessionRecordContext;
import io.evitadb.store.traffic.stream.RingBufferInputStream;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.utils.Assert;
import io.evitadb.utils.IOUtils;
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
public class OffHeapTrafficRecorder
	implements TrafficRecorder, TrafficRecordingReader, TrafficRecordingExporter, LabelIntrospector, Closeable {
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
	private final Pool<Kryo> trafficRecorderKryoPool = new Pool<>(true, true) {
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
	private FileManagementService fileManagementService;
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
	 * Sampling percentage that determines the target fraction of traffic that is recorded, from 0 to 100.
	 * Zero means that nothing is recorded (recording effectively disabled), 100 means that all traffic is
	 * recorded. A new session is recorded only while the current recorded fraction
	 * ({@link #computeCurrentSamplingRate()}) has not yet exceeded this target - see {@link #createSession}.
	 */
	private int samplingPercentage;
	/**
	 * Contains reference to the asynchronous task executor that clears finalized session memory blocks and writes
	 * them to disk buffer.
	 */
	private DelayedAsyncTask freeMemoryTask;
	/**
	 * Contains reference to the asynchronous task executor that initiates the indexing of the disk buffer.
	 */
	private DelayedAsyncTask indexTask;
	/**
	 * Last time when the data from the {@link #diskBuffer} was read.
	 */
	private long lastRead = -1;

	/**
	 * Converts an {@link OffsetDateTime} to epoch milliseconds without allocating an intermediate
	 * {@link java.time.Instant}, unlike {@code offsetDateTime.toInstant().toEpochMilli()}. The result is
	 * exactly equal to that expression: {@link OffsetDateTime#toEpochSecond()} yields the same UTC
	 * epoch-second as the corresponding {@code Instant}, and {@link OffsetDateTime#getNano()} is the
	 * nano-of-second. Called once per recorded traffic record on the hot write path, so avoiding the
	 * per-call {@code Instant} allocation is worthwhile.
	 *
	 * @param offsetDateTime the timestamp to convert
	 * @return milliseconds since the epoch
	 */
	private static long epochMillis(@Nonnull OffsetDateTime offsetDateTime) {
		return offsetDateTime.toEpochSecond() * 1000L + offsetDateTime.getNano() / 1_000_000;
	}

	public OffHeapTrafficRecorder() {
		this(16_384);
	}

	public OffHeapTrafficRecorder(int blockSizeBytes) {
		this.blockSizeBytes = blockSizeBytes;
	}

	@Override
	public void init(
		@Nonnull String catalogName,
		@Nonnull FileManagementService fileManagementService,
		@Nonnull Scheduler scheduler,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TrafficRecordingOptions recordingOptions
	) {
		this.init(
			catalogName,
			fileManagementService,
			scheduler,
			storageOptions,
			recordingOptions,
			recordingOptions.trafficFlushIntervalInMilliseconds()
		);
	}

	@Override
	public void setSamplingPercentage(int samplingPercentage) {
		this.recordedRecords.set(0);
		this.missedRecords.set(0);
		this.samplingPercentage = samplingPercentage;
	}

	@Override
	public void setSessionSink(@Nullable SessionSink sessionSink) {
		if (sessionSink == null) {
			this.diskBuffer.setSessionSink(null);
		} else if (sessionSink instanceof RandomAccessFileSessionSink rafss) {
			this.diskBuffer.setSessionSink(rafss);
		} else {
			throw new GenericEvitaInternalError(
				"Only RandomAccessFileSessionSink is supported in OffHeapTrafficRecorder!"
			);
		}
	}

	@Override
	public void createSession(@Nonnull UUID sessionId, long catalogVersion, @Nonnull OffsetDateTime created) {
		// test sampling rate
		if (this.samplingPercentage > 0 && computeCurrentSamplingRate() <= this.samplingPercentage) {
			final SessionTraffic sessionTraffic = new SessionTraffic(
				sessionId,
				catalogVersion,
				created,
				this.copyBufferPool.obtain(),
				this::prepareStorageBlock,
				this.trafficRecorderKryoPool::obtain,
				this.trafficRecorderKryoPool::free
			);
			sessionTraffic.record(
				new SessionStartContainer(
					sessionId,
					sessionTraffic.nextRecordingId(),
					catalogVersion,
					created
				),
				ex -> discardSession(sessionTraffic),
				ex -> {
					discardSession(sessionTraffic);
					log.error("Failed to record session start for session {}.", sessionId, ex);
				},
				() -> {
					this.createdSessions.incrementAndGet();
					this.trackedSessionsIndex.put(sessionId, sessionTraffic);
				}
			);
		} else {
			this.missedRecords.incrementAndGet();
		}
	}

	@Override
	public void closeSession(@Nonnull UUID sessionId, @Nullable String finishedWithError) {
		final SessionTraffic sessionTraffic = this.trackedSessionsIndex.remove(sessionId);
		if (sessionTraffic != null && !sessionTraffic.isFinished()) {
			final byte[] bufferToReturn = sessionTraffic.finish();
			sessionTraffic.record(
				new SessionCloseContainer(
					sessionId,
					sessionTraffic.nextRecordingId(),
					sessionTraffic.getRecordCount(),
					sessionTraffic.getCatalogVersion(),
					sessionTraffic.getCreated(),
					sessionTraffic.getDurationInMillis(),
					sessionTraffic.getFetchCount(),
					sessionTraffic.getBytesFetchedTotal(),
					sessionTraffic.getRecordCount(),
					sessionTraffic.getRecordsMissedOut(),
					sessionTraffic.getQueryCount(),
					sessionTraffic.getEntityFetchCount(),
					sessionTraffic.getMutationCount(),
					finishedWithError
				),
				ex -> discardSession(sessionTraffic),
				ex -> {
					discardSession(sessionTraffic);
					log.error("Failed to record session close for session {}.", sessionId, ex);
				},
				() -> {
					sessionTraffic.close();
					this.copyBufferPool.free(bufferToReturn);
					this.finishedSessions.incrementAndGet();
					this.finalizedSessions.offer(sessionTraffic);
					this.freeMemoryTask.schedule();
				}
			);
		} else {
			this.missedRecords.incrementAndGet();
		}
	}

	@Override
	public void recordQuery(
		@Nonnull UUID sessionId,
		@Nonnull String queryDescription,
		@Nonnull Query query,
		@Nonnull Label[] labels,
		@Nonnull OffsetDateTime now,
		int totalRecordCount,
		int ioFetchCount,
		int ioFetchedSizeBytes,
		@Nonnull int[] primaryKeys,
		@Nullable String finishedWithError
	) {
		doRecord(
			this.trackedSessionsIndex.get(sessionId),
			sessionTraffic -> {
				final HeadConstraint head = query.getHead();
				final Collection collectionConstraint = head == null ?
					null : QueryUtils.findConstraint(head, Collection.class);
				final io.evitadb.api.requestResponse.trafficRecording.Label entityTypeLabel = collectionConstraint == null
					?
					null
					:
						new io.evitadb.api.requestResponse.trafficRecording.Label(
							"entity-type", collectionConstraint.getEntityType());

				// merge the caller-supplied labels with the derived entity-type label into a single sized array,
				// avoiding the per-call Optional chain + double Stream allocation on this hot write path; the
				// order (caller labels first, then the entity-type label) matches the previous stream-concat
				final io.evitadb.api.requestResponse.trafficRecording.Label[] finalLabels;
				if (labels.length == 0) {
					finalLabels = entityTypeLabel == null ?
						io.evitadb.api.requestResponse.trafficRecording.Label.EMPTY_LABELS :
						new io.evitadb.api.requestResponse.trafficRecording.Label[]{entityTypeLabel};
				} else {
					final int extra = entityTypeLabel == null ? 0 : 1;
					final io.evitadb.api.requestResponse.trafficRecording.Label[] merged =
						new io.evitadb.api.requestResponse.trafficRecording.Label[labels.length + extra];
					for (int i = 0; i < labels.length; i++) {
						merged[i] = new io.evitadb.api.requestResponse.trafficRecording.Label(
							labels[i].getLabelName(), labels[i].getLabelValue()
						);
					}
					if (entityTypeLabel != null) {
						merged[labels.length] = entityTypeLabel;
					}
					finalLabels = merged;
				}
				return new QueryContainer(
					sessionId,
					sessionTraffic.nextRecordingId(),
					queryDescription,
					query,
					finalLabels,
					now, (int) (System.currentTimeMillis() - epochMillis(now)),
					totalRecordCount, ioFetchCount, ioFetchedSizeBytes, primaryKeys,
					finishedWithError
				);
			}
		);
	}

	@Override
	public void recordFetch(
		@Nonnull UUID sessionId,
		@Nonnull Query query,
		@Nonnull OffsetDateTime now,
		int ioFetchCount,
		int ioFetchedSizeBytes,
		int primaryKey,
		@Nullable String finishedWithError
	) {
		doRecord(
			this.trackedSessionsIndex.get(sessionId),
			sessionTraffic -> new EntityFetchContainer(
				sessionId,
				sessionTraffic.nextRecordingId(),
				query,
				now,
				(int) (System.currentTimeMillis() - epochMillis(now)),
				ioFetchCount, ioFetchedSizeBytes, primaryKey,
				finishedWithError
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
		int primaryKey,
		@Nullable String finishedWithError
	) {
		doRecord(
			this.trackedSessionsIndex.get(sessionId),
			sessionTraffic -> new EntityEnrichmentContainer(
				sessionId,
				sessionTraffic.nextRecordingId(),
				query,
				now,
				(int) (System.currentTimeMillis() - epochMillis(now)),
				ioFetchCount, ioFetchedSizeBytes, primaryKey,
				finishedWithError
			)
		);
	}

	@Override
	public void recordMutation(
		@Nonnull UUID sessionId,
		@Nonnull OffsetDateTime now,
		@Nonnull Mutation mutation,
		@Nullable String finishedWithError
	) {
		doRecord(
			this.trackedSessionsIndex.get(sessionId),
			sessionTraffic -> new MutationContainer(
				sessionId,
				sessionTraffic.nextRecordingId(),
				now,
				(int) (System.currentTimeMillis() - epochMillis(now)),
				mutation,
				finishedWithError
			)
		);
	}

	@Override
	public void setupSourceQuery(
		@Nonnull UUID sessionId,
		@Nonnull UUID sourceQueryId,
		@Nonnull OffsetDateTime now,
		@Nonnull String sourceQuery,
		@Nonnull Label[] labels,
		@Nullable String finishedWithError
	) {
		doRecord(
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
					Arrays.stream(labels)
						.map(label -> new io.evitadb.api.requestResponse.trafficRecording.Label(
							label.getLabelName(),
							label.getLabelValue()
						))
						.toArray(io.evitadb.api.requestResponse.trafficRecording.Label[]::new),
					finishedWithError
				);
			}
		);
	}

	@Override
	public void closeSourceQuery(
		@Nonnull UUID sessionId,
		@Nonnull UUID sourceQueryId,
		@Nullable String finishedWithError
	) {
		doRecord(
			this.trackedSessionsIndex.get(sessionId),
			sessionTraffic -> sessionTraffic.closeSourceQuery(sourceQueryId, finishedWithError)
		);
	}

	@Override
	public void close() throws IOException {
		this.freeMemory();
		this.memoryBlock.set(null);
		this.trackedSessionsIndex.clear();
		IOUtils.closeQuietly(
			this.freeMemoryTask::close,
			this.indexTask::close
		);
		this.diskBuffer.close(filePath -> this.fileManagementService.purgeManagedTempFile(filePath));
	}

	@Nonnull
	@Override
	public Stream<TrafficRecording> getRecordings(
		@Nonnull TrafficRecordingCaptureRequest request
	) throws TemporalDataNotAvailableException, IndexNotReady {
		try {
			this.lastRead = System.currentTimeMillis();
			return this.diskBuffer.getSessionRecordsStream(
				request,
				this::readTrafficRecord
			);
		} catch (IndexNotReady ex) {
			this.indexTask.scheduleImmediately();
			throw ex;
		}
	}

	@Nonnull
	@Override
	public Stream<TrafficRecording> getRecordingsReversed(
		@Nonnull TrafficRecordingCaptureRequest request
	) throws TemporalDataNotAvailableException, IndexNotReady {
		try {
			this.lastRead = System.currentTimeMillis();
			return this.diskBuffer.getSessionRecordsReversedStream(
				request,
				this::readTrafficRecord
			);
		} catch (IndexNotReady ex) {
			this.indexTask.scheduleImmediately();
			throw ex;
		}
	}

	@Nonnull
	@Override
	public java.util.Collection<String> getLabelsNamesOrderedByCardinality(
		@Nullable String nameStartingWith, int limit) throws IndexNotReady {
		try {
			this.lastRead = System.currentTimeMillis();
			return this.diskBuffer.getLabelsNamesOrderedByCardinality(nameStartingWith, limit);
		} catch (IndexNotReady ex) {
			this.indexTask.scheduleImmediately();
			throw ex;
		}
	}

	@Nonnull
	@Override
	public java.util.Collection<String> getLabelValuesOrderedByCardinality(
		@Nonnull String nameEquals, @Nullable String valueStartingWith, int limit) throws IndexNotReady {
		try {
			this.lastRead = System.currentTimeMillis();
			return this.diskBuffer.getLabelValuesOrderedByCardinality(
				nameEquals, valueStartingWith, limit
			);
		} catch (IndexNotReady ex) {
			this.indexTask.scheduleImmediately();
			throw ex;
		}
	}

	/**
	 * Exports a consistent snapshot of the disk ring buffer's current window (the window "as of now") into
	 * the caller's sink. Always preceded by a synchronous drain (see {@link #drainFinalizedSessionsToDisk()})
	 * so that sessions closed before this call are guaranteed to be part of the exported window - this is
	 * what delivers "recent closed sessions are included" without a load-dependent tail-chase. Open
	 * in-flight sessions are never exported. The walk itself runs *outside* the drain's synchronized
	 * block/monitor, so it never stalls the live recorder for its whole duration - only individual
	 * per-session shared span locks are taken, and those never block a concurrent writer for longer than
	 * one session's worth of copying.
	 *
	 * <p>Adapts between the {@link TrafficRecordingExporter} contract (which uses only types visible from
	 * {@code evita_api}, since callers such as {@code TrafficRecordingEngine} never depend on this module)
	 * and {@link DiskRingBuffer}'s own richer nested types.
	 *
	 * @param sessionConsumer  invoked once per exported session
	 * @param progressListener invoked after each processed (exported or skipped) session
	 * @return a summary of how many sessions were exported/skipped and how many bytes were copied
	 * @throws IOException if the sessionConsumer's own I/O (e.g. writing to a zip stream) fails
	 */
	@Nonnull
	@Override
	public TrafficRecordingExporter.ExportSummary exportTrafficRecording(
		@Nonnull TrafficRecordingExporter.ExportedSessionConsumer sessionConsumer,
		@Nonnull TrafficRecordingExporter.ExportProgressListener progressListener
	) throws IOException {
		drainFinalizedSessionsToDisk();

		final byte[] copyBuffer = this.copyBufferPool.obtain();
		try {
			final DiskRingBuffer.ExportSummary summary = this.diskBuffer.exportSnapshot(
				copyBuffer,
				(location, byteSource) -> sessionConsumer.accept(location.sequenceOrder(), byteSource::copyTo),
				progressListener::onProgress
			);
			return new TrafficRecordingExporter.ExportSummary(
				summary.exportedSessionCount(),
				summary.exportedByteCount(),
				summary.skippedSessionCount(),
				summary.totalSessionCount()
			);
		} finally {
			this.copyBufferPool.free(copyBuffer);
		}
	}

	void init(
		@Nonnull String catalogName,
		@Nonnull FileManagementService fileManagementService,
		@Nonnull Scheduler scheduler,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TrafficRecordingOptions recordingOptions,
		long trafficFlushIntervalInMilliseconds
	) {
		this.catalogName = catalogName;
		this.fileManagementService = fileManagementService;
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
		// the disk buffer must be able to hold at least one whole memory block append; this guarantees
		// that the per-block append in drainFinalizedSessionsToDisk can never trip DATA_TOO_LARGE, so
		// the only place that exception can originate during a drain is the whole-session appendSession
		// check - which is what the drain's leak-safe recovery relies on
		Assert.isPremiseValid(
			recordingOptions.trafficDiskBufferSizeInBytes() >= this.blockSizeBytes,
			"Traffic disk buffer size (" + recordingOptions.trafficDiskBufferSizeInBytes() +
				" B) must be at least the memory block size (" + this.blockSizeBytes + " B)."
		);
		// create ring buffer on disk
		this.diskBuffer = new DiskRingBuffer(
			fileManagementService.createManagedTempFile("traffic-recording-buffer-" + catalogName + ".bin"),
			recordingOptions.trafficDiskBufferSizeInBytes()
		);

		this.freeMemoryTask = new DelayedAsyncTask(
			this.catalogName, "Traffic recorder - memory buffer cleanup", scheduler,
			this::freeMemory, trafficFlushIntervalInMilliseconds, TimeUnit.MILLISECONDS, 0
		);

		this.indexTask = new DelayedAsyncTask(
			this.catalogName, "Traffic recorder - disk buffer indexing", scheduler,
			this::index, Long.MAX_VALUE, TimeUnit.MILLISECONDS, 0
		);

		this.copyBufferPool = new Pool<>(true, true) {
			@Override
			protected byte[] create() {
				return new byte[storageOptions.outputBufferSize()];
			}
		};
	}

	/**
	 * Synchronously drains all currently finalized (closed) sessions from the off-heap memory buffer into
	 * the disk ring buffer, freeing their memory blocks for reuse. This is the body previously inlined in
	 * {@link #freeMemory()}, promoted to a directly callable method so the on-demand export can force a
	 * drain (guaranteeing "recent closed sessions are included") without scheduling-and-polling the
	 * background {@link #freeMemoryTask}. Being {@code synchronized} on the same monitor as
	 * {@link #freeMemory()} already serializes the two call sites - no additional coordination needed.
	 */
	synchronized void drainFinalizedSessionsToDisk() {
		this.diskBuffer.updateIndexTransactionally(
			() -> {
				this.diskBuffer.setLastRealSamplingRate(computeCurrentSamplingRate());
				final ByteBuffer memoryByteBuffer = this.memoryBlock.get();
				do {
					//noinspection resource
					final SessionTraffic finalizedSession = this.finalizedSessions.poll();
					if (finalizedSession != null) {
						int totalSize = 0;
						final OfInt memoryBlockIds = finalizedSession.getMemoryBlockIds();
						while (memoryBlockIds.hasNext()) {
							memoryBlockIds.nextInt();
							totalSize += memoryBlockIds.hasNext() ?
								this.blockSizeBytes : finalizedSession.getCurrentByteBufferPosition();
						}

						try {
							final SessionLocation sessionLocation = this.diskBuffer.appendSession(
								finalizedSession.getRecordCount(), totalSize);
							final OfInt memoryBlockIdsToFree = finalizedSession.getMemoryBlockIds();
							while (memoryBlockIdsToFree.hasNext()) {
								final int freeBlock = memoryBlockIdsToFree.nextInt();
								final int blockStart = freeBlock * this.blockSizeBytes;
								// the last block may not be fully occupied
								final int blockLength = memoryBlockIdsToFree.hasNext() ?
									this.blockSizeBytes : finalizedSession.getCurrentByteBufferPosition();
								this.diskBuffer.append(
									memoryByteBuffer.slice(blockStart, blockLength)
								);
								this.freeBlocks.offer(freeBlock);
							}
							this.diskBuffer.sessionWritten(
								sessionLocation,
								finalizedSession.getSessionId(),
								finalizedSession.getCreated(),
								finalizedSession.getDurationInMillis(),
								finalizedSession.getRecordingTypes(),
								finalizedSession.getLabels(),
								finalizedSession.getFetchCount(),
								finalizedSession.getBytesFetchedTotal()
							);
						} catch (MemoryNotAvailableException ex) {
							// The finalized session is larger than the whole disk ring buffer, so it can
							// never be persisted. Drop it, but ALWAYS return its memory blocks to the free
							// pool - the session was already polled off the finalized queue, and without
							// this the blocks would leak permanently (the exception would otherwise abort
							// the whole drain and is even swallowed by updateIndexTransactionally). Because
							// the disk buffer is guaranteed to hold at least one whole memory block (see the
							// premise check in init), only the whole-session appendSession above can throw
							// this - it does so before any block has been written to disk or returned here,
							// so returning every block is correct and never double-frees.
							final OfInt leakedBlocks = finalizedSession.getMemoryBlockIds();
							while (leakedBlocks.hasNext()) {
								this.freeBlocks.offer(leakedBlocks.nextInt());
							}
							this.droppedSessions.incrementAndGet();
							this.missedRecords.addAndGet(finalizedSession.getRecordCount());
							log.warn(
								"Finalized session {} ({} bytes) exceeds the traffic disk buffer size and was dropped.",
								finalizedSession.getSessionId(), totalSize
							);
						}
					}
				} while (!this.finalizedSessions.isEmpty());
			}
		);
	}

	/**
	 * Discards the current session by freeing associated memory resources and
	 * incrementing relevant counters for dropped sessions and missed records.
	 * This is typically invoked in scenarios where there's a memory shortage,
	 * and the session needs to be closed with its data being discarded.
	 *
	 * @param sessionTraffic the session traffic containing memory block IDs and record count
	 */
	private void discardSession(@Nonnull SessionTraffic sessionTraffic) {
		this.copyBufferPool.free(sessionTraffic.discard());
		this.droppedSessions.incrementAndGet();
		this.missedRecords.addAndGet(sessionTraffic.getRecordCount());
		// when there is memory shortage, when session is closed - free the memory and throw away the data
		final OfInt memoryBlockIds = sessionTraffic.getMemoryBlockIds();
		while (memoryBlockIds.hasNext()) {
			this.freeBlocks.offer(memoryBlockIds.nextInt());
		}
		// remove the session from the tracked sessions index
		this.trackedSessionsIndex.remove(sessionTraffic.getSessionId());
		// schedule memory cleaning
		this.freeMemoryTask.schedule();
	}

	/**
	 * Calculates the current sampling rate as a percentage.
	 * The sampling rate is determined based on the ratio of recorded records to the total of recorded and missed records.
	 * If no records have been recorded or missed yet, the method returns 0 - this makes the very first session pass
	 * the {@code currentRate <= samplingPercentage} gate in {@link #createSession} (for any target above zero) and
	 * start recording, after which the ratio tracks the configured target.
	 *
	 * @return the current sampling rate as an integer percentage (0-100)
	 */
	private int computeCurrentSamplingRate() {
		final long recorded = this.recordedRecords.get();
		final long missed = this.missedRecords.get();
		final long recordedAndMissed = recorded + missed;
		return recordedAndMissed == 0 ? 0 : (int) (((double) recorded / (double) recordedAndMissed) * 100.0);
	}

	/**
	 * Reads a traffic record from a specified file position.
	 *
	 * @param sessionSequenceOrder  the session sequence order of the recording
	 * @param sessionRecordsCount   the number of records in this session
	 * @param filePosition          the position within the file to read the traffic record from
	 * @param targetFileInputStream the file input stream to read the traffic record from
	 * @return a {@code StorageRecord} containing the traffic recording
	 */
	@Nonnull
	private StorageRecord<TrafficRecording> readTrafficRecord(
		long sessionSequenceOrder,
		int sessionRecordsCount,
		long filePosition,
		@Nonnull RandomAccessFileInputStream targetFileInputStream
	) {
		final byte[] byteBuffer = this.copyBufferPool.obtain();
		final Kryo kryoInstance = this.trafficRecorderKryoPool.obtain();
		try {
			final ObservableInput<RingBufferInputStream> input = new ObservableInput<>(
				new RingBufferInputStream(
					targetFileInputStream,
					this.diskBuffer.getDiskBufferFileSize(),
					filePosition
				),
				byteBuffer,
				Checksum.NO_OP,
				null
			);
			return StorageRecord.read(
				input,
				(theInput, recordLength) -> CurrentSessionRecordContext.fetch(
					sessionSequenceOrder,
					sessionRecordsCount,
					() -> (TrafficRecording) kryoInstance.readClassAndObject(input)
				)
			);
		} finally {
			this.copyBufferPool.free(byteBuffer);
			this.trafficRecorderKryoPool.free(kryoInstance);
		}
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
	private <T extends TrafficRecording> void doRecord(
		@Nullable SessionTraffic sessionTraffic,
		@Nonnull Function<SessionTraffic, T> containerFactory
	) {
		if (sessionTraffic != null && !sessionTraffic.isFinished()) {
			final T container = containerFactory.apply(sessionTraffic);
			sessionTraffic.record(
				container,
				ex -> discardSession(sessionTraffic),
				ex -> {
					log.error("Failed to record traffic data for session {}.", sessionTraffic.getSessionId(), ex);
					discardSession(sessionTraffic);
				},
				this.recordedRecords::incrementAndGet
			);
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
					.slice(freeBlockId * this.blockSizeBytes, this.blockSizeBytes)
			);
		}
	}

	/**
	 * Processes disk buffer to index data and read traffic records.
	 *
	 * @return always -1 - reschedule according to plan
	 */
	private long index() {
		try {
			this.diskBuffer.indexData(this::readTrafficRecord);
		} catch (Exception ex) {
			log.error("Failed to index disk buffer.", ex);
		}
		return -1L;
	}

	/**
	 * Frees up memory blocks that have been allocated to finalized sessions by draining them to the disk
	 * buffer, then publishes statistical information about traffic sessions and purges the disk buffer
	 * index if it wasn't read for a long time.
	 *
	 * @return Always returns -1 as a placeholder for future implementations or changes.
	 */
	private synchronized long freeMemory() {
		drainFinalizedSessionsToDisk();

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
	 *
	 * Note: the two static instances are pre-built and shared. They are used purely for control flow
	 * (never surfaced to a client), so the fact that their captured stack traces point at class-load
	 * time rather than the actual throw site is intentional and acceptable - it avoids the cost of
	 * filling in a stack trace on every memory-shortage occurrence on the hot recording path.
	 */
	public static class MemoryNotAvailableException extends EvitaInternalError {
		public static final MemoryNotAvailableException NO_SLOT_FREE = new MemoryNotAvailableException(
			"No free slot in memory buffer!");
		public static final MemoryNotAvailableException DATA_TOO_LARGE = new MemoryNotAvailableException(
			"Session data is too large to fit into the traffic disk buffer!");

		@Serial private static final long serialVersionUID = 567086221625997669L;

		public MemoryNotAvailableException() {
			super("Memory shortage during session recording.");
		}

		MemoryNotAvailableException(@Nonnull String message) {
			super(message);
		}

	}

}
