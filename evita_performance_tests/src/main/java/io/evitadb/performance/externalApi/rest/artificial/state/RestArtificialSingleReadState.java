/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.performance.externalApi.rest.artificial.state;

import io.evitadb.performance.externalApi.rest.artificial.RestArtificialEntitiesBenchmark;
import io.evitadb.performance.externalApi.rest.artificial.RestArtificialFullDatabaseBenchmarkState;
import io.evitadb.test.Entities;
import io.evitadb.test.generator.DataGenerator;
import lombok.SneakyThrows;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.infra.Blackhole;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.performance.artificial.ArtificialFullDatabaseBenchmarkState.PRODUCT_COUNT;

/**
 * Base state class for {@link RestArtificialEntitiesBenchmark#singleEntityRead(RestArtificialFullDatabaseBenchmarkState, RestArtificialSingleReadState, Blackhole)}.
 * See benchmark description on the method.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class RestArtificialSingleReadState extends AbstractRestArtificialState {

	/**
	 * Prepares artificial product for the next operation that is measured in the benchmark.
	 */
	@SneakyThrows
	@Setup(Level.Invocation)
	public void prepareCall(RestArtificialFullDatabaseBenchmarkState benchmarkState) {
		final Set<String> parameters = new HashSet<>();

		/* 75% times fetch attributes */
		if (benchmarkState.getRandom().nextInt(4) != 0) {
			parameters.add("attributeContent=code&attributeContent=quantity");
		}
		/* 75% times fetch associated data */
		if (benchmarkState.getRandom().nextInt(4) != 0) {
			final String associatedData = benchmarkState.getProductSchema()
				.getAssociatedData()
				.keySet()
				.stream()
				.filter(it -> benchmarkState.getRandom().nextInt(4) != 0)
				.map(it -> "associatedDataContent=" + it)
				.collect(Collectors.joining("&"));
			if (!associatedData.isEmpty()) {
				parameters.add(associatedData);
			}
		}
		/* 50% times fetch prices */
		if (benchmarkState.getRandom().nextBoolean()) {
			final Currency randomExistingCurrency = Arrays.stream(DataGenerator.CURRENCIES)
				.skip(benchmarkState.getRandom().nextInt(DataGenerator.CURRENCIES.length))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("No currencies found!"));
			final String[] priceLists = Arrays.stream(DataGenerator.PRICE_LIST_NAMES)
				.filter(it -> benchmarkState.getRandom().nextBoolean())
				.toArray(String[]::new);

			parameters.add("priceInCurrency=" + randomExistingCurrency.toString());
			for (String priceList : priceLists) {
				parameters.add("priceInPriceLists=" + priceList);
			}
			parameters.add("priceValidIn=" + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME).replace("+", "%2B"));

			/* 75% only filtered prices */
			if (benchmarkState.getRandom().nextInt(4) != 0) {
				parameters.add("priceContent=true");
			} else {
				// todo lho have to add support for all prices
				parameters.add("priceContent=true");
			}
		}

		/* 25% times load references */
		if (benchmarkState.getRandom().nextInt(4) == 0) {
			Stream.of(Entities.BRAND, Entities.CATEGORY, Entities.PRICE_LIST, Entities.STORE)
				.filter(it -> benchmarkState.getProductSchema().getReference(it).isPresent())
				.forEach(ref -> {
					// todo lho reimplement when api is fixed with correct cases
//					final String refFieldName = StringUtils.toCamelCase(ref);
//					parameters.add("referenceContent=" + refFieldName);
					parameters.add("referenceContent=" + ref);
				});
		}

		final Locale randomExistingLocale = benchmarkState.getProductSchema()
			.getLocales()
			.stream()
			.skip(benchmarkState.getRandom().nextInt(benchmarkState.getProductSchema().getLocales().size()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("No locales found!"));

		this.resource = String.format(
			"%s/product/get?primaryKey=%d%s",
			randomExistingLocale.toLanguageTag(),
			benchmarkState.getRandom().nextInt(PRODUCT_COUNT) + 1,
			!parameters.isEmpty() ? "&" + String.join("&", parameters) : ""
		);
	}
}
