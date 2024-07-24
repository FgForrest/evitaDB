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

package io.evitadb.core.cdc.predicate;

import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.mutation.Mutation.StreamDirection;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;

import javax.annotation.Nonnull;
import java.util.Comparator;

import static java.util.Optional.ofNullable;

/**
 * Interface contains only static methods allowing to create predicate chains allowing to filter out mutations that
 * match the given {@link ChangeCatalogCaptureRequest} criteria.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface MutationPredicateFactory {

	/**
	 * Method creates a predicate chain that filters out mutations that match the given {@link ChangeCatalogCaptureRequest}
	 * criteria. The predicate chain is created in such a way that it captures only mutations that are older than
	 * the given version and index. Thus the method is named "reversed" as it is used to capture changes in the reversed
	 * order.
	 *
	 * @param criteria criteria to be used for creating the predicate chain
	 * @return predicate chain that filters out mutations that match the given criteria
	 */
	@Nonnull
	static MutationPredicate createChangeCatalogCapturePredicate(@Nonnull ChangeCatalogCaptureRequest criteria) {
		return createPredicateUsingComparator(criteria, Comparator.naturalOrder(), Comparator.naturalOrder(), StreamDirection.FORWARD);
	}

	/**
	 * Method creates a predicate chain that filters out mutations that match the given {@link ChangeCatalogCaptureRequest}
	 * criteria. The predicate chain is created in such a way that it captures only mutations that are older than
	 * the given version and index. Thus the method is named "reversed" as it is used to capture changes in the reversed
	 * order.
	 *
	 * @param criteria criteria to be used for creating the predicate chain
	 * @return predicate chain that filters out mutations that match the given criteria
	 */
	@Nonnull
	static MutationPredicate createReversedChangeCatalogCapturePredicate(@Nonnull ChangeCatalogCaptureRequest criteria) {
		return createPredicateUsingComparator(criteria, Comparator.reverseOrder(), Comparator.reverseOrder(), StreamDirection.REVERSE);
	}

	/**
	 * Method creates a predicate chain that filters out mutations that match the given {@link ChangeCatalogCaptureRequest}
	 * criteria using the given version / index comparator.
	 *
	 * @param criteria criteria to be used for creating the predicate chain
	 * @param versionComparator comparator to be used for comparing versions
	 * @param indexComparator comparator to be used for comparing indexes
	 * @param direction direction of the stream
	 * @return predicate chain that filters out mutations that match the given criteria
	 */
	@Nonnull
	private static MutationPredicate createPredicateUsingComparator(
		@Nonnull ChangeCatalogCaptureRequest criteria,
		@Nonnull Comparator<Long> versionComparator,
		@Nonnull Comparator<Integer> indexComparator,
		@Nonnull StreamDirection direction
		) {
		MutationPredicateContext context = new MutationPredicateContext(direction);
		MutationPredicate mutationPredicate = ofNullable(criteria.sinceIndex())
			.map(index -> (MutationPredicate) new VersionAndIndexPredicate(context, criteria.sinceVersion(), index, versionComparator, indexComparator))
			.orElseGet(() -> new VersionPredicate(context, criteria.sinceVersion(), versionComparator));

		if (criteria.area() != null) {
			final AreaPredicate areaPredicate = criteria.area() == CaptureArea.SCHEMA ?
				new SchemaAreaPredicate(context) : new DataAreaPredicate(context);
			mutationPredicate = mutationPredicate.and(areaPredicate);
			if (criteria.site() != null) {
				mutationPredicate = areaPredicate.createSitePredicate(criteria.site())
					.map(mutationPredicate::and)
					.orElse(mutationPredicate);
			}
		}
		return mutationPredicate;
	}

}
