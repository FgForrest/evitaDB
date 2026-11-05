/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.spi.export.model;

import io.evitadb.api.exception.ExportFileTooLargeException;
import org.junit.jupiter.api.*;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.EXPORT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Contracts for the byte boundary, one-time refusal and unpublished failure lifecycle.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(EXPORT)
@DisplayName("Size capped output stream")
class SizeCappedOutputStreamTest {
	/** Records delegate lifecycle calls without introducing a mock. */
	private static class RecordingStream extends ByteArrayOutputStream {
		private int flushes;
		private boolean closed;

		@Override
		public void flush() {
			this.flushes++;
		}

		@Override
		public void close() {
			this.closed = true;
		}
	}

	@Nested
	@DisplayName("Byte boundary")
	class ByteBoundary {
		@Test
		@DisplayName("should pass every write form through exactly the cap and close the delegate")
		void shouldAllowExactlyTheCap() throws IOException {
			final RecordingStream sink = new RecordingStream();
			final SizeCappedOutputStream cap = new SizeCappedOutputStream(sink, 5, () -> {
				fail("the supplier must remain lazy for an admitted file");
				return null;
			});
			cap.write(1);
			cap.write(new byte[]{2, 3});
			cap.write(new byte[]{0, 4, 5, 0}, 1, 2);
			cap.flush();
			cap.close();
			assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, sink.toByteArray());
			assertEquals(1, sink.flushes);
			assertTrue(sink.closed);
			assertDoesNotThrow(cap::checkFailure);
		}

		@Test
		@DisplayName("should reject a crossing array chunk without forwarding any of it")
		void shouldRejectCrossingArrayChunk() throws IOException {
			assertRefusal(false);
		}

		@Test
		@DisplayName("should reject a crossing single byte without forwarding it")
		void shouldRejectCrossingSingleByte() throws IOException {
			assertRefusal(true);
		}

		@Test
		@DisplayName("should let an empty file through a zero cap and refuse its first byte")
		void shouldAdmitOnlyEmptyFileWhenCapIsZero() throws IOException {
			final RecordingStream emptySink = new RecordingStream();
			final SizeCappedOutputStream emptyCap = new SizeCappedOutputStream(emptySink, 0, () -> {
				fail("an empty file does not cross a zero cap");
				return null;
			});
			emptyCap.write(new byte[0]);
			emptyCap.flush();
			emptyCap.close();
			assertEquals(1, emptySink.flushes);
			assertTrue(emptySink.closed);
			assertDoesNotThrow(emptyCap::checkFailure);

			final RecordingStream refusingSink = new RecordingStream();
			final SizeCappedOutputStream refusingCap = trippableCap(refusingSink, 0);
			assertThrows(ExportFileTooLargeException.class, () -> refusingCap.write(1));
			assertEquals(0, refusingSink.size());
		}

		@Test
		@DisplayName("should reject an out-of-range slice without counting it against the cap")
		void shouldStayUsableWhenSliceIsOutOfRange() throws IOException {
			final RecordingStream sink = new RecordingStream();
			final SizeCappedOutputStream cap = trippableCap(sink, 2);
			assertThrows(IndexOutOfBoundsException.class, () -> cap.write(new byte[]{1, 2}, 1, 2));
			assertEquals(0, sink.size());
			cap.write(new byte[]{1, 2});
			assertArrayEquals(new byte[]{1, 2}, sink.toByteArray());
			assertDoesNotThrow(cap::checkFailure);
		}
	}

	@Nested
	@DisplayName("Failure rethrow")
	class FailureRethrow {
		@Test
		@DisplayName("should do nothing when the cap never tripped")
		void shouldReturnWhenCapDidNotTrip() {
			final SizeCappedOutputStream cap = trippableCap(new RecordingStream(), 2);
			final RuntimeException caught = new RuntimeException("unrelated failure");
			assertDoesNotThrow(() -> cap.rethrowFailure(caught, false));
			assertDoesNotThrow(() -> cap.rethrowFailure(caught, true));
			assertEquals(0, caught.getSuppressed().length);
		}

		@Test
		@DisplayName("should leave the cancellation in charge when it is what unwound the task")
		void shouldReturnWhenCancellationDriven() {
			final SizeCappedOutputStream cap = trippableCap(new RecordingStream(), 0);
			final ExportFileTooLargeException refusal = trip(cap);
			final RuntimeException cancellation = new RuntimeException(new InterruptedException("cancelled"));
			assertDoesNotThrow(() -> cap.rethrowFailure(cancellation, true));
			assertEquals(0, refusal.getSuppressed().length);
		}

		@Test
		@DisplayName("should restore the refusal and retain the exception that replaced it")
		void shouldRethrowRefusalWithReplacementSuppressed() {
			final SizeCappedOutputStream cap = trippableCap(new RecordingStream(), 0);
			final ExportFileTooLargeException refusal = trip(cap);
			final RuntimeException replacement = new RuntimeException("flush lost the cause");
			assertSame(
				refusal,
				assertThrows(ExportFileTooLargeException.class, () -> cap.rethrowFailure(replacement, false))
			);
			assertArrayEquals(new Throwable[]{replacement}, refusal.getSuppressed());
		}

		@Test
		@DisplayName("should not suppress the refusal in itself when it reached the task unchanged")
		void shouldRethrowRefusalWithoutSelfSuppression() {
			final SizeCappedOutputStream cap = trippableCap(new RecordingStream(), 0);
			final ExportFileTooLargeException refusal = trip(cap);
			assertSame(
				refusal,
				assertThrows(ExportFileTooLargeException.class, () -> cap.rethrowFailure(refusal, false))
			);
			assertEquals(0, refusal.getSuppressed().length);
		}

		/** Trips the cap with a write no cap below 16 bytes admits, and returns the remembered refusal. */
		@Nonnull
		private static ExportFileTooLargeException trip(@Nonnull SizeCappedOutputStream cap) {
			return assertThrows(ExportFileTooLargeException.class, () -> cap.write(new byte[16]));
		}
	}

	/** Builds a cap whose refusal needs no storage measurement, for tests that do not inspect the supplier. */
	@Nonnull
	private static SizeCappedOutputStream trippableCap(@Nonnull RecordingStream sink, long allowance) {
		return new SizeCappedOutputStream(
			sink, allowance,
			() -> new ExportFileTooLargeException("test", allowance, "export.fileSystem.sizeLimitBytes", null)
		);
	}

	/** Checks every call after the first refusal, including the explicit post-close safety check. */
	private static void assertRefusal(boolean singleByte) throws IOException {
		final RecordingStream sink = new RecordingStream();
		final AtomicInteger calls = new AtomicInteger();
		final SizeCappedOutputStream cap = new SizeCappedOutputStream(sink, 2, () -> {
			calls.incrementAndGet();
			return new ExportFileTooLargeException("test", 2, "export.fileSystem.sizeLimitBytes", null);
		});
		cap.write(new byte[]{1, 2});
		final ExportFileTooLargeException raised = assertThrows(ExportFileTooLargeException.class, () -> {
			if (singleByte) {
				cap.write(3);
			} else {
				cap.write(new byte[]{0, 3, 4, 0}, 1, 2);
			}
		});
		assertArrayEquals(new byte[]{1, 2}, sink.toByteArray(), "crossing chunk must be atomic");
		assertDoesNotThrow(() -> {
			cap.write(4);
			cap.write(new byte[]{5});
			cap.write(new byte[]{6}, 0, 1);
			cap.flush();
			cap.close();
		}, "later writes, flush and close must not throw the remembered instance again");
		assertEquals(0, sink.flushes);
		assertFalse(sink.closed, "a tripped cap must never close and publish its delegate");
		assertArrayEquals(new byte[]{1, 2}, sink.toByteArray());
		assertEquals(1, calls.get());
		assertSame(raised, assertThrows(ExportFileTooLargeException.class, cap::checkFailure));
	}
}
