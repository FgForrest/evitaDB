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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint;

import graphql.schema.SelectedField;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.BucketsFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidResponseUsageException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.priceHistogram;

/**
 * Custom constraint resolver which resolves additional constraints from output fields defined by client, rather
 * than using main query.
 * Resolves {@link io.evitadb.api.query.require.PriceHistogram} based on which extra result fields client specified.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class PriceHistogramResolver {

	@Nonnull
	public Optional<RequireConstraint> resolve(@Nonnull SelectionSetAggregator extraResultsSelectionSet) {
		final List<SelectedField> priceHistogramFields = extraResultsSelectionSet.getImmediateFields(ExtraResultsDescriptor.PRICE_HISTOGRAM.name());
		if (priceHistogramFields.isEmpty()) {
			return Optional.empty();
		}

		final Set<HistogramRequest> requests = priceHistogramFields.stream()
			.flatMap(f -> SelectionSetAggregator.getImmediateFields(HistogramDescriptor.BUCKETS.name(), f.getSelectionSet()).stream())
			.map(f -> {
				final int requestedBucketCount = (int) f.getArguments().get(BucketsFieldHeaderDescriptor.REQUESTED_COUNT.name());
				final HistogramBehavior behavior = (HistogramBehavior) f.getArguments().getOrDefault(BucketsFieldHeaderDescriptor.BEHAVIOR.name(), HistogramBehavior.STANDARD);
				return new HistogramRequest(requestedBucketCount, behavior);
			})
			.collect(Collectors.toSet());
		Assert.isTrue(
			!requests.isEmpty(),
			() -> new GraphQLInvalidResponseUsageException(
				"Price histogram must have at least one `" + HistogramDescriptor.BUCKETS.name() + "` field."
			)
		);
		Assert.isTrue(
			requests.size() == 1,
			() -> new GraphQLInvalidResponseUsageException(
				"Price histogram was requested with multiple different parameters. Only a single set of parameters can be requested."
			)
		);

		final HistogramRequest request = requests.iterator().next();
		return Optional.of(priceHistogram(request.requestedBucketCount(), request.behavior()));
	}
}
