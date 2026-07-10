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
import io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex;

import javax.annotation.Nonnull;

/**
 * {@link IndexComponent} wrapper for the dedicated {@link ReferenceTypeCardinalityIndex} carried by
 * {@link io.evitadb.index.ReferencedTypeEntityIndex}. The index keeps cardinalities of indexed
 * primary keys per owner entity primary key so we know when a referenced PK can be removed from
 * the bitmap shared by the parent index.
 *
 * This component does **not** contribute to the {@link EntityIndexManifest} —
 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexStoragePart}
 * is addressed independently by the `(entityIndexPrimaryKey, referenceName)` pair rather than via the
 * parent {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart} manifest.
 */
public final class ReferenceTypeCardinalityComponent implements IndexComponent {

	/**
	 * The wrapped cardinality index that tracks index-PK → owner-entity-PK multiplicities. Held by
	 * reference because the parent index never swaps the instance during its lifetime.
	 */
	@Nonnull private final ReferenceTypeCardinalityIndex indexPrimaryKeyCardinality;
	/**
	 * The reference name read from the parent index key's discriminator, used as the second half of
	 * the {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexStoragePart}
	 * key.
	 */
	@Nonnull private final String referenceName;

	/**
	 * @param indexPrimaryKeyCardinality the wrapped cardinality index
	 * @param referenceName              the reference name for the storage part key
	 */
	public ReferenceTypeCardinalityComponent(
		@Nonnull ReferenceTypeCardinalityIndex indexPrimaryKeyCardinality,
		@Nonnull String referenceName
	) {
		this.indexPrimaryKeyCardinality = indexPrimaryKeyCardinality;
		this.referenceName = referenceName;
	}

	@Override
	public void collectModifiedStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull EntityIndexManifest manifest,
		@Nonnull TrappedChanges trappedChanges
	) {
		// emit only when the wrapped index reports dirty changes (the index self-gates on its dirty flag); no manifest
		// contribution — the granular cardinality parts are addressed independently of the parent EntityIndexStoragePart.
		// PAGED indexes emit one leaf page per changed leaf + a removal per freed leaf + a PAGED root; SINGLE indexes emit
		// one inline root.
		this.indexPrimaryKeyCardinality.appendStorageParts(
			entityIndexPrimaryKey, this.referenceName, trappedChanges
		);
	}

	@Override
	public void resetDirty() {
		this.indexPrimaryKeyCardinality.resetDirty();
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.indexPrimaryKeyCardinality.removeLayer(transactionalLayer);
	}

}
