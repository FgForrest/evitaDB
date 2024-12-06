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


import io.evitadb.core.traffic.TrafficRecording;
import io.evitadb.core.traffic.TrafficRecordingCaptureRequest;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.traffic.OffHeapTrafficRecorder.MemoryNotAvailableException;
import io.evitadb.store.traffic.data.SessionLocation;
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
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * TODO JNO - document me
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
	private final RandomAccessFile diskBufferFile;
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

	static boolean segmentsOverlap(@Nullable FileLocation locationA, @Nullable FileLocation locationB) {
		/* TODO JNO - write test for this! */
		if (locationA == null || locationB == null) {
			return false;
		}
		return locationA.startingPosition() <= locationB.getEndPosition()
			&& locationB.startingPosition() <= locationA.getEndPosition();
	}

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

	public void sessionWritten(
		@Nonnull SessionLocation sessionLocation,
		@Nonnull LongFunction<StorageRecord<TrafficRecording>> reader
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
			this.readSessionRecords(sessionLocation.sequenceOrder(), sessionLocation.fileLocation(), reader, index::sessionExists)
				.forEach(tr -> index.indexRecording(sessionLocation.sequenceOrder(), tr));
		}
	}

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

	@Nonnull
	public DiskRingBufferIndex indexData(
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

	public void close(@Nonnull Consumer<Path> fileCleanLogic) {
		try {
			this.fileChannel.close();
			this.diskBufferFile.close();
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

	private void updateSessionLocations(int totalBytesToWrite) {
		this.ringBufferTail = (this.ringBufferTail + totalBytesToWrite) % this.diskBufferFileSize;

		final DiskRingBufferIndex index = this.sessionIndex.get();
		SessionLocation head = this.sessionLocations.peekFirst();
		while (head != null) {
			if (head.fileLocation().startingPosition() < this.ringBufferHead) {
				this.sessionLocations.removeFirst();
				if (index != null) {
					index.removeSession(head.sequenceOrder());
				}
				head = this.sessionLocations.peekFirst();
			} else {
				break;
			}
		}
	}

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
							if (sessionExistenceChecker.test(sessionSequenceId)) {
								if (lastFileLocation != null && lastFileLocation.getEndPosition() == fileLocation.getEndPosition()) {
									return null;
								} else {
									final StorageRecord<TrafficRecording> tr = reader.apply(
										lastFileLocation == null ?
											fileLocation.startingPosition() + LEAD_DESCRIPTOR_BYTE_SIZE :
											lastFileLocation.getEndPosition() + 1
									);
									lastLocationRead.set(tr.fileLocation());
									return tr.payload();
								}
							} else {
								return null;
							}
						}
					);
				}
			)
			.takeWhile(Objects::nonNull);
	}

	private void lockAndWrite(
		@Nonnull FileLocation writeSegment,
		@Nonnull SessionLocation sessionBeingWritten,
		@Nonnull IOExceptionThrowingLambda writeLambda
	) throws IOException {
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
						return currentlyActiveSegments;
					} else {
						return new ActiveSegments(
							currentlyActiveSegments.readSegment(),
							writeSegment,
							sessionBeingWritten
						);
					}
				}
			);
		} while (newValue.writeSegment() != writeSegment);

		writeLambda.run();

		do {
			newValue = this.activeSegments.updateAndGet(
				currentlyActiveSegments -> {
					Assert.isPremiseValid(
						currentlyActiveSegments.writeSegment() == writeSegment,
						"Write segment is not locked. This is not expected!"
					);
					return new ActiveSegments(
						currentlyActiveSegments.readSegment(),
						null,
						sessionBeingWritten
					);
				}
			);
		} while (newValue.writeSegment() != null);
	}

	@Nullable
	private <T> T lockAndRead(@Nonnull FileLocation readSegment, @Nonnull Supplier<T> writeLambda) {
		ActiveSegments newValue = this.activeSegments.updateAndGet(
				currentlyActiveSegments -> {
					Assert.isPremiseValid(
						currentlyActiveSegments.readSegment() == null,
						"Read segment is already locked. This is not expected!"
					);
					if (segmentsOverlap(currentlyActiveSegments.writeSegment(), readSegment)) {
						return currentlyActiveSegments;
					} else {
						return new ActiveSegments(
							readSegment,
							currentlyActiveSegments.writeSegment(),
							currentlyActiveSegments.sessionBeingWritten()
						);
					}
				}
			);

		if (newValue.readSegment() == readSegment) {
			try {
				return writeLambda.get();
			} finally {
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
				} while (newValue.readSegment() != null);
			}
		} else {
			return null;
		}
	}

	@FunctionalInterface
	private interface IOExceptionThrowingLambda {

		void run() throws IOException;

	}

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
