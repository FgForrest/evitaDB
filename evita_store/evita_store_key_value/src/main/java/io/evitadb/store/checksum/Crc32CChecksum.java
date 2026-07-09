/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2021-2026
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

package io.evitadb.store.checksum;

import io.evitadb.utils.Crc32CWrapper;

import javax.annotation.Nonnull;
import java.util.zip.CRC32C;

/**
 * CRC32C implementation of the {@link Checksum} interface, returned by both
 * {@link ChecksumFactory#createChecksum()} and {@link ChecksumFactory#createCumulativeChecksum(long)} - the
 * two factory methods only pick the starting state (zero vs. a known initial value), not a different
 * implementation. Every {@link Checksum} operation is supported directly, without exception.
 *
 * Internally this class operates in one of two modes, switching lazily:
 *
 * - **Value mode** (the initial mode, and the mode entered by {@link #reset()}/{@link #reset(long)}): folds
 *   of any fixed-width primitive - {@link #update(byte)}, {@link #update(int)}, {@link #update(long)} - and
 *   {@link #combine(long, int)} operate on a bare {@code long} field via {@link Crc32CWrapper}'s static
 *   GF(2) combine primitives - {@link Crc32CWrapper#combine(long, long, long)},
 *   {@link Crc32CWrapper#combineLong(long, long)}, {@link Crc32CWrapper#combineInt(long, int)},
 *   {@link Crc32CWrapper#combineByte(long, byte)}. None of these ever need to seed a live {@link CRC32C}
 *   register to an arbitrary state, so this mode never pays {@link Crc32CWrapper}'s {@code forceValue}/
 *   {@code reverseCrc32c} cost. All three {@code update} overloads behave symmetrically here by design -
 *   nothing in the {@link Checksum} contract distinguishes a 1/4/8-byte fold by width, so none is special-cased.
 * - **Stream mode**: {@link #update(byte[])} and {@link #update(byte[], int, int)} process genuine,
 *   arbitrary-length byte data and need a live, further-updatable {@link Crc32CWrapper} instance -
 *   unlike the fixed-width folds above, these are **not** given a value-mode fast path even though
 *   {@link Crc32CWrapper#combine(long, long, long)} is mathematically general enough to fold a chunk of
 *   any length: a real call site rarely issues exactly one array update in isolation (header bytes,
 *   payload bytes and a trailing control byte are typically streamed as several consecutive calls on the
 *   same instance), so computing each chunk's checksum from a fresh, zero-state scratch object and folding
 *   it in via {@code combine()} would pay that combine's O(set bits in length) cost on *every* call: worse
 *   than paying {@code forceValue} exactly once at the mode transition and then streaming every subsequent
 *   byte through the live, hardware-accelerated register at native speed. Entering stream mode from value
 *   mode seeds the live instance from the current bare-long value (via {@code forceValue}, skipped
 *   entirely when that value is zero) exactly once.
 *
 * This fallback is not a purely theoretical corner case - several real call sites genuinely interleave both
 * modes on the very same instance without an intervening {@code reset()}: the WAL write path
 * ({@code AbstractMutationLog.appendTransaction} folds a content-length primitive, then streams the
 * serialized transaction bytes directly, then combines two more sub-checksums), WAL recovery scanning
 * ({@code AbstractMutationLog.scanWalFile}), and the WAL-format migration path ({@code Migration_2026_1}).
 * In those cases exactly one {@code forceValue} call is paid at the value-to-stream transition; every
 * operation before and after it (including any subsequent value-mode folds once back in value mode via
 * {@link #combine}) stays on the cheap path. Call sites that are purely value-mode for their entire
 * lifetime (e.g. the WAL read-side {@code AbstractMutationSupplier.cumulativeChecksum}) never pay it at
 * all, and call sites that only ever stream real bytes (e.g. {@code ObservableOutput}'s per-record forward
 * checksum) never leave value mode with a nonzero value, so {@code forceValue} is skipped there too.
 *
 * This is the standard checksum implementation used throughout the evitaDB storage layer when checksums
 * are enabled via {@link io.evitadb.api.configuration.StorageOptions#computeCRC32C()}.
 *
 * @see ChecksumFactory
 * @see Crc32CChecksumFactory
 * @see Crc32CWrapper
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class Crc32CChecksum implements Checksum {
	private final Crc32CWrapper crc32Wrapper;
	private long value;
	private boolean valueMode;

	/**
	 * Creates a new CRC32C checksum calculator initialized to zero.
	 */
	public Crc32CChecksum() {
		this(0L);
	}

	/**
	 * Creates a new CRC32C checksum calculator initialized with the specified initial value.
	 *
	 * Does not seed a live CRC32C register (no {@code forceValue} call) - the initial value is tracked as a
	 * bare {@code long} until a genuine byte stream requires a live register (see the class documentation).
	 *
	 * @param initialChecksum the initial checksum value to start from
	 */
	public Crc32CChecksum(long initialChecksum) {
		this.crc32Wrapper = new Crc32CWrapper();
		this.value = initialChecksum & 0xFFFFFFFFL;
		this.valueMode = true;
	}

	@Override
	public void update(byte b) {
		if (this.valueMode) {
			this.value = Crc32CWrapper.combineByte(this.value, b);
		} else {
			this.crc32Wrapper.withByte(b);
		}
	}

	@Override
	public void update(int b) {
		if (this.valueMode) {
			this.value = Crc32CWrapper.combineInt(this.value, b);
		} else {
			this.crc32Wrapper.withInt(b);
		}
	}

	@Override
	public void update(long l) {
		if (this.valueMode) {
			this.value = Crc32CWrapper.combineLong(this.value, l);
		} else {
			this.crc32Wrapper.withLong(l);
		}
	}

	@Override
	public boolean equalsTo(long expectedChecksum) {
		return getValue() == expectedChecksum;
	}

	@Override
	public void update(@Nonnull byte[] b) {
		enterStreamMode();
		this.crc32Wrapper.withByteArray(b);
	}

	@Override
	public void update(@Nonnull byte[] b, int off, int len) {
		enterStreamMode();
		this.crc32Wrapper.withByteArray(b, off, len);
	}

	@Override
	public void combine(long checksum, int contentLength) {
		this.value = Crc32CWrapper.combine(getValue(), checksum, contentLength);
		this.valueMode = true;
	}

	@Override
	public long getValue() {
		return this.valueMode ? this.value : this.crc32Wrapper.getValue();
	}

	@Override
	public void reset() {
		this.value = 0L;
		this.valueMode = true;
	}

	@Override
	public void reset(long initialValue) {
		this.value = initialValue & 0xFFFFFFFFL;
		this.valueMode = true;
	}

	/**
	 * Transitions from value mode to stream mode, seeding the live {@link Crc32CWrapper} register to the
	 * current bare-long value if necessary, so that subsequent byte-stream {@code update} calls continue
	 * from the correct state. A no-op if already in stream mode.
	 */
	private void enterStreamMode() {
		if (this.valueMode) {
			if (this.value == 0L) {
				this.crc32Wrapper.reset();
			} else {
				this.crc32Wrapper.reset(this.value);
			}
			this.valueMode = false;
		}
	}
}
