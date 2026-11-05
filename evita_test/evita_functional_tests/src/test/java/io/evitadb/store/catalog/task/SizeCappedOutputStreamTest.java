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

package io.evitadb.store.catalog.task;

import io.evitadb.api.exception.ExportFileTooLargeException;
import org.junit.jupiter.api.*;

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
