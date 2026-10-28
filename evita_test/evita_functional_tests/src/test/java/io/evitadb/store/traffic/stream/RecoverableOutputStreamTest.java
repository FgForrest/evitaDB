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

package io.evitadb.store.traffic.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.STREAM;
import static io.evitadb.test.TestTags.TRAFFIC_ENGINE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies byte order and lazy allocation when writes cross several supplied memory blocks.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("RecoverableOutputStream multi-block writes")
@Tag(STORAGE)
@Tag(STREAM)
@Tag(TRAFFIC_ENGINE)
class RecoverableOutputStreamTest {
	private final List<ByteBuffer> buffers = new ArrayList<>();
	private final RecoverableOutputStream stream = new RecoverableOutputStream(() -> {
		final ByteBuffer buffer = ByteBuffer.allocate(8);
		this.buffers.add(buffer);
		return buffer;
	});

	/**
	 * A nonzero source offset and distinct bytes expose repeated prefixes in subsequent blocks.
	 */
	@Test
	@DisplayName("Should preserve a first write spanning four buffers")
	void shouldPreserveFirstWriteAcrossMultipleBuffers() {
		final byte[] input = createInput(32);
		this.stream.write(input, 2, 27);

		assertEquals(4, this.buffers.size());
		assertEquals(3, this.stream.getBufferPosition());
		assertArrayEquals(Arrays.copyOfRange(input, 2, 29), getWrittenBytes());
	}

	/**
	 * The suffix exceeds a fresh block after the partially filled block has been completed.
	 */
	@Test
	@DisplayName("Should continue a partial buffer through several fresh buffers")
	void shouldPreserveWriteWhenPartialBufferNeedsMultipleNewBuffers() {
		final byte[] input = createInput(32);
		this.stream.write(input, 0, 3);
		this.stream.write(input, 3, 25);

		assertEquals(4, this.buffers.size());
		assertEquals(4, this.stream.getBufferPosition());
		assertArrayEquals(Arrays.copyOf(input, 28), getWrittenBytes());
	}

	/**
	 * Completing a block exactly must not acquire the next block in advance.
	 */
	@Test
	@DisplayName("Should keep an exact fit in the current buffer")
	void shouldKeepExactFitInCurrentBuffer() {
		final byte[] input = createInput(8);
		this.stream.write(input, 0, 3);
		this.stream.write(input, 3, 5);

		assertEquals(1, this.buffers.size());
		assertEquals(8, this.stream.getBufferPosition());
		assertArrayEquals(input, getWrittenBytes());
	}

	/**
	 * Single-byte writes must allocate only when the next byte crosses a full block boundary.
	 */
	@Test
	@DisplayName("Should preserve single-byte writes across a boundary")
	void shouldPreserveSingleByteWritesAcrossBoundary() {
		final byte[] input = createInput(10);
		this.stream.write(input, 0, 8);
		assertEquals(1, this.buffers.size());
		assertEquals(8, this.stream.getBufferPosition());

		this.stream.write(input[8]);
		this.stream.write(input[9]);

		assertEquals(2, this.buffers.size());
		assertEquals(2, this.stream.getBufferPosition());
		assertArrayEquals(input, getWrittenBytes());
	}

	/**
	 * Construction leaves buffer acquisition to the first write.
	 */
	@Test
	@DisplayName("Should acquire the first buffer lazily")
	void shouldAcquireFirstBufferLazily() {
		assertEquals(-1, this.stream.getBufferPosition());
		assertTrue(this.buffers.isEmpty());

		this.stream.write(0x80);

		assertEquals(1, this.buffers.size());
		assertEquals(1, this.stream.getBufferPosition());
		assertArrayEquals(new byte[]{(byte) 0x80}, getWrittenBytes());
	}

	/**
	 * A source range outside the array is rejected as `OutputStream` requires, even when nothing would be copied.
	 */
	@Test
	@DisplayName("Should reject a source range outside the array")
	void shouldRejectSourceRangeOutsideArray() {
		final byte[] input = new byte[8];

		assertThrows(IndexOutOfBoundsException.class, () -> this.stream.write(input, 0, -1));
		assertThrows(IndexOutOfBoundsException.class, () -> this.stream.write(input, 9, 0));
		assertThrows(IndexOutOfBoundsException.class, () -> this.stream.write(input, 4, 5));
		assertTrue(this.buffers.isEmpty());
	}

	/**
	 * Creates distinct bytes so that duplicated or skipped segments cannot pass unnoticed.
	 */
	@Nonnull
	private static byte[] createInput(int length) {
		final byte[] input = new byte[length];
		for (int i = 0; i < length; i++) {
			input[i] = (byte) (i + 1);
		}
		return input;
	}

	/**
	 * Concatenates exactly the bytes written to the supplied buffers, excluding unused capacity.
	 */
	@Nonnull
	private byte[] getWrittenBytes() {
		final ByteArrayOutputStream output = new ByteArrayOutputStream(this.buffers.size() * 8);
		for (final ByteBuffer buffer : this.buffers) {
			output.write(buffer.array(), 0, buffer.position());
		}
		return output.toByteArray();
	}
}
