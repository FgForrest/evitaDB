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

import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ComparableReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.RepresentativeAttributeDefinition;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.IntObjBiFunction;
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
import java.io.Serializable;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToIntBiFunction;
import java.util.function.ToIntFunction;
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
	private static final ToIntBiFunction<ReferenceContract, ReferenceKey> FULL_COMPARISON_FUNCTION =
		(examinedReference, rk) -> ReferenceKey.FULL_COMPARATOR.compare(examinedReference.getReferenceKey(), rk);
	private static final ToIntBiFunction<ReferenceContract, ReferenceKey> GENERIC_COMPARISON_FUNCTION =
		(examinedReference, rk) -> ReferenceKey.GENERIC_COMPARATOR.compare(examinedReference.getReferenceKey(), rk);

	/**
	 * Id used for lookups in persistent storage for this particular container.
	 */
	@Getter private final int entityPrimaryKey;
	/**
	 * Contains information about size of this container in bytes.
	 */
	private final int sizeInBytes;
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

	public ReferencesStoragePart(
		int entityPrimaryKey, int lastUsedPrimaryKey, @Nonnull Reference[] references, int sizeInBytes) {
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
	 *
	 * @return map of all reference keys that were assigned new primary keys during this operation
	 */
	@Nonnull
	public Map<ComparableReferenceKey, ReferenceKey> assignMissingIdsAndSort() {
		if (this.unassignedPrimaryKeys) {
			final int lupkBefore = this.lastUsedPrimaryKey;
			Map<ComparableReferenceKey, ReferenceKey> assignedKeys = null;
			ReferenceKey previousReferenceKey = null;
			for (int i = 0; i < this.references.length; i++) {
				Reference reference = this.references[i];
				if (previousReferenceKey != null) {
					Assert.isPremiseValid(
						ReferenceKey.FULL_COMPARATOR.compare(previousReferenceKey, reference.getReferenceKey()) < 0,
						() -> "References must be sorted in ascending order according to their business key: "
							+ Arrays.stream(this.references)
							        .map(Reference::getReferenceKey)
							        .map(String::valueOf)
							        .collect(Collectors.joining(", "))
					);
				}
				// remember the previous key for the next iteration
				previousReferenceKey = reference.getReferenceKey();
				// assign primary keys to references that don't have it yet (update the key)
				if (!reference.getReferenceKey().isKnownInternalPrimaryKey()) {
					reference = new Reference(++this.lastUsedPrimaryKey, reference);
					this.references[i] = reference;
					this.dirty = true;
					if (assignedKeys == null) {
						assignedKeys = new HashMap<>(16);
					}
					assignedKeys.put(
						new ComparableReferenceKey(previousReferenceKey),
						reference.getReferenceKey()
					);
				}
			}

			if (lupkBefore != this.lastUsedPrimaryKey) {
				// after modifications, we need to ensure the references are still sorted
				Arrays.sort(this.references, ReferenceContract.FULL_COMPARATOR);
			}

			this.unassignedPrimaryKeys = false;
			return assignedKeys == null ?
				Collections.emptyMap() : assignedKeys;
		}
		return Collections.emptyMap();
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
		InsertionPosition insertionPosition;
		if (referenceKey.isUnknownReference()) {
			insertionPosition = findPositionInGeneralManner(referenceKey);
			// we are adding a new reference-verify that we are not duplicating the existing reference
			assertNoConflictingReferencePresent(referenceKey, insertionPosition);
		} else {
			insertionPosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
				referenceKey, this.references, FULL_COMPARISON_FUNCTION
			);
			if (insertionPosition.alreadyPresent()) {
				// adapt to the already assigned primary key, if our counter is lower
				if (referenceKey.internalPrimaryKey() > this.lastUsedPrimaryKey) {
					this.lastUsedPrimaryKey = referenceKey.internalPrimaryKey();
				}
			} else if (referenceKey.isNewReference()) {
				// try to find nonsingle reference matching generic part of the key
				final InsertionPosition genericSearchResult = findPositionInGeneralManner(referenceKey);
				// but verify there is no conflicting reference present
				assertNoConflictingReferencePresent(referenceKey, insertionPosition);
				// but we can accept this position, only if an internal primary key is not known
				/* TOBEDONE #538 - due to backward compatibility with 2025.6, we may simplify later */
				if (
					genericSearchResult.alreadyPresent() &&
						this.references[genericSearchResult.position()].getReferenceKey().isUnknownReference()
				) {
					insertionPosition = genericSearchResult;
				}
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
						originalReference.getReferenceKey().internalPrimaryKey() == mutatedReference.getReferenceKey()
						                                                                            .internalPrimaryKey(),
					() -> "Mutation must not remove the internal primary key from the existing reference!"
				);
				this.dirty = true;
			}
		} else {
			mutatedReference = (Reference) mutator.apply(null);
			this.references = ArrayUtils.insertRecordIntoArrayOnIndex(mutatedReference, this.references, position);
			this.dirty = true;
			this.unassignedPrimaryKeys = this.unassignedPrimaryKeys ||
				!mutatedReference.getReferenceKey()
				                 .isKnownInternalPrimaryKey();
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

		final int startPosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			new ReferenceKey(referenceName, Integer.MIN_VALUE, Integer.MIN_VALUE),
			refs, FULL_COMPARISON_FUNCTION
		).position();
		final int endPosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			new ReferenceKey(referenceName, Integer.MAX_VALUE, Integer.MAX_VALUE),
			refs, FULL_COMPARISON_FUNCTION
		).position();

		final int[] refIds = new int[Math.max(endPosition - startPosition, 0)];
		int index = 0;
		for (int i = startPosition; i < refs.length && i < endPosition; i++) {
			final Reference ref = refs[i];
			if (referenceName.equals(ref.getReferenceName())) {
				if (ref.exists() && (index == 0 || refIds[index - 1] != ref.getReferencedPrimaryKey())) {
					refIds[index++] = ref.getReferencedPrimaryKey();
				}
			} else {
				break;
			}
		}

		return refIds.length == index ? refIds : Arrays.copyOf(refIds, index);
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

		final int startPosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			new ReferenceKey(referenceName, Integer.MIN_VALUE, Integer.MIN_VALUE),
			refs, FULL_COMPARISON_FUNCTION
		).position();
		final int endPosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			new ReferenceKey(referenceName, Integer.MAX_VALUE, Integer.MAX_VALUE),
			refs, FULL_COMPARISON_FUNCTION
		).position();

		final int[] refIds = new int[Math.max(endPosition - startPosition, 0)];
		int index = 0;
		for (int i = startPosition; i < refs.length && i < endPosition; i++) {
			final Reference ref = refs[i];
			if (referenceName.equals(ref.getReferenceName())) {
				if (ref.exists() && ref.getGroup().isPresent()) {
					final int groupId = ref.getGroup().get().getPrimaryKeyOrThrowException();
					final InsertionPosition position = ArrayUtils.computeInsertPositionOfIntInOrderedArray(
						groupId, refIds, 0, index);
					if (!position.alreadyPresent()) {
						System.arraycopy(
							refIds, position.position(), refIds, position.position() + 1, index - position.position());
						refIds[position.position()] = groupId;
						index++;
					}
				}
			} else {
				break;
			}
		}

		return refIds.length == index ? refIds : Arrays.copyOf(refIds, index);
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
		final int index = findReferenceIndex(referenceKey);
		Assert.isPremiseValid(
			index >= 0,
			() -> "Reference " + referenceKey + " for entity `" + this.entityPrimaryKey + "` was not found!"
		);
		final ReferenceContract reference = this.references[index];
		Assert.isPremiseValid(
			reference.exists(),
			() -> "Reference " + referenceKey + " for entity `" + this.entityPrimaryKey + "` was not found!"
		);
		Assert.isPremiseValid(
			index + 1 == this.references.length ||
				ReferenceKey.FULL_COMPARATOR.compare(this.references[index + 1].getReferenceKey(), referenceKey) != 0,
			() -> "There is more than one reference " + referenceKey + " for entity `" + this.entityPrimaryKey + "`!"
		);
		return reference;
	}

	/**
	 * Finds all references associated with the provided {@code referenceKey} in the current container.
	 * If no references matching the key are found, an exception is thrown.
	 *
	 * @param referenceKey the reference key to locate in the references array, must not be null
	 * @return a list of {@link ReferenceContract} objects associated with the provided {@code referenceKey}, never null
	 * @throws IllegalStateException if no matching references are found
	 */
	@Nonnull
	public List<ReferenceContract> findReferencesOrThrowException(@Nonnull ReferenceKey referenceKey) {
		Assert.isPremiseValid(
			referenceKey.isUnknownReference(),
			() -> "This method makes sense only with generic reference key!"
		);
		final InsertionPosition startPosition = findPositionInGeneralManner(referenceKey);
		if (startPosition.alreadyPresent()) {
			int index = startPosition.position();
			Assert.isPremiseValid(
				index >= 0,
				() -> "Reference " + referenceKey + " for entity `" + this.entityPrimaryKey + "` was not found!"
			);
			if (index + 1 < this.references.length &&
				this.references[index + 1].getReferenceKey().equals(referenceKey)) {
				final List<ReferenceContract> references = new ArrayList<>(Math.min(8, this.references.length - index));
				while (
					index < this.references.length &&
						this.references[index].getReferenceKey().equals(referenceKey)) {
					final Reference reference = this.references[index++];
					if (reference.exists()) {
						references.add(reference);
					}
				}
				return references;
			} else if (this.references[index].exists()) {
				return Collections.singletonList(this.references[index]);
			}
		}
		throw new GenericEvitaInternalError(
			"Reference " + referenceKey + " for entity `" + this.entityPrimaryKey + "` was not found!"
		);
	}

	/**
	 * Finds all dropped references associated with the given reference key.
	 *
	 * This method is specifically used to retrieve references that are marked as "dropped"
	 * for the provided generic reference key. If no dropped references are present
	 * or if the reference key is not associated with this entity, an empty list is returned.
	 *
	 * @param referenceKey the unique key identifying the reference(s) to search for;
	 *                     it must be a generic reference key.
	 * @return a list of dropped references corresponding to the provided reference key;
	 *         if no dropped references exist, an empty list is returned.
	 * @throws IllegalArgumentException if the provided reference key is not a generic reference key
	 *                                  or if it does not exist within the context of this entity.
	 */
	@Nonnull
	public List<ReferenceContract> findAllReferences(
		@Nonnull ReferenceKey referenceKey,
		@Nonnull Predicate<ReferenceContract> filter
	) {
		Assert.isPremiseValid(
			referenceKey.isUnknownReference(),
			() -> "This method makes sense only with generic reference key!"
		);
		final InsertionPosition startPosition = findPositionInGeneralManner(referenceKey);
		if (startPosition.alreadyPresent()) {
			int index = startPosition.position();
			Assert.isPremiseValid(
				index >= 0,
				() -> "Reference " + referenceKey + " for entity `" + this.entityPrimaryKey + "` was not found!"
			);
			if (index + 1 < this.references.length &&
				this.references[index + 1].getReferenceKey().equals(referenceKey)) {
				final List<ReferenceContract> references = new ArrayList<>(Math.min(8, this.references.length - index));
				while (
					index < this.references.length &&
						this.references[index].getReferenceKey().equals(referenceKey)) {
					final Reference reference = this.references[index++];
					if (filter.test(reference)) {
						references.add(reference);
					}
				}
				return references;
			} else if (filter.test(this.references[index])) {
				return Collections.singletonList(this.references[index]);
			} else {
				return Collections.emptyList();
			}
		} else {
			return Collections.emptyList();
		}
	}

	/**
	 * Finds reference to target entity specified by `referenceKey` in current container or returns empty result.
	 *
	 * @return found reference or empty result
	 */
	@Nonnull
	public Optional<ReferenceContract> findReference(@Nonnull ReferenceKey referenceKey) {
		final int index = findReferenceIndex(referenceKey);
		if (index < 0 || !this.references[index].exists()) {
			return Optional.empty();
		} else {
			Assert.isPremiseValid(
				index + 1 == this.references.length ||
					!this.references[index + 1].getReferenceKey().equals(referenceKey),
				() -> "There is more than one reference " + referenceKey + " for entity `" + this.entityPrimaryKey + "`!"
			);
			return Optional.of(this.references[index]);
		}
	}

	/**
	 * Finds a reference within the storage part that matches the given `referenceSchema`, `referenceKey`,
	 * and the required `representativeAttributeValues`. If no matching reference is found, an exception is thrown.
	 *
	 * @param referenceSchema the schema defining the structure and attributes of the reference; must not be null
	 * @param referenceKey the key identifying the target reference; must not be null
	 * @param requiredRepresentativeAttributeValues an array of values that must match the representative attributes of the reference; must not be null
	 * @return the located {@link ReferenceContract} that matches the specified criteria, or null if no matching reference is found
	 * @throws GenericEvitaInternalError if no matching reference is found
	 */
	@Nonnull
	public ReferenceContract findReferenceOrThrowException(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull Serializable[] requiredRepresentativeAttributeValues
	) {
		return findReference(
			referenceSchema, referenceKey, requiredRepresentativeAttributeValues
		).orElseThrow(
			() -> new GenericEvitaInternalError(
				"Reference " + referenceKey + " for entity `" + this.entityPrimaryKey + "` was not found!"
			)
		);
	}

	/**
	 * Finds a reference within the storage part that matches the given `referenceSchema`, `genericReferenceKey`,
	 * and the required `representativeAttributeValues`. If no matching reference is found, an exception is thrown.
	 *
	 * @param referenceSchema the schema defining the structure and attributes of the reference; must not be null
	 * @param genericReferenceKey the key identifying the target reference; must not be null
	 * @param requiredRepresentativeAttributeValues an array of values that must match the representative attributes of the reference; must not be null
	 * @return the located {@link ReferenceContract} that matches the specified criteria, or null if no matching reference is found
	 * @throws GenericEvitaInternalError if no matching reference is found
	 */
	@Nonnull
	public Optional<ReferenceContract> findReference(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull ReferenceKey genericReferenceKey,
		@Nonnull Serializable[] requiredRepresentativeAttributeValues
	) {
		final InsertionPosition position = findPositionInGeneralManner(genericReferenceKey);
		if (position.alreadyPresent()) {
			final RepresentativeAttributeDefinition rad = referenceSchema.getRepresentativeAttributeDefinition();
			int index = position.position();
			ReferenceContract reference = this.references[index];
			do {
				if (reference.exists()) {
					final Serializable[] representativeValues = rad.getRepresentativeValues(reference);
					if (Arrays.equals(representativeValues, requiredRepresentativeAttributeValues)) {
						return Optional.of(reference);
					}
				}
				index++;
				if (index < this.references.length) {
					reference = this.references[index];
				} else {
					break;
				}
			} while (reference.getReferenceKey().equals(genericReferenceKey));
		}
		return Optional.empty();
	}

	/**
	 * Replaces references in the existing collection of references based on the provided reference schema,
	 * reference key, representative attribute values function, and reference modifier function.
	 *
	 * @param referenceSchema               the schema defining how references are structured and the rules for representative
	 *                                      attributes, must not be null
	 * @param genericReferenceKey           the key used to identify a group of references to be processed, must not be null
	 * @param representativeAttributeValues a function that determines the position or index to resolve based
	 *                                      on the representative attribute values, must not be null
	 * @param referenceModifier             a function that modifies the reference based on a specific index and the existing
	 *                                      reference, must not be null
	 * @return the number of references that were replaced
	 */
	public int replaceReferences(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull ReferenceKey genericReferenceKey,
		@Nonnull ToIntFunction<Serializable[]> representativeAttributeValues,
		@Nonnull IntObjBiFunction<Reference, Reference> referenceModifier
	) {
		int replaced = 0;
		final InsertionPosition position = findPositionInGeneralManner(genericReferenceKey);
		if (position.alreadyPresent()) {
			final RepresentativeAttributeDefinition rad = referenceSchema.getRepresentativeAttributeDefinition();
			int index = position.position();
			Reference reference = this.references[index];
			do {
				if (reference.exists()) {
					final Serializable[] representativeValues = rad.getRepresentativeValues(reference);
					final int resolvedIndex = representativeAttributeValues.applyAsInt(representativeValues);
					if (resolvedIndex >= 0) {
						this.references[index] = referenceModifier.apply(resolvedIndex, reference);
						this.dirty = true;
						replaced++;
					}
				}
				index++;
				if (index < this.references.length) {
					reference = this.references[index];
				} else {
					break;
				}
			} while (reference.getReferenceKey().equals(genericReferenceKey));
		}

		if (replaced > 0) {
			// after modifications, we need to ensure the references are still sorted
			Arrays.sort(this.references, ReferenceContract.FULL_COMPARATOR);
		}

		return replaced;
	}

	/**
	 * Finds the index of a given {@link ReferenceKey} in the references array. The method determines the index
	 * based on various conditions, including whether the reference is a known internal primary key,
	 * a new reference, or an unknown reference. If the reference is not found, the method may return -1.
	 *
	 * @param referenceKey the reference key to find in the references array
	 * @return the index of the reference key in the references array, or -1 if the reference is not found
	 */
	private int findReferenceIndex(@Nonnull ReferenceKey referenceKey) {
		final int index;
		if (referenceKey.isKnownInternalPrimaryKey()) {
			index = ArrayUtils.binarySearch(this.references, referenceKey, FULL_COMPARISON_FUNCTION);
		} else if (referenceKey.isNewReference()) {
			final int exactIndex = ArrayUtils.binarySearch(this.references, referenceKey, FULL_COMPARISON_FUNCTION);
			if (exactIndex >= 0) {
				index = exactIndex;
			} else {
				// try to find nonsingle reference matching generic part of the key
				final InsertionPosition position = findPositionInGeneralManner(referenceKey);
				/* TOBEDONE #538 - due to backward compatibility with 2025.6, we may simplify later */
				index = position.alreadyPresent() &&
					this.references[position.position()]
						.getReferenceKey()
						.isUnknownReference() ?
					position.position() : -1;
			}
		} else {
			final InsertionPosition position = findPositionInGeneralManner(referenceKey);
			assertNoConflictingReferencePresent(referenceKey, position);
			// we can accept this position, only if an internal primary key is not known
			index = position.alreadyPresent() ? position.position() : -1;
		}
		return index;
	}

	/**
	 * Finds the position of the provided {@link ReferenceKey} in the references array in a general manner.
	 * If the reference is already present in the array, the method adjusts the position to the first occurrence
	 * of the same generic key. Otherwise, it determines where the reference should be inserted while maintaining
	 * order.
	 *
	 * @param referenceKey The reference key to locate or determine the insertion position for; must not be null.
	 * @return The {@link InsertionPosition} object that contains the calculated position and whether the reference
	 *         key was already present in the array.
	 */
	@Nonnull
	private InsertionPosition findPositionInGeneralManner(@Nonnull ReferenceKey referenceKey) {
		final InsertionPosition position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			referenceKey, this.references, GENERIC_COMPARISON_FUNCTION
		);
		if (position.alreadyPresent()) {
			int index = position.position();
			while (index > 0 && this.references[index - 1].getReferenceKey().equalsInGeneral(this.references[index].getReferenceKey())) {
				// move to the first occurrence of the same generic key
				index--;
			}
			return new InsertionPosition(index, true);
		} else {
			return position;
		}
	}

	/**
	 * Checks if the provided {@link ReferenceKey} is present in the references.
	 *
	 * @param referenceKey the key to be checked for presence in the references
	 * @return true if the reference key exists in the references, false otherwise
	 */
	public boolean contains(@Nonnull ReferenceKey referenceKey) {
		if (referenceKey.isKnownInternalPrimaryKey()) {
			return ArrayUtils.binarySearch(this.references, referenceKey, FULL_COMPARISON_FUNCTION) >= 0;
		} else if (referenceKey.isNewReference()) {
			final int exactIndex = ArrayUtils.binarySearch(this.references, referenceKey, FULL_COMPARISON_FUNCTION);
			if (exactIndex >= 0) {
				return true;
			} else {
				// try to find nonsingle reference matching generic part of the key
				final InsertionPosition position = findPositionInGeneralManner(referenceKey);
				/* TOBEDONE #538 - due to backward compatibility with 2025.6, we may simplify later */
				return position.alreadyPresent() &&
					this.references[position.position()].getReferenceKey().isUnknownReference();
			}
		} else {
			final InsertionPosition position = findPositionInGeneralManner(referenceKey);
			assertNoConflictingReferencePresent(referenceKey, position);
			return position.alreadyPresent();
		}
	}

	/**
	 * Ensures that no conflicting reference exists at the provided insertion position for the given reference key.
	 * A conflict is identified if:
	 * - The reference already exists in the specified position.
	 * - The provided position is not the last in the array.
	 * - The next reference at the position matches the same reference key as the one being added.
	 *
	 * Throws an exception if a conflict is detected, indicating that references must be unique.
	 *
	 * @param referenceKey      the key of the reference to be checked for uniqueness
	 * @param insertionPosition the position in the references array to check for conflicts
	 */
	private void assertNoConflictingReferencePresent(
		@Nonnull ReferenceKey referenceKey,
		@Nonnull InsertionPosition insertionPosition
	) {
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
