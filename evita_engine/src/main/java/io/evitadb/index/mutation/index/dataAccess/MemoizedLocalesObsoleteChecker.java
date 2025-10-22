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

package io.evitadb.index.mutation.index.dataAccess;


import io.evitadb.store.spi.model.storageParts.accessor.WritableEntityStorageContainerAccessor;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * MemoizedLocalesObsoleteChecker is responsible for checking the obsolescence of memoized locales
 * within an enclosing class. The class keeps track of the last known sets of added and removed locales, and it can
 * determine whether the locales have changed since the last check.
 */
@RequiredArgsConstructor
class MemoizedLocalesObsoleteChecker {
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
