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
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;

import javax.annotation.Nonnull;

/**
 * {@link IndexComponent} adapter for the per-attribute {@link AttributeCardinalityIndex} map carried
 * by {@link io.evitadb.index.ReducedGroupEntityIndex} and
 * {@link io.evitadb.index.ReferencedTypeEntityIndex}. These cardinality indexes track how many times
 * a given attribute value has been registered for a given record so that the underlying filter / sort
 * indexes are only mutated on 0→1 and 1→0 transitions.
 *
 * The component:
 *
 * 1. emits per-entry `AttributeCardinalityIndexStoragePart` instances for every dirty cardinality
 *    index,
 * 2. announces a `CARDINALITY` {@link AttributeIndexStorageKey} per entry into the shared
 *    {@link EntityIndexManifest} so the parent index advertises them in the parent
 *    `EntityIndexStoragePart`,
 * 3. forwards reset/remove-layer calls into every wrapped {@link AttributeCardinalityIndex} via the
 *    {@link TransactionalMap} machinery.
 */
public final class AttributeCardinalityIndexMapComponent implements IndexComponent {

	/**
	 * Backing per-attribute cardinality map owned by the parent index. Held by reference because the
	 * parent index never swaps the map instance during its lifetime — only the contents change.
	 */
	@Nonnull private final TransactionalMap<AttributeIndexKey, AttributeCardinalityIndex> cardinalityIndexes;
	/**
	 * The parent {@link EntityIndexKey} used as the prefix for every synthesized
	 * {@link AttributeIndexStorageKey} of type {@link AttributeIndexType#CARDINALITY}.
	 */
	@Nonnull private final EntityIndexKey entityIndexKey;

	/**
	 * @param cardinalityIndexes the wrapped per-attribute cardinality map
	 * @param entityIndexKey     the parent index key used to compose storage keys
	 */
	public AttributeCardinalityIndexMapComponent(
		@Nonnull TransactionalMap<AttributeIndexKey, AttributeCardinalityIndex> cardinalityIndexes,
		@Nonnull EntityIndexKey entityIndexKey
	) {
		this.cardinalityIndexes = cardinalityIndexes;
		this.entityIndexKey = entityIndexKey;
	}

	@Override
	public void collectModifiedStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull EntityIndexManifest manifest,
		@Nonnull TrappedChanges trappedChanges
	) {
		// emit one storage part per dirty cardinality index AND announce a CARDINALITY key for
		// every live entry — manifest population is unconditional so that a clean entry still
		// shows up in the parent's EntityIndexStoragePart manifest.
		// `forEach` rather than `entrySet()`: a HashMap keeps the view it hands out, so an accessor asked for on this
		// path would stay on the map for the lifetime of the owning index - see `TransactionalMap#forEach`
		this.cardinalityIndexes.forEach((key, index) -> {
			final StoragePart part = index.createStoragePart(entityIndexPrimaryKey, key);
			if (part != null) {
				trappedChanges.addChangeToStore(part);
			}
			manifest.addAttributeKey(
				new AttributeIndexStorageKey(this.entityIndexKey, AttributeIndexType.CARDINALITY, key)
			);
		});
	}

	@Override
	public void resetDirty() {
		// reset every per-attribute cardinality index — the map itself has no own dirty flag.
		// `forEach` for the reason given on `collectModifiedStorageParts` above
		this.cardinalityIndexes.forEach((key, index) -> index.resetDirty());
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// TransactionalMap#removeLayer drops its own diff layer AND propagates into every value
		// that is a TransactionalLayerProducer, so per-entry layers are covered too
		this.cardinalityIndexes.removeLayer(transactionalLayer);
	}

}
