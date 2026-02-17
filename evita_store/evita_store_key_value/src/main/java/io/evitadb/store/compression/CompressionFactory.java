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

package io.evitadb.store.compression;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Factory interface for creating compressor and decompressor instances used in the storage layer.
 *
 * This factory pattern allows the storage system to optionally compress data before writing it to disk,
 * reducing storage space requirements. Compression is only applied when it results in smaller data size
 * than the original, ensuring that compression never increases storage requirements.
 *
 * The factory returns {@link Optional} values to indicate whether compression is enabled or disabled:
 * - {@link Optional#empty()} indicates compression is disabled
 * - {@link Optional} with a value indicates compression is enabled and provides the compressor/decompressor
 *
 * Currently, the interface is designed for ZIP-based compression using {@link Deflater} and {@link Inflater}
 * from the standard Java library. The interface can be extended in the future to support additional compression
 * algorithms if needed.
 *
 * The interface includes a {@link #NO_COMPRESSION} factory that returns empty {@link Optional} values,
 * used when compression is disabled via {@link io.evitadb.api.configuration.StorageOptions#compress()}.
 *
 * Standard implementation: {@link ZipCompressionFactory}
 *
 * @see ZipCompressionFactory
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface CompressionFactory {
	/**
	 * No-operation factory instance used when compression is disabled.
	 * All methods return {@link Optional#empty()}, indicating no compression should be applied.
	 */
	CompressionFactory NO_COMPRESSION = new NoCompressionFactory();

	/**
	 * Creates a decompressor instance for reading compressed data from storage.
	 *
	 * @return {@link Optional} containing a decompressor if compression is enabled,
	 *         or {@link Optional#empty()} if compression is disabled
	 */
	@Nonnull
	Optional<Inflater> createDecompressor();

	/**
	 * Creates a compressor instance for writing compressed data to storage.
	 *
	 * @return {@link Optional} containing a compressor if compression is enabled,
	 *         or {@link Optional#empty()} if compression is disabled
	 */
	@Nonnull
	Optional<Deflater> createCompressor();

	/**
	 * No-operation implementation of {@link CompressionFactory} used when compression is disabled.
	 * All methods return {@link Optional#empty()}, indicating that no compression or decompression
	 * should be performed on the data.
	 *
	 * This implementation is used when {@link io.evitadb.api.configuration.StorageOptions#compress()}
	 * is set to false, allowing the storage layer to skip compression overhead entirely.
	 */
	class NoCompressionFactory implements CompressionFactory {

		@Nonnull
		@Override
		public Optional<Inflater> createDecompressor() {
			return Optional.empty();
		}

		@Nonnull
		@Override
		public Optional<Deflater> createCompressor() {
			return Optional.empty();
		}

	}
}
