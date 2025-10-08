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

package io.evitadb.index.facet;

import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import lombok.Data;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * FacetIdIndex contains information about all entity ids that use certain facet as their
 * {@link Entity#getReference(String, int)}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Data
public class FacetIdIndex implements VoidTransactionMemoryProducer<FacetIdIndex>, IndexDataStructure {
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Identification of the facet - links to {@link EntityReference#getPrimaryKey()}
	 */
	@Getter private final int facetId;
	/**
	 * Contains bitmap with all entity ids that refer to this facet entity.
	 */
	private final TransactionalBitmap records;

	public FacetIdIndex(int facetId) {
		this.facetId = facetId;
		this.records = new TransactionalBitmap();
	}

	public FacetIdIndex(int facetId, Bitmap records) {
		this.facetId = facetId;
		this.records = new TransactionalBitmap(records);
	}

	/**
	 * Adds new entity primary key to this facet index.
	 *
	 * @return true if entity id was really added
	 */
	public boolean addFacet(int entityPrimaryKey) {
		return this.records.add(entityPrimaryKey);
	}

	/**
	 * Removes entity primary key from this facet index.
	 *
	 * @return true if entity id was really removed
	 */
	public boolean removeFacet(int entityPrimaryKey) {
		return this.records.remove(entityPrimaryKey);
	}

	/**
	 * Returns true if there is no entity id linked to this facet and the entire index is useless.
	 */
	public boolean isEmpty() {
		return this.records.isEmpty();
	}

	/**
	 * Returns count of all entity ids referring to this facet.
	 */
	public int size() {
		return this.records.size();
	}

	@Override
	public String toString() {
		return this.facetId + ": " + this.records;
	}

	@Override
	public void resetDirty() {
		// do nothing here
	}

	/*
		Implementation of TransactionalLayerProducer
	 */

	@Nonnull
	@Override
	public FacetIdIndex createCopyWithMergedTransactionalMemory(@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		return new FacetIdIndex(
			this.facetId, transactionalLayer.getStateCopyWithCommittedChanges(this.records)
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.records.removeLayer(transactionalLayer);
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
	}
}
