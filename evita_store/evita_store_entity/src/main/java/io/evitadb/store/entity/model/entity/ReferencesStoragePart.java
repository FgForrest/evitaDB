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

package io.evitadb.store.entity.model.entity;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
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
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
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
import java.util.OptionalInt;
import java.util.function.ToIntBiFunction;
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
@EqualsAndHashCode(exclude = {"dirty", "sizeInBytes"})
@ToString(of = "entityPrimaryKey")
public class ReferencesStoragePart implements EntityStoragePart {
	@Serial private static final long serialVersionUID = -8694125339226376099L;
	private static final Reference[] EMPTY_REFERENCES = new Reference[0];

	/**
	 * Id used for lookups in persistent storage for this particular container.
	 */
	@Getter private final int entityPrimaryKey;
	/**
	 * Contains last used primary key among all references in this container. This is needed for assigning new unique
	 * reference internal primary keys when new references are added (we cannot allow to reuse keys of removed references).
	 */
	@Getter private int lastUsedPrimaryKey;
	/**
	 * See {@link Entity#getReferences()}. References are sorted in ascending order according to {@link EntityReference} comparator.
	 */
	@Getter private Reference[] references = EMPTY_REFERENCES;
	/**
	 * Contains information about size of this container in bytes.
	 */
	private final int sizeInBytes;
	/**
	 * Contains true if anything changed in this container.
	 */
	@Getter private boolean dirty;
	/**
	 * Contains true if some of the references in this container have unassigned internal primary keys (negative or zero values).
	 */
	private boolean unassignedPrimaryKeys = false;

	public ReferencesStoragePart(int entityPrimaryKey) {
		this.entityPrimaryKey = entityPrimaryKey;
		this.lastUsedPrimaryKey = 0;
		this.sizeInBytes = -1;
	}

	public ReferencesStoragePart(int entityPrimaryKey, int lastUsedPrimaryKey, @Nonnull Reference[] references, int sizeInBytes) {
		this.entityPrimaryKey = entityPrimaryKey;
		this.lastUsedPrimaryKey = lastUsedPrimaryKey;
		this.references = references;
		this.sizeInBytes = sizeInBytes;
	}

	@Nullable
	@Override
	public Long getStoragePartPK() {
		return (long) this.entityPrimaryKey;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		return this.entityPrimaryKey;
	}

	@Override
	public boolean isEmpty() {
		return this.references.length == 0 || Arrays.stream(this.references).noneMatch(Droppable::exists);
	}

	@Nonnull
	@Override
	public OptionalInt sizeInBytes() {
		return this.sizeInBytes == -1 ? OptionalInt.empty() : OptionalInt.of(this.sizeInBytes);
	}

	/**
	 * This method assigns missing primary keys to references that lack them in the current storage part.
	 *
	 * If the field `unassignedPrimaryKeys` is set to true, it indicates that some references in the array
	 * do not have assigned primary keys. The method iterates over the `references` array and checks each reference.
	 * For references with a negative internal primary key, it generates a new primary key incrementally based on
	 * the `lastUsedPrimaryKey` field and assigns it to the reference.
	 *
	 * After the assignment procedure, the storage part marks itself as modified by setting the `dirty` field to true.
	 * Finally, the `unassignedPrimaryKeys` field is set to false to indicate all missing primary keys have been assigned.
	 */
	public void assignMissingIdsAndSort() {
		if (this.unassignedPrimaryKeys) {
			Reference previousReference = null;
			for (int i = 0; i < this.references.length; i++) {
				Reference reference = this.references[i];
				if (previousReference != null) {
					final ReferenceKey previousKeyWithId = previousReference.getReferenceKey();
					Assert.isPremiseValid(
						previousKeyWithId.compareTo(reference.getReferenceKey()) < 0,
						() -> "References must be sorted in ascending order according to their business key: "
							+ Arrays.stream(this.references)
							        .map(Reference::getReferenceKey)
							        .map(String::valueOf)
							        .collect(Collectors.joining(", "))
					);
				}
				// assign primary keys to references that don't have it yet
				if (!reference.getReferenceKey().isKnownInternalPrimaryKey()) {
					reference = new Reference(++this.lastUsedPrimaryKey, reference);
					this.references[i] = reference;
					this.dirty = true;
				}
				previousReference = reference;
			}

			this.unassignedPrimaryKeys = false;
		}
	}

	/**
	 * Adds new or replaces existing reference of the entity. This method can be used only if the references doesn't
	 * allow duplicated references (same business key).
	 *
	 * @param referenceKey reference key of the reference to be added or replaced
	 * @return the internal reference contract that has been modified
	 */
	@Nonnull
	public ReferenceContract replaceOrAddReference(
		@Nonnull ReferenceKey referenceKey,
		@Nonnull UnaryOperator<ReferenceContract> mutator
	) {
		final InsertionPosition insertionPosition;
		if (referenceKey.isKnownInternalPrimaryKey()) {
			insertionPosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
				referenceKey,
				this.references,
				(ToIntBiFunction<ReferenceContract, ReferenceKey>)
					(examinedReference, rk) -> examinedReference.getReferenceKey().compareTo(rk)
			);
		} else {
			insertionPosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
				new ReferenceKey(referenceKey.referenceName(), referenceKey.primaryKey(), Integer.MIN_VALUE),
				this.references,
				(ToIntBiFunction<ReferenceContract, ReferenceKey>)
					(examinedReference, rk) -> examinedReference.getReferenceKey().compareTo(rk)
			);
			if (referenceKey.isUnknownReference()) {
				// we are adding a new reference-verify that we are not duplicating existing reference
				Assert.isPremiseValid(
					// either there is no such reference yet
					!insertionPosition.alreadyPresent() ||
						// or the found position is the last one in array
						insertionPosition.position() + 1 == this.references.length ||
						// or the next reference is different than the one we are adding
						!this.references[insertionPosition.position() + 1].getReferenceKey().equals(referenceKey),
					() -> "There is already existing reference with key " + referenceKey + " in entity " + this.entityPrimaryKey + "! References must be unique!"
				);
			}
		}

		// insert or replace reference on computed position
		final int position = insertionPosition.position();
		final Reference mutatedReference;
		if (insertionPosition.alreadyPresent()) {
			mutatedReference = (Reference) mutator.apply(this.references[position]);
			final Reference originalReference = this.references[position];
			if (originalReference.differsFrom(mutatedReference)) {
				this.references[position] = mutatedReference;
				Assert.isPremiseValid(
					originalReference.getReferenceKey().equals(mutatedReference.getReferenceKey()) &&
					originalReference.getReferenceKey().internalPrimaryKey() == mutatedReference.getReferenceKey().internalPrimaryKey(),
					() -> "Mutation must not remove the internal primary key from the existing reference!"
				);
				this.dirty = true;
			}
		} else {
			mutatedReference = (Reference) mutator.apply(null);
			this.references = ArrayUtils.insertRecordIntoArrayOnIndex(mutatedReference, this.references, position);
			this.dirty = true;
			this.unassignedPrimaryKeys = this.unassignedPrimaryKeys || !mutatedReference.getReferenceKey().isKnownInternalPrimaryKey();
		}

		return mutatedReference;
	}

	/**
	 * Returns reference array as collection so it can be easily used in {@link Entity}.
	 */
	@Nonnull
	public Collection<ReferenceContract> getReferencesAsCollection() {
		return Arrays.stream(this.references).collect(Collectors.toList());
	}

	/**
	 * Returns array of primary keys of all referenced entities of particular `referenceName`.
	 */
	@Nonnull
	public int[] getReferencedIds(@Nonnull String referenceName) {
		return Arrays.stream(this.references)
			.filter(Droppable::exists)
			.filter(it -> Objects.equals(referenceName, it.getReferenceName()))
			.mapToInt(it -> it.getReferenceKey().primaryKey())
			.toArray();
	}

	/**
	 * Returns array of distinct primary keys of all referenced entities of particular `referenceName`.
	 */
	@Nonnull
	public int[] getDistinctReferencedIds(@Nonnull String referenceName) {
		final Reference[] refs = this.references;
		if (refs.length == 0) {
			return ArrayUtils.EMPTY_INT_ARRAY;
		}

		int[] tmp = new int[refs.length];
		int size = 0;
		Reference previousRef = null;
		for (final Reference ref : refs) {
			if (ref.exists() &&
				referenceName.equals(ref.getReferenceName()) &&
				(previousRef == null || previousRef.getReferencedPrimaryKey() != ref.getReferencedPrimaryKey())
			) {
				tmp[size++] = ref.getReferenceKey().primaryKey();
				previousRef = ref;
			}
		}

		if (size == 0) {
			return ArrayUtils.EMPTY_INT_ARRAY;
		}

		return size == tmp.length ? tmp : Arrays.copyOf(tmp, size);
	}

	/**
	 * Returns array of distinct primary keys of all referenced entity groups of particular `referenceName`.
	 */
	@Nonnull
	public int[] getDistinctReferencedGroupIds(@Nonnull String referenceName) {
		final Reference[] refs = this.references;
		if (refs.length == 0) {
			return ArrayUtils.EMPTY_INT_ARRAY;
		}

		Reference previousRef = null;
		final IntSet groupIds = new IntHashSet(refs.length);
		for (final Reference ref : refs) {
			if (ref.exists() && referenceName.equals(ref.getReferenceName())) {
				final Optional<GroupEntityReference> group = ref.getGroup();
				if (group.isPresent()) {
					final int groupPk = group.get().getPrimaryKeyOrThrowException();
					if (previousRef == null ||
						!referenceName.equals(previousRef.getReferenceName()) ||
						!groupIds.contains(groupPk)
					) {
						groupIds.add(groupPk);
					}
				}
				previousRef = ref;
			}
		}

		return groupIds.toArray();
	}

	/**
	 * Returns true if passed locale is found among localized attributes of any reference held in this storage part.
	 */
	public boolean isLocalePresent(@Nonnull Locale locale) {
		for (Reference reference : this.references) {
			if (reference.exists() && reference.getAttributeLocales().contains(locale)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Finds reference to target entity specified by `referenceKey` in current container or throws exception.
	 *
	 * @throws IllegalStateException when reference is not found
	 */
	@Nonnull
	public ReferenceContract findReferenceOrThrowException(@Nonnull ReferenceKey referenceKey) {
		final int index = ArrayUtils.binarySearch(
			this.references, referenceKey,
			(referenceContract, theReferenceKey) -> referenceContract.getReferenceKey().compareTo(theReferenceKey)
		);
		Assert.isPremiseValid(index >= 0, () -> "Reference " + referenceKey + " for entity `" + this.entityPrimaryKey + "` was not found!");
		final ReferenceContract reference = this.references[index];
		Assert.isPremiseValid(reference.exists(), () -> "Reference " + referenceKey + " for entity `" + this.entityPrimaryKey + "` was not found!");
		Assert.isPremiseValid(
			index + 1 == this.references.length ||
			!this.references[index + 1].getReferenceKey().equals(referenceKey),
			() -> "There is more than one reference " + referenceKey + " for entity `" + this.entityPrimaryKey + "`!"
		);
		return reference;
	}

	/**
	 * Checks if the provided {@link ReferenceKey} is present in the references.
	 *
	 * @param referenceKey the key to be checked for presence in the references
	 * @return true if the reference key exists in the references, false otherwise
	 */
	public boolean contains(@Nonnull ReferenceKey referenceKey) {
		return ArrayUtils.binarySearch(
			this.references, referenceKey,
			(referenceContract, theReferenceKey) -> referenceContract.getReferenceKey().compareTo(theReferenceKey)
		) >= 0;
	}

}
