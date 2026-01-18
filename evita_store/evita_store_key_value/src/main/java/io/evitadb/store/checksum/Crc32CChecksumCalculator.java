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

package io.evitadb.store.checksum;

import io.evitadb.utils.Crc32CWrapper;

/**
 * CRC32C implementation of the {@link Checksum} interface for data integrity verification.
 *
 * This implementation uses the CRC32C (Castagnoli) algorithm via {@link Crc32CWrapper}, which provides
 * efficient checksum computation with hardware acceleration support on modern CPUs. CRC32C offers a good
 * balance between performance and error detection capabilities, making it suitable for high-throughput
 * storage operations in evitaDB.
 *
 * The calculator supports both regular checksums (starting from zero) and cumulative checksums (starting
 * from a given initial value). Cumulative checksums are used for aggregating checksums across multiple
 * data blocks, such as in Write-Ahead Log (WAL) integrity verification where individual record checksums
 * are combined into a running checksum for the entire log sequence.
 *
 * This is the standard checksum implementation used throughout the evitaDB storage layer when checksums
 * are enabled via {@link io.evitadb.api.configuration.StorageOptions#computeCRC32C()}.
 *
 * @see ChecksumFactory
 * @see Crc32CChecksumCalculatorFactory
 * @see Crc32CWrapper
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class Crc32CChecksumCalculator implements Checksum {
	private final Crc32CWrapper crc32Wrapper;

	/**
	 * Creates a new CRC32C checksum calculator initialized to zero.
	 * Use this constructor for computing checksums of individual data blocks.
	 */
	public Crc32CChecksumCalculator() {
		this.crc32Wrapper = new Crc32CWrapper();
	}

	/**
	 * Creates a new CRC32C checksum calculator initialized with the specified initial value.
	 * Use this constructor for creating cumulative checksums that aggregate multiple checksum
	 * values, such as maintaining a running checksum across a sequence of WAL records.
	 *
	 * @param initialChecksum the initial checksum value to start from
	 */
	public Crc32CChecksumCalculator(long initialChecksum) {
		this.crc32Wrapper = new Crc32CWrapper(initialChecksum);
	}

	@Override
	public void update(byte b) {
		this.crc32Wrapper.withByte(b);
	}

	@Override
	public void update(int b) {
		this.crc32Wrapper.withInt(b);
	}

	@Override
	public void update(long l) {
		this.crc32Wrapper.withLong(l);
	}

	@Override
	public boolean equalsTo(long expectedChecksum) {
		return this.crc32Wrapper.getValue() == expectedChecksum;
	}

	@Override
	public void update(byte[] b) {
		this.crc32Wrapper.withByteArray(b);
	}

	@Override
	public void update(byte[] b, int off, int len) {
		this.crc32Wrapper.withByteArray(b, off, len);
	}

	@Override
	public void combine(long checksum, int contentLength) {
		this.crc32Wrapper.withAnotherChecksum(checksum, contentLength);
	}

	@Override
	public long getValue() {
		return this.crc32Wrapper.getValue();
	}

	@Override
	public void reset() {
		this.crc32Wrapper.reset();
	}

}
