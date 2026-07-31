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
 * at {@link #COMPRESSION_LEVEL} — measured, not the library default — and NOWRAP mode (raw DEFLATE without ZLIB
 * headers), which is more efficient for storage operations where data format is controlled internally.
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
	 * DEFLATE level used for every record written by the storage layer. Deliberately **3**, not
	 * {@link Deflater#DEFAULT_COMPRESSION} (which is level 6).
	 *
	 * Compression is applied **per record** — {@code ObservableOutput} resets the deflater, feeds one record's
	 * payload, and finishes it in a single `deflate()` call — so the dictionary never carries across records and the
	 * level's cost/benefit is decided at record scale, not stream scale. Measured that way against level 6, on real
	 * serialized entity records and on prose (standing in for text-heavy attribute bodies), at 4 KB / 16 KB / 64 KB
	 * record sizes:
	 *
	 * | data | 4 KB | 16 KB | 64 KB |
	 * |---|---|---|---|
	 * | serialized entities | +1.4 % size, 1.46x faster | +2.4 %, 1.65x | +3.2 %, 1.79x |
	 * | prose | +4.5 %, 1.31x | +7.6 %, 1.68x | +11.2 %, 2.35x |
	 *
	 * Level 3 is the highest-speed level that stays inside a ~10-15 % size budget on **every** shape measured.
	 * Level 1 is faster still (up to 3.1x) but costs +20 % on large prose-like records, which is exactly the
	 * text-heavy case this is meant to help — so it was rejected. Level 9 is not worth its price: it buys 1-6 % size
	 * and runs 1.3x to 5.5x *slower*.
	 *
	 * **This is not a configuration knob on purpose.** The level is a storage-layer implementation detail: any
	 * DEFLATE level produces a stream the same {@link Inflater} reads, so changing it is both backward and forward
	 * compatible — catalogs written at level 6 stay readable, and nothing needs migrating. Exposing it would
	 * advertise a tuning axis whose correct value is already known, and add a compatibility surface for no gain.
	 */
	private static final int COMPRESSION_LEVEL = 3;

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
	 * - {@link #COMPRESSION_LEVEL}, chosen by measurement — see that field for the numbers and for why it is not
	 *   configurable
	 * - NOWRAP mode (true parameter) for raw DEFLATE format without ZLIB headers
	 *
	 * @return {@link Optional} containing a new deflater instance
	 */
	@Nonnull
	@Override
	public Optional<Deflater> createCompressor() {
		return Optional.of(new Deflater(COMPRESSION_LEVEL, true));
	}

}
