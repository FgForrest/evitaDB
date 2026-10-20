/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.index.mutation.local.dataAccess;


import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * This implementation of attribute accessor looks up for attribute in {@link ReferencesStoragePart}.
 */
@NotThreadSafe
class ReferenceEntityStoragePartAccessorAttributeValueByRepresentativeReferenceKeySupplier implements ExistingAttributeValueSupplier {
	private final WritableEntityStorageContainerAccessor containerAccessor;
	private final ReferenceSchema referenceSchema;
	private final RepresentativeReferenceKey storedReferenceKey;
	private final RepresentativeReferenceKey currentReferenceKey;
	private final String entityType;
	private final int entityPrimaryKey;
	private final MemoizedLocalesObsoleteChecker memoizedLocalesObsoleteChecker;
	private Set<Locale> memoizedOriginalLocales;
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	private Optional<ReferenceContract> memoizedReference;
	private int memoizedReferenceIndex = -1;

	public ReferenceEntityStoragePartAccessorAttributeValueByRepresentativeReferenceKeySupplier(
		@Nonnull WritableEntityStorageContainerAccessor containerAccessor,
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull RepresentativeReferenceKey storedReferenceKey,
		@Nonnull RepresentativeReferenceKey currentReferenceKey,
		@Nonnull String entityType,
		int entityPrimaryKey
	) {
		this.containerAccessor = containerAccessor;
		this.referenceSchema = referenceSchema;
		this.storedReferenceKey = storedReferenceKey;
		this.currentReferenceKey = currentReferenceKey;
		this.entityType = entityType;
		this.entityPrimaryKey = entityPrimaryKey;
		this.memoizedLocalesObsoleteChecker = new MemoizedLocalesObsoleteChecker(containerAccessor);
	}

	@Nonnull
	@Override
	public Set<Locale> getEntityExistingAttributeLocales() {
		memoizeLocales();
		return this.memoizedOriginalLocales;
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey) {
		return getMemoizedReference()
			.filter(Droppable::exists)
			.flatMap(it -> it.getAttributeValue(attributeKey))
			.filter(Droppable::exists);
	}

	@Nonnull
	@Override
	public Stream<AttributeValue> getAttributeValues() {
		return getMemoizedReference()
			.filter(Droppable::exists)
			.map(ReferenceContract::getAttributeValues)
			.stream()
			.flatMap(Collection::stream)
			.filter(Droppable::exists);
	}

	@Nonnull
	@Override
	public Stream<AttributeValue> getAttributeValues(@Nonnull Locale locale) {
		return getMemoizedReference()
			.filter(Droppable::exists)
			.map(ReferenceContract::getAttributeValues)
			.stream()
			.flatMap(Collection::stream)
			.filter(Droppable::exists)
			.filter(it -> Objects.equals(it.key().locale(), locale));
	}

	/**
	 * Memoizes the locales associated with an entity if the current memoized locales are detected to be obsolete.
	 * The method first checks for obsolescence of locales using the provided checker. If locales are obsolete,
	 * it retrieves the entity storage part to access the attribute locales. These locales are then used to
	 * generate new memoized locales before and after applying changes. The newly calculated locales are stored
	 * for future use to ensure efficient retrieval without redundant recalculation.
	 */
	private void memoizeLocales() {
		if (this.memoizedLocalesObsoleteChecker.isLocalesObsolete()) {
			final Set<Locale> storagePart = this.containerAccessor.getEntityStoragePart(
				this.entityType, this.entityPrimaryKey, EntityExistence.MUST_EXIST
			).getAttributeLocales();
			this.memoizedOriginalLocales = this.memoizedLocalesObsoleteChecker.produceNewMemoizedLocalesBeforeChangesAreApplied(storagePart);
		}
	}

	/**
	 * Retrieves a memoized reference if it exists and is still valid, otherwise updates the memoized reference
	 * by searching through the current references.
	 *
	 * The method ensures that the memoized reference is up-to-date by checking its validity against the current
	 * references. If the memoized reference is deemed obsolete or null, it searches for the reference
	 * within the available references and memoizes it - the first reference in the array matching the current or the
	 * stored representative reference key.
	 *
	 * The references are kept sorted by their name and the primary key of the referenced entity (see
	 * {@link ReferencesStoragePart}), so the references a target key can match form one contiguous run: the run is
	 * found by a binary search and only the references within it - the duplicates of one target - are matched by their
	 * representative values. The search costs `log2(n)` key comparisons rather than a scan of all `n` references of
	 * the entity, which matters when a partition is populated from another owner holding hundreds of references.
	 *
	 * @return an {@link Optional} containing the memoized {@link ReferenceContract} if found, otherwise an empty {@link Optional}.
	 */
	@Nonnull
	private Optional<ReferenceContract> getMemoizedReference() {
		final ReferencesStoragePart referencesStorageContainer = this.containerAccessor.getReferencesStoragePart(this.entityType, this.entityPrimaryKey);
		final ReferenceContract[] references = referencesStorageContainer.getReferences();
		// we need to check the memoized instance is still the same, when the reference or its contents are modified
		// the entire reference is replaced, so we need to retrieve it again
		if (isReferenceNullOrObsolete(references)) {
			final ReferenceKey currentTargetKey = this.currentReferenceKey.referenceKey();
			final ReferenceKey storedTargetKey = this.storedReferenceKey.referenceKey();
			final int currentMatch = findMatchInRunOf(references, currentTargetKey);
			// the stored key targets another run only when the target itself differs - otherwise the run was matched
			// against both keys already
			final int storedMatch = storedTargetKey.equalsInGeneral(currentTargetKey)
				? -1 : findMatchInRunOf(references, storedTargetKey);
			// the first reference in the array matching either key wins
			this.memoizedReferenceIndex = currentMatch == -1 || (storedMatch != -1 && storedMatch < currentMatch)
				? storedMatch : currentMatch;
			this.memoizedReference = this.memoizedReferenceIndex == -1
				? Optional.empty() : Optional.of(references[this.memoizedReferenceIndex]);
		}
		return this.memoizedReference;
	}

	/**
	 * Finds the first existing reference matching the current or the stored representative reference key among the
	 * references of the given target - the run of references whose key equals the target key in general (by name and
	 * referenced primary key), found by a binary search over the sorted references.
	 *
	 * @param references the references of the entity, sorted by their name and referenced primary key
	 * @param targetKey  the key of the target whose run is searched
	 * @return the index of the matching reference, or -1 when the run holds none
	 */
	private int findMatchInRunOf(@Nonnull ReferenceContract[] references, @Nonnull ReferenceKey targetKey) {
		// the lower bound of the run - the first reference not ordered before the target
		int low = 0;
		int high = references.length;
		while (low < high) {
			final int middle = (low + high) >>> 1;
			if (ReferenceKey.GENERIC_COMPARATOR.compare(references[middle].getReferenceKey(), targetKey) < 0) {
				low = middle + 1;
			} else {
				high = middle;
			}
		}
		for (int i = low; i < references.length; i++) {
			final ReferenceContract reference = references[i];
			final ReferenceKey referenceKey = reference.getReferenceKey();
			if (!referenceKey.equalsInGeneral(targetKey)) {
				// the end of the run
				return -1;
			}
			if (!reference.exists()) {
				continue;
			}
			final RepresentativeReferenceKey theReferenceKey =
				this.referenceSchema.getName().equals(reference.getReferenceName()) &&
					this.referenceSchema.getCardinality().allowsDuplicates() ?
					new RepresentativeReferenceKey(
						referenceKey,
						this.referenceSchema.getRepresentativeAttributeDefinition()
							.getRepresentativeValues(reference)
					) :
					new RepresentativeReferenceKey(referenceKey);
			if (
				Objects.equals(theReferenceKey, this.currentReferenceKey) ||
					Objects.equals(theReferenceKey, this.storedReferenceKey)
			) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Checks if the memoized reference is either null or obsolete.
	 * The reference is considered obsolete if the reference array length has changed or the specific reference in the array
	 * has been replaced.
	 *
	 * @param references An array of {@link ReferenceContract} that represents the current references.
	 * @return {@code true} if the memoized reference is null or considered obsolete; {@code false} otherwise.
	 */
	@Nonnull
	private Boolean isReferenceNullOrObsolete(@Nonnull ReferenceContract[] references) {
		//noinspection OptionalAssignedToNull
		return this.memoizedReference == null ||
			this.memoizedReference
				.map(
					it ->
						// array may be enlarged or reduced in the meantime
						references.length <= this.memoizedReferenceIndex ||
							// or the reference was replaced
							references[this.memoizedReferenceIndex] != it
				)
				.orElse(true);
	}
}
