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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeBetween;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeGreaterThan;
import static io.evitadb.api.query.QueryConstraints.attributeLessThan;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that a temporal value beyond the window of moments expressible as epoch milliseconds - the JDK sentinels
 * `OffsetDateTime.MAX` / `MIN` and `LocalDateTime.MAX` / `MIN` - is clamped to the edge of that window as it enters,
 * and that the clamped value then behaves like any other value in every index it reaches: the filter index (equality
 * and range lookups), the sort index and the unique index, before and after the catalog is reloaded from disk.
 *
 * The temporal index keys are epoch milliseconds in a single `long`, so a sentinel that was not clamped could not be
 * indexed at all - the key encoding would overflow. The ordinary year-9999 moment next to the sentinel proves the
 * clamped value is not mistaken for, nor swallows, a realistic far-future value.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Temporal attributes beyond the supported window are clamped and indexed")
@Tag(ENGINE)
@Tag(STORAGE)
@Tag(ATTRIBUTE)
@Tag(FILTER)
@Tag(ORDER)
@Tag(DATA_TYPE)
class TemporalAttributeSupportedWindowFunctionalTest implements EvitaTestSupport {
	private static final String ENTITY_PRODUCT = "product";
	/** A unique (and therefore filterable) and sortable `OffsetDateTime` attribute. */
	private static final String ATTR_VALID_UNTIL = "validUntil";
	/** A filterable and sortable `LocalDateTime` attribute. */
	private static final String ATTR_LOCAL_VALID_UNTIL = "localValidUntil";
	private static final int PK_ORDINARY = 1;
	private static final int PK_MAX = 2;
	private static final int PK_YEAR_9999 = 3;
	private static final int PK_MIN = 4;
	private static final OffsetDateTime ORDINARY_MOMENT = OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	/** A realistic far-future moment that must stay distinct from the clamped sentinel. */
	private static final OffsetDateTime YEAR_9999_MOMENT = OffsetDateTime.of(9999, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	/** The last whole millisecond evitaDB can hold, written out independently of the production constants. */
	private static final Instant LATEST_SUPPORTED_INSTANT =
		Instant.ofEpochMilli((Long.MAX_VALUE / 1_000L - 1L) * 1_000L + 999L);

	private TestPaths paths;
	private Evita evita;

	/**
	 * Defines the schema and writes the four products - an ordinary moment, the upper sentinel, a year-9999 moment and
	 * the lower sentinel - in both temporal types.
	 *
	 * @param session the session the catalog is set up through
	 */
	private static void defineAndSeed(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withAttribute(
				// unique implies filterable - the attribute is indexed in the unique and the filter index alike
				ATTR_VALID_UNTIL, OffsetDateTime.class, whichIs -> whichIs.unique().sortable().nullable()
			)
			.withAttribute(
				ATTR_LOCAL_VALID_UNTIL, LocalDateTime.class, whichIs -> whichIs.filterable().sortable().nullable()
			)
			.updateVia(session);
		createProduct(session, PK_ORDINARY, ORDINARY_MOMENT, ORDINARY_MOMENT.toLocalDateTime());
		createProduct(session, PK_MAX, OffsetDateTime.MAX, LocalDateTime.MAX);
		createProduct(session, PK_YEAR_9999, YEAR_9999_MOMENT, YEAR_9999_MOMENT.toLocalDateTime());
		createProduct(session, PK_MIN, OffsetDateTime.MIN, LocalDateTime.MIN);
	}

	/**
	 * Creates a single product carrying both temporal attributes.
	 *
	 * @param session    the session the write runs in
	 * @param pk         the primary key of the product
	 * @param validUntil the value of the `OffsetDateTime` attribute
	 * @param localValue the value of the `LocalDateTime` attribute
	 */
	private static void createProduct(
		@Nonnull EvitaSessionContract session,
		int pk,
		@Nonnull OffsetDateTime validUntil,
		@Nonnull LocalDateTime localValue
	) {
		session.createNewEntity(ENTITY_PRODUCT, pk)
			.setAttribute(ATTR_VALID_UNTIL, validUntil)
			.setAttribute(ATTR_LOCAL_VALID_UNTIL, localValue)
			.upsertVia(session);
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("temporalAttributeSupportedWindow");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	@AfterEach
	void tearDown() {
		try {
			if (this.evita != null && this.evita.isActive()) {
				this.evita.close();
			}
		} finally {
			cleanupTestPaths(this.paths);
		}
	}

	@Test
	@DisplayName("should index a clamped sentinel in filter, sort and unique indexes and keep it across a restart")
	void shouldIndexClampedSentinelAndKeepItAcrossRestart() {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, TemporalAttributeSupportedWindowFunctionalTest::defineAndSeed);
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);

		assertAll(storedValuesAreClamped());
		assertAll(indexesAnswer());

		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		// the catalog is loaded in the background - its load is what reads the clamped index keys back
		this.evita.waitUntilFullyInitialized();

		assertAll(
			Stream.concat(Arrays.stream(storedValuesAreClamped()), Arrays.stream(indexesAnswer()))
				.toArray(Executable[]::new)
		);
	}

	/**
	 * Returns checks that the sentinels were stored as the edges of the supported window, the offset of an
	 * `OffsetDateTime` kept and a `LocalDateTime` read as the wall clock at UTC.
	 *
	 * @return the checks
	 */
	@Nonnull
	private Executable[] storedValuesAreClamped() {
		return new Executable[]{
			() -> assertEquals(
				OffsetDateTime.ofInstant(LATEST_SUPPORTED_INSTANT, ZoneOffset.MIN),
				readAttribute(PK_MAX, ATTR_VALID_UNTIL)
			),
			() -> assertEquals(
				LocalDateTime.ofInstant(LATEST_SUPPORTED_INSTANT, ZoneOffset.UTC),
				readAttribute(PK_MAX, ATTR_LOCAL_VALID_UNTIL)
			),
			() -> assertEquals(YEAR_9999_MOMENT, readAttribute(PK_YEAR_9999, ATTR_VALID_UNTIL))
		};
	}

	/**
	 * Returns checks that equality lookups, range lookups and sorting all treat the clamped sentinels as the extreme
	 * values they stand for, distinct from the ordinary year-9999 moment.
	 *
	 * @return the checks
	 */
	@Nonnull
	private Executable[] indexesAnswer() {
		return new Executable[]{
			// equality: the sentinel itself, and any other moment beyond the window, lands on the clamped key
			() -> assertEquals(List.of(PK_MAX), filtered(attributeEquals(ATTR_VALID_UNTIL, OffsetDateTime.MAX))),
			() -> assertEquals(
				List.of(PK_MAX), filtered(attributeEquals(ATTR_VALID_UNTIL, LocalDateTime.MAX.atOffset(ZoneOffset.UTC)))
			),
			() -> assertEquals(List.of(PK_MIN), filtered(attributeEquals(ATTR_VALID_UNTIL, OffsetDateTime.MIN))),
			() -> assertEquals(
				List.of(PK_MAX), filtered(attributeEquals(ATTR_LOCAL_VALID_UNTIL, LocalDateTime.MAX))
			),
			// range lookups: the year-9999 moment is neither swallowed by nor mistaken for the sentinel
			() -> assertEquals(
				List.of(PK_MAX), filtered(attributeGreaterThan(ATTR_VALID_UNTIL, YEAR_9999_MOMENT.plusMonths(6L)))
			),
			() -> assertEquals(
				List.of(PK_MAX, PK_YEAR_9999),
				filtered(attributeBetween(ATTR_VALID_UNTIL, ORDINARY_MOMENT.plusDays(1L), OffsetDateTime.MAX))
			),
			() -> assertEquals(
				List.of(PK_ORDINARY, PK_YEAR_9999, PK_MIN),
				filtered(attributeLessThan(ATTR_VALID_UNTIL, OffsetDateTime.MAX))
			),
			// sorting: the sentinels sort at the extremes
			() -> assertEquals(
				List.of(PK_MIN, PK_ORDINARY, PK_YEAR_9999, PK_MAX), ordered(attributeNatural(ATTR_VALID_UNTIL))
			),
			() -> assertEquals(
				List.of(PK_MIN, PK_ORDINARY, PK_YEAR_9999, PK_MAX), ordered(attributeNatural(ATTR_LOCAL_VALID_UNTIL))
			)
		};
	}

	/**
	 * Returns the primary keys of the products matched by the filter, in ascending order.
	 *
	 * @param filter the filter constraint
	 * @return the matched primary keys
	 */
	@Nonnull
	private List<Integer> filtered(@Nonnull FilterConstraint filter) {
		return queryPrimaryKeys(filter, null);
	}

	/**
	 * Returns the primary keys of all products in the order the ordering constraint sorts them.
	 *
	 * @param order the ordering constraint
	 * @return the sorted primary keys
	 */
	@Nonnull
	private List<Integer> ordered(@Nonnull OrderConstraint order) {
		return queryPrimaryKeys(null, order);
	}

	/**
	 * Runs a query over the products and returns the primary keys it found, in the order it returned them.
	 *
	 * @param filter the filter constraint, or `null` for all products
	 * @param order  the ordering constraint, or `null` for the default primary-key order
	 * @return the primary keys found
	 */
	@Nonnull
	private List<Integer> queryPrimaryKeys(@Nullable FilterConstraint filter, @Nullable OrderConstraint order) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, List<Integer>>) session -> {
				final List<EntityReference> references = session.query(
					query(
						collection(ENTITY_PRODUCT),
						filter == null ? null : filterBy(filter),
						order == null ? null : orderBy(order),
						require(page(1, Integer.MAX_VALUE))
					),
					EntityReference.class
				).getRecordData();
				final List<Integer> primaryKeys = new ArrayList<>(references.size());
				for (final EntityReference reference : references) {
					primaryKeys.add(reference.getPrimaryKey());
				}
				return primaryKeys;
			}
		);
	}

	/**
	 * Reads an attribute of a product back from the catalog.
	 *
	 * @param pk            the primary key of the product
	 * @param attributeName the attribute to read
	 * @return the stored value
	 */
	@Nullable
	private Serializable readAttribute(int pk, @Nonnull String attributeName) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, Serializable>) session -> {
				final SealedEntity entity = session.getEntity(ENTITY_PRODUCT, pk, attributeContentAll())
					.orElseThrow(() -> new AssertionError("Product `" + pk + "` was not found!"));
				return entity.getAttribute(attributeName);
			}
		);
	}

}
