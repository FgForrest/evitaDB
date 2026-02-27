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
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PrimitiveIterator.OfInt;
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
	 * Set of all valid referenced entity primary keys for a particular entity.
	 */
	private final BaseBitmap referencedEntityIds;
	/**
	 * Map of allowed representative keys to the set of source entity primary keys that may see them.
	 * Used only for references with duplicate cardinality, where representative attribute values
	 * disambiguate multiple references to the same entity. When `null` or missing a particular key,
	 * the visibility decision falls back to the {@link #unrestrictedKeysAllowed} flag.
	 */
	private Map<RepresentativeReferenceKey, Bitmap> restrictedKeys = null;
	/**
	 * Flag indicating whether representative keys without an explicit entry in {@link #restrictedKeys} are
	 * considered allowed. Defaults to `true`, meaning references are visible unless explicitly excluded.
	 * Set to `false` by {@link ValidEntityToReferenceMapping#forbidAllExceptIncludingDiscriminators} to require
	 * an explicit restriction entry for each representative key — used for duplicate-cardinality references
	 * after filtering narrows the valid set.
	 */
	private boolean unrestrictedKeysAllowed = true;

	/**
	 * Creates a new representative mapping with the given key producer function.
	 *
	 * @param representativeKeyProducer function that extracts a {@link RepresentativeReferenceKey} from
	 *                                  a {@link ReferenceDecorator}, used during {@link #contains} checks
	 *                                  to produce the lookup key for a given reference
	 */
	public RepresentativeMapping(
		@Nonnull Function<ReferenceDecorator, RepresentativeReferenceKey> representativeKeyProducer
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
	 * Sets whether unrestricted keys are allowed in the context of this mapping.
	 *
	 * @param unrestrictedKeysAllowed a boolean flag indicating whether unrestricted keys are permitted.
	 *                                 If true, unrestricted keys are allowed; if false, they are not.
	 */
	public void setUnrestrictedKeysAllowed(boolean unrestrictedKeysAllowed) {
		this.unrestrictedKeysAllowed = unrestrictedKeysAllowed;
	}

	/**
	 * Records a fine-grained restriction for a specific {@link RepresentativeReferenceKey}. The provided
	 * `entityPrimaryKeys` bitmap represents the set of **source entity** primary keys that are allowed to
	 * see references identified by the given representative key. If the internal restricted keys map is not
	 * yet initialized, it is created to accommodate the restriction.
	 *
	 * @param representativeReferenceKey the key identifying the reference (including representative attribute
	 *                                   values for duplicate-cardinality references)
	 * @param entityPrimaryKeys          the set of source entity primary keys allowed to see this reference
	 */
	public void restrictTo(@Nonnull RepresentativeReferenceKey representativeReferenceKey, @Nonnull Bitmap entityPrimaryKeys) {
		if (this.restrictedKeys == null) {
			this.restrictedKeys = CollectionUtils.createHashMap(8);
		}
		this.restrictedKeys.put(representativeReferenceKey, entityPrimaryKeys);
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
	 * Checks whether the given reference is visible for the specified source entity. The method operates
	 * in two modes depending on the produced {@link RepresentativeReferenceKey}:
	 *
	 * - **No representative attribute values** (simple reference without duplicates): performs a direct
	 *   bitmap lookup — returns `true` if the referenced entity's primary key is present in
	 *   {@link #referencedEntityIds}.
	 * - **With representative attribute values** (duplicate-cardinality reference): looks up the
	 *   {@link #restrictedKeys} map for the produced key. If an explicit restriction exists, checks
	 *   whether `entityPrimaryKey` is in the allowed bitmap. If no restriction is found, falls back
	 *   to the {@link #unrestrictedKeysAllowed} flag.
	 *
	 * @param entityPrimaryKey the primary key of the source entity
	 * @param reference        the reference decorator to check visibility for
	 * @return `true` if the reference is visible for the given source entity, `false` otherwise
	 */
	public boolean contains(int entityPrimaryKey, @Nonnull ReferenceDecorator reference) {
		final RepresentativeReferenceKey referenceKey = this.representativeKeyProducer.apply(reference);
		if (referenceKey.representativeAttributeValues().length == 0) {
			return this.referencedEntityIds.contains(referenceKey.primaryKey());
		} else {
			final Bitmap restrictedPrimaryKeys = this.restrictedKeys == null ? null : this.restrictedKeys.get(referenceKey);
			if (restrictedPrimaryKeys == null) {
				return this.unrestrictedKeysAllowed;
			} else {
				return restrictedPrimaryKeys.contains(entityPrimaryKey);
			}
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
			final Iterator<Entry<RepresentativeReferenceKey, Bitmap>> it2 = this.restrictedKeys.entrySet().iterator();
			while (it2.hasNext()) {
				Entry<RepresentativeReferenceKey, Bitmap> pk = it2.next();
				sb.append(pk.getKey()).append(": ").append(pk.getValue());
				if (it2.hasNext()) {
					sb.append(", ");
				}
			}
		}
		return sb.toString();
	}
}
