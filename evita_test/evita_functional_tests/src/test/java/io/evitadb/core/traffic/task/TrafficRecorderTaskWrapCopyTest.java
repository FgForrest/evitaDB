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

package io.evitadb.core.traffic.task;

import io.evitadb.stream.RandomAccessFileInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.EXPORT;
import static io.evitadb.test.TestTags.TRAFFIC_ENGINE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Verifies the wrap-aware raw-byte copy used by the live traffic-recording export
 * ({@link TrafficRecorderTask#copyPossiblyWrappingSession}). A session whose bytes physically wrap the
 * end of the ring buffer file must be reassembled in two segments (tail-of-file then head-of-file);
 * the pre-fix plain `seek + copy of length bytes` silently truncated the wrapped tail at EOF.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(ENGINE)
@Tag(TRAFFIC_ENGINE)
@Tag(EXPORT)
class TrafficRecorderTaskWrapCopyTest {
	private static final int FILE_SIZE = 32;

	/**
	 * Builds a ring-buffer-like file of {@link #FILE_SIZE} bytes where {@code byte[i] == i + 1}, so any
	 * mis-seek or truncation is caught by the position-encoding pattern.
	 */
	private static Path patternFile() throws Exception {
		final byte[] pattern = new byte[FILE_SIZE];
		for (int i = 0; i < FILE_SIZE; i++) {
			pattern[i] = (byte) (i + 1);
		}
		final Path file = Files.createTempFile("TrafficRecorderTaskWrapCopyTest", ".bin");
		Files.write(file, pattern);
		return file;
	}

	/**
	 * Returns the expected bytes of a session of {@code length} bytes starting at {@code start},
	 * reading the pattern ring-wise (wrapping at {@link #FILE_SIZE}).
	 */
	private static byte[] expectedWrapped(int start, int length) {
		final byte[] expected = new byte[length];
		for (int k = 0; k < length; k++) {
			expected[k] = (byte) (((start + k) % FILE_SIZE) + 1);
		}
		return expected;
	}

	@Test
	@DisplayName("A session that wraps the end of the ring buffer file is copied verbatim in two segments")
	void shouldCopyWrappingSessionInTwoSegments() throws Exception {
		final Path file = patternFile();
		try (final RandomAccessFileInputStream in = new RandomAccessFileInputStream(new RandomAccessFile(file.toFile(), "r"))) {
			// session starts near the end and runs 10 bytes past it -> 4 tail bytes + 6 head bytes
			final int start = 28;
			final int length = 10;
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			TrafficRecorderTask.copyPossiblyWrappingSession(in, out, start, length, FILE_SIZE, new byte[8]);
			assertArrayEquals(
				expectedWrapped(start, length), out.toByteArray(),
				"The wrapped session must be reassembled from the tail-of-file and head-of-file segments, " +
					"not truncated at EOF."
			);
		} finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	@DisplayName("A session that ends exactly at the buffer end is copied as a single segment")
	void shouldCopySessionEndingExactlyAtBufferEndAsSingleSegment() throws Exception {
		final Path file = patternFile();
		try (final RandomAccessFileInputStream in = new RandomAccessFileInputStream(new RandomAccessFile(file.toFile(), "r"))) {
			// start 24, length 8 -> ends exactly at 32 (no wrap)
			final int start = 24;
			final int length = 8;
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			TrafficRecorderTask.copyPossiblyWrappingSession(in, out, start, length, FILE_SIZE, new byte[8]);
			assertArrayEquals(expectedWrapped(start, length), out.toByteArray());
		} finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	@DisplayName("A non-wrapping session is copied verbatim")
	void shouldCopyNonWrappingSessionVerbatim() throws Exception {
		final Path file = patternFile();
		try (final RandomAccessFileInputStream in = new RandomAccessFileInputStream(new RandomAccessFile(file.toFile(), "r"))) {
			final int start = 4;
			final int length = 8;
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			TrafficRecorderTask.copyPossiblyWrappingSession(in, out, start, length, FILE_SIZE, new byte[8]);
			assertArrayEquals(expectedWrapped(start, length), out.toByteArray());
		} finally {
			Files.deleteIfExists(file);
		}
	}
}
