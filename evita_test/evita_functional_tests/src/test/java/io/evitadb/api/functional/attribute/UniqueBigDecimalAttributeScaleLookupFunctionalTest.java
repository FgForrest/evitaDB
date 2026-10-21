/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.core.Evita;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins that an equality lookup finds an entity by a unique `BigDecimal` attribute whatever scale the value was written
 * in and whatever scale the lookup uses, as long as the two are numerically equal.
 *
 * Every unique structure keys a `BigDecimal` by its value with the trailing zeros stripped, so `1.5` and `1.50` are one
 * key and `30` is held as `3E+1`. A non-localized `unique` attribute keeps its values in the shared filter tree, which
 * re-encodes the probe to the same scaled integer as its keys, so the two always meet. A `uniqueGlobally` /
 * `uniqueGloballyWithinLocale` catalog attribute lives in the catalog's standalone unique tree, and a localized
 * attribute unique across locales in the collection's standalone owner tree; both order their keys by value *and*
 * scale. The lookup value must be stripped the same way as the stored keys there too, or a match is impossible.
 *
 * ## The fixture
 *
 * Every collection holds three entities: entity 1 written as `1.5` (no trailing zeros), entity 2 as `2.50` (a trailing
 * zero) and entity 3 as `30` (an integral value, whose stripped form has a negative scale).
 *
 * | collection | attribute | declared as |
 * |---|---|---|
 * | `globalPrice` | `globalPrice` | catalog, `uniqueGlobally` |
 * | `globalLocalPrice` | `globalLocalPrice` | catalog, localized, `uniqueGloballyWithinLocale` |
 * | `ownerPrice` | `localizedPrice` | entity, localized, `unique` |
 * | `ownerPrice` | `foldedPrice` | entity, `unique` |
 *
 * The last row is the control: its values live in the shared filter tree.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Equality lookup of a unique BigDecimal attribute at any scale")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(FILTER)
@Tag(ATTRIBUTE)
public class UniqueBigDecimalAttributeScaleLookupFunctionalTest {
	private static final String SCALE_LOOKUP = "uniqueBigDecimalScaleLookup";
	private static final String SCALE_UNIQUENESS = "uniqueBigDecimalScaleUniqueness";
	private static final String GLOBAL_PRICE_ENTITY = "globalPrice";
	private static final String GLOBAL_LOCAL_PRICE_ENTITY = "globalLocalPrice";
	private static final String OWNER_ENTITY = "ownerPrice";
	private static final String GLOBAL_PRICE = "globalPrice";
	private static final String GLOBAL_LOCAL_PRICE = "globalLocalPrice";
	private static final String LOCALIZED_PRICE = "localizedPrice";
	private static final String FOLDED_PRICE = "foldedPrice";
	private static final int INDEXED_DECIMAL_PLACES = 2;
	private static final BigDecimal SHORT_VALUE = new BigDecimal("1.5");
	private static final BigDecimal SHORT_VALUE_PADDED = new BigDecimal("1.50");
	private static final BigDecimal PADDED_VALUE = new BigDecimal("2.50");
	private static final BigDecimal PADDED_VALUE_STRIPPED = new BigDecimal("2.5");
	private static final BigDecimal INTEGRAL_VALUE = new BigDecimal("30");
	private static final BigDecimal INTEGRAL_VALUE_PADDED = new BigDecimal("30.00");
	private static final int WRITTEN_SHORT = 1;
	private static final int WRITTEN_PADDED = 2;
	private static final int WRITTEN_INTEGRAL = 3;
	/**
	 * Primary key of the entity whose upsert is expected to be refused by a uniqueness check.
	 */
	private static final int REFUSED_PK = 10;

	/**
	 * Defines the catalog and the three collections described on the class, without any entity.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	private static void defineSchema(@Nonnull Evita evita) {
		evita.defineCatalog(TEST_CATALOG)
			.withAttribute(
				GLOBAL_PRICE, BigDecimal.class,
				thatIs -> thatIs.uniqueGlobally().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
			)
			.withAttribute(
				GLOBAL_LOCAL_PRICE, BigDecimal.class,
				thatIs -> thatIs.localized().uniqueGloballyWithinLocale().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
			)
			.updateViaNewSession(evita);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(GLOBAL_PRICE_ENTITY)
					.withoutGeneratedPrimaryKey().withGlobalAttribute(GLOBAL_PRICE).updateVia(session);
				session.defineEntitySchema(GLOBAL_LOCAL_PRICE_ENTITY)
					.withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH).withGlobalAttribute(GLOBAL_LOCAL_PRICE)
					.updateVia(session);
				session.defineEntitySchema(OWNER_ENTITY)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH)
					.withAttribute(
						LOCALIZED_PRICE, BigDecimal.class,
						thatIs -> thatIs.localized().unique().nullable().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
					)
					.withAttribute(
						FOLDED_PRICE, BigDecimal.class,
						thatIs -> thatIs.unique().nullable().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
					)
					.updateVia(session);
			}
		);
	}

	/**
	 * Writes `value` as entity `pk` into every collection of the fixture, in every attribute it declares.
	 *
	 * @param session the read-write session
	 * @param pk      the primary key of the entity in every collection
	 * @param value   the value written to every attribute
	 */
	private static void writeEverywhere(@Nonnull EvitaSessionContract session, int pk, @Nonnull BigDecimal value) {
		session.createNewEntity(GLOBAL_PRICE_ENTITY, pk).setAttribute(GLOBAL_PRICE, value).upsertVia(session);
		session.createNewEntity(GLOBAL_LOCAL_PRICE_ENTITY, pk)
			.setAttribute(GLOBAL_LOCAL_PRICE, Locale.ENGLISH, value).upsertVia(session);
		session.createNewEntity(OWNER_ENTITY, pk)
			.setAttribute(LOCALIZED_PRICE, Locale.ENGLISH, value)
			.setAttribute(FOLDED_PRICE, value)
			.upsertVia(session);
	}

	/**
	 * Builds the lookup fixture described on the class.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = SCALE_LOOKUP, destroyAfterClass = true)
	void setUp(@Nonnull Evita evita) {
		// the spellings must differ as BigDecimals and agree numerically, or every assertion below proves nothing
		assertNotEquals(SHORT_VALUE, SHORT_VALUE_PADDED);
		assertEquals(0, SHORT_VALUE.compareTo(SHORT_VALUE_PADDED));
		assertNotEquals(PADDED_VALUE, PADDED_VALUE_STRIPPED);
		assertEquals(0, PADDED_VALUE.compareTo(PADDED_VALUE_STRIPPED));
		assertNotEquals(INTEGRAL_VALUE, INTEGRAL_VALUE.stripTrailingZeros());
		assertNotEquals(INTEGRAL_VALUE, INTEGRAL_VALUE_PADDED);
		assertEquals(0, INTEGRAL_VALUE.compareTo(INTEGRAL_VALUE_PADDED));

		defineSchema(evita);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				writeEverywhere(session, WRITTEN_SHORT, SHORT_VALUE);
				writeEverywhere(session, WRITTEN_PADDED, PADDED_VALUE);
				writeEverywhere(session, WRITTEN_INTEGRAL, INTEGRAL_VALUE);
			}
		);
	}

	/**
	 * Builds a writable copy of the fixture holding only entity 1 (`1.5`), on which a uniqueness check is attempted.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = SCALE_UNIQUENESS, readOnly = false, destroyAfterClass = true)
	void setUpUniqueness(@Nonnull Evita evita) {
		defineSchema(evita);
		evita.updateCatalog(TEST_CATALOG, session -> {
			writeEverywhere(session, WRITTEN_SHORT, SHORT_VALUE);
		});
	}

	/**
	 * Asserts that the query, run within `collection` (or across the catalog when `null`) and optionally in `locale`,
	 * returns exactly the entity `expectedPk` of `expectedType`.
	 */
	private static void assertFinds(
		@Nonnull EvitaSessionContract session,
		@Nullable String collection,
		@Nullable Locale locale,
		@Nonnull FilterConstraint filter,
		@Nonnull String expectedType,
		int expectedPk,
		@Nonnull String route
	) {
		final FilterConstraint constraint = locale == null ? filter : and(filter, entityLocaleEquals(locale));
		final List<String> found = session.queryListOfEntityReferences(
			collection == null
				? Query.query(filterBy(constraint))
				: Query.query(collection(collection), filterBy(constraint))
		).stream().map(UniqueBigDecimalAttributeScaleLookupFunctionalTest::describe).toList();
		assertEquals(
			List.of(expectedType + ":" + expectedPk), found,
			route + " lookup by " + filter + " missed entity " + expectedPk + "!"
		);
	}

	/**
	 * Renders a reference as `type:pk` so a mismatch reads directly in the assertion message.
	 */
	@Nonnull
	private static String describe(@Nonnull EntityReferenceContract reference) {
		return reference.getType() + ":" + reference.getPrimaryKey();
	}

	/**
	 * Runs `attributeEquals` and `attributeInSet` for `probe` within the collection and, where `catalogWide` is set,
	 * across the whole catalog as well, expecting entity `expectedPk`. Every lookup is reported on its own, so one
	 * failing route does not hide the outcome of the others.
	 */
	private static void assertEqualityLookupsFind(
		@Nonnull Evita evita,
		@Nonnull String collection,
		@Nullable Locale locale,
		@Nonnull String attributeName,
		@Nonnull BigDecimal probe,
		int expectedPk,
		boolean catalogWide
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<Executable> lookups = new ArrayList<>(4);
				lookups.add(() -> assertFinds(
					session, collection, locale, attributeEquals(attributeName, probe), collection, expectedPk,
					"collection"
				));
				lookups.add(() -> assertFinds(
					session, collection, locale, attributeInSet(attributeName, probe), collection, expectedPk,
					"collection"
				));
				if (catalogWide) {
					lookups.add(() -> assertFinds(
						session, null, locale, attributeEquals(attributeName, probe), collection, expectedPk,
						"catalog-wide"
					));
					lookups.add(() -> assertFinds(
						session, null, locale, attributeInSet(attributeName, probe), collection, expectedPk,
						"catalog-wide"
					));
				}
				assertAll(lookups);
				return null;
			}
		);
	}

	/**
	 * Asserts that upserting entity {@link #REFUSED_PK} with `1.50` into `collection` is refused, because entity 1
	 * already holds the numerically equal `1.5` in the same unique structure. Runs in its own transaction, so the
	 * refusal rolls back and leaves the fixture untouched for the next attempt.
	 */
	@Nonnull
	private static Executable refusesPaddedTwin(
		@Nonnull Evita evita,
		@Nonnull String collection,
		@Nonnull Consumer<EntityBuilder> writer,
		@Nonnull String structure
	) {
		return () -> assertThrows(
			UniqueValueViolationException.class,
			() -> evita.updateCatalog(TEST_CATALOG, session -> {
				final EntityBuilder builder = session.createNewEntity(collection, REFUSED_PK);
				writer.accept(builder);
				builder.upsertVia(session);
			}),
			structure + " accepted `1.50` next to an existing `1.5`!"
		);
	}

	/**
	 * Catalog attribute held in the catalog's standalone unique tree.
	 */
	@DisplayName("Globally unique attribute")
	@Nested
	class GloballyUniqueAttribute {

		@DisplayName("Should find a value written without trailing zeros by the same spelling")
		@UseDataSet(SCALE_LOOKUP)
		@Test
		void shouldFindShortValueByExactProbe(Evita evita) {
			assertEqualityLookupsFind(evita, GLOBAL_PRICE_ENTITY, null, GLOBAL_PRICE, SHORT_VALUE, WRITTEN_SHORT, true);
		}

		@DisplayName("Should find a value written without trailing zeros by a padded probe")
		@UseDataSet(SCALE_LOOKUP)
		@Test
		void shouldFindShortValueByPaddedProbe(Evita evita) {
			assertEqualityLookupsFind(
				evita, GLOBAL_PRICE_ENTITY, null, GLOBAL_PRICE, SHORT_VALUE_PADDED, WRITTEN_SHORT, true
			);
		}

		@DisplayName("Should find a value written with trailing zeros by the same spelling")
		@UseDataSet(SCALE_LOOKUP)
		@Test
		void shouldFindPaddedValueByExactProbe(Evita evita) {
			assertEqualityLookupsFind(
				evita, GLOBAL_PRICE_ENTITY, null, GLOBAL_PRICE, PADDED_VALUE, WRITTEN_PADDED, true
			);
		}

		@DisplayName("Should find a value written with trailing zeros by a stripped probe")
		@UseDataSet(SCALE_LOOKUP)
		@Test
		void shouldFindPaddedValueByStrippedProbe(Evita evita) {
			assertEqualityLookupsFind(
				evita, GLOBAL_PRICE_ENTITY, null, GLOBAL_PRICE, PADDED_VALUE_STRIPPED, WRITTEN_PADDED, true
			);
		}

		@DisplayName("Should find an integral value by the same spelling")
		@UseDataSet(SCALE_LOOKUP)
		@Test
		void shouldFindIntegralValueByExactProbe(Evita evita) {
			assertEqualityLookupsFind(
				evita, GLOBAL_PRICE_ENTITY, null, GLOBAL_PRICE, INTEGRAL_VALUE, WRITTEN_INTEGRAL, true
			);
		}

		@DisplayName("Should find an integral value by a padded probe")
		@UseDataSet(SCALE_LOOKUP)
		@Test
		void shouldFindIntegralValueByPaddedProbe(Evita evita) {
			assertEqualityLookupsFind(
				evita, GLOBAL_PRICE_ENTITY, null, GLOBAL_PRICE, INTEGRAL_VALUE_PADDED, WRITTEN_INTEGRAL, true
			);
		}
	}

	/**
	 * Localized catalog attribute held in the catalog's standalone unique tree, one per locale.
	 */
	@DisplayName("Globally unique attribute within locale")
	@Nested
	class GloballyUniqueWithinLocaleAttribute {

		@DisplayName("Should find a value written without trailing zeros by the same spelling")
		@UseDataSet(SCALE_LOOKUP)
		@Test
		void shouldFindShortValueByExactProbe(Evita evita) {
			assertEqualityLookupsFind(
				evita, GLOBAL_LOCAL_PRICE_ENTITY, Locale.ENGLISH, GLOBAL_LOCAL_PRICE, SHORT_VALUE, WRITTEN_SHORT, true
			);
		}

		@DisplayName("Should find a value written without trailing zeros by a padded probe")
		@UseDataSet(SCALE_LOOKUP)
		@Test
		void shouldFindShortValueByPaddedProbe(Evita evita) {
			assertEqualityLookupsFind(
				evita, GLOBAL_LOCAL_PRICE_ENTITY, Locale.ENGLISH, GLOBAL_LOCAL_PRICE, SHORT_VALUE_PADDED,
				WRITTEN_SHORT, true
			);
		}

		@DisplayName("Should find a value written with trailing zeros by the same spelling")
		@UseDataSet(SCALE_LOOKUP)
		@Test
		void shouldFindPaddedValueByExactProbe(Evita evita) {
			assertEqualityLookupsFind(
				evita, GLOBAL_LOCAL_PRICE_ENTITY, Locale.ENGLISH, GLOBAL_LOCAL_PRICE, PADDED_VALUE, WRITTEN_PADDED,
				true
			);
		}

		@DisplayName("Should find an integral value by a padded probe")
		@UseDataSet(SCALE_LOOKUP)
		@Test
		void shouldFindIntegralValueByPaddedProbe(Evita evita) {
			assertEqualityLookupsFind(
				evita, GLOBAL_LOCAL_PRICE_ENTITY, Locale.ENGLISH, GLOBAL_LOCAL_PRICE, INTEGRAL_VALUE_PADDED,
				WRITTEN_INTEGRAL, true
			);
		}
	}

	/**
	 * Localized entity attribute unique across locales, held in the collection's standalone owner tree.
	 */
	@DisplayName("Localized attribute unique within the collection")
	@Nested
	class LocalizedCollectionUniqueAttribute {

		@DisplayName("Should find a value written without trailing zeros by the same spelling")
		@UseDataSet(SCALE_LOOKUP)
		@Test
		void shouldFindShortValueByExactProbe(Evita evita) {
			assertEqualityLookupsFind(
				evita, OWNER_ENTITY, Locale.ENGLISH, LOCALIZED_PRICE, SHORT_VALUE, WRITTEN_SHORT, false
			);
		}

		@DisplayName("Should find a value written without trailing zeros by a padded probe")
		@UseDataSet(SCALE_LOOKUP)
		@Test
		void shouldFindShortValueByPaddedProbe(Evita evita) {
			assertEqualityLookupsFind(
				evita, OWNER_ENTITY, Locale.ENGLISH, LOCALIZED_PRICE, SHORT_VALUE_PADDED, WRITTEN_SHORT, false
			);
		}

		@DisplayName("Should find a value written with trailing zeros by the same spelling")
		@UseDataSet(SCALE_LOOKUP)
		@Test
		void shouldFindPaddedValueByExactProbe(Evita evita) {
			assertEqualityLookupsFind(
				evita, OWNER_ENTITY, Locale.ENGLISH, LOCALIZED_PRICE, PADDED_VALUE, WRITTEN_PADDED, false
			);
		}

		@DisplayName("Should find an integral value by a padded probe")
		@UseDataSet(SCALE_LOOKUP)
		@Test
		void shouldFindIntegralValueByPaddedProbe(Evita evita) {
			assertEqualityLookupsFind(
				evita, OWNER_ENTITY, Locale.ENGLISH, LOCALIZED_PRICE, INTEGRAL_VALUE_PADDED, WRITTEN_INTEGRAL, false
			);
		}
	}

	/**
	 * Non-localized entity attribute, its values held in the shared filter tree.
	 */
	@DisplayName("Non-localized attribute unique within the collection")
	@Nested
	class FoldedCollectionUniqueAttribute {

		@DisplayName("Should find a value written without trailing zeros by a padded probe")
		@UseDataSet(SCALE_LOOKUP)
		@Test
		void shouldFindShortValueByPaddedProbe(Evita evita) {
			assertEqualityLookupsFind(evita, OWNER_ENTITY, null, FOLDED_PRICE, SHORT_VALUE_PADDED, WRITTEN_SHORT, false);
		}

		@DisplayName("Should find a value written with trailing zeros by the same spelling")
		@UseDataSet(SCALE_LOOKUP)
		@Test
		void shouldFindPaddedValueByExactProbe(Evita evita) {
			assertEqualityLookupsFind(evita, OWNER_ENTITY, null, FOLDED_PRICE, PADDED_VALUE, WRITTEN_PADDED, false);
		}

		@DisplayName("Should find an integral value by a padded probe")
		@UseDataSet(SCALE_LOOKUP)
		@Test
		void shouldFindIntegralValueByPaddedProbe(Evita evita) {
			assertEqualityLookupsFind(
				evita, OWNER_ENTITY, null, FOLDED_PRICE, INTEGRAL_VALUE_PADDED, WRITTEN_INTEGRAL, false
			);
		}
	}

	/**
	 * Every unique structure keys a numerically equal value once, whatever its scale - `1.50` collides with `1.5`.
	 */
	@DisplayName("Should refuse a numerically equal value at another scale in every unique structure")
	@UseDataSet(value = SCALE_UNIQUENESS, destroyAfterTest = true)
	@Test
	void shouldRefuseNumericallyEqualValueAtAnotherScale(Evita evita) {
		assertAll(
			refusesPaddedTwin(
				evita, GLOBAL_PRICE_ENTITY, it -> it.setAttribute(GLOBAL_PRICE, SHORT_VALUE_PADDED),
				"globally unique attribute"
			),
			refusesPaddedTwin(
				evita, GLOBAL_LOCAL_PRICE_ENTITY,
				it -> it.setAttribute(GLOBAL_LOCAL_PRICE, Locale.ENGLISH, SHORT_VALUE_PADDED),
				"globally unique attribute within locale"
			),
			refusesPaddedTwin(
				evita, OWNER_ENTITY, it -> it.setAttribute(LOCALIZED_PRICE, Locale.ENGLISH, SHORT_VALUE_PADDED),
				"localized attribute unique within the collection"
			),
			refusesPaddedTwin(
				evita, OWNER_ENTITY, it -> it.setAttribute(FOLDED_PRICE, SHORT_VALUE_PADDED),
				"non-localized attribute unique within the collection"
			)
		);
	}

}
