/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.spi.store.catalog.persistence.storageParts.compressor;

import io.evitadb.spi.store.catalog.exception.CompressionKeyUnknownException;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.util.Collections;
import java.util.Map;
import java.util.OptionalInt;

/**
 * A read-only **view** over a live {@link ReadWriteKeyCompressor} instance. Unlike {@link ReadOnlyKeyCompressor},
 * which creates a snapshot copy of the key maps at construction time, this class delegates all read operations
 * directly to the underlying write compressor. This means callers always see the latest state — including keys
 * that were registered after the view was created.
 *
 * The only method whose behavior differs from the delegate is {@link #getId(Comparable)}: instead of allocating
 * a new id for an unknown key (the mutating behavior of {@link ReadWriteKeyCompressor#getId(Comparable)}), this
 * implementation throws a {@link CompressionKeyUnknownException}, matching the contract of
 * {@link ReadOnlyKeyCompressor#getId(Comparable)}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see ReadOnlyKeyCompressor for the snapshot-based read-only variant
 * @see ReadWriteKeyCompressor for the mutable variant this class wraps
 */
@NotThreadSafe
public class ReadOnlyKeyCompressorView implements KeyCompressor {
	@Serial private static final long serialVersionUID = -4827193605812947381L;

	/**
	 * The underlying mutable key compressor whose read-only methods are delegated to directly.
	 */
	@Nonnull private final ReadWriteKeyCompressor delegate;

	/**
	 * Creates a new read-only view over the given write compressor.
	 *
	 * @param delegate the live write compressor to wrap
	 */
	public ReadOnlyKeyCompressorView(@Nonnull ReadWriteKeyCompressor delegate) {
		this.delegate = delegate;
	}

	@Nonnull
	@Override
	public Map<Integer, Object> getKeys() {
		return Collections.unmodifiableMap(this.delegate.getKeys());
	}

	/**
	 * Returns the internal id assigned to the given key. Unlike {@link ReadWriteKeyCompressor#getId(Comparable)},
	 * this method never allocates a new id — it throws {@link CompressionKeyUnknownException} when the key
	 * is not found.
	 */
	@Override
	public <T extends Comparable<T>> int getId(@Nonnull T key) throws CompressionKeyUnknownException {
		final OptionalInt id = this.delegate.getIdIfExists(key);
		Assert.isPremiseValid(id.isPresent(), () -> new CompressionKeyUnknownException("There is no id for key " + key + "!"));
		return id.getAsInt();
	}

	@Nonnull
	@Override
	public <T extends Comparable<T>> OptionalInt getIdIfExists(@Nonnull T key) {
		return this.delegate.getIdIfExists(key);
	}

	@Nonnull
	@Override
	public <T extends Comparable<T>> T getKeyForId(int id) {
		return this.delegate.getKeyForId(id);
	}

	@Nullable
	@Override
	public <T extends Comparable<T>> T getKeyForIdIfExists(int id) {
		return this.delegate.getKeyForIdIfExists(id);
	}

}
