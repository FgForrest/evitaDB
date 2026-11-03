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

package io.evitadb.core.query.fetch;


import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.function.Function;

/**
 * The RepresentativeMapping class is a container for managing and filtering entity references
 * using both numeric identifiers and representative keys.
 *
 * Maintains set of all valid referenced entity primary keys for particular entity and, for references judged row by
 * row, the set of admitted representative keys.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class RepresentativeMapping {
	/**
	 * Function that produces representative keys from references.
	 */
	private final Function<ReferenceContract, RepresentativeReferenceKey> representativeKeyProducer;
	/**
	 * Set of all valid referenced entity primary keys for a particular entity.
	 */
	private final BaseBitmap referencedEntityIds;
	/**
	 * The representative keys of the reference rows admitted one by one by {@link #admit(ReferenceContract)} - used
	 * only for references with duplicate cardinality whose rows are judged by a filter, where two rows referencing
	 * the same entity may be given different verdicts. NULL until the first row is admitted.
	 */
	@Nullable private Set<RepresentativeReferenceKey> admittedKeys = null;

	/**
	 * Creates a new representative mapping with the given key producer function.
	 *
	 * @param representativeKeyProducer function that extracts a {@link RepresentativeReferenceKey} from
	 *                                  a {@link ReferenceContract}, used when a row is
	 *                                  {@link #admit(ReferenceContract) admitted} and when it is looked up
	 */
	public RepresentativeMapping(
		@Nonnull Function<ReferenceContract, RepresentativeReferenceKey> representativeKeyProducer
	) {
		this.representativeKeyProducer = representativeKeyProducer;
		this.referencedEntityIds = new BaseBitmap();
	}

	/**
	 * Converts the set of referenced entity identifiers into a {@link Formula} representation.
	 * If the set of identifiers is empty, a predefined {@link EmptyFormula} instance is returned,
	 * representing no entities. Otherwise, a new {@link ConstantFormula} is created with the set of identifiers.
	 *
	 * @return a {@link Formula} instance representing the current state of referenced entity identifiers.
	 *         Returns {@link EmptyFormula#INSTANCE} if the set is empty, otherwise returns a {@link ConstantFormula}.
	 */
	@Nonnull
	public Formula toFormula() {
		return this.referencedEntityIds.isEmpty() ?
			EmptyFormula.INSTANCE :
			new ConstantFormula(this.referencedEntityIds);
	}

	/**
	 * Adds a referenced entity primary key to the set of valid referenced entity identifiers.
	 *
	 * @param referencedEntityPrimaryKey the primary key of the entity to be added
	 *                                   to the set of referenced entity identifiers
	 */
	public void add(int referencedEntityPrimaryKey) {
		this.referencedEntityIds.add(referencedEntityPrimaryKey);
	}

	/**
	 * Admits a single reference row: its referenced primary key becomes valid and its representative key is recorded,
	 * so that {@link #isAdmitted(ReferenceContract)} selects this row and not the other rows referencing the same
	 * entity.
	 *
	 * @param row the admitted reference row
	 */
	public void admit(@Nonnull ReferenceContract row) {
		this.referencedEntityIds.add(row.getReferencedPrimaryKey());
		if (this.admittedKeys == null) {
			this.admittedKeys = CollectionUtils.createHashSet(8);
		}
		this.admittedKeys.add(this.representativeKeyProducer.apply(row));
	}

	/**
	 * Returns TRUE when the very row was admitted by {@link #admit(ReferenceContract)} - compared by its
	 * representative key, which is unique among the rows of one entity.
	 *
	 * @param row the reference row, raw or decorated
	 * @return TRUE when the row was admitted
	 */
	public boolean isAdmitted(@Nonnull ReferenceContract row) {
		return this.admittedKeys != null && this.admittedKeys.contains(this.representativeKeyProducer.apply(row));
	}

	/**
	 * Returns TRUE when the referenced entity primary key is among the valid ones.
	 *
	 * @param referencedEntityPrimaryKey the referenced entity primary key
	 * @return TRUE when the key is valid
	 */
	public boolean containsReferencedPrimaryKey(int referencedEntityPrimaryKey) {
		return this.referencedEntityIds.contains(referencedEntityPrimaryKey);
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(this.referencedEntityIds.size() * 5);
		final OfInt it = this.referencedEntityIds.iterator();
		while (it.hasNext()) {
			final int pk = it.next();
			sb.append(pk);
			if (it.hasNext()) {
				sb.append(", ");
			}
		}
		return sb.toString();
	}
}
