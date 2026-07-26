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
import io.evitadb.exception.NotMonitored;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.store.catalog.trafficRecorder.RandomAccessFileSessionSink;
import io.evitadb.spi.store.catalog.trafficRecorder.SessionSink;
import io.evitadb.spi.store.catalog.trafficRecorder.TrafficRecorder;
import io.evitadb.spi.store.catalog.trafficRecorder.model.SessionLocation;
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.query.QuerySerializationKryoConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.traffic.event.TrafficRecorderMissReason;
import io.evitadb.store.traffic.event.TrafficRecorderSkippedRecordsEvent;
import io.evitadb.store.traffic.event.TrafficRecorderStatisticsEvent;
import io.evitadb.store.traffic.serializer.CurrentSessionRecordContext;
import io.evitadb.store.traffic.stream.RingBufferInputStream;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
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
import java.util.EnumMap;
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
	 * Best-effort soft cap on {@link #discardedSessionReasons} - the guard in {@link #discardSession} reads a
	 * non-atomic {@code mappingCount()} snapshot, so concurrent discards may momentarily push the map slightly
	 * past this value; it is a memory backstop, not a strict bound. An entry is added when a session is
	 * discarded and removed when that session's {@link #closeSession} arrives, so a mid-flight discard
	 * self-drains once the session is closed. Two cases leave an entry until {@link #close()}: a session
	 * discarded during its own close record (no later close will arrive to evict it) and a discarded session
	 * that is never closed at all. Once the map is at the cap, further discards simply fall back to
	 * {@link TrafficRecorderMissReason#SAMPLING} for their trailing records rather than growing it unboundedly.
	 */
	private static final int MAX_DISCARDED_SESSION_REASONS = 1024;
	/**
	 * Remembers, per session id, the reason a session was discarded (see {@link #discardSession}) after it has
	 * already been removed from {@link #trackedSessionsIndex}. It lets the trailing records that still arrive
	 * for a discarded session, and its eventual {@link #closeSession}, be attributed to the real discard reason
	 * (e.g. {@link TrafficRecorderMissReason#MEMORY_SHORTAGE}) instead of the benign
	 * {@link TrafficRecorderMissReason#SAMPLING}. Entries are evicted on close and bounded by
	 * {@link #MAX_DISCARDED_SESSION_REASONS}. A {@link ConcurrentHashMap} because {@link #discardSession} and
	 * the {@code record*} paths run on different threads.
	 */
	private final ConcurrentHashMap<UUID, TrafficRecorderMissReason> discardedSessionReasons =
		CollectionUtils.createConcurrentHashMap(64);
	/**
	 * Queue of all sessions that were finished and are waiting to be written to disk buffer.
	 */
	private final Queue<SessionTraffic> finalizedSessions = new ConcurrentLinkedQueue<>();
	/**
	 * Enumerates the miss reasons once so per-reason counters and the periodic delta bookkeeping can be
	 * indexed by {@link TrafficRecorderMissReason#ordinal()} without recomputing {@code values()}.
	 */
	private static final TrafficRecorderMissReason[] MISS_REASONS = TrafficRecorderMissReason.values();
	/**
	 * Counter of records successfully captured. Monotonic (never reset) so the periodic metric emission
	 * can publish it as a delta; the sampling ratio uses it relative to {@link #samplingRecordedBaseline}.
	 */
	private final AtomicLong recordedRecords = new AtomicLong();
	/**
	 * Per-reason counters of records that were not persisted (see {@link TrafficRecorderMissReason}).
	 * Monotonic; pre-populated with one {@link AtomicLong} per reason at construction time so only atomic
	 * reads/updates of the values ever happen afterwards (no structural modification -> concurrent-safe).
	 */
	private final Map<TrafficRecorderMissReason, AtomicLong> missedRecordsByReason = createReasonCounters();
	/**
	 * Per-reason counters of whole sessions that were dropped (see {@link TrafficRecorderMissReason}).
	 * Same monotonic/pre-populated contract as {@link #missedRecordsByReason}.
	 */
	private final Map<TrafficRecorderMissReason, AtomicLong> droppedSessionsByReason = createReasonCounters();
	/**
	 * Counter of off-heap memory blocks allocated over the recorder's lifetime (monotonic, delta-emitted).
	 */
	private final AtomicLong blocksAllocated = new AtomicLong();
	/**
	 * Counter of created (admitted) sessions. Monotonic, delta-emitted.
	 */
	private final AtomicLong createdSessions = new AtomicLong();
	/**
	 * Counter of finished (cleanly closed and queued to disk) sessions. Monotonic, delta-emitted.
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
	 * Total number of off-heap memory blocks the buffer is divided into (capacity / blockSizeBytes). Set
	 * once in {@link #init}; used together with {@code freeBlocks.size()} to derive the used-blocks gauge.
	 */
	private int totalMemoryBlocks;
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
	 * Baselines captured at the last {@link #setSamplingPercentage} call so the sampling ratio restarts
	 * fresh for a new target WITHOUT resetting the monotonic metric counters (resetting them would make
	 * the periodic delta emission go negative). The ratio is
	 * {@code (recordedRecords - samplingRecordedBaseline) / ((recordedRecords - samplingRecordedBaseline) +
	 * (sampledOut - samplingSampledOutBaseline))}, where {@code sampledOut} is the SAMPLING-reason counter.
	 */
	private volatile long samplingRecordedBaseline;
	private volatile long samplingSampledOutBaseline;
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
	 * Last emitted cumulative counter values, captured after each {@link #freeMemory()} emission so the
	 * next emission can publish the delta since the previous one. Only ever touched inside the
	 * {@code synchronized} {@link #freeMemory()}, so plain fields are sufficient.
	 */
	private long lastCreatedSessionsEmitted;
	private long lastFinishedSessionsEmitted;
	private long lastRecordedRecordsEmitted;
	private long lastBlocksAllocatedEmitted;
	private long lastDiskBytesAppendedEmitted;
	/**
	 * Per-reason last-emitted values (indexed by {@link TrafficRecorderMissReason#ordinal()}) backing the
	 * delta emission of {@link TrafficRecorderSkippedRecordsEvent}. Only touched inside {@link #freeMemory()}.
	 */
	private final long[] lastMissedRecordsEmitted = new long[MISS_REASONS.length];
	private final long[] lastDroppedSessionsEmitted = new long[MISS_REASONS.length];

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

	/**
	 * Creates a fully pre-populated map holding one {@link AtomicLong} per {@link TrafficRecorderMissReason}.
	 * Because every key is present from the start and no key is ever added or removed afterwards, concurrent
	 * {@code get(...)} reads on the returned {@link EnumMap} are safe without additional synchronization.
	 *
	 * @return an enum map with a zeroed counter for every reason
	 */
	@Nonnull
	private static Map<TrafficRecorderMissReason, AtomicLong> createReasonCounters() {
		final Map<TrafficRecorderMissReason, AtomicLong> counters = new EnumMap<>(TrafficRecorderMissReason.class);
		for (final TrafficRecorderMissReason reason : MISS_REASONS) {
			counters.put(reason, new AtomicLong());
		}
		return counters;
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
		// rebaseline the sampling ratio so it restarts fresh for the new target, WITHOUT resetting the
		// monotonic metric counters - resetting them would make the periodic delta emission go negative
		this.samplingRecordedBaseline = this.recordedRecords.get();
		this.samplingSampledOutBaseline = this.missedRecordsByReason.get(TrafficRecorderMissReason.SAMPLING).get();
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
				ex -> discardSession(sessionTraffic, TrafficRecorderMissReason.MEMORY_SHORTAGE),
				ex -> {
					discardSession(sessionTraffic, TrafficRecorderMissReason.SERIALIZATION_ERROR);
					log.error("Failed to record session start for session {}.", sessionId, ex);
				},
				() -> {
					this.createdSessions.incrementAndGet();
					this.trackedSessionsIndex.put(sessionId, sessionTraffic);
				}
			);
		} else {
			// deliberate sampling skip: the session is not admitted either because sampling is disabled
			// (samplingPercentage <= 0) or because the recorded fraction already meets the configured target -
			// benign in both cases, tracked under the SAMPLING reason
			this.missedRecordsByReason.get(TrafficRecorderMissReason.SAMPLING).incrementAndGet();
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
				ex -> discardSession(sessionTraffic, TrafficRecorderMissReason.MEMORY_SHORTAGE),
				ex -> {
					discardSession(sessionTraffic, TrafficRecorderMissReason.SERIALIZATION_ERROR);
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
			// closing a session that is no longer tracked: either it was sampled out at creation (benign
			// SAMPLING), or it was discarded under resource pressure - in which case attribute the close to the
			// real discard reason and evict the now-consumed entry (see discardedSessionReasons)
			final TrafficRecorderMissReason closeReason = this.discardedSessionReasons.remove(sessionId);
			this.missedRecordsByReason
				.get(closeReason != null ? closeReason : TrafficRecorderMissReason.SAMPLING)
				.incrementAndGet();
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
			sessionId,
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
			sessionId,
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
			sessionId,
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
			sessionId,
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
			sessionId,
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
			sessionId,
			this.trackedSessionsIndex.get(sessionId),
			sessionTraffic -> sessionTraffic.closeSourceQuery(sourceQueryId, finishedWithError)
		);
	}

	@Override
	public void close() throws IOException {
		this.freeMemory();
		this.memoryBlock.set(null);
		this.trackedSessionsIndex.clear();
		this.discardedSessionReasons.clear();
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
		this.totalMemoryBlocks = blockCount;
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

						int freedBlocks = 0;
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
								freedBlocks++;
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
						} catch (MemoryNotAvailableException | UnexpectedIOException ex) {
							// The finalized session could not be fully persisted: either it is larger than the whole
							// disk ring buffer (MemoryNotAvailableException from appendSession, before any block was
							// written) or a disk write failed part-way through (UnexpectedIOException from append). Drop
							// it, but ALWAYS return its still-held memory blocks to the free pool - the session was already
							// polled off the finalized queue, so without this the blocks would leak permanently (the
							// exception is otherwise swallowed by updateIndexTransactionally and aborts the whole drain).
							// The first `freedBlocks` blocks were already offered back inside the loop above; skip exactly
							// those when re-iterating (getMemoryBlockIds() yields insertion order) so none is double-freed.
							final OfInt remainingBlocks = finalizedSession.getMemoryBlockIds();
							int blocksToSkip = freedBlocks;
							while (remainingBlocks.hasNext()) {
								final int block = remainingBlocks.nextInt();
								if (blocksToSkip > 0) {
									blocksToSkip--;
								} else {
									this.freeBlocks.offer(block);
								}
							}
							// classify the drop cause: DATA_TOO_LARGE from appendSession (session larger than the
							// whole disk ring buffer) is DISK_SHORTAGE, a failed disk write is IO_ERROR
							final TrafficRecorderMissReason reason = ex instanceof MemoryNotAvailableException ?
								TrafficRecorderMissReason.DISK_SHORTAGE : TrafficRecorderMissReason.IO_ERROR;
							this.droppedSessionsByReason.get(reason).incrementAndGet();
							this.missedRecordsByReason.get(reason).addAndGet(finalizedSession.getRecordCount());
							log.warn(
								"Finalized session {} ({} bytes) could not be persisted to the traffic disk buffer and was dropped: {}",
								finalizedSession.getSessionId(), totalSize, ex.getMessage()
							);
						}
					}
				} while (!this.finalizedSessions.isEmpty());
			}
		);
	}

	/**
	 * Discards the current session by freeing associated memory resources and
	 * incrementing the per-reason dropped-session and missed-record counters.
	 * This is typically invoked when a session cannot be recorded (memory shortage)
	 * or its serialization failed, and the session needs to be closed with its data discarded.
	 *
	 * @param sessionTraffic the session traffic containing memory block IDs and record count
	 * @param reason         the reason the session is being discarded (drives the metric dimension)
	 */
	private void discardSession(@Nonnull SessionTraffic sessionTraffic, @Nonnull TrafficRecorderMissReason reason) {
		this.copyBufferPool.free(sessionTraffic.discard());
		this.droppedSessionsByReason.get(reason).incrementAndGet();
		this.missedRecordsByReason.get(reason).addAndGet(sessionTraffic.getRecordCount());
		// when there is memory shortage, when session is closed - free the memory and throw away the data
		final OfInt memoryBlockIds = sessionTraffic.getMemoryBlockIds();
		while (memoryBlockIds.hasNext()) {
			this.freeBlocks.offer(memoryBlockIds.nextInt());
		}
		// remember why this session was discarded BEFORE removing it from the tracked index, so trailing
		// records and the eventual close of the now-removed session are attributed to the real reason instead
		// of the benign SAMPLING (bounded: closeSession evicts the entry; the size guard caps the pathological
		// never-closed case, whose trailing records then fall back to SAMPLING)
		if (this.discardedSessionReasons.mappingCount() < MAX_DISCARDED_SESSION_REASONS) {
			this.discardedSessionReasons.put(sessionTraffic.getSessionId(), reason);
		}
		// remove the session from the tracked sessions index
		this.trackedSessionsIndex.remove(sessionTraffic.getSessionId());
		// schedule memory cleaning
		this.freeMemoryTask.schedule();
	}

	/**
	 * Calculates the current sampling rate as a percentage since the last {@link #setSamplingPercentage}
	 * rebaseline. The rate is the ratio of recorded records to the total of recorded records and records
	 * deliberately skipped for SAMPLING - failure drops (memory/disk shortage, IO, serialization errors)
	 * are intentionally EXCLUDED: counting them here would lower the computed rate and make the sampling
	 * gate in {@link #createSession} admit MORE sessions exactly when the recorder is already under
	 * pressure (a positive-feedback loop). If nothing has been recorded or sampled out yet, the method
	 * returns 0 - this makes the very first session pass the {@code currentRate <= samplingPercentage}
	 * gate (for any target above zero) and start recording, after which the ratio tracks the target.
	 *
	 * @return the current sampling rate as an integer percentage (0-100)
	 */
	private int computeCurrentSamplingRate() {
		final long recorded = this.recordedRecords.get() - this.samplingRecordedBaseline;
		final long sampledOut = this.missedRecordsByReason.get(TrafficRecorderMissReason.SAMPLING).get() -
			this.samplingSampledOutBaseline;
		final long recordedAndSampledOut = recorded + sampledOut;
		return recordedAndSampledOut <= 0 ?
			0 : (int) (((double) recorded / (double) recordedAndSampledOut) * 100.0);
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
	 * @param sessionId        id of the session the record belongs to; used to recover the discard reason
	 *                         when a record arrives after its session was already discarded
	 * @param sessionTraffic   the session traffic object containing the data to be recorded
	 * @param containerFactory the traffic recording container object containing the data to be recorded
	 *                         and its associated metadata
	 */
	private <T extends TrafficRecording> void doRecord(
		@Nonnull UUID sessionId,
		@Nullable SessionTraffic sessionTraffic,
		@Nonnull Function<SessionTraffic, T> containerFactory
	) {
		if (sessionTraffic != null && !sessionTraffic.isFinished()) {
			final T container = containerFactory.apply(sessionTraffic);
			sessionTraffic.record(
				container,
				ex -> discardSession(sessionTraffic, TrafficRecorderMissReason.MEMORY_SHORTAGE),
				ex -> {
					log.error("Failed to record traffic data for session {}.", sessionTraffic.getSessionId(), ex);
					discardSession(sessionTraffic, TrafficRecorderMissReason.SERIALIZATION_ERROR);
				},
				this.recordedRecords::incrementAndGet
			);
		} else {
			if (sessionTraffic != null) {
				sessionTraffic.registerRecordMissedOut();
			}
			// no active recording session for this record: it was either sampled out / never admitted (benign
			// SAMPLING), or already discarded under resource pressure - in which case attribute the record to the
			// real discard reason instead of masking it as SAMPLING. The isEmpty() guard keeps the common
			// no-discard-outstanding miss path free of the extra lookup.
			final TrafficRecorderMissReason reason = this.discardedSessionReasons.isEmpty()
				? TrafficRecorderMissReason.SAMPLING
				: this.discardedSessionReasons.getOrDefault(sessionId, TrafficRecorderMissReason.SAMPLING);
			this.missedRecordsByReason.get(reason).incrementAndGet();
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
			this.blocksAllocated.incrementAndGet();
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
		// capture the drain backlog BEFORE draining - after the drain it is ~0 and would never show buildup
		final int finalizedSessionsBacklog = this.finalizedSessions.size();
		drainFinalizedSessionsToDisk();

		// publish throughput/memory/churn snapshot + per-reason skip breakdown
		publishStatisticsEvents(finalizedSessionsBacklog);

		// if the disk buffer wasn't read for a long time, we can purge it
		if (this.lastRead > 0 && System.currentTimeMillis() - this.lastRead > INDEX_INACTIVITY_DURATION) {
			this.diskBuffer.releaseIndex();
		}

		return -1L;
	}

	/**
	 * Publishes the periodic traffic-recorder metrics: a {@link TrafficRecorderStatisticsEvent} snapshot
	 * (throughput counters as deltas since the previous emission, memory/churn state as instantaneous
	 * gauges) followed by one {@link TrafficRecorderSkippedRecordsEvent} per {@link TrafficRecorderMissReason}
	 * that saw activity (its missed/dropped counters as deltas). Emitting deltas is required because the
	 * metric pipeline increments the Prometheus counter by the committed value on each event; emitting the
	 * cumulative totals would over-count. Idle reasons are skipped to avoid emitting no-op zero increments.
	 *
	 * Invoked only while holding this recorder's monitor (from the {@code synchronized} {@link #freeMemory()}
	 * and marked {@code synchronized} itself so a direct test call is equally safe), which is what makes the
	 * plain {@code lastXxxEmitted} delta bookkeeping race-free.
	 *
	 * @param finalizedSessionsBacklog drain backlog sampled by the caller before the preceding drain, so the
	 *                                 gauge reflects accumulation rather than the post-drain (near-zero) state
	 */
	synchronized void publishStatisticsEvents(int finalizedSessionsBacklog) {
		final long createdSessionsNow = this.createdSessions.get();
		final long finishedSessionsNow = this.finishedSessions.get();
		final long recordedRecordsNow = this.recordedRecords.get();
		final long blocksAllocatedNow = this.blocksAllocated.get();
		final long diskBytesAppendedNow = this.diskBuffer.getBytesAppendedTotal();

		new TrafficRecorderStatisticsEvent(
			this.catalogName,
			createdSessionsNow - this.lastCreatedSessionsEmitted,
			finishedSessionsNow - this.lastFinishedSessionsEmitted,
			recordedRecordsNow - this.lastRecordedRecordsEmitted,
			blocksAllocatedNow - this.lastBlocksAllocatedEmitted,
			diskBytesAppendedNow - this.lastDiskBytesAppendedEmitted,
			this.totalMemoryBlocks - this.freeBlocks.size(),
			this.totalMemoryBlocks,
			this.trackedSessionsIndex.size(),
			finalizedSessionsBacklog,
			this.diskBuffer.getUsedBytes(),
			this.diskBuffer.getResidentSessionCount()
		).commit();

		this.lastCreatedSessionsEmitted = createdSessionsNow;
		this.lastFinishedSessionsEmitted = finishedSessionsNow;
		this.lastRecordedRecordsEmitted = recordedRecordsNow;
		this.lastBlocksAllocatedEmitted = blocksAllocatedNow;
		this.lastDiskBytesAppendedEmitted = diskBytesAppendedNow;

		// one event per reason that changed since the previous emission (idle reasons are skipped)
		for (final TrafficRecorderMissReason reason : MISS_REASONS) {
			final int ordinal = reason.ordinal();
			final long missedNow = this.missedRecordsByReason.get(reason).get();
			final long droppedNow = this.droppedSessionsByReason.get(reason).get();
			final long missedDelta = missedNow - this.lastMissedRecordsEmitted[ordinal];
			final long droppedDelta = droppedNow - this.lastDroppedSessionsEmitted[ordinal];
			if (missedDelta != 0 || droppedDelta != 0) {
				new TrafficRecorderSkippedRecordsEvent(
					this.catalogName, reason.name(), missedDelta, droppedDelta
				).commit();
				this.lastMissedRecordsEmitted[ordinal] = missedNow;
				this.lastDroppedSessionsEmitted[ordinal] = droppedNow;
			}
		}
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
	 *
	 * The type is marked {@link NotMonitored} because it is a benign, expected-and-handled control-flow
	 * signal (a skipped intercept), not an engine fault - it is tracked through the traffic-recorder skip
	 * metrics ({@link TrafficRecorderSkippedRecordsEvent}) rather than the internal-error metric.
	 */
	@NotMonitored
	public static class MemoryNotAvailableException extends EvitaInternalError {
		public static final MemoryNotAvailableException NO_SLOT_FREE =
			new MemoryNotAvailableException("No free slot in memory buffer!");
		public static final MemoryNotAvailableException DATA_TOO_LARGE =
			new MemoryNotAvailableException("Session data is too large to fit into the traffic disk buffer!");

		@Serial private static final long serialVersionUID = 567086221625997669L;

		public MemoryNotAvailableException() {
			super("Memory shortage during session recording.");
		}

		MemoryNotAvailableException(@Nonnull String message) {
			super(message);
		}

	}

}
