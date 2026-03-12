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

package io.evitadb.spi.store.catalog.persistence.storageParts.entity;

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
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
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
import java.util.function.Supplier;
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
	 * See {@link Entity#getReferences()}. References are sorted in ascending order according to
	 * {@link EntityReference} comparator.
	 */
	private Reference[] references = EMPTY_REFERENCES;
	/**
	 * Contains copy of the {@link #references} but only if there are any modifications in them.
	 */
	private Reference[] modifiedReferences;
	/**
	 * Contains true if anything changed in this container.
	 */
	@Getter private boolean dirty;
	/**
	 * Contains true if some of the references in this container have unassigned internal primary keys
	 * (negative or zero values).
	 */
	private boolean unassignedPrimaryKeys = false;
	/**
	 * Contains set of all reference keys that contains "known" internal id, which was not found in the current
	 * reference set and needs to be treated as unknown and reassigned. Set is initialized only if such reference
	 * is found.
	 */
	@Nullable private Set<ComparableReferenceKey> referenceKeysForReassignment = null;

	/**
	 * Finds the position of the provided {@link ReferenceKey} in the references array in a general manner.
	 * If the reference is already present in the array, the method adjusts the position to the first occurrence
	 * of the same generic key. Otherwise, it determines where the reference should be inserted while maintaining
	 * order.
	 *
	 * @param references   The array of references to search within; must not be null.
	 * @param referenceKey The reference key to locate or determine the insertion position for; must not be null.
	 * @return The {@link InsertionPosition} object that contains the calculated position and whether the reference
	 * key was already present in the array.
	 */
	@Nonnull
	private static InsertionPosition findPositionInGeneralManner(
		@Nonnull ReferenceContract[] references,
		@Nonnull ReferenceKey referenceKey
	) {
		final InsertionPosition position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			referenceKey, references, GENERIC_COMPARISON_FUNCTION
		);
		if (position.alreadyPresent()) {
			int index = position.position();
			while (index > 0 && references[index - 1].getReferenceKey().equalsInGeneral(
				references[index].getReferenceKey())) {
				// move to the first occurrence of the same generic key
				index--;
			}
			return new InsertionPosition(index, true);
		} else {
			return position;
		}
	}

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
		final Reference[] theReferences = getReferences();
		for (final Reference reference : theReferences) {
			if (reference.exists()) {
				return false;
			}
		}
		return true;
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
			final Reference[] theReferences = getReferencesForUpdate();
			final Set<ComparableReferenceKey> refKeysForReassignment = this.referenceKeysForReassignment == null ?
				Collections.emptySet() : this.referenceKeysForReassignment;
			final int lupkBefore = this.lastUsedPrimaryKey;
			Map<ComparableReferenceKey, ReferenceKey> assignedKeys = null;
			ReferenceKey previousReferenceKey = null;
			for (int i = 0; i < theReferences.length; i++) {
				Reference reference = theReferences[i];
				if (previousReferenceKey != null) {
					Assert.isPremiseValid(
						ReferenceKey.FULL_COMPARATOR.compare(previousReferenceKey, reference.getReferenceKey()) < 0,
						() -> "References must be sorted in ascending order according to their business key: "
							+ Arrays.stream(theReferences)
							.map(Reference::getReferenceKey)
							.map(String::valueOf)
							.collect(Collectors.joining(", "))
					);
				}
				// remember the previous key for the next iteration
				previousReferenceKey = reference.getReferenceKey();
				// assign primary keys to references that don't have it yet (update the key)
				if (
					!reference.getReferenceKey().isKnownInternalPrimaryKey()
						|| refKeysForReassignment.contains(new ComparableReferenceKey(reference.getReferenceKey()))
				) {
					reference = new Reference(++this.lastUsedPrimaryKey, reference);
					theReferences[i] = reference;
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
				Arrays.sort(theReferences, ReferenceContract.FULL_COMPARATOR);
			}

			this.unassignedPrimaryKeys = false;
			this.referenceKeysForReassignment = null;
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
		@Nonnull UnaryOperator<ReferenceContract> mutator,
		@Nonnull Supplier<MissingReferenceBehavior> missingReferenceBehavior
	) {
		final Reference[] theReferences = getReferencesForUpdate();
		InsertionPosition insertionPosition;
		if (referenceKey.isUnknownReference()) {
			insertionPosition = findPositionInGeneralManner(theReferences, referenceKey);
			// we are adding a new reference-verify that we are not duplicating the existing reference
			assertNoConflictingReferencePresent(referenceKey, insertionPosition);
		} else {
			insertionPosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
				referenceKey, theReferences, FULL_COMPARISON_FUNCTION
			);
			if (insertionPosition.alreadyPresent()) {
				// adapt to the already assigned primary key, if our counter is lower
				if (referenceKey.internalPrimaryKey() > this.lastUsedPrimaryKey) {
					this.lastUsedPrimaryKey = referenceKey.internalPrimaryKey();
				}
			} else if (referenceKey.isNewReference()) {
				// try to find nonsingle reference matching generic part of the key
				final InsertionPosition genericSearchResult = findPositionInGeneralManner(theReferences, referenceKey);
				// but verify there is no conflicting reference present
				assertNoConflictingReferencePresent(referenceKey, insertionPosition);
				// but we can accept this position, only if an internal primary key is not known
				/* TOBEDONE #538 - due to backward compatibility with 2025.6, we may simplify later */
				if (
					genericSearchResult.alreadyPresent() &&
						theReferences[genericSearchResult.position()].getReferenceKey().isUnknownReference()
				) {
					insertionPosition = genericSearchResult;
				}
			}
		}

		// insert or replace reference on computed position
		final int position = insertionPosition.position();
		final Reference mutatedReference;
		if (insertionPosition.alreadyPresent()) {
			mutatedReference = (Reference) mutator.apply(theReferences[position]);
			final Reference originalReference = theReferences[position];
			if (originalReference.differsFrom(mutatedReference)) {
				theReferences[position] = mutatedReference;
				Assert.isPremiseValid(
					originalReference.getReferenceKey().equals(mutatedReference.getReferenceKey()) &&
						originalReference.getReferenceKey().internalPrimaryKey()
							== mutatedReference.getReferenceKey().internalPrimaryKey(),
					() -> "Mutation must not remove the internal primary key from the existing reference!"
				);
				this.dirty = true;
			}
		} else {
			mutatedReference = (Reference) mutator.apply(null);
			this.modifiedReferences = ArrayUtils.insertRecordIntoArrayOnIndex(
				mutatedReference, theReferences, position
			);
			this.dirty = true;
			this.unassignedPrimaryKeys = true;
			if (missingReferenceBehavior.get() == MissingReferenceBehavior.GENERATE_NEW_INTERNAL_KEY) {
				if (this.referenceKeysForReassignment == null) {
					this.referenceKeysForReassignment = new HashSet<>(16);
				}
				this.referenceKeysForReassignment.add(new ComparableReferenceKey(mutatedReference.getReferenceKey()));
			}
		}

		return mutatedReference;
	}

	/**
	 * Retrieves the references to be used for reading. If modified references are available,
	 * they will be returned; otherwise, the default references will be provided.
	 *
	 * @return an array of references to be used for reading, either the modified references
	 * or the default references if the modified references are null.
	 */
	@Nonnull
	public Reference[] getReferences() {
		return this.modifiedReferences == null ? this.references : this.modifiedReferences;
	}

	/**
	 * Returns reference array as collection so it can be easily used in {@link Entity}.
	 */
	@Nonnull
	public Collection<ReferenceContract> getReferencesAsCollection() {
		return Arrays.asList(getReferences());
	}

	/**
	 * Returns array of primary keys of all referenced entities of particular `referenceName`.
	 */
	@Nonnull
	public int[] getReferencedIds(@Nonnull String referenceName) {
		final ReferenceRange range = findReferenceRange(referenceName);
		if (range == null) {
			return ArrayUtils.EMPTY_INT_ARRAY;
		}

		final int[] refIds = new int[range.maxSize()];
		int index = 0;
		for (int i = range.start(); i < range.refs().length && i < range.end(); i++) {
			final Reference ref = range.refs()[i];
			if (referenceName.equals(ref.getReferenceName())) {
				if (ref.exists()) {
					refIds[index++] = ref.getReferenceKey().primaryKey();
				}
			} else {
				break;
			}
		}

		return refIds.length == index ? refIds : Arrays.copyOf(refIds, index);
	}

	/**
	 * Returns array of distinct primary keys of all referenced entities of particular `referenceName`.
	 */
	@Nonnull
	public int[] getDistinctReferencedIds(@Nonnull String referenceName) {
		final ReferenceRange range = findReferenceRange(referenceName);
		if (range == null) {
			return ArrayUtils.EMPTY_INT_ARRAY;
		}

		final int[] refIds = new int[range.maxSize()];
		int index = 0;
		for (int i = range.start(); i < range.refs().length && i < range.end(); i++) {
			final Reference ref = range.refs()[i];
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
		final ReferenceRange range = findReferenceRange(referenceName);
		if (range == null) {
			return ArrayUtils.EMPTY_INT_ARRAY;
		}

		final int[] refIds = new int[range.maxSize()];
		int index = 0;
		for (int i = range.start(); i < range.refs().length && i < range.end(); i++) {
			final Reference ref = range.refs()[i];
			if (referenceName.equals(ref.getReferenceName())) {
				if (ref.exists() && ref.getGroup().isPresent()) {
					final int groupId = ref.getGroup().get().getPrimaryKeyOrThrowException();
					final InsertionPosition position = ArrayUtils.computeInsertPositionOfIntInOrderedArray(
						groupId, refIds, 0, index);
					if (!position.alreadyPresent()) {
						System.arraycopy(
							refIds, position.position(), refIds, position.position() + 1,
							index - position.position()
						);
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
	 * Computes the start and end positions of references matching the given `referenceName`
	 * within the sorted references array using binary search boundaries.
	 *
	 * @param referenceName the name of the reference to search for
	 * @return a {@link ReferenceRange} containing the references array and computed boundaries,
	 *         or null if the references array is empty
	 */
	@Nullable
	private ReferenceRange findReferenceRange(@Nonnull String referenceName) {
		final Reference[] refs = getReferences();
		if (refs.length == 0) {
			return null;
		}

		final int startPosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			new ReferenceKey(referenceName, Integer.MIN_VALUE, Integer.MIN_VALUE),
			refs, FULL_COMPARISON_FUNCTION
		).position();
		final int endPosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			new ReferenceKey(referenceName, Integer.MAX_VALUE, Integer.MAX_VALUE),
			refs, FULL_COMPARISON_FUNCTION
		).position();

		return new ReferenceRange(refs, startPosition, endPosition);
	}

	/**
	 * Holds the result of a binary-search boundary computation over the sorted references array
	 * for a particular reference name.
	 *
	 * @param refs  the references array that was searched
	 * @param start the start index (inclusive) of references matching the name
	 * @param end   the end index (exclusive) of references matching the name
	 */
	private record ReferenceRange(
		@Nonnull Reference[] refs,
		int start,
		int end
	) {
		/**
		 * Returns the maximum possible number of references in the range.
		 */
		int maxSize() {
			return Math.max(this.end - this.start, 0);
		}
	}

	/**
	 * Returns true if passed locale is found among localized attributes of any reference held in this storage part.
	 */
	public boolean isLocalePresent(@Nonnull Locale locale) {
		final Reference[] theReferences = getReferences();
		for (Reference reference : theReferences) {
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
		final Reference[] theReferences = getReferences();
		final int index = findReferenceIndex(referenceKey);
		Assert.isPremiseValid(
			index >= 0,
			() -> "Reference " + referenceKey + " for entity `" + this.entityPrimaryKey + "` was not found!"
		);
		final ReferenceContract reference = theReferences[index];
		Assert.isPremiseValid(
			reference.exists(),
			() -> "Reference " + referenceKey + " for entity `" + this.entityPrimaryKey + "` was not found!"
		);
		Assert.isPremiseValid(
			index + 1 == theReferences.length ||
				ReferenceKey.FULL_COMPARATOR.compare(theReferences[index + 1].getReferenceKey(), referenceKey) != 0,
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
		final Reference[] theReferences = getReferences();
		final InsertionPosition startPosition = findPositionInGeneralManner(theReferences, referenceKey);
		if (startPosition.alreadyPresent()) {
			int index = startPosition.position();
			Assert.isPremiseValid(
				index >= 0,
				() -> "Reference " + referenceKey + " for entity `" + this.entityPrimaryKey + "` was not found!"
			);
			if (index + 1 < theReferences.length &&
				theReferences[index + 1].getReferenceKey().equals(referenceKey)) {
				final List<ReferenceContract> references = new ArrayList<>(Math.min(8, theReferences.length - index));
				while (
					index < theReferences.length &&
						theReferences[index].getReferenceKey().equals(referenceKey)) {
					final Reference reference = theReferences[index++];
					if (reference.exists()) {
						references.add(reference);
					}
				}
				return references;
			} else if (theReferences[index].exists()) {
				return Collections.singletonList(theReferences[index]);
			}
		}
		throw new GenericEvitaInternalError(
			"Reference " + referenceKey + " for entity `" + this.entityPrimaryKey + "` was not found!"
		);
	}

	/**
	 * Finds all references matching the given generic reference key and filter predicate.
	 *
	 * This method searches for references associated with the provided generic reference key
	 * and returns only those that satisfy the given filter predicate. If no matching references
	 * are found or if the reference key is not associated with this entity, an empty list
	 * is returned.
	 *
	 * @param referenceKey the unique key identifying the reference(s) to search for;
	 *                     it must be a generic reference key
	 * @param filter       predicate to apply to each found reference
	 * @return a list of references matching the key and filter; if none match, an empty
	 * list is returned
	 * @throws IllegalArgumentException if the provided reference key is not a generic
	 *                                  reference key
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
		final Reference[] theReferences = getReferences();
		final InsertionPosition startPosition = findPositionInGeneralManner(theReferences, referenceKey);
		if (startPosition.alreadyPresent()) {
			int index = startPosition.position();
			Assert.isPremiseValid(
				index >= 0,
				() -> "Reference " + referenceKey + " for entity `" + this.entityPrimaryKey + "` was not found!"
			);
			if (index + 1 < theReferences.length &&
				theReferences[index + 1].getReferenceKey().equals(referenceKey)) {
				final List<ReferenceContract> references = new ArrayList<>(Math.min(8, theReferences.length - index));
				while (
					index < theReferences.length &&
						theReferences[index].getReferenceKey().equals(referenceKey)) {
					final Reference reference = theReferences[index++];
					if (filter.test(reference)) {
						references.add(reference);
					}
				}
				return references;
			} else if (filter.test(theReferences[index])) {
				return Collections.singletonList(theReferences[index]);
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
		final Reference[] theReferences = getReferences();
		if (index < 0 || !theReferences[index].exists()) {
			return Optional.empty();
		} else if (referenceKey.isUnknownReference()) {
			Assert.isPremiseValid(
				index + 1 == theReferences.length ||
					!theReferences[index + 1].getReferenceKey().equals(referenceKey),
				() -> "There is more than one reference " + referenceKey + " for entity `" + this.entityPrimaryKey + "`!"
			);
			return Optional.of(theReferences[index]);
		} else {
			return Optional.of(theReferences[index]);
		}
	}

	/**
	 * Finds a reference within the storage part that matches the given `referenceSchema`, `referenceKey`,
	 * and the required `representativeAttributeValues`. If no matching reference is found, an exception
	 * is thrown.
	 *
	 * @param referenceSchema                       the schema defining the structure and attributes of
	 *                                              the reference; must not be null
	 * @param referenceKey                          the key identifying the target reference; must not
	 *                                              be null
	 * @param requiredRepresentativeAttributeValues an array of values that must match the representative
	 *                                              attributes of the reference; must not be null
	 * @return the located {@link ReferenceContract} that matches the specified criteria
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
	 * Finds a reference within the storage part that matches the given `referenceSchema`,
	 * `genericReferenceKey`, and the required `representativeAttributeValues`. If no matching
	 * reference is found, an empty optional is returned.
	 *
	 * @param referenceSchema                       the schema defining the structure and attributes of
	 *                                              the reference; must not be null
	 * @param genericReferenceKey                   the key identifying the target reference; must not
	 *                                              be null
	 * @param requiredRepresentativeAttributeValues an array of values that must match the representative
	 *                                              attributes of the reference; must not be null
	 * @return the located {@link ReferenceContract} that matches the specified criteria, or empty
	 * optional if no matching reference is found
	 */
	@Nonnull
	public Optional<ReferenceContract> findReference(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull ReferenceKey genericReferenceKey,
		@Nonnull Serializable[] requiredRepresentativeAttributeValues
	) {
		final Reference[] theReferences = getReferences();
		final InsertionPosition position = findPositionInGeneralManner(theReferences, genericReferenceKey);
		if (position.alreadyPresent()) {
			final RepresentativeAttributeDefinition rad = referenceSchema.getRepresentativeAttributeDefinition();
			int index = position.position();
			ReferenceContract reference = theReferences[index];
			do {
				if (reference.exists()) {
					final Serializable[] representativeValues = rad.getRepresentativeValues(reference);
					if (Arrays.equals(representativeValues, requiredRepresentativeAttributeValues)) {
						return Optional.of(reference);
					}
				}
				index++;
				if (index < theReferences.length) {
					reference = theReferences[index];
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
	 * @param referenceSchema               the schema defining how references are structured and the
	 *                                      rules for representative attributes, must not be null
	 * @param genericReferenceKey           the key used to identify a group of references to be
	 *                                      processed, must not be null
	 * @param representativeAttributeValues a function that determines the position or index to resolve
	 *                                      based on the representative attribute values, must not
	 *                                      be null
	 * @param referenceModifier             a function that modifies the reference based on a specific
	 *                                      index and the existing reference, must not be null
	 * @return the number of references that were replaced
	 */
	public int replaceReferences(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull ReferenceKey genericReferenceKey,
		@Nonnull ToIntFunction<Serializable[]> representativeAttributeValues,
		@Nonnull IntObjBiFunction<Reference, Reference> referenceModifier
	) {
		final Reference[] theReferences = getReferencesForUpdate();
		int replaced = 0;
		final InsertionPosition position = findPositionInGeneralManner(theReferences, genericReferenceKey);
		if (position.alreadyPresent()) {
			final RepresentativeAttributeDefinition rad = referenceSchema.getRepresentativeAttributeDefinition();
			int index = position.position();
			Reference reference = theReferences[index];
			do {
				if (reference.exists()) {
					final Serializable[] representativeValues = rad.getRepresentativeValues(reference);
					final int resolvedIndex = representativeAttributeValues.applyAsInt(representativeValues);
					if (resolvedIndex >= 0) {
						theReferences[index] = referenceModifier.apply(resolvedIndex, reference);
						this.dirty = true;
						replaced++;
					}
				}
				index++;
				if (index < theReferences.length) {
					reference = theReferences[index];
				} else {
					break;
				}
			} while (reference.getReferenceKey().equals(genericReferenceKey));
		}

		if (replaced > 0) {
			// after modifications, we need to ensure the references are still sorted
			Arrays.sort(theReferences, ReferenceContract.FULL_COMPARATOR);
		}

		return replaced;
	}

	/**
	 * Checks if the provided {@link ReferenceKey} is present in the references.
	 *
	 * @param referenceKey the key to be checked for presence in the references
	 * @return true if the reference key exists in the references, false otherwise
	 */
	public boolean contains(@Nonnull ReferenceKey referenceKey) {
		final ReferenceContract[] theReferences = getReferences();
		if (referenceKey.isKnownInternalPrimaryKey()) {
			return ArrayUtils.binarySearch(theReferences, referenceKey, FULL_COMPARISON_FUNCTION) >= 0;
		} else if (referenceKey.isNewReference()) {
			final int exactIndex = ArrayUtils.binarySearch(theReferences, referenceKey, FULL_COMPARISON_FUNCTION);
			if (exactIndex >= 0) {
				return true;
			} else {
				// try to find nonsingle reference matching generic part of the key
				final InsertionPosition position = findPositionInGeneralManner(theReferences, referenceKey);
				/* TOBEDONE #538 - due to backward compatibility with 2025.6, we may simplify later */
				return position.alreadyPresent() &&
					theReferences[position.position()].getReferenceKey().isUnknownReference();
			}
		} else {
			final InsertionPosition position = findPositionInGeneralManner(theReferences, referenceKey);
			assertNoConflictingReferencePresent(referenceKey, position);
			return position.alreadyPresent();
		}
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
		final Reference[] theReferences = getReferences();
		if (referenceKey.isKnownInternalPrimaryKey()) {
			index = ArrayUtils.binarySearch(theReferences, referenceKey, FULL_COMPARISON_FUNCTION);
		} else if (referenceKey.isNewReference()) {
			final int exactIndex = ArrayUtils.binarySearch(theReferences, referenceKey, FULL_COMPARISON_FUNCTION);
			if (exactIndex >= 0) {
				index = exactIndex;
			} else {
				// try to find nonsingle reference matching generic part of the key
				final InsertionPosition position = findPositionInGeneralManner(theReferences, referenceKey);
				/* TOBEDONE #538 - due to backward compatibility with 2025.6, we may simplify later */
				index = position.alreadyPresent() &&
					theReferences[position.position()]
						.getReferenceKey()
						.isUnknownReference() ?
					position.position() : -1;
			}
		} else {
			final InsertionPosition position = findPositionInGeneralManner(theReferences, referenceKey);
			assertNoConflictingReferencePresent(referenceKey, position);
			// we can accept this position, only if an internal primary key is not known
			index = position.alreadyPresent() ? position.position() : -1;
		}
		return index;
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
		final ReferenceContract[] theReferences = getReferences();
		Assert.isPremiseValid(
			// either there is no such reference yet
			!insertionPosition.alreadyPresent() ||
				// or the found position is the last one in array
				insertionPosition.position() + 1 == theReferences.length ||
				// or the next reference is different than the one we are adding
				!theReferences[insertionPosition.position() + 1].getReferenceKey().equals(referenceKey),
			() -> "There is already existing reference with key " + referenceKey +
				" in entity " + this.entityPrimaryKey + "! References must be unique!"
		);
	}

	/**
	 * Retrieves an array of references that may be updated or modified.
	 * If the references have not been previously modified, a copy of the original references
	 * is created and stored for potential updates.
	 *
	 * @return an array of references that can be safely updated.
	 */
	@Nonnull
	private Reference[] getReferencesForUpdate() {
		if (this.modifiedReferences == null) {
			this.modifiedReferences = Arrays.copyOf(this.references, this.references.length);
		}
		return this.modifiedReferences;
	}

	/**
	 * Defines behavior when a reference with assigned (known) internal primary key is missing in the storage part.
	 */
	public enum MissingReferenceBehavior {

		/**
		 * Existing internal primary key will be accepted as is.
		 */
		ACCEPT_INTERNAL_KEY,
		/**
		 * New internal primary key will be generated for the reference.
		 */
		GENERATE_NEW_INTERNAL_KEY

	}

}
