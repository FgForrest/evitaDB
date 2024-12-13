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


import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.traffic.OffHeapTrafficRecorder.MemoryNotAvailableException;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * This class wraps the disk buffer file and provides methods for appending new sessions and reading the session records.
 * The disk buffer file is overwritten in a ring buffer fashion, where the head and tail pointers are used to determine
 * the currently meaningful span of data in the file. If the tail pointer is less than the head pointer, the data is
 * wrapped around the end of the file. Head and tail pointer respect the session boundaries, so there is usually a gap
 * between the tail and the head pointer representing the space of unusable partial data of the oldest session.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
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
	private final Deque<SessionLocation> sessionLocations = new LinkedList<>();
	/**
	 * Optional index, that is maintained if there is a reader that could use it.
	 */
	private final AtomicReference<DiskRingBufferIndex> sessionIndex = new AtomicReference<>();
	/**
	 * Path to file used for storing traffic data when they are completed in the memory buffer.
	 */
	private final Path diskBufferFilePath;
	/**
	 * File used for storing traffic data when they are completed in the memory buffer.
	 */
	@Getter private final RandomAccessFile diskBufferFile;
	/**
	 * Channel for the disk buffer file.
	 */
	private final FileChannel fileChannel;
	/**
	 * Size of the disk buffer file.
	 */
	private final long diskBufferFileSize;
	/**
	 * Atomic reference holding the segments which are currently in active use (read / write).
	 */
	private final AtomicReference<ActiveSegments> activeSegments = new AtomicReference<>(new ActiveSegments());
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
		return locationA.startingPosition() <= locationB.getEndPosition()
			&& locationB.startingPosition() <= locationA.getEndPosition();
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
		if (request.type() != null) {
			requestPredicate = requestPredicate.and(
				tr -> Arrays.stream(request.type()).anyMatch(it -> it == tr.type())
			);
		}
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
	public DiskRingBuffer(@Nonnull Path diskBufferFilePath, long diskBufferFileSize) {
		try {
			this.diskBufferFilePath = diskBufferFilePath;
			this.diskBufferFileSize = diskBufferFileSize;
			this.diskBufferFile = new RandomAccessFile(this.diskBufferFilePath.toFile(), "rw");
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
		this.sessionLocations.add(sessionLocation);

		// Prepare descriptor
		this.descriptorByteBuffer.putLong(sessionSequenceOrder);
		this.descriptorByteBuffer.putInt(totalSize);
		this.descriptorByteBuffer.flip();

		// Write descriptor
		this.append(sessionLocation, this.descriptorByteBuffer);
		this.descriptorByteBuffer.clear();

		// Update index if present
		final DiskRingBufferIndex index = this.sessionIndex.get();
		if (index != null) {
			index.setupSession(sessionLocation);
		}

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
			} else if (this.ringBufferTail + totalBytesToWrite > this.diskBufferFileSize + this.ringBufferHead) {
				// disk buffer is full - we need to wrap around (and copy the data to export file, if any is requested)
				/* TODO JNO - EXPAND EXPORT */
				this.ringBufferHead = this.ringBufferTail;
			}
			final int lengthToWrite = Math.min(Math.toIntExact(this.diskBufferFileSize - this.ringBufferTail), totalBytesToWrite);
			if (lengthToWrite < totalBytesToWrite) {
				lockAndWrite(
					new FileLocation(this.fileChannel.position(), lengthToWrite),
					sessionLocation,
					() -> {
						int writtenBytes = this.fileChannel.write(memoryByteBuffer.slice(0, lengthToWrite));
						Assert.isPremiseValid(writtenBytes == lengthToWrite, "Failed to write all bytes to the disk buffer file.");
						updateSessionLocations(writtenBytes);
					}
				);
				this.fileChannel.position(0);
				lockAndWrite(
					new FileLocation(this.fileChannel.position(), totalBytesToWrite - lengthToWrite),
					sessionLocation,
					() -> {
						int writtenBytes = this.fileChannel.write(memoryByteBuffer.slice(lengthToWrite, totalBytesToWrite - lengthToWrite));
						Assert.isPremiseValid(writtenBytes == totalBytesToWrite - lengthToWrite, "Failed to write all bytes to the disk buffer file.");
						updateSessionLocations(writtenBytes);
					}
				);
			} else {
				lockAndWrite(
					new FileLocation(this.fileChannel.position(), totalBytesToWrite),
					sessionLocation,
					() -> {
						int writtenBytes = this.fileChannel.write(memoryByteBuffer);
						Assert.isPremiseValid(writtenBytes == totalBytesToWrite, "Failed to write all bytes to the disk buffer file.");
						updateSessionLocations(totalBytesToWrite);
					}
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
		long durationInMillis,
		@Nonnull Set<TrafficRecordingType> recordingTypes,
		int fetchCount,
		int bytesFetchedTotal
	) {
		// finish writing the session
		ActiveSegments newValue;
		do {
			newValue = this.activeSegments.updateAndGet(
				currentlyActiveSegments -> {
					Assert.isPremiseValid(
						currentlyActiveSegments.writeSegment() == null,
						"Write segment is locked. This is not expected!"
					);
					Assert.isPremiseValid(
						currentlyActiveSegments.sessionBeingWritten() == sessionLocation,
						"Different session is being written. This is not expected!"
					);
					return new ActiveSegments(
						currentlyActiveSegments.readSegment(),
						null,
						null
					);
				}
			);
		} while (newValue.sessionBeingWritten() != null);

		// update index if present
		final DiskRingBufferIndex index = this.sessionIndex.get();
		if (index != null) {
			index.setupSession(
				sessionLocation,
				sessionId,
				created,
				durationInMillis,
				fetchCount,
				bytesFetchedTotal,
				recordingTypes
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
		@Nonnull LongFunction<StorageRecord<TrafficRecording>> reader
	) {
		DiskRingBufferIndex sessionIndex = this.sessionIndex.get();
		if (sessionIndex == null) {
			sessionIndex = indexData(reader);
		}

		final Predicate<TrafficRecording> requestPredicate = createRequestPredicate(request);
		DiskRingBufferIndex finalSessionIndex = sessionIndex;
		return sessionIndex.getSessionStream(request)
			.flatMap(it -> this.readSessionRecords(it.sequenceOrder(), it.fileLocation(), reader, finalSessionIndex::sessionExists))
			.filter(requestPredicate);
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
			this.fileChannel.close();
			this.diskBufferFile.close();
			this.sessionIndex.set(null);
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to close traffic recording buffer file: " + e.getMessage(),
				"Failed to close traffic recording buffer file.",
				e
			);
		} finally {
			fileCleanLogic.accept(this.diskBufferFilePath);
		}
	}

	/**
	 * Indexes session data from previously recorded traffic by reading session
	 * locations and recording data using the provided reader function.
	 * Each session is set up in the index to manage its existence and integrity
	 * in the index, allowing updates and early removals as needed.
	 *
	 * @param reader a function that provides access to a storage record of
	 *               TrafficRecording instances, given a long identifier.
	 * @return a DiskRingBufferIndex containing indexed session data and
	 * recordings for the disk ring buffer.
	 */
	@Nonnull
	private DiskRingBufferIndex indexData(
		@Nonnull LongFunction<StorageRecord<TrafficRecording>> reader
	) {
		final DiskRingBufferIndex index = new DiskRingBufferIndex();
		this.sessionIndex.set(index);
		for (SessionLocation sessionLocation : this.sessionLocations) {
			// we need to set up the session in the index first, so that `index::sessionExists` returns true
			// and also to allow write logic to remove the session early when overwritten by the new data
			index.setupSession(sessionLocation);
			this.readSessionRecords(sessionLocation.sequenceOrder(), sessionLocation.fileLocation(), reader, index::sessionExists)
				.forEach(tr -> index.indexRecording(sessionLocation.sequenceOrder(), tr));
		}
		return index;
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
	private void updateSessionLocations(int totalBytesToWrite) {
		this.ringBufferTail = (this.ringBufferTail + totalBytesToWrite) % this.diskBufferFileSize;

		final DiskRingBufferIndex index = this.sessionIndex.get();
		SessionLocation head = this.sessionLocations.peekFirst();
		while (head != null) {
			// if the session precedes the head of the ring buffer, remove it
			if (head.fileLocation().startingPosition() < this.ringBufferHead) {
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
		@Nonnull LongFunction<StorageRecord<TrafficRecording>> reader,
		@Nonnull LongPredicate sessionExistenceChecker
	) {
		return Stream.generate(
				() -> {
					final AtomicReference<FileLocation> lastLocationRead = new AtomicReference<>(null);
					return lockAndRead(
						fileLocation,
						() -> {
							final FileLocation lastFileLocation = lastLocationRead.get();
							// check if the session still exists before reading the records
							if (sessionExistenceChecker.test(sessionSequenceId)) {
								// finalize stream when the expected session end position is reached
								if (lastFileLocation != null && lastFileLocation.getEndPosition() == fileLocation.getEndPosition()) {
									return null;
								} else {
									// read the next record from the file
									final StorageRecord<TrafficRecording> tr = reader.apply(
										lastFileLocation == null ?
											fileLocation.startingPosition() + LEAD_DESCRIPTOR_BYTE_SIZE :
											lastFileLocation.getEndPosition() + 1
									);
									lastLocationRead.set(tr.fileLocation());
									// return the payload of the record
									return tr.payload();
								}
							} else {
								// session no longer exists, finalize the stream
								return null;
							}
						}
					);
				}
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
		// lock the write segment and write the data
		ActiveSegments newValue;
		do {
			newValue = this.activeSegments.updateAndGet(
				currentlyActiveSegments -> {
					Assert.isPremiseValid(
						currentlyActiveSegments.writeSegment() == null,
						"Write segment is already locked. This is not expected!"
					);
					Assert.isPremiseValid(
						currentlyActiveSegments.sessionBeingWritten() == null || currentlyActiveSegments.sessionBeingWritten() == sessionBeingWritten,
						"Different session is being written. This is not expected!"
					);
					if (segmentsOverlap(currentlyActiveSegments.readSegment(), writeSegment)) {
						// if the write segment is currently being read, do not write to it, this will cause busy waiting
						return currentlyActiveSegments;
					} else {
						// otherwise lock the write segment and write the data
						return new ActiveSegments(
							currentlyActiveSegments.readSegment(),
							writeSegment,
							sessionBeingWritten
						);
					}
				}
			);
			// we're busy waiting for the write segment to be unlocked by the reader (this should be rare and short)
		} while (newValue.writeSegment() != writeSegment);

		// write the data
		writeLambda.run();

		// release the lock on the write segment
		do {
			newValue = this.activeSegments.updateAndGet(
				currentlyActiveSegments -> {
					Assert.isPremiseValid(
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
			);
			// we're busy waiting for the write segment to be unlocked, this should practically never happen
		} while (newValue.writeSegment() != null);
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
		ActiveSegments newValue = this.activeSegments.updateAndGet(
			currentlyActiveSegments -> {
				Assert.isPremiseValid(
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
		);

		// if we succeeded in locking the read segment, execute the read lambda
		if (newValue.readSegment() == readSegment) {
			try {
				return readLambda.get();
			} finally {
				// release the lock on the read segment
				do {
					newValue = this.activeSegments.updateAndGet(
						currentlyActiveSegments -> {
							Assert.isPremiseValid(
								currentlyActiveSegments.writeSegment() == readSegment,
								"Read segment is not locked. This is not expected!"
							);
							return new ActiveSegments(
								null,
								currentlyActiveSegments.writeSegment(),
								currentlyActiveSegments.sessionBeingWritten()
							);
						}
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
	}

}
