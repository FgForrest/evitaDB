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
 * Factory implementation for creating CRC32C-based {@link Checksum} instances.
 *
 * This factory creates {@link Crc32CChecksumCalculator} instances that use the CRC32C (Castagnoli)
 * algorithm for data integrity verification. CRC32C provides efficient checksum computation with
 * hardware acceleration support on modern CPUs, making it well-suited for high-throughput storage
 * operations in evitaDB.
 *
 * The factory follows the singleton pattern with a single {@link #INSTANCE} that can be shared
 * across the application. This is the standard factory used in the evitaDB storage layer when
 * checksums are enabled via {@link io.evitadb.api.configuration.StorageOptions#computeCRC32C()}.
 *
 * @see ChecksumFactory
 * @see Crc32CChecksumCalculator
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class Crc32CChecksumCalculatorFactory implements ChecksumFactory {
	/**
	 * Singleton instance of the CRC32C checksum factory.
	 * Use this constant to obtain checksums throughout the storage layer.
	 */
	public static final Crc32CChecksumCalculatorFactory INSTANCE = new Crc32CChecksumCalculatorFactory();

	@Nonnull
	@Override
	public Checksum createChecksum() {
		return new Crc32CChecksumCalculator();
	}

	@Nonnull
	@Override
	public Checksum createCumulativeChecksum(long initialChecksum) {
		return new Crc32CChecksumCalculator(initialChecksum);
	}

}
