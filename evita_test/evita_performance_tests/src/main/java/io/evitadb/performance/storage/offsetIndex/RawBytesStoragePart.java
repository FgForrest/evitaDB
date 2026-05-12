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

package io.evitadb.performance.storage.offsetIndex;

import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * Test-only `StoragePart` that wraps an opaque random `byte[]` payload of caller-controlled size.
 *
 * Used by the {@link OffsetIndexCompactionBenchmark}'s `HUGE` profile to produce records with
 * payloads that are large *and* cannot be compressed away by `KeyCompressor` (which deduplicates
 * `Comparable` keys but has nothing to do with raw byte arrays). This is what reproduces the
 * "byte arrays per record up to 660 KB" allocation pattern described in issue #1157 — the
 * `EntityBodyStoragePart`-based profiles serialize down to ~4 KB per record because the random
 * `AssociatedDataKey` strings get fully de-duplicated by the offset-index's key compressor.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class RawBytesStoragePart implements StoragePart {
	@Serial private static final long serialVersionUID = 1L;

	private final long primaryKey;
	private final byte[] data;

	public RawBytesStoragePart(long primaryKey, @Nonnull byte[] data) {
		this.primaryKey = primaryKey;
		this.data = data;
	}

	public long primaryKey() {
		return this.primaryKey;
	}

	@Nonnull
	public byte[] data() {
		return this.data;
	}

	@Nullable
	@Override
	public Long getStoragePartPK() {
		return this.primaryKey;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		return this.primaryKey;
	}
}
