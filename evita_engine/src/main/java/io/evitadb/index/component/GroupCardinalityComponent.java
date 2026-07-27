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
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.PersistentTransactionalMap;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GroupCardinalityIndexStoragePart;

import javax.annotation.Nonnull;

/**
 * {@link IndexComponent} that wraps the group-level cardinality state owned by
 * {@link io.evitadb.index.ReducedGroupEntityIndex}. The state consists of three pieces:
 *
 * - `cardinalityDirty` — a single transactional flag flipped whenever any of the maps below change,
 * - `pkCardinalities` — per-owner-entity-PK cardinality tracking so that an entity is only added to
 *   the underlying bitmap on the 0→1 transition and only removed on the 1→0 transition,
 * - `referencedPrimaryKeysIndex` — per-referenced-entity-PK bitmap of owner entity PKs.
 *
 * This component does **not** contribute to the {@link EntityIndexManifest} —
 * {@link GroupCardinalityIndexStoragePart} is addressed independently by the
 * `(entityIndexPrimaryKey, referenceName)` pair rather than via the parent
 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart} manifest.
 */
public final class GroupCardinalityComponent implements IndexComponent {

	/**
	 * Transactional flag set whenever any of the three pieces of state below change. Drives the
	 * conditional emit of {@link GroupCardinalityIndexStoragePart}.
	 */
	@Nonnull private final TransactionalBoolean cardinalityDirty;
	/**
	 * Per-owner-entity-PK cardinality map: tracks how many references currently resolve to the
	 * owning group for each entity primary key.
	 */
	@Nonnull private final PersistentTransactionalMap<Integer, Integer> pkCardinalities;
	/**
	 * Per-referenced-entity-PK reverse-mapping bitmap: for each facet PK, the set of owner entity
	 * PKs that reference it within the owning group.
	 */
	@Nonnull private final TransactionalMap<Integer, TransactionalBitmap> referencedPrimaryKeysIndex;
	/**
	 * The reference name read from the parent index key's discriminator, used as the second half of
	 * the {@link GroupCardinalityIndexStoragePart} key.
	 */
	@Nonnull private final String referenceName;

	/**
	 * @param cardinalityDirty           the transactional dirty flag
	 * @param pkCardinalities            the owner-PK cardinality map
	 * @param referencedPrimaryKeysIndex the referenced-PK → owner-PKs reverse-mapping
	 * @param referenceName              the reference name for the storage part key
	 */
	public GroupCardinalityComponent(
		@Nonnull TransactionalBoolean cardinalityDirty,
		@Nonnull PersistentTransactionalMap<Integer, Integer> pkCardinalities,
		@Nonnull TransactionalMap<Integer, TransactionalBitmap> referencedPrimaryKeysIndex,
		@Nonnull String referenceName
	) {
		this.cardinalityDirty = cardinalityDirty;
		this.pkCardinalities = pkCardinalities;
		this.referencedPrimaryKeysIndex = referencedPrimaryKeysIndex;
		this.referenceName = referenceName;
	}

	@Override
	public void collectModifiedStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull EntityIndexManifest manifest,
		@Nonnull TrappedChanges trappedChanges
	) {
		// emit only when the dirty flag indicates real change — group cardinality storage parts
		// are addressed independently of the parent manifest, so no manifest contribution here
		if (this.cardinalityDirty.isTrue()) {
			trappedChanges.addChangeToStore(
				new GroupCardinalityIndexStoragePart(
					entityIndexPrimaryKey,
					this.referenceName,
					this.pkCardinalities,
					this.referencedPrimaryKeysIndex
				)
			);
		}
	}

	@Override
	public void resetDirty() {
		this.cardinalityDirty.reset();
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.cardinalityDirty.removeLayer(transactionalLayer);
		this.pkCardinalities.removeLayer(transactionalLayer);
		this.referencedPrimaryKeysIndex.removeLayer(transactionalLayer);
	}

}
