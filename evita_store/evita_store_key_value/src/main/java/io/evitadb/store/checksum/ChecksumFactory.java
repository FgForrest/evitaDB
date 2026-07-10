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
 * based on configuration settings. The two creation methods below return an instance with the same
 * full set of capabilities - every {@link Checksum} operation is supported by either - they only differ
 * in the starting state the instance is seeded with. Pick {@link #createCumulativeChecksum(long)} whenever
 * a known starting value needs to be continued (e.g. resuming a persisted checksum), and
 * {@link #createChecksum()} whenever a fresh instance is needed; both are otherwise interchangeable.
 *
 * The interface includes a {@link #NO_OP} factory that creates no-operation checksums when
 * checksum computation is disabled via {@link io.evitadb.api.configuration.StorageOptions#computeCRC32C()}.
 * Both methods on that factory return the exact same {@link Checksum#NO_OP} singleton.
 *
 * Standard implementation: {@link Crc32CChecksumFactory}
 *
 * @see Checksum
 * @see Crc32CChecksumFactory
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface ChecksumFactory {
	/**
	 * No-operation factory instance used when checksum computation is disabled.
	 * Creates {@link Checksum#NO_OP} instances for all checksum creation requests.
	 */
	ChecksumFactory NO_OP = new NoOpChecksumCalculatorFactory();

	/**
	 * Creates a fresh checksum instance initialized to zero - e.g. verifying the forward checksum of one
	 * storage record's payload as it is read or written.
	 *
	 * @return a new checksum instance initialized to zero
	 */
	@Nonnull
	Checksum createChecksum();

	/**
	 * Creates a checksum instance initialized with a known starting value - e.g. combining each WAL
	 * record's own checksum into a running cumulative checksum for the entire log, or resuming a checksum
	 * from a previously-persisted value. Equivalent to {@link #createChecksum()} followed by
	 * {@code reset(initialChecksum)}, just without the redundant zero-initialization.
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
