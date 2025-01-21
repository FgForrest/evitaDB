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
import io.evitadb.api.requestResponse.trafficRecording.QueryContainer;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;
import io.evitadb.core.Transaction;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.function.LongBiObjectFunction;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.offsetIndex.stream.RandomAccessFileInputStream;
import io.evitadb.store.traffic.OffHeapTrafficRecorder.MemoryNotAvailableException;
import io.evitadb.utils.IOUtils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
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
	public static final int LEAD_DESCRIPTOR_BYTE_SIZE = 12;
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
	private final AtomicReference<DiskRingBufferIndex> sessionIndex = new AtomicReference<>();
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
	@Getter private final RandomAccessFileInputStream diskBufferFileReadInputStream;
	/**
	 * Channel for the disk buffer file.
	 */
	private final FileChannel fileChannel;
	/**
	 * Size of the disk buffer file.
	 */
	@Getter private final long diskBufferFileSize;
	/**
	 * Atomic reference holding the segments which are currently in active use (read / write).
	 */
	private final AtomicReference<ActiveSegments> activeSegments = new AtomicReference<>(new ActiveSegments());
	/**
	 * Lock with condition for the segments atomic update.
	 */
	private final ReentrantLock segmentsLock = new ReentrantLock();
	private final Condition segmentsCondition = segmentsLock.newCondition();
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
	 * Creates a predicate for filtering TrafficRecording objects based on the criteria specified
	 * in the given TrafficRecordingCaptureRequest. The predicate checks each TrafficRecording
	 * against multiple criteria including type, sessionId, creation time, fetched bytes, and duration.
	 *
	 * @param request the TrafficRecordingCaptureRequest containing the criteria to be used for filtering
	 * @return a predicate that evaluates to true for TrafficRecording objects matching the specified criteria
	 */
	@Nonnull
	private static Predicate<TrafficRecording> createRequestPredicate(@Nonnull TrafficRecordingCaptureRequest request) {
		Predicate<TrafficRecording> requestPredicate = tr -> true;
		if (request.sessionId() != null) {
			requestPredicate = requestPredicate.and(
				tr -> request.sessionId().equals(tr.sessionId())
			);
		}
		if (request.since() != null) {
			requestPredicate = requestPredicate.and(
				tr -> tr.created().isAfter(request.since())
			);
		}
		if (request.fetchingMoreBytesThan() != null) {
			requestPredicate = requestPredicate.and(
				tr -> tr.ioFetchedSizeBytes() > request.fetchingMoreBytesThan()
			);
		}
		if (request.longerThan() != null) {
			final long thresholdMillis = request.longerThan().toMillis();
			requestPredicate = requestPredicate.and(
				trafficRecording -> trafficRecording.durationInMilliseconds() > thresholdMillis
			);
		}
		if (request.types() != null) {
			requestPredicate = requestPredicate.and(
				tr -> Arrays.stream(request.types()).anyMatch(it -> it == tr.type())
			);
		}
		if (request.labels() != null) {
			requestPredicate = requestPredicate.and(
				tr -> tr instanceof QueryContainer qc &&
					Arrays.stream(request.labels()).anyMatch(it -> Arrays.asList(qc.labels()).contains(it))
			);
		}
		return requestPredicate;
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
			this.diskBufferFile = new RandomAccessFile(this.diskBufferFilePath.toFile(), "rw");
			this.diskBufferFileReadInputStream = new RandomAccessFileInputStream(new RandomAccessFile(this.diskBufferFilePath.toFile(), "r"));
			// Initialize the file size
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
	 * Executes the given lambda within a transaction if a session index is present.
	 * If no session index is available, the lambda will be executed directly.
	 *
	 * @param lambda A Runnable containing the code to execute within a transaction.
	 *               Must not be null.
	 */
	public void updateIndexTransactionally(@Nonnull Runnable lambda) {
		final DiskRingBufferIndex index = this.sessionIndex.get();
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
					.ifPresent(it -> this.sessionIndex.set((DiskRingBufferIndex) it));
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
	public SessionLocation appendSession(int totalSize) {
		final int totalSizeWithHeader = totalSize + LEAD_DESCRIPTOR_BYTE_SIZE;
		if (totalSizeWithHeader > this.diskBufferFileSize) {
			throw MemoryNotAvailableException.DATA_TOO_LARGE;
		}

		final long sessionSequenceOrder = this.sequenceOrder.incrementAndGet();
		final FileLocation fileLocation = new FileLocation(this.ringBufferTail, totalSizeWithHeader);
		final SessionLocation sessionLocation = new SessionLocation(sessionSequenceOrder, fileLocation);

		// Prepare descriptor
		this.descriptorByteBuffer.putLong(sessionSequenceOrder);
		this.descriptorByteBuffer.putInt(totalSize);
		this.descriptorByteBuffer.flip();

		// Write descriptor
		this.append(sessionLocation, this.descriptorByteBuffer);
		this.descriptorByteBuffer.clear();

		return sessionLocation;
	}

	/**
	 * Appends the given memory buffer to the disk buffer file.
	 *
	 * @param memoryByteBuffer source memory buffer
	 */
	public void append(@Nonnull SessionLocation sessionLocation, @Nonnull ByteBuffer memoryByteBuffer) {
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
					sessionLocation,
					() -> writeDataToFileChannel(memoryByteBuffer.slice(0, lengthToWrite), lengthToWrite)
				);
				this.fileChannel.position(0);
				lockAndWrite(
					new FileLocation(this.fileChannel.position(), totalBytesToWrite - lengthToWrite),
					sessionLocation,
					() -> {
						final int restLengthToWrite = totalBytesToWrite - lengthToWrite;
						writeDataToFileChannel(memoryByteBuffer.slice(lengthToWrite, restLengthToWrite), restLengthToWrite);
					}
				);
			} else {
				lockAndWrite(
					new FileLocation(this.fileChannel.position(), totalBytesToWrite),
					sessionLocation,
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
		// finish writing the session
		ActiveSegments newValue;
		do {
			newValue = updateSegments(
				() -> this.activeSegments.updateAndGet(
					currentlyActiveSegments -> {
						isPremiseValid(
							currentlyActiveSegments.writeSegment() == null,
							"Write segment is locked. This is not expected!"
						);
						isPremiseValid(
							currentlyActiveSegments.sessionBeingWritten() == sessionLocation,
							"Different session is being written. This is not expected!"
						);
						return new ActiveSegments(
							currentlyActiveSegments.readSegment(),
							null,
							null
						);
					}
				)
			);
		} while (newValue.sessionBeingWritten() != null);

		// update index if present
		this.sessionLocations.add(sessionLocation);
		final DiskRingBufferIndex index = this.sessionIndex.get();
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
							final DiskRingBufferIndex newIndex = this.sessionIndex.get();
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
		@Nonnull LongBiObjectFunction<DiskRingBuffer, StorageRecord<TrafficRecording>> reader
	) {
		DiskRingBufferIndex sessionIndex = this.sessionIndex.get();
		notNull(sessionIndex, IndexNotReady::new);

		final Predicate<TrafficRecording> requestPredicate = createRequestPredicate(request);
		return sessionIndex.getSessionStream(request)
			.flatMap(it -> this.readSessionRecords(it.sequenceOrder(), it.fileLocation(), reader, sessionIndex::sessionExists))
			.filter(requestPredicate);
	}

	@Nonnull
	public Collection<String> getLabelsNamesOrderedByCardinality(
		@Nullable String nameStartingWith,
		int limit
	) {
		DiskRingBufferIndex sessionIndex = this.sessionIndex.get();
		notNull(sessionIndex, IndexNotReady::new);

		return sessionIndex.getLabelsNamesOrderedByCardinality(nameStartingWith, limit);
	}

	@Nonnull
	public Collection<String> getLabelValuesOrderedByCardinality(
		@Nonnull String nameEquals,
		@Nullable String valueStartingWith,
		int limit
	) {
		DiskRingBufferIndex sessionIndex = this.sessionIndex.get();
		notNull(sessionIndex, IndexNotReady::new);

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
		@Nonnull LongBiObjectFunction<DiskRingBuffer, StorageRecord<TrafficRecording>> reader
	) {
		if (this.sessionIndexingRunning.compareAndSet(false, true)) {
			try {
				this.postponedIndexUpdates.set(new ArrayDeque<>(512));
				final DiskRingBufferIndex index = new DiskRingBufferIndex();
				for (SessionLocation sessionLocation : this.sessionLocations) {
					// we need to set up the session in the index first, so that `index::sessionExists` returns true
					// and also to allow write logic to remove the session early when overwritten by the new data
					index.setupSession(sessionLocation);
					this.readSessionRecords(sessionLocation.sequenceOrder(), sessionLocation.fileLocation(), reader, index::sessionExists)
						.forEach(tr -> index.indexRecording(sessionLocation.sequenceOrder(), tr));
				}

				// when session index is ready, set it as the active index
				this.sessionIndex.set(index);
			} finally {
				final Deque<Runnable> theLambdasToExecute = this.postponedIndexUpdates.getAndSet(null);
				notNull(theLambdasToExecute, "Postponed index updates are null. This is not expected!");
				theLambdasToExecute.forEach(Runnable::run);
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
			this.sessionIndex.set(null);
			IOUtils.close(
				() -> new UnexpectedIOException(
					"Failed to close traffic recording buffer file: " + this.diskBufferFilePath.toString(),
					"Failed to close traffic recording buffer file."
				),
				this.fileChannel::close,
				this.diskBufferFileReadInputStream::close
			);
		} finally {
			fileCleanLogic.accept(this.diskBufferFilePath);
		}
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
			final DiskRingBufferIndex index = this.sessionIndex.get();
			final LongNumberRange[] erasedArea = newTail <= this.diskBufferFileSize ?
				new LongNumberRange[] { LongNumberRange.between(this.ringBufferTail + 1, newTail) } :
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
			new LongNumberRange[] { LongNumberRange.between(recordPosition.startingPosition(), recordPosition.endPosition()) } :
			new LongNumberRange[] {
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
	 * @param sessionSequenceId       the unique identifier for the session sequence to read records for
	 * @param fileLocation            the file location specifying where to read the session records from
	 * @param reader                  a function that reads a StorageRecord of TrafficRecording from a given position
	 * @param sessionExistenceChecker a predicate that determines if the session exists based on its sequence ID
	 * @return a stream of TrafficRecording objects read from the specified file location
	 */
	@Nonnull
	private Stream<TrafficRecording> readSessionRecords(
		long sessionSequenceId,
		@Nonnull FileLocation fileLocation,
		@Nonnull LongBiObjectFunction<DiskRingBuffer, StorageRecord<TrafficRecording>> reader,
		@Nonnull LongPredicate sessionExistenceChecker
	) {
		final AtomicLong lastLocationRead = new AtomicLong(-1);
		return Stream.generate(
				() -> lockAndRead(
					fileLocation,
					() -> {
						final long lastFileLocation = lastLocationRead.get();
						// check if the session still exists before reading the records
						if (sessionExistenceChecker.test(sessionSequenceId)) {
							// finalize stream when the expected session end position is reached
							if (lastFileLocation != -1L && lastFileLocation == fileLocation.endPosition() % this.diskBufferFileSize) {
								return null;
							} else {
								// read the next record from the file
								final long startPosition = lastLocationRead.get() == -1 ?
									fileLocation.startingPosition() + LEAD_DESCRIPTOR_BYTE_SIZE :
									lastFileLocation;
								try {
									final StorageRecord<TrafficRecording> tr = reader.apply(sessionSequenceId, startPosition, this);
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
										"Error reading session #{} traffic record from disk buffer at position {} ({}): {}",
										sessionSequenceId, startPosition, this.activeSegments.get(), ex.getMessage()
									);
									return null;
								}
							}
						} else {
							// session no longer exists, finalize the stream
							return null;
						}
					}
				)
			)
			.takeWhile(Objects::nonNull);
	}

	/**
	 * Acquires a lock on the specified write segment and writes data to it using the provided
	 * write lambda function. The method ensures that the write segment is not currently being read or
	 * being written by a different session before proceeding. After writing, it releases the lock
	 * on the write segment.
	 *
	 * @param writeSegment        the file location to be locked and written to
	 * @param sessionBeingWritten the session currently being written
	 * @param writeLambda         the lambda function that performs the write operation
	 * @throws IOException if an I/O error occurs during the write process
	 */
	private void lockAndWrite(
		@Nonnull FileLocation writeSegment,
		@Nonnull SessionLocation sessionBeingWritten,
		@Nonnull IOExceptionThrowingLambda writeLambda
	) throws IOException {
        // Step 1: lock the write segment and write the data
		ActiveSegments newValue;
        while (true) {
            // Try to update the segments
			newValue = updateSegments(
				() -> this.activeSegments.updateAndGet(
					currentlyActiveSegments -> {
						isPremiseValid(
							currentlyActiveSegments.writeSegment() == null,
							"Write segment is already locked. This is not expected!"
						);
						isPremiseValid(
							currentlyActiveSegments.sessionBeingWritten() == null || currentlyActiveSegments.sessionBeingWritten() == sessionBeingWritten,
							"Different session is being written. This is not expected!"
						);
                        // If the write segment is currently being read, we cannot proceed yet
						if (segmentsOverlap(currentlyActiveSegments.readSegment(), writeSegment)) {
                            // Return existing state (i.e. we failed to lock)
							return currentlyActiveSegments;
						} else {
                            // Otherwise lock the write segment and move on
							return new ActiveSegments(
								currentlyActiveSegments.readSegment(),
								writeSegment,
								sessionBeingWritten
							);
						}
					}
				)
			);
            // If we successfully locked the segment, break out of the loop
            if (newValue.writeSegment() == writeSegment) {
                break;
            }
            // otherwise, wait until someone else changes the state (i.e., finishes reading)
	        waitForSegmentUpdate();
        }

        // Step 2: write the data
		writeLambda.run();

        // Step 3: release the lock on the write segment
        while (true) {
			newValue = updateSegments(
				() -> this.activeSegments.updateAndGet(
					currentlyActiveSegments -> {
						isPremiseValid(
							currentlyActiveSegments.writeSegment() == writeSegment,
							"Write segment is not locked. This is not expected!"
						);
                        // unlock the write segment
						return new ActiveSegments(
							currentlyActiveSegments.readSegment(),
							null,
							sessionBeingWritten
						);
					}
				)
			);
            // If the write segment is unlocked, we’re done
            if (newValue.writeSegment() == null) {
                break;
            }
            // Otherwise, wait for the condition to change
	        waitForSegmentUpdate();
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
	private <T> T lockAndRead(@Nonnull FileLocation readSegment, @Nonnull Supplier<T> readLambda) {
		// lock the read segment and execute the read lambda
		ActiveSegments newValue = updateSegments(
			() -> this.activeSegments.updateAndGet(
				currentlyActiveSegments -> {
					isPremiseValid(
						currentlyActiveSegments.readSegment() == null,
						"Read segment is already locked. This is not expected!"
					);
					if (segmentsOverlap(currentlyActiveSegments.writeSegment(), readSegment)) {
						// if the read segment is currently being written, do not read from it,
						// this will cause reading to be skipped entirely - the data will be mangled anyway
						return currentlyActiveSegments;
					} else {
						// otherwise lock the read segment and execute the read lambda
						return new ActiveSegments(
							readSegment,
							currentlyActiveSegments.writeSegment(),
							currentlyActiveSegments.sessionBeingWritten()
						);
					}
				}
			)
		);

		// if we succeeded in locking the read segment, execute the read lambda
		if (newValue.readSegment() == readSegment) {
			try {
				return readLambda.get();
			} finally {
				// release the lock on the read segment
				do {
					newValue = updateSegments(
						() -> this.activeSegments.updateAndGet(
							currentlyActiveSegments -> {
								isPremiseValid(
									currentlyActiveSegments.readSegment() == readSegment,
									"Read segment is not locked. This is not expected!"
								);
								return new ActiveSegments(
									null,
									currentlyActiveSegments.writeSegment(),
									currentlyActiveSegments.sessionBeingWritten()
								);
							}
						)
					);
					// we're busy waiting for the read segment to be unlocked, this should practically never happen
				} while (newValue.readSegment() != null);
			}
		} else {
			// we failed to lock the read segment, return null
			return null;
		}
	}

	/**
	 * Waits for the segment update signal.
	 *
	 * This method blocks the current thread until a signal is received
	 * on the segmentsCondition, indicating that a segment update has occurred.
	 * The method utilizes a lock to ensure thread safety when waiting
	 * for the condition to be signaled.
	 *
	 * This wait is uninterruptible and will not respond to thread interruptions.
	 */
	private void waitForSegmentUpdate() {
		this.segmentsLock.lock();
		try {
			this.segmentsCondition.awaitUninterruptibly();
		} finally {
			this.segmentsLock.unlock();
		}
	}

	/**
	 * Updates the active segments by executing the provided supplier within a thread-safe context. The plain
	 * {@link AtomicReference#updateAndGet(UnaryOperator)} doesn't guarantee that the lambda will be executed
	 * concurrently on top of different witness values, which is necessary for the correct operation of the
	 * disk ring buffer.
	 *
	 * @param lambda a supplier that provides the updated ActiveSegments instance
	 * @return the updated ActiveSegments instance
	 */
	@Nonnull
	private ActiveSegments updateSegments(@Nonnull Supplier<ActiveSegments> lambda) {
		this.segmentsLock.lock();
		try {
			ActiveSegments result = lambda.get();
			// notify any threads waiting to see if they can progress (e.g. waiting readers/writers)
			this.segmentsCondition.signalAll();
			return result;
		} finally {
			this.segmentsLock.unlock();
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

	/**
	 * Represents the segments currently active for reading and writing within a disk-based ring buffer system.
	 *
	 * This record is used to track the current read segment, write segment, and any session that is being actively
	 * written. The use of nullable fields allows flexibility in indicating the absence of a particular segment or
	 * session activity.
	 *
	 * @param readSegment         the file location currently being read by some process; can be null if no process is reading
	 * @param writeSegment        the file location currently being written by some process; can be null if no process is writing
	 * @param sessionBeingWritten the session location that is in the process of being written; can be null if no session is being written;
	 *                            this field is used for sanity check, to ensure that the session being written is the one that was locked
	 */
	private record ActiveSegments(
		@Nullable FileLocation readSegment,
		@Nullable FileLocation writeSegment,
		@Nullable SessionLocation sessionBeingWritten
	) {

		private ActiveSegments() {
			this(null, null, null);
		}

		@Override
		public String toString() {
			return "ActiveSegments{" +
				"readSegment=" + readSegment +
				", writeSegment=" + writeSegment +
				", sessionBeingWritten=" + sessionBeingWritten +
				'}';
		}
	}

}
