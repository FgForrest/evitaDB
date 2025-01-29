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

package io.evitadb.store.traffic;


import io.evitadb.api.exception.IndexNotReady;
import io.evitadb.api.requestResponse.trafficRecording.Label;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;
import io.evitadb.core.Transaction;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.function.LongIntLongObjectFunction;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.spi.SessionLocation;
import io.evitadb.store.spi.SessionSink;
import io.evitadb.store.spi.TrafficRecorder;
import io.evitadb.store.spi.TrafficRecorder.StreamDirection;
import io.evitadb.store.traffic.OffHeapTrafficRecorder.MemoryNotAvailableException;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.IOUtils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.utils.Assert.isPremiseValid;
import static io.evitadb.utils.Assert.notNull;
import static java.util.Optional.ofNullable;

/**
 * This class wraps the disk buffer file and provides methods for appending new sessions and reading the session records.
 * The disk buffer file is overwritten in a ring buffer fashion, where the head and tail pointers are used to determine
 * the currently meaningful span of data in the file. If the tail pointer is less than the head pointer, the data is
 * wrapped around the end of the file. Head and tail pointer respect the session boundaries, so there is usually a gap
 * between the tail and the head pointer representing the space of unusable partial data of the oldest session.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class DiskRingBuffer {
	public static final int LEAD_DESCRIPTOR_BYTE_SIZE = 16;
	/**
	 * Byte buffer used for writing the descriptor to the disk buffer file.
	 */
	private final ByteBuffer descriptorByteBuffer = ByteBuffer.allocate(LEAD_DESCRIPTOR_BYTE_SIZE);
	/**
	 * Sequence order for the sessions in the disk buffer file.
	 */
	private final AtomicLong sequenceOrder = new AtomicLong(0);
	/**
	 * Ordered queue of session locations in the disk buffer file.
	 */
	private final Deque<SessionLocation> sessionLocations = new ConcurrentLinkedDeque<>();
	/**
	 * Optional index, that is maintained if there is a reader that could use it.
	 */
	private final AtomicReference<TrafficRecordingIndex> sessionIndex = new AtomicReference<>();
	/**
	 * Atomic boolean used for locking the session index creation.
	 */
	private final AtomicBoolean sessionIndexingRunning = new AtomicBoolean();
	/**
	 * Contains set of postponed index updates, that were captured during initial session index creation.
	 * The reference is empty when no indexing is being done (i.e. almost always).
	 */
	private final AtomicReference<Deque<Runnable>> postponedIndexUpdates = new AtomicReference<>();
	/**
	 * Transaction used for writing to the session index.
	 */
	private final AtomicReference<Transaction> transaction = new AtomicReference<>();
	/**
	 * Path to file used for storing traffic data when they are completed in the memory buffer.
	 */
	private final Path diskBufferFilePath;
	/**
	 * File used for storing traffic data when they are completed in the memory buffer.
	 */
	@Getter private final RandomAccessFile diskBufferFile;
	/**
	 * Read input stream used for reading from the disk buffer file.
	 */
	private final Supplier<RandomAccessFileInputStream> diskBufferFileReadInputStreamFactory;
	/**
	 * Channel for the disk buffer file.
	 */
	private final FileChannel fileChannel;
	/**
	 * Size of the disk buffer file.
	 */
	@Getter private final long diskBufferFileSize;
	/**
	 * Consumer for the recorded data (optional).
	 */
	private final AtomicReference<SessionSink> sessionSink = new AtomicReference<>();
	/**
	 * Data available only when indexing is running on background.
	 */
	private final AtomicInteger indexedSessions = new AtomicInteger();
	/**
	 * Head of the ring buffer.
	 */
	@Getter(AccessLevel.PROTECTED)
	private long ringBufferHead = 0L;
	/**
	 * Tail of the ring buffer.
	 */
	@Getter(AccessLevel.PROTECTED)
	private long ringBufferTail = 0L;

	/**
	 * Last real sampling rate to be propagated to the sink.
	 */
	@Setter
	private int lastRealSamplingRate = 0;

	/**
	 * Determines if two file segments overlap based on their starting and ending positions.
	 *
	 * @param locationA the first file location to compare; can be null
	 * @param locationB the second file location to compare; can be null
	 * @return true if the segments overlap; false if either location is null or they do not overlap
	 */
	static boolean segmentsOverlap(@Nullable FileLocation locationA, @Nullable FileLocation locationB) {
		if (locationA == null || locationB == null) {
			return false;
		}
		return locationA.startingPosition() <= locationB.endPosition()
			&& locationB.startingPosition() <= locationA.endPosition();
	}

	/**
	 * Constructs a new DiskRingBuffer for managing a file-based ring buffer. This constructor
	 * initializes the specified disk buffer file for reading and writing, setting its size
	 * to the given file size.
	 *
	 * @param diskBufferFilePath the path to the disk buffer file where the ring buffer data is stored
	 * @param diskBufferFileSize the size of the disk buffer file in bytes
	 * @throws UnexpectedIOException if an I/O error occurs during the creation of the disk buffer file
	 */
	public DiskRingBuffer(
		@Nonnull Path diskBufferFilePath,
		long diskBufferFileSize
	) {
		try {
			this.diskBufferFilePath = diskBufferFilePath;
			this.diskBufferFileSize = diskBufferFileSize;

			// we need to start with empty file
			FileUtils.deleteFileIfExists(this.diskBufferFilePath);
			final File plainDiskBufferFile = this.diskBufferFilePath.toFile();

			this.diskBufferFile = new RandomAccessFile(plainDiskBufferFile, "rw");
			this.diskBufferFileReadInputStreamFactory = () -> {
				try {
					return new RandomAccessFileInputStream(
						new RandomAccessFile(plainDiskBufferFile, "r"), true
					);
				} catch (FileNotFoundException e) {
					throw new UnexpectedIOException(
						"Failed to create traffic recording buffer file input stream: " + e.getMessage(),
						"Failed to create traffic recording buffer file input stream.",
						e
					);
				}
			};
			// Initialize the file size (allocate space on disk)
			this.diskBufferFile.setLength(diskBufferFileSize);
			this.fileChannel = this.diskBufferFile.getChannel();
		} catch (Exception e) {
			throw new UnexpectedIOException(
				"Failed to create traffic recording buffer file: " + e.getMessage(),
				"Failed to create traffic recording buffer file.",
				e
			);
		}
	}

	/**
	 * Allows to export session data before being deleted.
	 *
	 * @param sessionSink the session sink to be set
	 */
	public void setSessionSink(@Nullable SessionSink sessionSink) {
		if (sessionSink != null) {
			try {
				sessionSink.initSourceInputStream(
					new RandomAccessFileInputStream(
						new RandomAccessFile(this.diskBufferFilePath.toFile(), "r"),
						true
					)
				);
			} catch (FileNotFoundException e) {
				throw new UnexpectedIOException(
					"Failed to create traffic recording buffer file input stream: " + e.getMessage(),
					"Failed to create traffic recording buffer file input stream.",
					e
				);
			}
		} else {
			final SessionSink currentSessionSing = this.sessionSink.get();
			if (currentSessionSing != null) {
				currentSessionSing.onClose(this.sessionLocations, this.lastRealSamplingRate);
			}
		}
		this.sessionSink.set(sessionSink);
	}

	/**
	 * Executes the given lambda within a transaction if a session index is present.
	 * If no session index is available, the lambda will be executed directly.
	 *
	 * @param lambda A Runnable containing the code to execute within a transaction.
	 *               Must not be null.
	 */
	public void updateIndexTransactionally(@Nonnull Runnable lambda) {
		final TrafficRecordingIndex index = this.sessionIndex.get();
		if (index != null) {
			final Transaction transaction = new Transaction(index);
			isPremiseValid(
				this.transaction.compareAndSet(null, transaction),
				"Transaction already exists. This is not expected!"
			);
			try {
				Transaction.executeInTransactionIfProvided(
					transaction,
					lambda
				);
			} catch (Exception ex) {
				log.error("Error during transactional write: " + ex.getMessage());
			} finally {
				transaction.close();
				ofNullable(transaction.getCommitedState())
					.ifPresent(it -> this.sessionIndex.set((TrafficRecordingIndex) it));
				isPremiseValid(
					this.transaction.compareAndSet(transaction, null),
					"Transaction was removed. This is not expected!"
				);
			}
		} else {
			lambda.run();
		}
	}

	/**
	 * Appends a new session descriptor to the disk buffer file. The descriptor consists of:
	 *
	 * - 8 bytes for the sequence order (mono-increasing number of appended sessions)
	 * - 4 bytes for the total size of the serialized session traffic records in Bytes
	 *
	 * @param totalSize total size of the serialized session traffic records in Bytes
	 * @return sequence order of the appended session
	 */
	@Nonnull
	public SessionLocation appendSession(int sessionRecordsCount, int totalSize) {
		final int totalSizeWithHeader = totalSize + LEAD_DESCRIPTOR_BYTE_SIZE;
		if (totalSizeWithHeader > this.diskBufferFileSize) {
			throw MemoryNotAvailableException.DATA_TOO_LARGE;
		}

		final long sessionSequenceOrder = this.sequenceOrder.incrementAndGet();
		final FileLocation fileLocation = new FileLocation(this.ringBufferTail, totalSizeWithHeader);
		final SessionLocation sessionLocation = new SessionLocation(sessionSequenceOrder, sessionRecordsCount, fileLocation);

		// Prepare descriptor
		this.descriptorByteBuffer.putLong(sessionSequenceOrder);
		this.descriptorByteBuffer.putInt(sessionRecordsCount);
		this.descriptorByteBuffer.putInt(totalSize);
		this.descriptorByteBuffer.flip();

		// Write descriptor
		this.append(this.descriptorByteBuffer);
		this.descriptorByteBuffer.clear();

		return sessionLocation;
	}

	/**
	 * Appends the given memory buffer to the disk buffer file.
	 *
	 * @param memoryByteBuffer source memory buffer
	 */
	public void append(@Nonnull ByteBuffer memoryByteBuffer) {
		try {
			final int totalBytesToWrite = memoryByteBuffer.limit();
			if (totalBytesToWrite > this.diskBufferFileSize) {
				throw MemoryNotAvailableException.DATA_TOO_LARGE;
			}

			final int lengthToWrite = Math.min(Math.toIntExact(this.diskBufferFileSize - this.ringBufferTail), totalBytesToWrite);
			updateSessionLocations(totalBytesToWrite);

			if (lengthToWrite < totalBytesToWrite) {
				lockAndWrite(
					new FileLocation(this.fileChannel.position(), lengthToWrite),
					() -> writeDataToFileChannel(memoryByteBuffer.slice(0, lengthToWrite), lengthToWrite)
				);
				this.fileChannel.position(0);
				lockAndWrite(
					new FileLocation(this.fileChannel.position(), totalBytesToWrite - lengthToWrite),
					() -> {
						final int restLengthToWrite = totalBytesToWrite - lengthToWrite;
						writeDataToFileChannel(memoryByteBuffer.slice(lengthToWrite, restLengthToWrite), restLengthToWrite);
					}
				);
			} else {
				lockAndWrite(
					new FileLocation(this.fileChannel.position(), totalBytesToWrite),
					() -> writeDataToFileChannel(memoryByteBuffer, totalBytesToWrite)
				);
			}
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to append traffic recording buffer file: " + e.getMessage(),
				"Failed to append traffic recording buffer file.",
				e
			);
		}
	}

	/**
	 * Finalizes the writing of a session to the disk buffer and updates the index if present.
	 *
	 * @param sessionLocation   the location of the session in the disk buffer
	 * @param sessionId         the unique identifier for the session
	 * @param created           the creation time of the session
	 * @param durationInMillis  the duration of the session in milliseconds
	 * @param recordingTypes    the set of recording types associated with the session
	 * @param fetchCount        the number of fetch operations that occurred during the session
	 * @param bytesFetchedTotal the total number of bytes fetched during the session
	 */
	public void sessionWritten(
		@Nonnull SessionLocation sessionLocation,
		@Nonnull UUID sessionId,
		@Nonnull OffsetDateTime created,
		int durationInMillis,
		@Nonnull Set<TrafficRecordingType> recordingTypes,
		@Nonnull Set<Label> labels,
		int fetchCount,
		int bytesFetchedTotal
	) {
		// update index if present
		this.sessionLocations.add(sessionLocation);
		final TrafficRecordingIndex index = this.sessionIndex.get();
		if (index != null) {
			index.setupSession(
				sessionLocation,
				sessionId,
				created,
				durationInMillis,
				fetchCount,
				bytesFetchedTotal,
				recordingTypes,
				labels
			);
		} else {
			ofNullable(this.postponedIndexUpdates.get())
				.ifPresent(
					postponedUpdates -> postponedUpdates.add(
						() -> {
							final TrafficRecordingIndex newIndex = this.sessionIndex.get();
							newIndex.setupSession(
								sessionLocation,
								sessionId,
								created,
								durationInMillis,
								fetchCount,
								bytesFetchedTotal,
								recordingTypes,
								labels
							);
						}
					)
				);
		}
	}

	/**
	 * Retrieves a stream of TrafficRecording objects based on the criteria specified in the given request.
	 * The TrafficRecording objects are filtered according to the criteria from the request. Method creates missing
	 * memory index if it is not present yet.
	 *
	 * @param request the TrafficRecordingCaptureRequest containing criteria to filter the recordings.
	 * @param reader  a function that retrieves a StorageRecord of TrafficRecording given a long identifier.
	 * @return a stream of TrafficRecording objects matching the request criteria.
	 */
	@Nonnull
	public Stream<TrafficRecording> getSessionRecordsStream(
		@Nonnull TrafficRecordingCaptureRequest request,
		@Nonnull LongIntLongObjectFunction<RandomAccessFileInputStream, StorageRecord<TrafficRecording>> reader
	) {
		TrafficRecordingIndex sessionIndex = this.sessionIndex.get();
		notNull(sessionIndex, () -> new IndexNotReady(calculateIndexingPercentage()));

		final RandomAccessFileInputStream inputStream = this.getDiskBufferFileReadInputStream();
		final Predicate<TrafficRecording> requestPredicate = TrafficRecorder.createRequestPredicate(request, StreamDirection.FORWARD);
		return sessionIndex.getSessionStream(request)
			.flatMap(
				it -> this.readSessionRecords(
					it.sequenceOrder(), it.sessionRecordsCount(), it.fileLocation(), inputStream, reader
				)
			)
			.filter(requestPredicate)
			.onClose(inputStream::close);
	}

	/**
	 * Retrieves a stream of TrafficRecording objects based on the criteria specified in the given request.
	 * The TrafficRecording objects are filtered according to the criteria from the request. Method creates missing
	 * memory index if it is not present yet. The stream is ordered in reversed order (newest records first).
	 *
	 * @param request the TrafficRecordingCaptureRequest containing criteria to filter the recordings.
	 * @param reader  a function that retrieves a StorageRecord of TrafficRecording given a long identifier.
	 * @return a stream of TrafficRecording objects matching the request criteria.
	 */
	@Nonnull
	public Stream<TrafficRecording> getSessionRecordsReversedStream(
		@Nonnull TrafficRecordingCaptureRequest request,
		@Nonnull LongIntLongObjectFunction<RandomAccessFileInputStream, StorageRecord<TrafficRecording>> reader
	) {
		TrafficRecordingIndex sessionIndex = this.sessionIndex.get();
		notNull(sessionIndex, () -> new IndexNotReady(calculateIndexingPercentage()));

		final RandomAccessFileInputStream inputStream = this.getDiskBufferFileReadInputStream();
		final Predicate<TrafficRecording> requestPredicate = TrafficRecorder.createRequestPredicate(request, StreamDirection.REVERSE);
		return sessionIndex.getSessionReversedStream(request)
			.flatMap(
				it -> {
					// this is inefficient, but we need to reverse the order of the records and there is no other simple way around
					// if it happens to be slow in real world scenarios, we'd have to add a support to the index
					final List<TrafficRecording> recordings = this.readSessionRecords(
						it.sequenceOrder(), it.sessionRecordsCount(), it.fileLocation(), inputStream, reader
					).collect(Collectors.toCollection(ArrayList::new));
					Collections.reverse(recordings);
					return recordings.stream();
				}
			)
			.filter(requestPredicate)
			.onClose(inputStream::close);
	}

	@Nonnull
	public Collection<String> getLabelsNamesOrderedByCardinality(
		@Nullable String nameStartingWith,
		int limit
	) {
		TrafficRecordingIndex sessionIndex = this.sessionIndex.get();
		notNull(sessionIndex, () -> new IndexNotReady(calculateIndexingPercentage()));

		return sessionIndex.getLabelsNamesOrderedByCardinality(nameStartingWith, limit);
	}

	@Nonnull
	public Collection<String> getLabelValuesOrderedByCardinality(
		@Nonnull String nameEquals,
		@Nullable String valueStartingWith,
		int limit
	) {
		TrafficRecordingIndex sessionIndex = this.sessionIndex.get();
		notNull(sessionIndex, () -> new IndexNotReady(calculateIndexingPercentage()));

		return sessionIndex.getLabelValuesOrderedByCardinality(nameEquals, valueStartingWith, limit);
	}

	/**
	 * Indexes session data from previously recorded traffic by reading session
	 * locations and recording data using the provided reader function.
	 * Each session is set up in the index to manage its existence and integrity
	 * in the index, allowing updates and early removals as needed.
	 *
	 * @param reader a function that provides access to a storage record of
	 *               TrafficRecording instances, given a long identifier.
	 */
	public void indexData(
		@Nonnull LongIntLongObjectFunction<RandomAccessFileInputStream, StorageRecord<TrafficRecording>> reader
	) {
		if (this.sessionIndexingRunning.compareAndSet(false, true)) {
			try (final RandomAccessFileInputStream diskBufferFileReadInputStream = this.getDiskBufferFileReadInputStream()) {
				this.indexedSessions.set(0);
				this.postponedIndexUpdates.set(new ArrayDeque<>(512));
				final TrafficRecordingIndex index = new TrafficRecordingIndex();
				for (SessionLocation sessionLocation : this.sessionLocations) {
					// we need to set up the session in the index first, so that `index::sessionExists` returns true
					// and also to allow write logic to remove the session early when overwritten by the new data
					index.setupSession(sessionLocation);
					this.readSessionRecords(
							sessionLocation.sequenceOrder(),
							sessionLocation.sessionRecordsCount(),
							sessionLocation.fileLocation(),
							diskBufferFileReadInputStream,
							reader
						)
						.forEach(tr -> index.indexRecording(sessionLocation.sequenceOrder(), tr));
					this.indexedSessions.incrementAndGet();
				}

				// when session index is ready, set it as the active index
				this.sessionIndex.set(index);
			} finally {
				final Deque<Runnable> theLambdasToExecute = this.postponedIndexUpdates.getAndSet(null);
				notNull(theLambdasToExecute, "Postponed index updates are null. This is not expected!");
				theLambdasToExecute.forEach(lambda -> {
					lambda.run();
					this.indexedSessions.incrementAndGet();
				});
				isPremiseValid(
					this.sessionIndexingRunning.compareAndSet(true, false),
					"Session indexing is not running. This is not expected!"
				);
			}
		}
	}

	/**
	 * Method to release the memory index if it is no longer needed.
	 */
	public void releaseIndex() {
		this.sessionIndex.set(null);
	}

	/**
	 * Closes the file channel and the disk buffer file associated with the DiskRingBuffer.
	 * After successfully closing these resources, it executes the provided file clean-up logic
	 * on the disk buffer file path to perform additional resource management or clean-up tasks.
	 *
	 * @param fileCleanLogic a Consumer that defines the clean-up logic to be executed on the
	 *                       disk buffer file path, ensuring that any necessary operations are
	 *                       performed after closing the resources.
	 * @throws UnexpectedIOException if an I/O error occurs while attempting to close the file
	 *                               channel or the disk buffer file.
	 */
	public void close(@Nonnull Consumer<Path> fileCleanLogic) {
		try {
			final SessionSink theSink = this.sessionSink.get();
			if (theSink != null) {
				theSink.onClose(this.sessionLocations, this.lastRealSamplingRate);
			}

			this.sessionLocations.clear();
			this.sessionIndex.set(null);
			IOUtils.close(
				() -> new UnexpectedIOException(
					"Failed to close traffic recording buffer file: " + this.diskBufferFilePath.toString(),
					"Failed to close traffic recording buffer file."
				),
				this.fileChannel::close,
				this.diskBufferFile::close
			);
		} finally {
			fileCleanLogic.accept(this.diskBufferFilePath);
		}
	}

	/**
	 * Creates new input stream for reading the disk buffer file. The caller is responsible for closing the stream.
	 *
	 * @return the input stream for reading the disk buffer file
	 */
	@Nonnull
	public RandomAccessFileInputStream getDiskBufferFileReadInputStream() {
		return this.diskBufferFileReadInputStreamFactory.get();
	}

	/**
	 * Calculates the percentage of indexed sessions in relation to the total number of sessions
	 * including postponed index updates.
	 *
	 * @return the indexing percentage as an integer value.
	 */
	private int calculateIndexingPercentage() {
		return (int) (
			((float) this.indexedSessions.get() /
				(float) (this.sessionLocations.size() +
					ofNullable(this.postponedIndexUpdates.get()).map(Deque::size).orElse(0))
			) * 100.0
		);
	}

	/**
	 * Writes data from the provided ByteBuffer to the associated FileChannel until the specified number of bytes is written.
	 *
	 * @param memoryByteBuffer  the ByteBuffer containing the data to be written to the FileChannel. Must not be null.
	 * @param totalBytesToWrite the total number of bytes to write from the ByteBuffer to the FileChannel.
	 * @throws IOException if an I/O error occurs during the write operation.
	 */
	private void writeDataToFileChannel(@Nonnull ByteBuffer memoryByteBuffer, int totalBytesToWrite) throws IOException {
		int totalBytesWritten = 0;
		while (totalBytesWritten < totalBytesToWrite) {
			int writtenBytes = this.fileChannel.write(memoryByteBuffer);
			totalBytesWritten += writtenBytes;
			isPremiseValid(writtenBytes > 0, "Failed to write all bytes to the disk buffer file.");
		}
		isPremiseValid(totalBytesWritten == totalBytesToWrite, "Failed to write all bytes to the disk buffer file.");
	}

	/**
	 * Updates the session locations within the disk buffer and adjusts the position
	 * of the ring buffer tail according to the specified amount of bytes to write.
	 * This method removes all session locations that are no longer relevant based on
	 * the current head and tail positions of the ring buffer and if the session index
	 * is present, it removes the session from the index as well.
	 *
	 * @param totalBytesToWrite the total number of bytes that are written to the ring buffer,
	 *                          used to adjust the position of the ring buffer tail.
	 */
	private void updateSessionLocations(int totalBytesToWrite) throws IOException {
		final long newTail = this.ringBufferTail + totalBytesToWrite;
		SessionLocation head = this.sessionLocations.peekFirst();
		while (head != null) {
			final TrafficRecordingIndex index = this.sessionIndex.get();
			final LongNumberRange[] erasedArea = newTail <= this.diskBufferFileSize ?
				new LongNumberRange[]{LongNumberRange.between(this.ringBufferTail + 1, newTail)} :
				Stream.of(
						LongNumberRange.between(this.ringBufferTail + 1, this.diskBufferFileSize),
						LongNumberRange.between(0L, newTail - this.diskBufferFileSize)
					)
					.filter(it -> it.getFrom() < it.getTo())
					.toArray(LongNumberRange[]::new);

			// if the session precedes the head of the ring buffer, remove it
			if (isWasted(erasedArea, head.fileLocation())) {
				this.sessionLocations.removeFirst();
				// remove the session from the index if present
				if (index != null) {
					index.removeSession(head.sequenceOrder());
				}
				// update the head to check the next session
				head = this.sessionLocations.peekFirst();
			} else {
				break;
			}
		}

		this.ringBufferHead = this.sessionLocations.isEmpty() ? this.ringBufferHead : this.sessionLocations.peekFirst().fileLocation().startingPosition();
		this.ringBufferTail = (this.ringBufferTail + totalBytesToWrite) % this.diskBufferFileSize;

		final SessionSink theSink = this.sessionSink.get();
		if (theSink != null) {
			theSink.onSessionLocationsUpdated(this.sessionLocations, this.lastRealSamplingRate);
		}
	}

	/**
	 * Determines whether a given recordPosition is outside the valid range
	 * of the ring buffer based on the current head and tail positions.
	 *
	 * @param recordPosition the recordPosition to check against the ring buffer's valid range
	 * @return true if the recordPosition is outside the valid range of the ring buffer; false otherwise
	 */
	private boolean isWasted(@Nonnull LongNumberRange[] waste, @Nonnull FileLocation recordPosition) {
		final LongNumberRange[] recordRanges = recordPosition.endPosition() <= this.diskBufferFileSize ?
			new LongNumberRange[]{LongNumberRange.between(recordPosition.startingPosition(), recordPosition.endPosition())} :
			new LongNumberRange[]{
				LongNumberRange.between(recordPosition.startingPosition(), this.diskBufferFileSize),
				LongNumberRange.between(0L, recordPosition.endPosition() - this.diskBufferFileSize)
			};
		return Arrays.stream(waste)
			.anyMatch(wasteRange -> Arrays.stream(recordRanges).anyMatch(wasteRange::overlaps));
	}

	/**
	 * Reads session records from a specified file location and provides a stream of TrafficRecording objects.
	 * The method ensures that the records are read only if the session exists and the file location is updated
	 * accordingly to prevent redundant reads.
	 *
	 * @param sessionSequenceId the unique identifier for the session sequence to read records for
	 * @param fileLocation      the file location specifying where to read the session records from
	 * @param inputStream       the input stream for reading the disk buffer file
	 * @param reader            a function that reads a StorageRecord of TrafficRecording from a given position
	 * @return a stream of TrafficRecording objects read from the specified file location
	 */
	@Nonnull
	private Stream<TrafficRecording> readSessionRecords(
		long sessionSequenceId,
		int sessionRecordsCount,
		@Nonnull FileLocation fileLocation,
		@Nonnull RandomAccessFileInputStream inputStream,
		@Nonnull LongIntLongObjectFunction<RandomAccessFileInputStream, StorageRecord<TrafficRecording>> reader
	) {
		final AtomicLong lastLocationRead = new AtomicLong(-1);
		return Stream.generate(
				() -> lockAndRead(
					fileLocation,
					() -> {
						if (!isSessionLocationStillInValidArea(fileLocation)) {
							// session was already removed in the meantime
							return null;
						} else {
							final long lastFileLocation = lastLocationRead.get();
							// finalize stream when the expected session end position is reached
							if (lastFileLocation != -1L && lastFileLocation == fileLocation.endPosition() % this.diskBufferFileSize) {
								return null;
							} else {
								// read the next record from the file
								final long startPosition = lastLocationRead.get() == -1 ?
									fileLocation.startingPosition() + LEAD_DESCRIPTOR_BYTE_SIZE :
									lastFileLocation;
								try {
									final StorageRecord<TrafficRecording> tr = reader.apply(
										sessionSequenceId, sessionRecordsCount, startPosition, inputStream
									);
									if (tr == null) {
										// finalize the stream on first error
										return null;
									} else {
										lastLocationRead.set((startPosition + tr.fileLocation().recordLength()) % this.diskBufferFileSize);
										// return the payload of the record
										return tr.payload();
									}
								} catch (Exception ex) {
									log.error(
										"Error reading session #{} traffic record from disk buffer at position {}: {}",
										sessionSequenceId, startPosition, ex.getMessage()
									);
									return null;
								}
							}
						}
					}
				)
			)
			.takeWhile(Objects::nonNull);
	}

	/**
	 * Checks if the provided file location is still within a valid area
	 * of the session's ring buffer.
	 *
	 * @param fileLocation the file location to check, containing starting and
	 *                     ending positions.
	 * @return true if the file location is within the valid area of the ring buffer,
	 * false otherwise.
	 */
	private boolean isSessionLocationStillInValidArea(@Nonnull FileLocation fileLocation) {
		final LongNumberRange[] validArea = this.ringBufferHead <= this.ringBufferTail ?
			new LongNumberRange[]{LongNumberRange.between(this.ringBufferHead, this.ringBufferTail)} :
			new LongNumberRange[]{
				LongNumberRange.between(this.ringBufferHead, this.diskBufferFileSize),
				LongNumberRange.between(0L, this.ringBufferTail)
			};
		return Arrays.stream(validArea)
			.anyMatch(va -> va.isWithin(fileLocation.startingPosition()) && va.isWithin(fileLocation.endPosition())) ||
			(validArea.length == 2 && validArea[0].isWithin(fileLocation.startingPosition()) && validArea[1].isWithin(fileLocation.endPosition()));
	}

	/**
	 * Acquires a lock on the specified write segment and writes data to it using the provided
	 * write lambda function. The method ensures that the write segment is not currently being read or
	 * being written by a different session before proceeding. After writing, it releases the lock
	 * on the write segment.
	 *
	 * @param writeSegment the file location to be locked and written to
	 * @param writeLambda  the lambda function that performs the write operation
	 * @throws IOException if an I/O error occurs during the write process
	 */
	private void lockAndWrite(
		@Nonnull FileLocation writeSegment,
		@Nonnull IOExceptionThrowingLambda writeLambda
	) throws IOException {
		FileLock lock = null;
		try {
			lock = this.fileChannel.lock(
				writeSegment.startingPosition(),
				writeSegment.recordLength(),
				false
			);

			writeLambda.run();

		} catch (IOException e) {
			log.error("Failed to finalize writing of session: " + e.getMessage(), e);
		} finally {
			if (lock != null) {
				IOUtils.closeQuietly(lock::release);
			}
		}
	}

	/**
	 * Acquires a lock on a specified read segment and attempts to execute a given operation
	 * while ensuring that no read or write operations currently exist on the segment.
	 * If successful, executes the provided lambda function and subsequently releases the lock.
	 *
	 * @param readSegment the file location to be locked for reading
	 * @param readLambda  the operation to be performed while the read segment is locked
	 * @return the result of the readLambda execution if successfully executed while the segment
	 * is locked, otherwise returns null if the lock could not be acquired
	 */
	@Nullable
	private <T> T lockAndRead(
		@Nonnull FileLocation readSegment,
		@Nonnull Supplier<T> readLambda
	) {
		FileLock lock = null;
		try {
			lock = this.fileChannel.lock(
				readSegment.startingPosition(),
				readSegment.recordLength(),
				true
			);

			return readLambda.get();

		} catch (OverlappingFileLockException e) {
			log.debug("Failed to acquire lock on read segment: " + e.getMessage());
			return null;
		} catch (IOException e) {
			log.error("Failed to finalize writing of session: " + e.getMessage(), e);
			return null;
		} finally {
			if (lock != null) {
				IOUtils.closeQuietly(lock::release);
			}
		}
	}

	/**
	 * A functional interface that represents a lambda expression or method reference
	 * that can throw an IOException during execution. This interface can be used
	 * when a lambda needs to handle IO operations that may result in an IOException
	 * and allows for concise handling of such exceptions.
	 */
	@FunctionalInterface
	private interface IOExceptionThrowingLambda {

		/**
		 * Executes a block of code encapsulated by this method, potentially throwing an IOException.
		 * This method is intended to be implemented by lambda expressions or method references
		 * that perform I/O operations. The implementation should handle necessary I/O logic
		 * and any IOException that may occur during execution.
		 *
		 * @throws IOException if an I/O error occurs during the execution of the method.
		 */
		void run() throws IOException;

	}

}
