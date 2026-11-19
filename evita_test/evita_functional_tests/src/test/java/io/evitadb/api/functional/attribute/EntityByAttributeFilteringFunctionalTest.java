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
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.core.Evita;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.IntegerNumberRange;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeInRange;
import static io.evitadb.api.query.QueryConstraints.attributeInSet;
import static io.evitadb.api.query.QueryConstraints.attributeLessThan;
import static io.evitadb.api.query.QueryConstraints.attributeLessThanEquals;
import static io.evitadb.api.query.QueryConstraints.attributeSetExact;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.orderBy;
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
	 * Dataset of products with a string label, some named like enum constants, and a decimal amount.
	 */
	@Nonnull
	private static final String COMPARABLE_ATTRIBUTE_PRODUCTS = "comparableAttributeProducts";
	/** Dataset of products with disjoint integer and decimal ranges. */
	@Nonnull
	private static final String RANGE_ATTRIBUTE_PRODUCTS = "rangeAttributeProducts";

	/**
	 * Creates products with filterable ranges at different indexed precisions.
	 *
	 * @param evita the embedded database
	 * @return the dataset carrier
	 */
	@Nonnull
	@DataSet(RANGE_ATTRIBUTE_PRODUCTS)
	DataCarrier setUpRangeAttributeProducts(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT).withoutGeneratedPrimaryKey()
				.withAttribute("integerRange", IntegerNumberRange.class, attribute -> attribute.filterable())
				.withAttribute("decimalRange", BigDecimalNumberRange.class,
					attribute -> attribute.indexDecimalPlaces(2).filterable())
				.updateVia(session);
			session.createNewEntity(Entities.PRODUCT, 1)
				.setAttribute("integerRange", IntegerNumberRange.between(1, 3))
				.setAttribute("decimalRange",
					BigDecimalNumberRange.between(new BigDecimal("1.10"), new BigDecimal("3.30")))
				.upsertVia(session);
			session.createNewEntity(Entities.PRODUCT, 2)
				.setAttribute("integerRange", IntegerNumberRange.between(7, 9))
				.setAttribute("decimalRange",
					BigDecimalNumberRange.between(new BigDecimal("7.10"), new BigDecimal("9.30")))
				.upsertVia(session);
		});
		return new DataCarrier();
	}

	/**
	 * Checks both query forms against the actual range indexes and their indexed precision.
	 *
	 * @param attributeName the integer or decimal range attribute
	 * @param point the floating-point value to look up
	 * @param expectedKeys the matching product primary keys
	 * @param evita the embedded database containing the ranges
	 */
	@ParameterizedTest
	@MethodSource("floatingPointRangePoints")
	@UseDataSet(RANGE_ATTRIBUTE_PRODUCTS)
	@Tag(QUERY)
	@DisplayName("Return the same primary keys for Java and EvitaQL floating-point range points")
	void shouldReturnSamePrimaryKeysForFloatingPointRangePoints(
		@Nonnull String attributeName, @Nonnull Number point, @Nonnull List<Integer> expectedKeys, @Nonnull Evita evita
	) {
		assertPrimaryKeys(
			evita, expectedKeys,
			query(collection(Entities.PRODUCT), filterBy(attributeInRange(attributeName, point))),
			DefaultQueryParser.getInstance().parseQuery(
				"query(collection(?), filterBy(attributeInRange(?, ?)))", Entities.PRODUCT, attributeName, point
			)
		);
	}

	/**
	 * Provides matching and non-matching points for each range type, including integer boundary rounding.
	 *
	 * @return range attributes, points and expected primary keys
	 */
	@Nonnull
	static Stream<Arguments> floatingPointRangePoints() {
		return Stream.of(
			Arguments.of("integerRange", 3.4d, List.of(1)),
			Arguments.of("integerRange", 3.4f, List.of(1)),
			Arguments.of("integerRange", 3.5d, List.of()),
			Arguments.of("integerRange", 3.5f, List.of()),
			Arguments.of("decimalRange", 2.1d, List.of(1)),
			Arguments.of("decimalRange", 2.1f, List.of(1)),
			Arguments.of("decimalRange", 3.4d, List.of()),
			Arguments.of("decimalRange", 3.4f, List.of())
		);
	}

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

	/**
	 * Seeds products with distinct enum names and decimal values.
	 *
	 * @param evita the embedded database
	 * @return the dataset carrier
	 */
	@Nonnull
	@DataSet(COMPARABLE_ATTRIBUTE_PRODUCTS)
	DataCarrier setUpComparableAttributeProducts(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT).withoutGeneratedPrimaryKey()
				.withAttribute("label", String.class, attribute -> attribute.filterable())
				.withAttribute("amount", BigDecimal.class,
					attribute -> attribute.indexDecimalPlaces(2).filterable().sortable())
				.updateVia(session);
			session.createNewEntity(Entities.PRODUCT, 1).setAttribute("label", "OPTIMIZED")
				.setAttribute("amount", new BigDecimal("1.50")).upsertVia(session);
			session.createNewEntity(Entities.PRODUCT, 2).setAttribute("label", "MONDAY")
				.setAttribute("amount", new BigDecimal("2.00")).upsertVia(session);
			session.createNewEntity(Entities.PRODUCT, 3).setAttribute("label", "OTHER")
				.setAttribute("amount", new BigDecimal("3.00")).upsertVia(session);
		});
		return new DataCarrier();
	}

	/**
	 * Compares enum filters made by the Java factory and parsed from positional or unsafe literal input.
	 *
	 * @param evita the embedded database containing the products
	 */
	@Test
	@UseDataSet(COMPARABLE_ATTRIBUTE_PRODUCTS)
	@Tag(QUERY)
	@DisplayName("Return the same primary keys for Java enums and EvitaQL attribute values")
	void shouldReturnSamePrimaryKeysForEnumAttributeValues(@Nonnull Evita evita) {
		final HistogramBehavior enumValue = HistogramBehavior.OPTIMIZED;
		assertPrimaryKeys(
			evita, List.of(1),
			query(collection(Entities.PRODUCT), filterBy(attributeEquals("label", enumValue))),
			DefaultQueryParser.getInstance().parseQuery(
				"query(collection(?), filterBy(attributeEquals(?, ?)))", Entities.PRODUCT, "label", enumValue
			)
		);
		assertPrimaryKeys(
			evita, List.of(2),
			query(collection(Entities.PRODUCT), filterBy(attributeEquals("label", DayOfWeek.MONDAY))),
			DefaultQueryParser.getInstance().parseQueryUnsafe(
				"query(collection(?), filterBy(attributeEquals('label', MONDAY)))", Entities.PRODUCT
			)
		);
	}

	/**
	 * Checks decimal filtering and exact ordering with the same double and float inputs in both query APIs.
	 *
	 * @param evita the embedded database containing the products
	 */
	@Test
	@UseDataSet(COMPARABLE_ATTRIBUTE_PRODUCTS)
	@Tag(QUERY)
	@DisplayName("Return the same ordered primary keys for Java and EvitaQL floating-point values")
	void shouldReturnSamePrimaryKeysForFloatingPointAttributeValues(@Nonnull Evita evita) {
		assertPrimaryKeys(
			evita, List.of(2, 1),
			query(
				collection(Entities.PRODUCT), filterBy(attributeInSet("amount", 1.5d, 2.0d)),
				orderBy(attributeSetExact("amount", 2.0d, 1.5d))
			),
			DefaultQueryParser.getInstance().parseQuery(
				"query(collection(?), filterBy(attributeInSet(?, ?)), orderBy(attributeSetExact(?, ?)))",
				Entities.PRODUCT, "amount", List.of(1.5d, 2.0d), "amount", List.of(2.0d, 1.5d)
			)
		);
		assertPrimaryKeys(
			evita, List.of(1, 2),
			query(collection(Entities.PRODUCT), filterBy(attributeLessThanEquals("amount", 2.0f))),
			DefaultQueryParser.getInstance().parseQuery(
				"query(collection(?), filterBy(attributeLessThanEquals(?, ?)))", Entities.PRODUCT, "amount", 2.0f
			)
		);
	}


	/**
	 * Compares the primary keys from the typed array, the single-value overload and a wrapped EvitaQL binding.
	 *
	 * @param evita the embedded database containing the products
	 */
	@Test
	@UseDataSet(COMPARABLE_ATTRIBUTE_PRODUCTS)
	@Tag(QUERY)
	@DisplayName("Return the same primary keys for typed and wrapped membership arrays")
	void shouldReturnSamePrimaryKeysForWrappedMembershipArray(@Nonnull Evita evita) {
		final String[] labels = {"OPTIMIZED", "MONDAY"};
		final Serializable values = labels;
		final Query typed = query(collection(Entities.PRODUCT), filterBy(attributeInSet("label", labels)));
		final Query wrapped = query(collection(Entities.PRODUCT), filterBy(attributeInSet("label", values)));
		final Query parsed = DefaultQueryParser.getInstance().parseQuery(
			"query(collection(?), filterBy(attributeInSet(?, ?)))", Entities.PRODUCT, "label", List.of(values)
		);
		assertPrimaryKeys(evita, List.of(1, 2), typed, wrapped);
		assertPrimaryKeys(evita, List.of(1, 2), wrapped, parsed);
	}

	/**
	 * Executes both forms on the same catalog and verifies their results in order.
	 *
	 * @param evita the embedded database
	 * @param expectedKeys the expected primary keys
	 * @param javaQuery the Java factory query
	 * @param evitaQlQuery the parsed EvitaQL query
	 */
	private static void assertPrimaryKeys(
		@Nonnull Evita evita, @Nonnull List<Integer> expectedKeys, @Nonnull Query javaQuery, @Nonnull Query evitaQlQuery
	) {
		assertEquals(javaQuery, evitaQlQuery);
		try (final EvitaSessionContract session = evita.createReadOnlySession(TEST_CATALOG)) {
			assertEquals(expectedKeys, session.queryEntityReference(javaQuery).getRecordData().stream()
				.map(EntityReferenceContract::getPrimaryKey).toList());
			assertEquals(expectedKeys, session.queryEntityReference(evitaQlQuery).getRecordData().stream()
				.map(EntityReferenceContract::getPrimaryKey).toList());
		}
	}

	@Nonnull
	@Override
	protected ReferenceSchemaBuilder makeReferenceIndexed(ReferenceSchemaBuilder whichIs) {
		return whichIs.indexedForFiltering();
	}
}
