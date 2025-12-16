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


import com.carrotsearch.hppc.predicates.IntPredicate;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.function.Function;

/**
 * The RepresentativeMapping class is a container for managing and filtering entity references
 * using both numeric identifiers and representative keys.
 *
 * Maintains set of all valid referenced entity primary keys for particular entity, which may be further
 * restricted to set of allowed representative keys.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class RepresentativeMapping {
	/**
	 * Function that produces representative keys from references.
	 */
	private final Function<ReferenceDecorator, RepresentativeReferenceKey> representativeKeyProducer;
	/**
	 * Set of all valid referenced entity primary keys for particular entity.
	 */
	private final BaseBitmap referencedEntityIds;
	/**
	 * Set of allowed representative keys - if empty or null, all representative keys are allowed.
	 */
	private Set<RepresentativeReferenceKey> restrictedKeys = null;

	public RepresentativeMapping(
		@Nonnull Function<ReferenceDecorator, RepresentativeReferenceKey> representativeKeyProducer,
		int expectedSize
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
	 * Clears all stored referenced entity primary keys, removing any entries
	 * previously added to the set of valid referenced entity identifiers.
	 */
	public void clear() {
		this.referencedEntityIds.clear();
	}

	/**
	 * Removes all referenced entity identifiers that match the provided predicate from the set of valid referenced entity identifiers.
	 *
	 * @param predicate the condition used to determine which referenced entity identifiers should be removed;
	 *                  it is a functional interface that evaluates to true for identifiers to be removed
	 */
	public void removeAll(@Nonnull IntPredicate predicate) {
		this.referencedEntityIds.removeAll(predicate);
	}

	/**
	 * Restricts the entity to the provided {@link RepresentativeReferenceKey}. If the internal set of restricted keys
	 * is not initialized, it is created to accommodate the restriction.
	 *
	 * @param representativeReferenceKey the key representing the reference to which the restriction is being applied
	 */
	public void restrictTo(@Nonnull RepresentativeReferenceKey representativeReferenceKey) {
		if (this.restrictedKeys == null) {
			this.restrictedKeys = CollectionUtils.createHashSet(8);
		}
		this.restrictedKeys.add(representativeReferenceKey);
	}

	/**
	 * Retains only the referenced entity identifiers in the current set that match the provided predicate.
	 * Any referenced entity identifiers that do not satisfy the predicate are removed from the set.
	 *
	 * @param predicate the condition used to determine which referenced entity identifiers should be retained;
	 *                  it is a functional interface that evaluates to true for identifiers to be retained
	 */
	public void retainAll(@Nonnull IntPredicate predicate) {
		this.referencedEntityIds.retainAll(predicate);
	}

	/**
	 * Checks if the provided reference is contained within the referenced entity identifiers.
	 * The method evaluates based on both primary key presence and potential restrictions applied.
	 *
	 * @param reference the reference to check, wrapped in a {@link ReferenceDecorator},
	 *                  which serves as the input for determining the presence.
	 * @return true if the reference is contained in the set of referenced entity identifiers
	 * and meets any applicable restrictions; otherwise, false.
	 */
	public boolean contains(@Nonnull ReferenceDecorator reference) {
		final RepresentativeReferenceKey referenceKey = this.representativeKeyProducer.apply(reference);
		if (referenceKey.representativeAttributeValues().length == 0) {
			return this.referencedEntityIds.contains(referenceKey.primaryKey());
		} else {
			return this.referencedEntityIds.contains(referenceKey.primaryKey()) &&
				(this.restrictedKeys == null || this.restrictedKeys.contains(referenceKey));
		}
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(this.referencedEntityIds.size() * 5);
		final OfInt it = this.referencedEntityIds.iterator();
		while (it.hasNext()) {
			int pk = it.next();
			sb.append(pk);
			if (it.hasNext()) {
				sb.append(", ");
			}
		}
		if (this.restrictedKeys != null) {
			sb.append(" restricted to: ");
			final Iterator<RepresentativeReferenceKey> it2 = this.restrictedKeys.iterator();
			while (it2.hasNext()) {
				RepresentativeReferenceKey pk = it2.next();
				sb.append(pk);
				if (it2.hasNext()) {
					sb.append(", ");
				}
			}
		}
		return sb.toString();
	}
}
