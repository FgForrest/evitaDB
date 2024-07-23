/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.performance.externalApi.grpc.artificial.state;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.performance.externalApi.grpc.artificial.GrpcArtificialEntitiesBenchmark;
import io.evitadb.performance.externalApi.grpc.artificial.GrpcArtificialFullDatabaseBenchmarkState;
import io.evitadb.test.Entities;
import io.evitadb.test.generator.DataGenerator;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.infra.Blackhole;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.performance.artificial.ArtificialFullDatabaseBenchmarkState.PRODUCT_COUNT;

/**
 * Base state class for {@link GrpcArtificialEntitiesBenchmark#paginatedEntityRead(GrpcArtificialFullDatabaseBenchmarkState, GrpcArtificialPageReadState, Blackhole)}.
 * See benchmark description on the method.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class GrpcArtificialPageReadState extends AbstractGrpcArtificialState {

	/**
	 * Prepares artificial product for the next operation that is measured in the benchmark.
	 */
	@Setup(Level.Invocation)
	public void prepareCall(GrpcArtificialFullDatabaseBenchmarkState benchmarkState) {
		final Set<RequireConstraint> requirements = new HashSet<>();
		final Set<EntityContentRequire> contentRequirements = new HashSet<>();
		/* 75% times fetch attributes */
		if (benchmarkState.getRandom().nextInt(4) != 0) {
			contentRequirements.add(attributeContentAll());
		}
		/* 75% times fetch associated data */
		if (benchmarkState.getRandom().nextInt(4) != 0) {
			contentRequirements.add(
				associatedDataContent(
					benchmarkState.getProductSchema()
						.getAssociatedData()
						.keySet()
						.stream()
						.filter(it -> benchmarkState.getRandom().nextInt(4) != 0)
						.toArray(String[]::new)
				)
			);
		}
		/* 50% times fetch prices */
		final FilterConstraint priceConstraint;
		if (benchmarkState.getRandom().nextBoolean()) {
			final Currency randomExistingCurrency = Arrays.stream(DataGenerator.CURRENCIES)
				.skip(benchmarkState.getRandom().nextInt(DataGenerator.CURRENCIES.length))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("No currencies found!"));
			final String[] priceLists = Arrays.stream(DataGenerator.PRICE_LIST_NAMES)
				.filter(it -> benchmarkState.getRandom().nextBoolean())
				.toArray(String[]::new);

			priceConstraint = and(
				priceInCurrency(randomExistingCurrency),
				priceInPriceLists(priceLists),
				priceValidIn(OffsetDateTime.now())
			);

			/* 75% only filtered prices */
			if (benchmarkState.getRandom().nextInt(4) != 0) {
				contentRequirements.add(priceContentRespectingFilter());
			} else {
				contentRequirements.add(priceContentAll());
			}
		} else {
			priceConstraint = null;
		}

		/* 25% times load references */
		if (benchmarkState.getRandom().nextInt(4) == 0) {
			/* 50% times load all references */
			if (benchmarkState.getRandom().nextBoolean()) {
				contentRequirements.add(referenceContentAll());
			} else {
				/* 50% select only some of them */
				contentRequirements.add(
					referenceContent(
						Stream.of(Entities.BRAND, Entities.CATEGORY, Entities.STORE)
							.filter(it -> benchmarkState.getProductSchema().getReference(it).isPresent())
							.filter(it -> benchmarkState.getRandom().nextBoolean())
							.toArray(String[]::new)
					)
				);
			}
		}

		final Locale randomExistingLocale = benchmarkState.getProductSchema()
			.getLocales()
			.stream()
			.skip(benchmarkState.getRandom().nextInt(benchmarkState.getProductSchema().getLocales().size()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("No locales found!"));

		requirements.add(
			page(benchmarkState.getRandom().nextInt(5) + 1, 20)
		);
		requirements.add(
			entityFetch(contentRequirements.toArray(new EntityContentRequire[0]))
		);

		setQuery(Query.query(
			collection(Entities.PRODUCT),
			filterBy(
				and(
					entityPrimaryKeyInSet(
						Stream.iterate(
								benchmarkState.getRandom().nextInt(benchmarkState.getRandom().nextInt(PRODUCT_COUNT) + 1),
								aLong -> benchmarkState.getRandom().nextInt(PRODUCT_COUNT) + 1
							)
							.limit(100)
							.toArray(Integer[]::new)
					),
					entityLocaleEquals(randomExistingLocale),
					priceConstraint
				)
			),
			require(
				requirements.toArray(new RequireConstraint[0])
			)
		));
	}

}
