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

package io.evitadb.core.cdc.predicate;

import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.Mutation.StreamDirection;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

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
	 * request. The predicate chain is created in such a way that it captures only mutations that are older than
	 * the given version and index. Thus the method is named "reversed" as it is used to capture changes in the reversed
	 * order.
	 *
	 * @param request request to be used for creating the predicate chain
	 * @return predicate chain that filters out mutations that match the given request
	 */
	@Nonnull
	static MutationPredicate createChangeCatalogCapturePredicate(@Nonnull ChangeCatalogCaptureRequest request) {
		return createPredicateUsingComparator(
			request.sinceVersion(),
			request.sinceIndex(),
			request.criteria(),
			Comparator.naturalOrder(),
			Comparator.naturalOrder(),
			StreamDirection.FORWARD
		);
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
		return createPredicateUsingComparator(
			criteria.sinceVersion(),
			criteria.sinceIndex(),
			criteria.criteria(),
			Comparator.reverseOrder(),
			Comparator.reverseOrder(),
			StreamDirection.REVERSE
		);
	}

	/**
	 * Method creates a predicate chain that filters out mutations that match the given {@link ChangeCatalogCaptureCriteria}
	 *
	 * @param criteria          criteria to be used for creating the predicate chain
	 * @param context           context of the predicate chain
	 * @return predicate chain that filters out mutations that match the given criteria
	 */
	@Nullable
	static MutationPredicate createCriteriaPredicate(
		@Nonnull ChangeCatalogCaptureCriteria criteria,
		@Nonnull MutationPredicateContext context
	) {
		MutationPredicate mutationPredicate = null;
		if (criteria.area() != null) {
			final AreaPredicate areaPredicate;
			switch (criteria.area()) {
				case SCHEMA -> areaPredicate = new SchemaAreaPredicate(context);
				case DATA -> areaPredicate = new DataAreaPredicate(context);
				case INFRASTRUCTURE -> areaPredicate = new InfrastructureAreaPredicate(context);
				default -> throw new GenericEvitaInternalError("Unknown area: " + criteria.area());
			}

			mutationPredicate = areaPredicate;
			if (criteria.site() != null) {
				mutationPredicate = areaPredicate.createSitePredicate(criteria.site())
					.map(mutationPredicate::and)
					.orElse(mutationPredicate);
			}
		}
		return mutationPredicate;
	}

	/**
	 * Method creates a predicate chain that filters out mutations that match the given {@link ChangeCatalogCaptureRequest}
	 * request using the given version / index comparator.
	 *
	 * @param versionComparator comparator to be used for comparing versions
	 * @param indexComparator   comparator to be used for comparing indexes
	 * @param direction         direction of the stream
	 * @return predicate chain that filters out mutations that match the given request
	 */
	@Nonnull
	static MutationPredicate createPredicateUsingComparator(
		@Nullable Long sinceCatalogVersion,
		@Nullable Integer sinceIndex,
		@Nullable ChangeCatalogCaptureCriteria[] criteria,
		@Nonnull Comparator<Long> versionComparator,
		@Nonnull Comparator<Integer> indexComparator,
		@Nonnull StreamDirection direction
	) {
		MutationPredicateContext context = new MutationPredicateContext(direction);
		MutationPredicate mutationPredicate = null;
		if (sinceCatalogVersion != null) {
			mutationPredicate = ofNullable(sinceIndex)
				.map(index -> (MutationPredicate) new VersionAndIndexPredicate(context, sinceCatalogVersion, index, versionComparator, indexComparator))
				.orElseGet(() -> new VersionPredicate(context, sinceCatalogVersion, versionComparator));
		}

		if (criteria != null) {
			final MutationPredicate[] mutationPredicates = Arrays.stream(criteria)
				.map(c -> createCriteriaPredicate(c, context))
				.filter(Objects::nonNull)
				.toArray(MutationPredicate[]::new);
			final MutationPredicate predicateToAdd;
			if (mutationPredicates.length == 1) {
				predicateToAdd = mutationPredicates[0];
			} else if (mutationPredicates.length > 1) {
				predicateToAdd = MutationPredicate.or(mutationPredicates);
			} else {
				predicateToAdd = null;
			}
			if (predicateToAdd != null) {
				mutationPredicate = mutationPredicate == null ?
					predicateToAdd : mutationPredicate.and(predicateToAdd);
			}
		}

		return mutationPredicate == null ? new TruePredicate(context) : mutationPredicate;
	}

	/**
	 * Fallback predicate that matches all mutations.
	 */
	class TruePredicate extends MutationPredicate {

		public TruePredicate(@Nonnull MutationPredicateContext context) {
			super(context);
		}

		@Override
		public boolean test(Mutation mutation) {
			return true;
		}
	}
}
