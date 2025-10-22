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

package io.evitadb.index.mutation.index.dataAccess;


import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import io.evitadb.store.spi.model.storageParts.accessor.WritableEntityStorageContainerAccessor;

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
class ReferenceEntityStoragePartAccessorAttributeValueSupplier implements ExistingAttributeValueSupplier {
	private final WritableEntityStorageContainerAccessor containerAccessor;
	private final ReferenceSchema referenceSchema;
	private final RepresentativeReferenceKey referenceKey;
	private final String entityType;
	private final int entityPrimaryKey;
	private final MemoizedLocalesObsoleteChecker memoizedLocalesObsoleteChecker;
	private Set<Locale> memoizedOriginalLocales;
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	private Optional<ReferenceContract> memoizedReference;
	private int memoizedReferenceIndex = -1;

	public ReferenceEntityStoragePartAccessorAttributeValueSupplier(
		WritableEntityStorageContainerAccessor containerAccessor,
		ReferenceSchema referenceSchema,
		RepresentativeReferenceKey referenceKey,
		String entityType,
		int entityPrimaryKey
	) {
		this.containerAccessor = containerAccessor;
		this.referenceSchema = referenceSchema;
		this.referenceKey = referenceKey;
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
	 * within the available references and memoizes it.
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
			this.memoizedReferenceIndex = -1;
			for (int i = 0; i < references.length; i++) {
				final ReferenceContract reference = references[i];
				if (reference.exists()) {
					final RepresentativeReferenceKey theReferenceKey =
						this.referenceSchema.getName().equals(reference.getReferenceName()) &&
							this.referenceSchema.getCardinality().allowsDuplicates() ?
							new RepresentativeReferenceKey(
								reference.getReferenceKey(),
								this.referenceSchema.getRepresentativeAttributeDefinition()
								                    .getRepresentativeValues(reference)
							) :
							new RepresentativeReferenceKey(reference.getReferenceKey());
					if (Objects.equals(theReferenceKey, this.referenceKey)) {
						this.memoizedReference = Optional.of(reference);
						this.memoizedReferenceIndex = i;
						break;
					}
				}
			}
			if (this.memoizedReferenceIndex == -1) {
				this.memoizedReference = Optional.empty();
			}
		}
		return this.memoizedReference;
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
