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

import javax.annotation.Nonnull;

/**
 * Interface for computing and verifying data integrity checksums in the storage layer.
 *
 * Checksums are used throughout evitaDB's storage system to ensure data integrity during write operations
 * and to detect data corruption. The interface supports both simple checksums for individual data blocks
 * and cumulative checksums that aggregate multiple checksum values (e.g., for WAL integrity verification).
 *
 * Implementations must provide methods to incrementally update the checksum with data, retrieve the final
 * checksum value, reset the checksum to its initial state, verify checksums, and combine multiple checksums.
 *
 * The interface includes a {@link #NO_OP} implementation that can be used when checksum computation is
 * disabled via {@link io.evitadb.api.configuration.StorageOptions#computeCRC32C()}.
 *
 * Standard implementation: {@link Crc32CChecksum} (CRC32C algorithm)
 *
 * @see ChecksumFactory
 * @see Crc32CChecksum
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface Checksum {
	/**
	 * No-operation checksum instance used when checksum computation is disabled.
	 * All operations are no-ops and verification always succeeds.
	 */
	Checksum NO_OP = new NoOpChecksumCalculator();

	/**
	 * Updates the checksum with the specified byte value.
	 *
	 * @param b the byte value to add to the checksum
	 */
	void update(byte b);

	/**
	 * Updates the checksum with the specified long value.
	 * The long is processed as 8 bytes in little-endian order that match Kryo implementation.
	 *
	 * @param l the long value to add to the checksum
	 */
	void update(long l);

	/**
	 * Updates the checksum with the specified int value.
	 * The int is processed as 4 bytes in little-endian order that match Kryo implementation.
	 *
	 * @param b the int value to add to the checksum
	 */
	void update(int b);

	/**
	 * Updates the checksum with the specified byte array.
	 *
	 * @param b the byte array to add to the checksum
	 */
	void update(@Nonnull byte[] b);

	/**
	 * Updates the checksum with a slice of the specified byte array.
	 *
	 * @param b   the byte array containing the data
	 * @param off the starting offset in the array
	 * @param len the number of bytes to process
	 */
	void update(@Nonnull byte[] b, int off, int len);

	/**
	 * Returns the current checksum value.
	 *
	 * @return the computed checksum as a long value
	 */
	long getValue();

	/**
	 * Resets the checksum to its initial state (with initial value set to zero),
	 * clearing all previously processed data.
	 */
	void reset();

	/**
	 * Resets the checksum to a specified initial value, clearing all previously processed data.
	 *
	 * @param initialValue the initial value to set the checksum to
	 */
	void reset(long initialValue);

	/**
	 * Verifies that the current checksum value matches the expected checksum.
	 *
	 * @param expectedChecksum the expected checksum value to compare against
	 * @return true if the checksums match, false otherwise
	 */
	boolean equalsTo(long expectedChecksum);

	/**
	 * Combines this checksum with another checksum value to create a cumulative checksum.
	 * This is used for aggregating checksums across multiple data blocks, such as in WAL
	 * integrity verification where individual record checksums are combined into a cumulative
	 * checksum for the entire log.
	 *
	 * @param checksum      the checksum value to combine with this checksum
	 * @param contentLength the length of the content that produced the checksum being combined
	 */
	void combine(long checksum, int contentLength);

	/**
	 * No-operation implementation of {@link Checksum} used when checksum computation is disabled.
	 * All update operations are no-ops, {@link #getValue()} returns 0, and {@link #equalsTo(long)}
	 * always returns true (no validation occurs).
	 *
	 * This implementation is used when {@link io.evitadb.api.configuration.StorageOptions#computeCRC32C()}
	 * is set to false, allowing the storage layer to skip checksum computation overhead.
	 */
	class NoOpChecksumCalculator implements Checksum {

		@Override
		public void update(byte b) {
			// no-op
		}

		@Override
		public void update(int b) {
			// no-op
		}

		@Override
		public void update(long l) {
			// no-op
		}

		@Override
		public void update(@Nonnull byte[] b, int off, int len) {
			// no-op
		}

		@Override
		public void update(@Nonnull byte[] b) {
			// no-op
		}

		@Override
		public void combine(long checksum, int contentLength) {
			// no-op
		}

		@Override
		public long getValue() {
			return 0;
		}

		@Override
		public void reset() {
			// no-op
		}

		@Override
		public void reset(long initialValue) {
			// no-op
		}

		@Override
		public boolean equalsTo(long expectedChecksum) {
			// no validation actually happens
			return true;
		}

	}

}
