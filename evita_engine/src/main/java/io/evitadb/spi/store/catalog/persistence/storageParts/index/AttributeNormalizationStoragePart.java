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


package io.evitadb.spi.store.catalog.persistence.storageParts.index;

import io.evitadb.index.analysis.AttributeNormalizationPolicy;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Carries one collection's immutable built-with normalization metadata through ordinary trapped changes.
 * The singleton is loaded before value indexes bind their functions and is published with the catalog bootstrap
 * record. It stores descriptions and retained identities, never executable functions or catalog-storage IO.
 *
 * @param policy collection policy shared by every scope and locale
 */
public record AttributeNormalizationStoragePart(@Nonnull AttributeNormalizationPolicy policy) implements StoragePart {
	@Serial private static final long serialVersionUID = 1L;

	@Override
	@Nonnull
	public Long getStoragePartPK() {
		return 1L;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		return 1L;
	}
}
