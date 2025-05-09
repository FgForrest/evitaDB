/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.store.entity.model.entity.AttributesStoragePart;
import io.evitadb.store.spi.model.storageParts.accessor.WritableEntityStorageContainerAccessor;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * This is auxiliary class that allows lazily fetch attribute container from persistent storage and remember it
 * for potential future requests.
 */
@NotThreadSafe
class EntityStoragePartAccessorAttributeValueSupplier implements ExistingAttributeValueSupplier {
	private final WritableEntityStorageContainerAccessor containerAccessor;
	private final String entityType;
	private final int entityPrimaryKey;
	private final MemoizedLocalesObsoleteChecker memoizedLocalesObsoleteChecker;
	private Set<Locale> memoizedOriginalLocales;
	private Set<Locale> memoizedCurrentLocales;

	public EntityStoragePartAccessorAttributeValueSupplier(
		@Nonnull WritableEntityStorageContainerAccessor containerAccessor,
		@Nonnull String entityType,
		int entityPrimaryKey
	) {
		this.containerAccessor = containerAccessor;
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
		final AttributesStoragePart currentAttributes = ofNullable(attributeKey.locale())
			.map(it -> this.containerAccessor.getAttributeStoragePart(this.entityType, this.entityPrimaryKey, it))
			.orElseGet(() -> this.containerAccessor.getAttributeStoragePart(this.entityType, this.entityPrimaryKey));

		return Optional.of(currentAttributes)
			.map(it -> it.findAttribute(attributeKey))
			.filter(Droppable::exists);
	}

	@Override
	@Nonnull
	public Stream<AttributeValue> getAttributeValues() {
		return Stream.concat(
				// combine global attributes
				Stream.of(
					Arrays.stream(
						this.containerAccessor.getAttributeStoragePart(this.entityType, this.entityPrimaryKey)
							.getAttributes()
					)
				),
				// with all locale-specific attributes
				getMemoizedLocalesWithAppliedChanges()
					.stream()
					.map(
						locale -> Arrays.stream(this.containerAccessor.getAttributeStoragePart(this.entityType, this.entityPrimaryKey, locale)
							.getAttributes())
					)
			)
			.flatMap(Function.identity())
			.filter(Droppable::exists);
	}

	@Override
	@Nonnull
	public Stream<AttributeValue> getAttributeValues(@Nonnull Locale locale) {
		return Arrays.stream(
			this.containerAccessor.getAttributeStoragePart(this.entityType, this.entityPrimaryKey, locale).getAttributes()
		).filter(Droppable::exists);
	}

	/**
	 * Retrieves a set of locales for the entity with any recent changes applied. This method first checks
	 * if the current memoized locales are obsolete. If they are, it recalculates the current locales by
	 * accessing the entity storage and applying changes. The recalculated locales are then memoized
	 * for later use.
	 *
	 * @return a set of locales representing the current state with applied changes, ensuring that the information
	 * is up-to-date with the entity storage.
	 */
	@Nonnull
	private Set<Locale> getMemoizedLocalesWithAppliedChanges() {
		memoizeLocales();
		return this.memoizedCurrentLocales;
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
			this.memoizedCurrentLocales = this.memoizedLocalesObsoleteChecker.produceNewMemoizedLocalesAfterChangesApplied(storagePart);
		}
	}

	/**
	 * MemoizedLocalesObsoleteChecker is responsible for checking the obsolescence of memoized locales
	 * within an enclosing class. The class keeps track of the last known sets of added and removed locales, and it can
	 * determine whether the locales have changed since the last check.
	 */
	@RequiredArgsConstructor
	private static class MemoizedLocalesObsoleteChecker {
		private final WritableEntityStorageContainerAccessor containerAccessor;
		private Set<Locale> memoizedAddedLocales;
		private Set<Locale> memoizedRemovedLocales;

		/**
		 * Checks whether the locales have changed since the last memoized state.
		 *
		 * The method compares the current sets of added and removed locales with previously
		 * memoized sets to determine if there has been any change. If either the added or removed
		 * locales have changed, the method updates the memoized sets and returns true.
		 *
		 * @return {@code true} if the locales have changed since the last check, otherwise {@code false}
		 */
		public boolean isLocalesObsolete() {
			final Set<Locale> currentAddedLocales = this.containerAccessor.getAddedLocales();
			final Set<Locale> currentRemovedLocales = this.containerAccessor.getRemovedLocales();
			final boolean localesObsolete;
			if (this.memoizedAddedLocales == null || this.memoizedRemovedLocales == null) {
				localesObsolete = true;
			} else {
				localesObsolete = !currentAddedLocales.equals(this.memoizedAddedLocales) ||
					!currentRemovedLocales.equals(this.memoizedRemovedLocales);
			}
			if (localesObsolete) {
				this.memoizedAddedLocales = new HashSet<>(currentAddedLocales);
				this.memoizedRemovedLocales = new HashSet<>(currentRemovedLocales);
			}
			return localesObsolete;
		}

		/**
		 * Produces a new set of memoized locales based on the provided set of entity locales.
		 * This method updates the provided set by removing any locales that have been memoized as added
		 * and adding any locales that have been memoized as removed.
		 *
		 * It's expected that {@link #isLocalesObsolete()} has been called before this method to ensure
		 * that the memoized locales are up-to-date.
		 *
		 * @param entityLocales the set of entity locales to be updated
		 * @return a set of updated locales reflecting the addition and removal of memoized locales
		 */
		@Nonnull
		public Set<Locale> produceNewMemoizedLocalesBeforeChangesAreApplied(@Nonnull Set<Locale> entityLocales) {
			final HashSet<Locale> updatedLocales = new HashSet<>(entityLocales);
			updatedLocales.removeAll(this.memoizedAddedLocales);
			updatedLocales.addAll(this.memoizedRemovedLocales);
			return updatedLocales;
		}

		/**
		 * Produces a new set of memoized locales based on the provided set of entity locales.
		 * This method updates the provided set by adding any locales that have been memoized as added
		 * and removing any locales that have been memoized as removed.
		 *
		 * It's expected that {@link #isLocalesObsolete()} has been called before this method to ensure
		 * that the memoized locales are up-to-date.
		 *
		 * @param entityLocales the set of entity locales to be updated
		 * @return a set of updated locales reflecting the addition and removal of memoized locales
		 */
		@Nonnull
		public Set<Locale> produceNewMemoizedLocalesAfterChangesApplied(@Nonnull Set<Locale> entityLocales) {
			final HashSet<Locale> updatedLocales = new HashSet<>(entityLocales);
			updatedLocales.addAll(this.memoizedAddedLocales);
			updatedLocales.removeAll(this.memoizedRemovedLocales);
			return updatedLocales;
		}
	}

}
