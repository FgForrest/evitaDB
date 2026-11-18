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

package io.evitadb.api.functional.attribute;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.parser.DefaultQueryParser;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.IsolateDataSetBySuffix;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeInSet;
import static io.evitadb.api.query.QueryConstraints.attributeLessThan;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * This test verifies whether entities can be filtered by attributes.
 * This test verifies correct behavior when the referenced entity is indexed for filtering only.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity filtering by attributes functionality")
@IsolateDataSetBySuffix("filtering")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(CONTRACT)
@Tag(ATTRIBUTE)
@Tag(FILTER)
public class EntityByAttributeFilteringFunctionalTest extends AbstractEntityByAttributeFilteringFunctionalTest {

	/**
	 * Dataset with independent currency and locale filters.
	 */
	@Nonnull
	private static final String CURRENCY_AND_LOCALE_PRODUCTS = "currencyAndLocaleProducts";

	/**
	 * Seeds products so neither filter alone selects the expected primary key.
	 *
	 * @param evita the embedded database
	 * @return the empty carrier for the dataset
	 */
	@Nonnull
	@DataSet(CURRENCY_AND_LOCALE_PRODUCTS)
	DataCarrier setUpCurrencyAndLocaleProducts(@Nonnull Evita evita) {
		final Currency usd = Currency.getInstance("USD");
		final Currency eur = Currency.getInstance("EUR");
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withAttribute("currency", Currency.class, attribute -> attribute.filterable())
				.withAttribute("lang", Locale.class, attribute -> attribute.filterable())
				.updateVia(session);
			session.createNewEntity(Entities.PRODUCT, 1)
				.setAttribute("currency", usd).setAttribute("lang", Locale.GERMAN).upsertVia(session);
			session.createNewEntity(Entities.PRODUCT, 2)
				.setAttribute("currency", eur).setAttribute("lang", Locale.GERMAN).upsertVia(session);
			session.createNewEntity(Entities.PRODUCT, 3)
				.setAttribute("currency", eur).setAttribute("lang", Locale.ENGLISH).upsertVia(session);
		});
		return new DataCarrier();
	}

	/**
	 * Compares the primary keys returned by parsed EvitaQL and by the Java factories.
	 *
	 * @param evita the embedded database containing the products
	 */
	@Test
	@UseDataSet(CURRENCY_AND_LOCALE_PRODUCTS)
	@Tag(QUERY)
	@DisplayName("Filter by a bound currency and a list of locales")
	void shouldReturnSamePrimaryKeysForBoundCurrencyAndLocales(@Nonnull Evita evita) {
		final Currency usd = Currency.getInstance("USD");
		final Query javaQuery = query(
			collection(Entities.PRODUCT),
			filterBy(attributeLessThan("currency", usd), attributeInSet("lang", Locale.GERMAN, Locale.FRENCH))
		);
		try (final EvitaSessionContract session = evita.createReadOnlySession(TEST_CATALOG)) {
			final List<Integer> expected = session.queryEntityReference(javaQuery).getRecordData().stream()
				.map(EntityReferenceContract::getPrimaryKey).toList();
			assertEquals(List.of(2), expected);

			final Query parsed = DefaultQueryParser.getInstance().parseQuery(
				"query(collection(?), filterBy(attributeLessThan(?, ?), attributeInSet(?, ?)))",
				Entities.PRODUCT, "currency", usd, "lang", List.of(Locale.GERMAN, Locale.FRENCH)
			);
			final List<Integer> actual = session.queryEntityReference(parsed).getRecordData().stream()
				.map(EntityReferenceContract::getPrimaryKey).toList();
			assertEquals(expected, actual);
		}
	}

	@Nonnull
	@Override
	protected ReferenceSchemaBuilder makeReferenceIndexed(ReferenceSchemaBuilder whichIs) {
		return whichIs.indexedForFiltering();
	}
}
