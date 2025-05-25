/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.store.spi.model.storageParts.index;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;

import javax.annotation.Nonnull;

/**
 * This DTO is a key allowing to identify proper {@link AttributeIndexStoragePart} implementation and store/load it from
 * file offset index. This key is maintained by {@link EntityIndexStoragePart} so that every entity index
 * knows where its attribute indexes are stored.
 */
public record AttributeIndexStorageKey(
	@Nonnull EntityIndexKey entityIndexKey,
	@Nonnull AttributeIndexType indexType,
	@Nonnull AttributeKey attribute
) implements Comparable<AttributeIndexStorageKey>, EntityIndexKeyAccessor {
	@Override
	public int compareTo(AttributeIndexStorageKey o) {
		final int firstComparison = this.entityIndexKey.compareTo(o.entityIndexKey);
		if (firstComparison == 0) {
			final int secondComparison = Integer.compare(this.indexType.ordinal(), o.indexType.ordinal());
			if (secondComparison == 0) {
				return this.attribute.compareTo(o.attribute);
			} else {
				return secondComparison;
			}
		} else {
			return firstComparison;
		}
	}
}
