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
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
 * `LocalDate` and `DateTimeRange` ride along as confirming negatives — neither carries a sub-millisecond
 * component evitaDB acts on, and `DateTimeRange` deliberately keeps the sub-millisecond digits of its bounds
 * because it compares at second granularity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Temporal attributes are stored and matched at millisecond precision")
@Tag(ENGINE)
@Tag(ATTRIBUTE)
@Tag(FILTER)
@Tag(DATA_TYPE)
public class TemporalAttributeMillisecondPrecisionFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ATTR_MOMENT = "validity";
	private static final String ATTR_LOCAL_MOMENT = "publishedAt";
	private static final String ATTR_TIME = "openedAt";
	private static final String ATTR_DAY = "publishedDay";
	private static final String ATTR_RANGE = "validityRange";

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

	/**
	 * Declares one filterable attribute per temporal data type evitaDB supports.
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(ATTR_MOMENT, OffsetDateTime.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTR_LOCAL_MOMENT, LocalDateTime.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTR_TIME, LocalTime.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTR_DAY, LocalDate.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTR_RANGE, DateTimeRange.class, whichIs -> whichIs.filterable().nullable())
			.updateVia(session);
	}

	/**
	 * Creates a single product carrying exactly one temporal attribute.
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
	 */
	@Nonnull
	private static List<Integer> primaryKeysMatching(
		@Nonnull EvitaSessionContract session,
		@Nonnull String attributeName,
		@Nonnull Serializable value
	) {
		final EvitaResponse<EntityReference> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(
					attributeEquals(attributeName, value)
				),
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
	 * Builds the standard test configuration with a disabled session inactivity timeout and per-test directories.
	 */
	@Nonnull
	private EvitaConfiguration createConfiguration(@Nonnull TestPaths paths) {
		return newTestEvitaConfigurationBuilder(paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * Spins up a fresh Evita instance bound to per-test directories, hands an open read-write session to the
	 * caller and tears the instance down afterwards. The catalog is left in warm-up mode so a single session can
	 * both write and read.
	 */
	private void runWithCatalog(@Nonnull String storageSuffix, @Nonnull Consumer<EvitaSessionContract> scenario) {
		final TestPaths paths = createTestPaths(storageSuffix);
		try (
			Evita evita = new Evita(createConfiguration(paths))
		) {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, scenario);
		} finally {
			cleanupTestPaths(paths);
		}
	}

	@Test
	@DisplayName("should store an OffsetDateTime cut to the millisecond and still match a nano-precise probe")
	void shouldStoreAndMatchOffsetDateTimeAtMillisecondPrecision() {
		runWithCatalog(
			"temporalMillisOffsetDateTime",
			session -> {
				defineSchema(session);
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
		runWithCatalog(
			"temporalMillisCollapse",
			session -> {
				defineSchema(session);
				createProduct(session, 1, ATTR_MOMENT, MOMENT_LOW);
				createProduct(session, 2, ATTR_MOMENT, MOMENT_HIGH);

				// both entities were written with different nanoseconds, yet both hold the same moment now
				assertEquals(MOMENT_TRUNCATED, readAttribute(session, 1, ATTR_MOMENT));
				assertEquals(MOMENT_TRUNCATED, readAttribute(session, 2, ATTR_MOMENT));

				// a third, again different, sub-millisecond probe finds both of them
				assertEquals(List.of(1, 2), primaryKeysMatching(session, ATTR_MOMENT, MOMENT_PROBE));
			}
		);
	}

	@Test
	@DisplayName("should store a LocalDateTime cut to the millisecond and still match a nano-precise probe")
	void shouldStoreAndMatchLocalDateTimeAtMillisecondPrecision() {
		runWithCatalog(
			"temporalMillisLocalDateTime",
			session -> {
				defineSchema(session);
				createProduct(session, 1, ATTR_LOCAL_MOMENT, LOCAL_MOMENT_NANOS);

				assertEquals(LOCAL_MOMENT_TRUNCATED, readAttribute(session, 1, ATTR_LOCAL_MOMENT));
				assertNotEquals(LOCAL_MOMENT_NANOS, readAttribute(session, 1, ATTR_LOCAL_MOMENT));

				assertEquals(List.of(1), primaryKeysMatching(session, ATTR_LOCAL_MOMENT, LOCAL_MOMENT_NANOS));
				assertEquals(List.of(1), primaryKeysMatching(session, ATTR_LOCAL_MOMENT, LOCAL_MOMENT_TRUNCATED));
			}
		);
	}

	@Test
	@DisplayName("should store a LocalTime cut to the millisecond and still match a nano-precise probe")
	void shouldStoreAndMatchLocalTimeAtMillisecondPrecision() {
		runWithCatalog(
			"temporalMillisLocalTime",
			session -> {
				defineSchema(session);
				createProduct(session, 1, ATTR_TIME, TIME_NANOS);

				assertEquals(TIME_TRUNCATED, readAttribute(session, 1, ATTR_TIME));
				assertNotEquals(TIME_NANOS, readAttribute(session, 1, ATTR_TIME));

				assertEquals(List.of(1), primaryKeysMatching(session, ATTR_TIME, TIME_NANOS));
				assertEquals(List.of(1), primaryKeysMatching(session, ATTR_TIME, TIME_TRUNCATED));
			}
		);
	}

	@Test
	@DisplayName("should leave a LocalDate and a DateTimeRange untouched")
	void shouldLeaveDateAndRangeUntouched() {
		runWithCatalog(
			"temporalMillisUntouched",
			session -> {
				defineSchema(session);
				final DateTimeRange range = DateTimeRange.between(MOMENT_PROBE, MOMENT_PROBE.plusDays(1));
				createProduct(session, 1, ATTR_DAY, DAY);
				createProduct(session, 2, ATTR_RANGE, range);

				assertEquals(DAY, readAttribute(session, 1, ATTR_DAY));
				// a DateTimeRange compares at second granularity, so its bounds deliberately keep their nanos
				assertEquals(range, readAttribute(session, 2, ATTR_RANGE));
				assertEquals(
					123_456_789,
					((DateTimeRange) readAttribute(session, 2, ATTR_RANGE)).getPreciseFrom().getNano()
				);
			}
		);
	}

	@Test
	@DisplayName("should refuse a moment evitaDB cannot represent as epoch milliseconds")
	void shouldRefuseUnrepresentableMoment() {
		runWithCatalog(
			"temporalMillisOutOfRange",
			session -> {
				defineSchema(session);

				assertThrows(
					EvitaInvalidUsageException.class,
					() -> createProduct(session, 1, ATTR_MOMENT, OffsetDateTime.MAX)
				);
				assertThrows(
					EvitaInvalidUsageException.class,
					() -> createProduct(session, 2, ATTR_LOCAL_MOMENT, LocalDateTime.MIN)
				);
			}
		);
	}
}
