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
 * ZIP-based implementation of {@link CompressionFactory} for data compression in the storage layer.
 *
 * This factory creates {@link Deflater} (compressor) and {@link Inflater} (decompressor) instances
 * from the standard Java library (java.util.zip package). The implementation uses DEFLATE algorithm
 * with default compression level and NOWRAP mode (raw DEFLATE without ZLIB headers), which is more
 * efficient for storage operations where data format is controlled internally.
 *
 * Compression is applied to reduce storage space requirements, but only when it results in smaller
 * data size than the original. This ensures compression never increases storage requirements.
 *
 * The factory follows the singleton pattern with a single {@link #INSTANCE} that can be shared
 * across the application. This is the standard compression factory used in the evitaDB storage layer
 * when compression is enabled via {@link io.evitadb.api.configuration.StorageOptions#compress()}.
 *
 * @see CompressionFactory
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ZipCompressionFactory implements CompressionFactory {
	/**
	 * Singleton instance of the ZIP compression factory.
	 * Use this constant to obtain compressors and decompressors throughout the storage layer.
	 */
	public static final CompressionFactory INSTANCE = new ZipCompressionFactory();

	/**
	 * Creates a new {@link Inflater} instance for decompressing data.
	 * The inflater is configured with NOWRAP mode (true parameter), which indicates
	 * raw DEFLATE format without ZLIB headers, matching the compression format used
	 * during data writing.
	 *
	 * @return {@link Optional} containing a new inflater instance
	 */
	@Nonnull
	@Override
	public Optional<Inflater> createDecompressor() {
		return Optional.of(new Inflater(true));
	}

	/**
	 * Creates a new {@link Deflater} instance for compressing data.
	 * The deflater is configured with:
	 * - {@link Deflater#DEFAULT_COMPRESSION} level for balanced compression ratio and speed
	 * - NOWRAP mode (true parameter) for raw DEFLATE format without ZLIB headers
	 *
	 * @return {@link Optional} containing a new deflater instance
	 */
	@Nonnull
	@Override
	public Optional<Deflater> createCompressor() {
		return Optional.of(new Deflater(Deflater.DEFAULT_COMPRESSION, true));
	}

}
