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
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeInRange;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end coverage for the millisecond precision guarantee: evitaDB cuts every temporal value to whole
 * milliseconds as it enters the database — on the write path and on the query path alike — so that a stored value
 * and a probe derived from the same instant still meet.
 *
 * The two halves have to be pinned separately, because either one on its own produces a green-looking suite:
 *
 * - dropping the **write**-side truncation leaves stored and probed values both nano-precise, so an equality
 *   filter written with the very same value still matches — which is why every scenario also reads the attribute
 *   back and compares it against the exact truncated moment;
 * - dropping the **query**-side truncation leaves a nano-precise probe hunting a truncated stored value, which no
 *   filter can match — which is why every scenario also filters with the *original* nano-precise value.
 *
 * The strongest assertion here is the collapsing one: two entities whose moments differ only below the
 * millisecond become indistinguishable to the index, and a third, different sub-millisecond probe matches both.
 * Without truncation that probe matches neither.
 *
 * `LocalDate` and `DateTimeRange` ride along as confirming negatives — neither carries a sub-millisecond component
 * evitaDB truncates on the way in. A `DateTimeRange` keeps its two bounds exactly as they were written, because it
 * derives its own comparison longs as whole epoch milliseconds and the sub-millisecond tail therefore changes
 * nothing the index can see. That the tail is invisible rather than merely unread is what
 * {@link #shouldMatchAValidityRangeAtTheMillisecondBoundary()} pins, driving the whole chain — input truncation,
 * the range column's key, the range index threshold and the `attributeInRange` probe — at the boundary that moving
 * from seconds to milliseconds actually shifted.
 *
 * **All scenarios share one embedded instance**, which is where nearly all the wall time of a test like this goes.
 * Isolation is preserved by construction rather than by teardown: every scenario owns its own attribute and its own
 * block of primary keys, so no filter can reach another scenario's entities and the methods stay independent in any
 * order.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Temporal attributes are stored and matched at millisecond precision")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag(ENGINE)
@Tag(ATTRIBUTE)
@Tag(FILTER)
@Tag(DATA_TYPE)
public class TemporalAttributeMillisecondPrecisionFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	/** The `OffsetDateTime` attribute of the round-trip scenario. */
	private static final String ATTR_MOMENT = "validity";
	/** The `OffsetDateTime` attribute of the sub-millisecond collapse scenario, which must see nobody else's writes. */
	private static final String ATTR_MOMENT_TWINS = "validityTwins";
	private static final String ATTR_LOCAL_MOMENT = "publishedAt";
	private static final String ATTR_TIME = "openedAt";
	private static final String ATTR_DAY = "publishedDay";
	/** The `DateTimeRange` attribute whose stored bounds are read back verbatim. */
	private static final String ATTR_RANGE = "validityRange";
	/** The `DateTimeRange` attribute the `attributeInRange` boundary scenario probes. */
	private static final String ATTR_RANGE_BOUNDARY = "validityRangeBoundary";

	/**
	 * Three moments sharing the same 123rd millisecond and differing only below it. They exist so the test can
	 * write two of them, probe with the third, and still match both — an outcome only truncation can produce.
	 */
	private static final OffsetDateTime MOMENT_LOW =
		OffsetDateTime.of(2026, 5, 20, 12, 19, 26, 123_000_001, ZoneOffset.UTC);
	private static final OffsetDateTime MOMENT_HIGH =
		OffsetDateTime.of(2026, 5, 20, 12, 19, 26, 123_999_999, ZoneOffset.UTC);
	private static final OffsetDateTime MOMENT_PROBE =
		OffsetDateTime.of(2026, 5, 20, 12, 19, 26, 123_456_789, ZoneOffset.UTC);
	private static final OffsetDateTime MOMENT_TRUNCATED =
		OffsetDateTime.of(2026, 5, 20, 12, 19, 26, 123_000_000, ZoneOffset.UTC);
	/**
	 * A moment one whole millisecond later — the nearest value that must NOT collapse onto {@link #MOMENT_TRUNCATED}.
	 */
	private static final OffsetDateTime MOMENT_NEXT_MILLISECOND =
		OffsetDateTime.of(2026, 5, 20, 12, 19, 26, 124_000_000, ZoneOffset.UTC);

	private static final LocalDateTime LOCAL_MOMENT_NANOS =
		LocalDateTime.of(2026, 5, 20, 12, 19, 26, 123_456_789);
	private static final LocalDateTime LOCAL_MOMENT_TRUNCATED =
		LocalDateTime.of(2026, 5, 20, 12, 19, 26, 123_000_000);
	private static final LocalTime TIME_NANOS = LocalTime.of(12, 19, 26, 123_456_789);
	private static final LocalTime TIME_TRUNCATED = LocalTime.of(12, 19, 26, 123_000_000);
	private static final LocalDate DAY = LocalDate.of(2026, 5, 20);

	/** The lower bound of the validity range the boundary scenario indexes, on a whole millisecond. */
	private static final OffsetDateTime RANGE_START =
		OffsetDateTime.of(2026, 5, 20, 12, 19, 26, 123_000_000, ZoneOffset.UTC);

	private TestPaths paths;
	private Evita evita;

	/**
	 * Declares one filterable attribute per scenario, so that a filter written by one can never reach another's
	 * entities. The two `OffsetDateTime` and the two `DateTimeRange` scenarios each get their own attribute for
	 * exactly that reason — their values would otherwise collapse onto one indexed key and their expected result
	 * sets would depend on execution order.
	 *
	 * @param session the session the schema is defined through
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(ATTR_MOMENT, OffsetDateTime.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTR_MOMENT_TWINS, OffsetDateTime.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTR_LOCAL_MOMENT, LocalDateTime.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTR_TIME, LocalTime.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTR_DAY, LocalDate.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTR_RANGE, DateTimeRange.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTR_RANGE_BOUNDARY, DateTimeRange.class, whichIs -> whichIs.filterable().nullable())
			.updateVia(session);
	}

	/**
	 * Creates a single product carrying exactly one temporal attribute.
	 *
	 * @param session       the session the write runs in
	 * @param pk            the primary key, taken from the calling scenario's own block
	 * @param attributeName the attribute to set
	 * @param value         the value to store
	 */
	private static void createProduct(
		@Nonnull EvitaSessionContract session,
		int pk,
		@Nonnull String attributeName,
		@Nonnull Serializable value
	) {
		session.createNewEntity(ENTITY_PRODUCT, pk)
			.setAttribute(attributeName, value)
			.upsertVia(session);
	}

	/**
	 * Returns the primary keys matched by an equality filter on the supplied attribute.
	 *
	 * @param session       the session the query runs in
	 * @param attributeName the attribute to filter on
	 * @param value         the value to compare against
	 * @return the matched primary keys, in the engine's order
	 */
	@Nonnull
	private static List<Integer> primaryKeysMatching(
		@Nonnull EvitaSessionContract session,
		@Nonnull String attributeName,
		@Nonnull Serializable value
	) {
		return primaryKeysMatching(session, attributeEquals(attributeName, value));
	}

	/**
	 * Returns the primary keys matched by an arbitrary filter constraint.
	 *
	 * @param session the session the query runs in
	 * @param filter  the constraint to apply
	 * @return the matched primary keys, in the engine's order
	 */
	@Nonnull
	private static List<Integer> primaryKeysMatching(
		@Nonnull EvitaSessionContract session,
		@Nonnull FilterConstraint filter
	) {
		final EvitaResponse<EntityReference> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(filter),
				require(
					page(1, Integer.MAX_VALUE)
				)
			),
			EntityReference.class
		);
		final List<Integer> primaryKeys = new ArrayList<>(result.getRecordData().size());
		for (final EntityReference reference : result.getRecordData()) {
			primaryKeys.add(reference.getPrimaryKey());
		}
		return primaryKeys;
	}

	/**
	 * Reads the named attribute of a seeded product back from the engine.
	 *
	 * @param session       the session the read runs in
	 * @param pk            the primary key of the product
	 * @param attributeName the attribute to read
	 * @return the stored value
	 */
	@Nonnull
	private static Serializable readAttribute(
		@Nonnull EvitaSessionContract session,
		int pk,
		@Nonnull String attributeName
	) {
		final SealedEntity entity = session.getEntity(ENTITY_PRODUCT, pk, entityFetchAllContent())
			.orElseThrow(() -> new AssertionError("Product with primary key `" + pk + "` was not found!"));
		final Serializable value = entity.getAttribute(attributeName);
		if (value == null) {
			throw new AssertionError("Attribute `" + attributeName + "` of product `" + pk + "` was not found!");
		}
		return value;
	}

	/**
	 * Builds the standard test configuration with a disabled session inactivity timeout and per-class directories.
	 *
	 * @param paths the per-class directories the instance is bound to
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration createConfiguration(@Nonnull TestPaths paths) {
		return newTestEvitaConfigurationBuilder(paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * Spins up the one embedded instance every scenario shares and defines the schema on it. The catalog is left in
	 * warm-up mode, so a single session can both write and read.
	 */
	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("temporalMillisecondPrecision");
		this.evita = new Evita(createConfiguration(this.paths));
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG, TemporalAttributeMillisecondPrecisionFunctionalTest::defineSchema
		);
	}

	/**
	 * Closes the shared instance and removes the directories it was bound to.
	 */
	@AfterAll
	void tearDown() {
		try {
			if (this.evita != null) {
				this.evita.close();
			}
		} finally {
			if (this.paths != null) {
				cleanupTestPaths(this.paths);
			}
		}
	}

	/**
	 * Hands an open read-write session on the shared catalog to the caller.
	 *
	 * @param scenario the scenario to run
	 */
	private void runScenario(@Nonnull Consumer<EvitaSessionContract> scenario) {
		this.evita.updateCatalog(TEST_CATALOG, scenario);
	}

	@Test
	@DisplayName("should store an OffsetDateTime cut to the millisecond and still match a nano-precise probe")
	void shouldStoreAndMatchOffsetDateTimeAtMillisecondPrecision() {
		runScenario(
			session -> {
				createProduct(session, 1, ATTR_MOMENT, MOMENT_PROBE);

				// the write half: the sub-millisecond digits are gone from the stored value
				assertEquals(MOMENT_TRUNCATED, readAttribute(session, 1, ATTR_MOMENT));
				assertNotEquals(MOMENT_PROBE, readAttribute(session, 1, ATTR_MOMENT));

				// the query half: the original nano-precise value still finds it
				assertEquals(List.of(1), primaryKeysMatching(session, ATTR_MOMENT, MOMENT_PROBE));
				assertEquals(List.of(1), primaryKeysMatching(session, ATTR_MOMENT, MOMENT_TRUNCATED));

				// and the next whole millisecond must not collapse onto it
				assertEquals(List.of(), primaryKeysMatching(session, ATTR_MOMENT, MOMENT_NEXT_MILLISECOND));
			}
		);
	}

	@Test
	@DisplayName("should collapse two moments that differ only below the millisecond onto one indexed value")
	void shouldCollapseSubMillisecondDistinctMoments() {
		runScenario(
			session -> {
				createProduct(session, 11, ATTR_MOMENT_TWINS, MOMENT_LOW);
				createProduct(session, 12, ATTR_MOMENT_TWINS, MOMENT_HIGH);

				// both entities were written with different nanoseconds, yet both hold the same moment now
				assertEquals(MOMENT_TRUNCATED, readAttribute(session, 11, ATTR_MOMENT_TWINS));
				assertEquals(MOMENT_TRUNCATED, readAttribute(session, 12, ATTR_MOMENT_TWINS));

				// a third, again different, sub-millisecond probe finds both of them
				assertEquals(List.of(11, 12), primaryKeysMatching(session, ATTR_MOMENT_TWINS, MOMENT_PROBE));
			}
		);
	}

	@Test
	@DisplayName("should store a LocalDateTime cut to the millisecond and still match a nano-precise probe")
	void shouldStoreAndMatchLocalDateTimeAtMillisecondPrecision() {
		runScenario(
			session -> {
				createProduct(session, 21, ATTR_LOCAL_MOMENT, LOCAL_MOMENT_NANOS);

				assertEquals(LOCAL_MOMENT_TRUNCATED, readAttribute(session, 21, ATTR_LOCAL_MOMENT));
				assertNotEquals(LOCAL_MOMENT_NANOS, readAttribute(session, 21, ATTR_LOCAL_MOMENT));

				assertEquals(List.of(21), primaryKeysMatching(session, ATTR_LOCAL_MOMENT, LOCAL_MOMENT_NANOS));
				assertEquals(List.of(21), primaryKeysMatching(session, ATTR_LOCAL_MOMENT, LOCAL_MOMENT_TRUNCATED));
			}
		);
	}

	@Test
	@DisplayName("should store a LocalTime cut to the millisecond and still match a nano-precise probe")
	void shouldStoreAndMatchLocalTimeAtMillisecondPrecision() {
		runScenario(
			session -> {
				createProduct(session, 31, ATTR_TIME, TIME_NANOS);

				assertEquals(TIME_TRUNCATED, readAttribute(session, 31, ATTR_TIME));
				assertNotEquals(TIME_NANOS, readAttribute(session, 31, ATTR_TIME));

				assertEquals(List.of(31), primaryKeysMatching(session, ATTR_TIME, TIME_NANOS));
				assertEquals(List.of(31), primaryKeysMatching(session, ATTR_TIME, TIME_TRUNCATED));
			}
		);
	}

	@Test
	@DisplayName("should leave a LocalDate and a DateTimeRange untouched")
	void shouldLeaveDateAndRangeUntouched() {
		runScenario(
			session -> {
				final DateTimeRange range = DateTimeRange.between(MOMENT_PROBE, MOMENT_PROBE.plusDays(1));
				createProduct(session, 41, ATTR_DAY, DAY);
				createProduct(session, 42, ATTR_RANGE, range);

				assertEquals(DAY, readAttribute(session, 41, ATTR_DAY));
				// a DateTimeRange keeps its bounds exactly as written: it derives its comparison longs as whole
				// epoch milliseconds itself, so the sub-millisecond tail changes nothing the index can see and
				// there is nothing for the input truncation to fix
				assertEquals(range, readAttribute(session, 42, ATTR_RANGE));
				assertEquals(
					123_456_789,
					((DateTimeRange) readAttribute(session, 42, ATTR_RANGE)).getPreciseFrom().getNano()
				);
			}
		);
	}

	@Test
	@DisplayName("should match a validity range at the millisecond boundary through attributeInRange")
	void shouldMatchAValidityRangeAtTheMillisecondBoundary() {
		runScenario(
			session -> {
				// the whole chain, at the boundary the move from seconds to milliseconds actually shifted: input
				// truncation, the range column's key, the range index threshold and the `attributeInRange` probe.
				// Every other scenario here stops at `attributeEquals` over a scalar
				final DateTimeRange validity = DateTimeRange.between(RANGE_START, RANGE_START.plusDays(1));
				createProduct(session, 51, ATTR_RANGE_BOUNDARY, validity);

				assertEquals(
					List.of(51),
					primaryKeysMatching(session, attributeInRange(ATTR_RANGE_BOUNDARY, RANGE_START)),
					"the lower bound itself is inside the validity"
				);
				assertEquals(
					List.of(51),
					primaryKeysMatching(
						session, attributeInRange(ATTR_RANGE_BOUNDARY, RANGE_START.plusNanos(999_999L))
					),
					"a probe still inside the lower bound's own millisecond must match too"
				);
				assertEquals(
					List.of(),
					primaryKeysMatching(
						session, attributeInRange(ATTR_RANGE_BOUNDARY, RANGE_START.minusNanos(1_000_000L))
					),
					"one whole millisecond earlier is outside, so the boundary is not simply matching everything"
				);
				assertEquals(
					List.of(51),
					primaryKeysMatching(
						session, attributeInRange(ATTR_RANGE_BOUNDARY, RANGE_START.plusDays(1))
					),
					"the upper bound is inclusive"
				);
				assertEquals(
					List.of(),
					primaryKeysMatching(
						session, attributeInRange(ATTR_RANGE_BOUNDARY, RANGE_START.plusDays(1).plusNanos(1_000_000L))
					),
					"one whole millisecond past the upper bound is outside"
				);
			}
		);
	}

	@Test
	@DisplayName("should refuse a moment evitaDB cannot represent as epoch milliseconds")
	void shouldRefuseUnrepresentableMoment() {
		runScenario(
			session -> {
				assertThrows(
					EvitaInvalidUsageException.class,
					() -> createProduct(session, 61, ATTR_MOMENT, OffsetDateTime.MAX)
				);
				assertThrows(
					EvitaInvalidUsageException.class,
					() -> createProduct(session, 62, ATTR_LOCAL_MOMENT, LocalDateTime.MIN)
				);
			}
		);
	}
}
