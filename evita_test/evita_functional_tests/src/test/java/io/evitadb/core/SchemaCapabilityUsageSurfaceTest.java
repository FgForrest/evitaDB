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

package io.evitadb.core;

import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.statistics.SchemaCapabilityUsageSnapshot;
import io.evitadb.api.statistics.SchemaCapabilityUsageSnapshot.Capability;
import io.evitadb.api.statistics.SchemaCapabilityUsageSnapshot.ElementKind;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the **diagnostic surface** the schema-capability counters are reported through - end to end, from a real
 * query and a real write down to the rows {@link io.evitadb.api.EvitaManagementContract#listCapabilityUsage} hands
 * back.
 *
 * The counting itself is pinned one level down, by `RequestedCapabilityAccumulationTest`,
 * `EntityIndexLocalMutationExecutorUsageTest` and the two registry tests, all of which read the engine-internal
 * registry directly. What only this class can prove is that the numbers **come out** - that the projection puts each
 * count on the row of the capability it belongs to, that a row names its owner, and that the two owners are reachable
 * through the one call that distinguishes them by entity type.
 *
 * The fixture is shaped so that **no row can pass by accident**: one attribute is only ever queried and one only ever
 * written, so a projection crossing the two counts, or reporting one capability's numbers against another's, produces
 * a failure rather than a plausible-looking table.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see SchemaCapabilityUsageSnapshot
 */
@DisplayName("Schema capability usage surface")
@Tag(ENGINE)
@Tag(MANAGEMENT)
@Tag(ATTRIBUTE)
class SchemaCapabilityUsageSurfaceTest implements EvitaTestSupport {
	private static final String CATALOG = "schemaCapabilityUsageSurfaceTest";
	private static final String ENTITY_PRODUCT = "product";
	/** Filterable, and **only ever queried** - the attribute whose update count must stay at zero. */
	private static final String ATTRIBUTE_EAN = "ean";
	/** Filterable, and **only ever written** - the attribute whose request count must stay at zero. */
	private static final String ATTRIBUTE_NAME = "name";
	/** Globally unique, so its capabilities are the catalog's rather than the collection's. */
	private static final String ATTRIBUTE_CODE = "code";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("SchemaCapabilityUsageSurfaceTest");
		this.evita = new Evita(getEvitaConfiguration());
		buildCatalog();
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("What a collection reports")
	class CollectionOwned {

		@Test
		@DisplayName("A queried attribute and a written one are told apart on their own rows")
		void shouldReportQueriedAndWrittenCapabilitiesSeparately() {
			writeNameOnly(1);
			filterByEan();

			final List<SchemaCapabilityUsageSnapshot> rows = SchemaCapabilityUsageSurfaceTest.this.evita
				.management()
				.listCapabilityUsage(CATALOG, ENTITY_PRODUCT);

			final SchemaCapabilityUsageSnapshot queried = rowOf(rows, ATTRIBUTE_EAN, Capability.FILTER);
			assertEquals(
				1L, queried.requestedCount(),
				"One query filtering by `" + ATTRIBUTE_EAN + "` must be reported as exactly one request - a larger " +
					"number means the surface is reporting candidate plans rather than logical queries"
			);
			assertEquals(
				0L, queried.updatedCount(),
				"Nothing ever wrote `" + ATTRIBUTE_EAN + "`, so a non-zero maintenance count here is another " +
					"capability's number reported against this row"
			);

			final SchemaCapabilityUsageSnapshot written = rowOf(rows, ATTRIBUTE_NAME, Capability.FILTER);
			assertEquals(
				0L, written.requestedCount(),
				"No query ever named `" + ATTRIBUTE_NAME + "`, so a non-zero request count is the queried " +
					"attribute's number landing on the wrong row"
			);
			assertTrue(
				written.updatedCount() >= 1L,
				"The upsert wrote `" + ATTRIBUTE_NAME + "`, so its maintenance has to be reported: " + written
			);
		}

		@Test
		@DisplayName("Every row carries the owner it belongs to and the element it describes")
		void shouldIdentifyTheOwnerAndTheElementOfEveryRow() {
			writeNameOnly(1);
			filterByEan();

			final List<SchemaCapabilityUsageSnapshot> rows = SchemaCapabilityUsageSurfaceTest.this.evita
				.management()
				.listCapabilityUsage(CATALOG, ENTITY_PRODUCT);

			assertTrue(!rows.isEmpty(), "A collection that has been queried and written reports no capability at all");
			for (final SchemaCapabilityUsageSnapshot row : rows) {
				assertEquals(
					ENTITY_PRODUCT, row.entityType(),
					"A row of a collection's own schema must name that collection, so that a client concatenating " +
						"several owners' rows can still tell them apart: " + row
				);
				assertEquals(ElementKind.ATTRIBUTE, row.elementKind(), row.toString());
				assertNull(row.containerName(), "The fixture declares no reference attributes: " + row);
				assertEquals(Scope.LIVE, row.scope(), row.toString());
				assertNotNull(
					row.observedSince(),
					"Without its observation window a zero count cannot be read as anything at all: " + row
				);
			}
		}

		@Test
		@DisplayName("The stamps say when, and only on the side that happened")
		void shouldStampOnlyTheSideThatHappened() {
			writeNameOnly(1);
			filterByEan();

			final List<SchemaCapabilityUsageSnapshot> rows = SchemaCapabilityUsageSurfaceTest.this.evita
				.management()
				.listCapabilityUsage(CATALOG, ENTITY_PRODUCT);

			// the two stamps are asserted where their own count is non-zero and nowhere else. A stamp is written after
			// the count it accompanies and is skipped while the resident value already falls in the current second, so
			// a present stamp is only ever claimed for a side that certainly recorded something
			final SchemaCapabilityUsageSnapshot queried = rowOf(rows, ATTRIBUTE_EAN, Capability.FILTER);
			assertNotNull(queried.lastRequestedAt(), "A requested capability arrived without its stamp: " + queried);
			assertNull(
				queried.lastUpdatedAt(),
				"A capability nothing ever wrote carries an update stamp, which means the sentinel for `never` was " +
					"rendered as an instant rather than as an absence: " + queried
			);
			assertEquals(queried.lastRequestedAt(), queried.lastRequestedAtIfKnown().orElse(null));

			final SchemaCapabilityUsageSnapshot written = rowOf(rows, ATTRIBUTE_NAME, Capability.FILTER);
			assertNotNull(written.lastUpdatedAt(), "A maintained capability arrived without its stamp: " + written);
			assertNull(
				written.lastRequestedAt(),
				"A capability no query ever named carries a request stamp: " + written
			);
			assertEquals(written.lastUpdatedAt(), written.lastUpdatedAtIfKnown().orElse(null));
		}

		@Test
		@DisplayName("An untouched catalog reports nothing rather than failing")
		void shouldReportNothingBeforeAnythingHappens() {
			assertTrue(
				SchemaCapabilityUsageSurfaceTest.this.evita
					.management()
					.listCapabilityUsage(CATALOG, ENTITY_PRODUCT)
					.isEmpty(),
				"A capability nothing has ever asked for or maintained has no holder yet, and an empty list is the " +
					"honest answer - the row would otherwise report an observation window that never opened"
			);
		}

		@Test
		@DisplayName("A collection the catalog does not hold is an error, not an empty list")
		void shouldRejectAnUnknownCollection() {
			// an empty list would be indistinguishable from a collection nothing has queried, which is exactly the
			// reading a typo must not be given
			assertThrows(
				CollectionNotFoundException.class,
				() -> SchemaCapabilityUsageSurfaceTest.this.evita
					.management()
					.listCapabilityUsage(CATALOG, "thereIsNoSuchCollection")
			);
		}

	}

	@Nested
	@DisplayName("What the catalog reports")
	class CatalogOwned {

		@Test
		@DisplayName("A collection-less query lands its request on a catalog-owned row")
		void shouldReportACollectionLessQueryOnTheCatalog() {
			writeCode(2);
			filterByCodeWithoutNamingACollection();

			final List<SchemaCapabilityUsageSnapshot> rows = SchemaCapabilityUsageSurfaceTest.this.evita
				.management()
				.listCapabilityUsage(CATALOG, null);

			final SchemaCapabilityUsageSnapshot row = rowOf(rows, ATTRIBUTE_CODE, Capability.FILTER);
			assertNull(
				row.entityType(),
				"A capability the catalog schema declares belongs to no collection, and naming one would send an " +
					"operator to a schema mutation that cannot drop it: " + row
			);
			assertNull(row.containerName(), "A catalog schema declares no references: " + row);
			assertEquals(
				1L, row.requestedCount(),
				"One query filtering by a globally-unique attribute without naming a collection must be reported as " +
					"exactly one request against the catalog"
			);
			assertTrue(row.updatedCount() >= 1L, "The upsert wrote `" + ATTRIBUTE_CODE + "`: " + row);
		}

		@Test
		@DisplayName("The two owners are separate listings, not one merged table")
		void shouldNotReportCatalogRowsAmongACollectionsOwn() {
			writeCode(2);
			filterByCodeWithoutNamingACollection();

			final List<SchemaCapabilityUsageSnapshot> collectionRows = SchemaCapabilityUsageSurfaceTest.this.evita
				.management()
				.listCapabilityUsage(CATALOG, ENTITY_PRODUCT);

			// the request went to the catalog, so the collection must not claim it too - a client concatenating both
			// listings would otherwise count one query twice, and an operator would read a flag as busier than it is
			for (final SchemaCapabilityUsageSnapshot row : collectionRows) {
				if (ATTRIBUTE_CODE.equals(row.elementName()) && row.capability() == Capability.FILTER) {
					assertEquals(
						0L, row.requestedCount(),
						"A query that named no collection was reported against one anyway: " + row
					);
				}
			}
		}

	}

	/**
	 * Finds the row describing one capability of an entity-declared element, failing with a sentence rather than a
	 * `NoSuchElement` when it is missing.
	 *
	 * @param rows        the listing to search
	 * @param elementName name of the attribute the row must describe
	 * @param capability  the capability the row must count
	 * @return the matching row
	 */
	@Nonnull
	private static SchemaCapabilityUsageSnapshot rowOf(
		@Nonnull List<SchemaCapabilityUsageSnapshot> rows,
		@Nonnull String elementName,
		@Nonnull Capability capability
	) {
		for (final SchemaCapabilityUsageSnapshot row : rows) {
			if (row.containerName() == null && elementName.equals(row.elementName())
				&& row.capability() == capability) {
				return row;
			}
		}
		throw new AssertionError(
			"The listing carries no `" + capability + "` row for `" + elementName + "` - a capability that was " +
				"exercised must be reported, since its absence reads to an operator as a flag nothing depends on. " +
				"What it does carry: " + rows
		);
	}

	/**
	 * Writes one product carrying the write-only attribute and nothing else, so the queried attribute's maintenance
	 * count stays provably at zero.
	 *
	 * @param primaryKey primary key of the product to write
	 */
	private void writeNameOnly(int primaryKey) {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, primaryKey)
						.setAttribute(ATTRIBUTE_NAME, "product-" + primaryKey)
				);
			}
		);
	}

	/**
	 * Writes one product carrying the globally-unique attribute, which is what brings the catalog's global unique
	 * index into existence for the collection-less query to be served from.
	 *
	 * @param primaryKey primary key of the product to write
	 */
	private void writeCode(int primaryKey) {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, primaryKey)
						.setAttribute(ATTRIBUTE_CODE, "code-" + primaryKey)
				);
			}
		);
	}

	/**
	 * Runs one query filtering by the query-only attribute. It matches nothing on purpose - the capability was
	 * requested by the query naming it, whatever the filter turned out to select.
	 */
	private void filterByEan() {
		this.evita.queryCatalog(
			CATALOG,
			session -> {
				session.queryOneEntityReference(
					query(collection(ENTITY_PRODUCT), filterBy(attributeEquals(ATTRIBUTE_EAN, "no-such-ean")))
				);
			}
		);
	}

	/**
	 * Runs one query filtering by the globally-unique attribute without naming a collection - the path that resolves
	 * against the catalog's own structures and therefore counts against the catalog's registry.
	 */
	private void filterByCodeWithoutNamingACollection() {
		this.evita.queryCatalog(
			CATALOG,
			session -> {
				assertTrue(
					session.queryOneEntityReference(
						query(filterBy(attributeEquals(ATTRIBUTE_CODE, "code-2")))
					).isPresent(),
					"The fixture's entity must be found through the catalog's global unique index, otherwise the " +
						"query never took the path this test is about"
				);
			}
		);
	}

	/**
	 * Builds the smallest fixture the surface needs: one attribute that is only queried, one that is only written, and
	 * one globally-unique attribute whose capabilities belong to the catalog rather than to the collection.
	 */
	private void buildCatalog() {
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(
						ATTRIBUTE_CODE, String.class,
						whichIs -> whichIs.uniqueGloballyInScope(Scope.LIVE).nullable()
					)
					.updateVia(session);

				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(ATTRIBUTE_CODE)
					// nullable, because the whole fixture rests on writing one of the two and not the other - a
					// mandatory attribute would force every upsert to touch both and collapse the distinction
					.withAttribute(ATTRIBUTE_EAN, String.class, whichIs -> whichIs.filterable().nullable())
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().nullable())
					.updateVia(session);
			}
		);
	}

	/**
	 * Builds the configuration of the embedded instance this test runs against.
	 *
	 * @return configuration rooted at this test's directories
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.storage(
				StorageOptions.builder()
					.storageDirectory(this.paths.storage())
					.workDirectory(this.paths.work())
					.build()
			)
			.build();
	}

}
