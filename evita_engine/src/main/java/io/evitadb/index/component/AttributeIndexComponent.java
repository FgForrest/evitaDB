/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.component;

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;

import javax.annotation.Nonnull;

/**
 * Thin adapter that wraps the existing {@link AttributeIndex} as an {@link IndexComponent} so the
 * parent {@link EntityIndex} can drive it through the uniform component loop. An adapter is used (rather than
 * implementing the interface on `AttributeIndex` directly) because the concrete `AttributeIndex` is already
 * the `@Delegate` target for its `AttributeIndexContract` read surface, and bolting another interface onto it would
 * entangle two unrelated concerns.
 *
 * The adapter holds the parent {@link EntityIndexKey} so it can synthesize the per-attribute
 * {@link AttributeIndexStorageKey}
 * entries when announcing keys into the {@link EntityIndexManifest}.
 */
public final class AttributeIndexComponent implements IndexComponent {

	/**
	 * The wrapped sub-index. Held by reference because the owning `EntityIndex` may swap its
	 * `attributeIndex` field through `createCopyWithMergedTransactionalMemory`, but the adapter
	 * lives only for the lifetime of one `EntityIndex` instance.
	 */
	@Nonnull private final AttributeIndex attributeIndex;
	/**
	 * The parent {@link EntityIndexKey} used as the prefix for every synthesized {@link AttributeIndexStorageKey}.
	 */
	@Nonnull private final EntityIndexKey entityIndexKey;

	/**
	 * @param attributeIndex the wrapped {@link AttributeIndex}
	 * @param entityIndexKey the parent index key used to compose storage keys
	 */
	public AttributeIndexComponent(
		@Nonnull AttributeIndex attributeIndex,
		@Nonnull EntityIndexKey entityIndexKey
	) {
		this.attributeIndex = attributeIndex;
		this.entityIndexKey = entityIndexKey;
	}

	@Override
	public void collectModifiedStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull EntityIndexManifest manifest,
		@Nonnull TrappedChanges trappedChanges
	) {
		this.attributeIndex.getModifiedStorageParts(entityIndexPrimaryKey, trappedChanges);
		this.attributeIndex.collectKeys(this.entityIndexKey, manifest.getAttributeKeys());
	}

	@Override
	public void resetDirty() {
		this.attributeIndex.resetDirty();
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.attributeIndex.removeLayer(transactionalLayer);
	}

}
