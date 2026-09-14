/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.store.kryo;

import javax.annotation.Nonnull;

import java.io.InputStream;

/**
 * A test utility that reads from a fixed byte array but exposes only a prefix of it, simulating a file that is
 * still being appended to: reads past the currently revealed prefix report end-of-input, and {@link #reveal(int)}
 * or {@link #revealAll()} then makes more of the remainder available on the same stream instance - exactly as a
 * writer extending a file does for a reader that is already positioned in it.
 *
 * This is the one condition {@link TrickleInputStream} cannot produce. A trickle stream always has more bytes to
 * give, it just gives them a few at a time; a growing stream genuinely runs out and then genuinely has more
 * later, which is the state a reader tailing a live write-ahead log meets every time it catches up with the
 * writer. {@link #getEndOfInputReports()} counts how many times that actually happened, so a test that claims to
 * cover the condition can prove it reached it rather than assuming so.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
class GrowingInputStream extends InputStream {
	/**
	 * The complete byte array, of which only a prefix is visible at any moment.
	 */
	private final byte[] data;
	/**
	 * Number of times a read call reported end-of-input because it had reached {@link #visibleLimit}.
	 */
	private int endOfInputReports;
	/**
	 * Exclusive index of the last byte a read call is currently allowed to see.
	 */
	private int visibleLimit;
	/**
	 * Index of the next byte to be returned.
	 */
	private int position;

	/**
	 * Creates a stream over `data` that initially exposes only its first `initiallyVisible` bytes.
	 *
	 * @param data             the complete byte array to read from
	 * @param initiallyVisible number of leading bytes visible before the first {@link #reveal(int)} call
	 */
	GrowingInputStream(@Nonnull byte[] data, int initiallyVisible) {
		this.data = data;
		this.visibleLimit = Math.min(initiallyVisible, data.length);
	}

	/**
	 * Makes `count` further bytes visible, standing in for the writer having appended that much.
	 *
	 * @param count number of additional bytes to expose
	 */
	void reveal(int count) {
		this.visibleLimit = Math.min(this.visibleLimit + count, this.data.length);
	}

	/**
	 * Makes every remaining byte visible, standing in for the writer having completed its append.
	 */
	void revealAll() {
		this.visibleLimit = this.data.length;
	}

	/**
	 * Returns how many times a read call has reported end-of-input so far. A test that names the
	 * end-of-input-then-more-bytes condition in its title must assert this is non-zero, or it is not testing
	 * what it says it tests.
	 *
	 * @return the number of `-1` returns made by either read overload
	 */
	int getEndOfInputReports() {
		return this.endOfInputReports;
	}

	@Override
	public int read() {
		if (this.position < this.visibleLimit) {
			return this.data[this.position++] & 0xFF;
		}
		this.endOfInputReports++;
		return -1;
	}

	@Override
	public int read(@Nonnull byte[] target, int offset, int length) {
		if (this.position >= this.visibleLimit) {
			this.endOfInputReports++;
			return -1;
		}
		final int readable = Math.min(length, this.visibleLimit - this.position);
		System.arraycopy(this.data, this.position, target, offset, readable);
		this.position += readable;
		return readable;
	}
}
