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
 * Factory interface for creating {@link Checksum} instances used in the storage layer.
 *
 * This factory pattern allows the storage system to create appropriate checksum calculators
 * based on configuration settings. The factory supports creating both regular checksums
 * (starting from initial state) and cumulative checksums (starting from a given initial value).
 *
 * Cumulative checksums are used for aggregating checksums across multiple data blocks, such as
 * in Write-Ahead Log (WAL) integrity verification where individual record checksums are combined
 * into a cumulative checksum for the entire log sequence.
 *
 * The interface includes a {@link #NO_OP} factory that creates no-operation checksums when
 * checksum computation is disabled via {@link io.evitadb.api.configuration.StorageOptions#computeCRC32C()}.
 *
 * Standard implementation: {@link Crc32CChecksumCalculatorFactory}
 *
 * @see Checksum
 * @see Crc32CChecksumCalculatorFactory
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface ChecksumFactory {
	/**
	 * No-operation factory instance used when checksum computation is disabled.
	 * Creates {@link Checksum#NO_OP} instances for all checksum creation requests.
	 */
	ChecksumFactory NO_OP = new NoOpChecksumCalculatorFactory();

	/**
	 * Creates a new checksum instance initialized to its default starting state.
	 *
	 * @return a new checksum instance ready to process data
	 */
	@Nonnull
	Checksum createChecksum();

	/**
	 * Creates a new cumulative checksum instance initialized with the specified initial value.
	 * This is used when combining multiple checksums into an aggregated checksum, such as
	 * maintaining a running checksum across a sequence of WAL records.
	 *
	 * @param initialChecksum the initial checksum value to start from
	 * @return a new checksum instance initialized with the given value
	 */
	@Nonnull
	Checksum createCumulativeChecksum(long initialChecksum);

	/**
	 * No-operation implementation of {@link ChecksumFactory} used when checksum computation is disabled.
	 * Always returns {@link Checksum#NO_OP} instances regardless of the creation method called.
	 *
	 * This implementation is used when {@link io.evitadb.api.configuration.StorageOptions#computeCRC32C()}
	 * is set to false, allowing the storage layer to skip checksum computation overhead entirely.
	 */
	class NoOpChecksumCalculatorFactory implements ChecksumFactory {

		@Nonnull
		@Override
		public Checksum createChecksum() {
			return Checksum.NO_OP;
		}

		@Nonnull
		@Override
		public Checksum createCumulativeChecksum(long initialChecksum) {
			return Checksum.NO_OP;
		}

	}

}
