/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.entity.model.entity;

import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.store.model.EntityStoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import lombok.Getter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * This container class represents collection of {@link Reference} of single {@link Entity}. Contains all references
 * along with grouping information and related attributes (localized and non-localized as well).
 *
 * Although query allows fetching references only of certain type, all references including all their attributes
 * are stored in single storage container because the data are expected to be small.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@ToString(of = "entityPrimaryKey")
public class ReferencesStoragePart implements EntityStoragePart {
	@Serial private static final long serialVersionUID = -4113353795728768940L;
	private static final ReferenceContract[] EMPTY_REFERENCES = new ReferenceContract[0];

	/**
	 * Id used for lookups in persistent storage for this particular container.
	 */
	@Getter private final int entityPrimaryKey;
	/**
	 * See {@link Entity#getReferences()}. References are sorted in ascending order according to {@link EntityReference} comparator.
	 */
	@Getter private ReferenceContract[] references = EMPTY_REFERENCES;
	/**
	 * Contains true if anything changed in this container.
	 */
	@Getter private boolean dirty;

	public ReferencesStoragePart(int entityPrimaryKey) {
		this.entityPrimaryKey = entityPrimaryKey;
	}

	public ReferencesStoragePart(int entityPrimaryKey, @Nonnull ReferenceContract[] references) {
		this.entityPrimaryKey = entityPrimaryKey;
		this.references = references;
	}

	@Nullable
	@Override
	public Long getStoragePartPK() {
		return (long) entityPrimaryKey;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		return entityPrimaryKey;
	}

	@Override
	public boolean isEmpty() {
		return references.length == 0 || Arrays.stream(references).noneMatch(Droppable::exists);
	}

	/**
	 * Adds new or replaces existing reference of the entity.
	 *
	 * @return the internal reference contract that has been modified
	 */
	public ReferenceContract replaceOrAddReference(@Nonnull ReferenceKey referenceKey, @Nonnull UnaryOperator<ReferenceContract> mutator) {
		final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			this.references, referenceKey,
			(examinedReference, rk) -> examinedReference.getReferenceKey().compareTo(rk)
		);
		final int position = insertionPosition.position();
		final ReferenceContract mutatedReference;
		if (insertionPosition.alreadyPresent()) {
			mutatedReference = mutator.apply(this.references[position]);
			if (this.references[position].differsFrom(mutatedReference)) {
				this.references[position] = mutatedReference;
				this.dirty = true;
			}
		} else {
			mutatedReference = mutator.apply(null);
			this.references = ArrayUtils.insertRecordIntoArray(mutatedReference, this.references, position);
			this.dirty = true;
		}

		return mutatedReference;
	}

	/**
	 * Returns reference array as collection so it can be easily used in {@link Entity}.
	 */
	@Nonnull
	public Collection<ReferenceContract> getReferencesAsCollection() {
		return Arrays.stream(references).collect(Collectors.toList());
	}

	/**
	 * Returns array of primary keys of all referenced entities of particular `referenceName`.
	 */
	@Nonnull
	public int[] getReferencedIds(@Nonnull String referenceName) {
		return Arrays.stream(references)
			.filter(Droppable::exists)
			.filter(it -> Objects.equals(referenceName, it.getReferenceName()))
			.mapToInt(it -> it.getReferenceKey().primaryKey())
			.toArray();
	}

	/**
	 * Returns array of primary keys of all referenced entity groups of particular `referenceName`.
	 */
	@Nonnull
	public int[] getReferencedGroupIds(@Nonnull String referenceName) {
		return Arrays.stream(references)
			.filter(Droppable::exists)
			.filter(it -> Objects.equals(referenceName, it.getReferenceName()))
			.map(ReferenceContract::getGroup)
			.filter(Optional::isPresent)
			.map(Optional::get)
			.mapToInt(GroupEntityReference::getPrimaryKey)
			.distinct()
			.toArray();
	}

	/**
	 * Returns true if passed locale is found among localized attributes of any reference held in this storage part.
	 */
	public boolean isLocalePresent(@Nonnull Locale locale) {
		return Arrays.stream(references)
			.filter(Droppable::exists)
			.anyMatch(it -> it.getAttributeLocales().contains(locale));
	}

	/**
	 * Finds reference to target entity specified by `referenceKey` in current container or throws exception.
	 *
	 * @throws IllegalStateException when reference is not found
	 */
	@Nonnull
	public ReferenceContract findReferenceOrThrowException(@Nonnull ReferenceKey referenceKey) {
		return Arrays
			.stream(references)
			.filter(Droppable::exists)
			.filter(it -> it.getReferenceKey().equals(referenceKey))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Reference " + referenceKey + " for entity `" + entityPrimaryKey + "` was not found!"));
	}
}
