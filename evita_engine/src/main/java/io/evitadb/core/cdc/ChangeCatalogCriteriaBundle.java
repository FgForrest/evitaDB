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

package io.evitadb.core.cdc;


import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.mutation.Mutation.StreamDirection;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;

import static io.evitadb.core.cdc.predicate.MutationPredicateFactory.createPredicateUsingComparator;

/**
 * This class bundles multiple {@link ChangeCatalogCaptureCriteria} objects together for efficient management
 * and comparison in the Change Data Capture (CDC) system. It ensures that the criteria are sorted for
 * consistent comparison and provides implementation of {@link Comparable} interface to allow
 * for ordering of bundles.
 *
 * The bundle is used in the CDC system to group related criteria and efficiently determine if a change
 * matches any of the criteria in the bundle. This is particularly useful when filtering changes
 * that subscribers are interested in based on multiple criteria.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see ChangeCatalogCaptureCriteria
 */
public record ChangeCatalogCriteriaBundle(
	@Nonnull ChangeCatalogCaptureCriteria[] criteria
) implements Comparable<ChangeCatalogCriteriaBundle> {
	/**
	 * A predefined constant instance of {@link ChangeCatalogCriteriaBundle} that matches all changes.
	 * This instance represents an empty set of {@link ChangeCatalogCaptureCriteria}, effectively acting
	 * as a "catch-all" configuration with no filtering applied.
	 */
	public static final ChangeCatalogCriteriaBundle CATCH_ALL = new ChangeCatalogCriteriaBundle(new ChangeCatalogCaptureCriteria[0]);

	public ChangeCatalogCriteriaBundle(@Nonnull ChangeCatalogCaptureCriteria[] criteria) {
		this.criteria = new ChangeCatalogCaptureCriteria[criteria.length];
		System.arraycopy(criteria, 0, this.criteria, 0, criteria.length);
		Arrays.sort(this.criteria);
	}

	/**
	 * Creates a {@link MutationPredicate} instance using the predefined {@link ChangeCatalogCaptureCriteria} array.
	 * The predicate is designed to filter mutations based on the specified criteria.
	 *
	 * @return a {@link MutationPredicate} that filters mutations according to the bundled criteria
	 */
	@Nonnull
	public MutationPredicate createPredicate(
		@Nullable Long sinceCatalogVersion,
		@Nullable Integer sinceIndex
	) {
		return createPredicateUsingComparator(
			sinceCatalogVersion, sinceIndex, this.criteria,
			Comparator.naturalOrder(), Comparator.naturalOrder(),
			StreamDirection.FORWARD
		);
	}

	@Override
	public int compareTo(ChangeCatalogCriteriaBundle o) {
		return ArrayUtils.compare(this.criteria, o.criteria);
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof ChangeCatalogCriteriaBundle that)) return false;

		return Arrays.equals(this.criteria, that.criteria);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(this.criteria);
	}
}
