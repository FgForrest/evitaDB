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

package io.evitadb.core.catalog;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.SchemaCapabilityUsageSnapshot.Capability;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.usage.SchemaCapabilityKey;
import io.evitadb.index.usage.SchemaCapabilityUsage;
import io.evitadb.index.usage.SchemaCapabilityUsageRegistry;
import io.evitadb.index.usage.SchemaCapabilityUsageRegistry.UsageEntry;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the **catalog-level twin** of the collection usage registry - the one that counts the capabilities of the
 * attributes the catalog schema declares itself.
 *
 * A globally-unique attribute is the case neither collection-level side can answer alone: the query asking for it may
 * name no collection at all (it is served from the catalog's global unique index), while the write maintaining it
 * arrives through whichever collection happens to own the entity. Its numbers therefore live on the catalog, and this
 * class pins the four things that makes true:
 *
 * - the registry survives every rebuild of the catalog object - going live, a commit, a catalog rename - because a
 *   catalog is replaced rather than mutated, exactly like a collection is;
 * - a query that names no collection lands its request there, and **not** on the collection that happens to hold the
 *   matching entity;
 * - an entity upsert writing the attribute lands one update there, however many indexes that took;
 * - dropping the attribute from the catalog schema takes its entry with it.
 *
 * `EntityCollectionUsageRegistryTest` applies the same discipline one level down, to the collection's registry.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see SchemaCapabilityUsageRegistry
 */
@DisplayName("Catalog usage registry")
@Tag(ENGINE)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
class CatalogUsageRegistryTest implements EvitaTestSupport {
	private static final String CATALOG = "catalogUsageRegistryTest";
	private static final String RENAMED_CATALOG = CATALOG + "Renamed";
	private static final String ENTITY_PRODUCT = "product";
	/** Globally unique, and used by the product collection - the attribute both live sides of the test go through. */
	private static final String ATTRIBUTE_CODE = "code";
	/**
	 * Globally unique and used by nothing, so the pruning test can take it away from the catalog schema without
	 * arguing with a collection that depends on it.
	 */
	private static final String ATTRIBUTE_LEGACY_URL = "legacyUrl";
	/** What a collection-less filter on `code` asks for - the flag its global uniqueness implies. */
	private static final SchemaCapabilityKey CODE_FILTER = SchemaCapabilityKey.entityAttribute(
		ATTRIBUTE_CODE, Capability.FILTER, Scope.LIVE
	);
	/** What maintaining the global unique index of `code` costs. */
	private static final SchemaCapabilityKey CODE_UNIQUE = SchemaCapabilityKey.entityAttribute(
		ATTRIBUTE_CODE, Capability.UNIQUE, Scope.LIVE
	);
	/** The entry the pruning test takes away. */
	private static final SchemaCapabilityKey LEGACY_URL_UNIQUE = SchemaCapabilityKey.entityAttribute(
		ATTRIBUTE_LEGACY_URL, Capability.UNIQUE, Scope.LIVE
	);
	/** An arbitrary but recognisable instant, and a later one, so a swapped stamp would be visible. */
	private static final long FIRST_MILLIS = 1_800_000_000_000L;
	private static final long SECOND_MILLIS = 1_800_000_060_000L;
	/** The message every identity assertion fails with - a sentence saying what broke, rather than "expected same". */
	private static final String REGISTRY_LOST =
		"The rebuilt catalog carries a registry of its own, so every catalog rebuilt this way silently resets its " +
			"global attributes' counters - pass the existing registry through this copy site: ";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CatalogUsageRegistryTest");
		this.evita = new Evita(getEvitaConfiguration());
		buildCatalog();
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Registry survives the catalog rebuild")
	class SurvivesRebuild {

		@Test
		@DisplayName("Going live")
		void shouldCarryTheRegistryThroughGoLive() {
			final Catalog before = catalogOf(CATALOG);
			final SchemaCapabilityUsageRegistry registry = recordUsageOn(before);

			CatalogUsageRegistryTest.this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);

			assertRegistryCarriedOver(registry, before, CATALOG, "going live");
		}

		@Test
		@DisplayName("A transactional write advancing the catalog version")
		void shouldCarryTheRegistryThroughACommit() {
			goLive();
			final Catalog before = catalogOf(CATALOG);
			final SchemaCapabilityUsageRegistry registry = recordUsageOn(before);

			upsertProduct(1);

			assertRegistryCarriedOver(registry, before, CATALOG, "the commit-time catalog version advance");
		}

		@Test
		@DisplayName("Renaming the catalog")
		void shouldCarryTheRegistryThroughACatalogRename() {
			final Catalog before = catalogOf(CATALOG);
			final SchemaCapabilityUsageRegistry registry = recordUsageOn(before);

			// the rename builds the whole catalog again through a constructor no commit and no go-live reaches, and
			// exchanges the catalog schema on the way - a prune that fired against the wrong schema version would
			// take `code` with it
			CatalogUsageRegistryTest.this.evita.renameCatalog(CATALOG, RENAMED_CATALOG);

			assertRegistryCarriedOver(registry, before, RENAMED_CATALOG, "the catalog rename");
		}

		@Test
		@DisplayName("The catalog counts into a registry of its own, not into a collection's")
		void shouldNotShareTheRegistryWithACollection() {
			final SchemaCapabilityUsageRegistry catalogRegistry = catalogOf(CATALOG).getUsageRegistry();
			final SchemaCapabilityUsageRegistry collectionRegistry = productCollection().getUsageRegistry();

			assertNotSame(catalogRegistry, collectionRegistry, "The catalog shares its registry with a collection");

			catalogRegistry.resolve(CODE_FILTER).recordRequested(FIRST_MILLIS);

			assertEquals(
				0L, collectionRegistry.resolve(CODE_FILTER).getRequestedCount(),
				"A recording on the catalog was visible on a collection"
			);
		}

		@Test
		@DisplayName("A restart starts the counters over, because they count since the catalog was loaded")
		void shouldStartOverAfterARestart() {
			recordUsageOn(catalogOf(CATALOG));

			restart();

			final SchemaCapabilityUsage usage = catalogOf(CATALOG).getUsageRegistry().resolve(CODE_FILTER);

			assertEquals(0L, usage.getRequestedCount(), "The counters are not persisted, by design");
			assertEquals(0L, usage.getUpdatedCount());
		}

	}

	@Nested
	@DisplayName("What lands on the catalog registry")
	class Attribution {

		@Test
		@DisplayName("A query naming no collection counts its request on the catalog")
		void shouldCountACollectionLessQueryOnTheCatalog() {
			upsertProduct(1);
			final SchemaCapabilityUsageRegistry catalogRegistry = catalogOf(CATALOG).getUsageRegistry();
			final SchemaCapabilityUsageRegistry collectionRegistry = productCollection().getUsageRegistry();

			CatalogUsageRegistryTest.this.evita.queryCatalog(
				CATALOG,
				session -> {
					assertTrue(
						session.queryOneEntityReference(
							query(filterBy(attributeEquals(ATTRIBUTE_CODE, "product-1")))
						).isPresent(),
						"The fixture's entity must be found through the catalog's global unique index, otherwise the " +
							"query never took the path this test is about"
					);
				}
			);

			assertEquals(
				1L, catalogRegistry.resolve(CODE_FILTER).getRequestedCount(),
				"One query filtering by a globally-unique attribute without naming a collection must land exactly " +
					"one request on the catalog registry"
			);
			assertTrue(
				catalogRegistry.resolve(CODE_FILTER).getLastRequestedAtMillis() > 0L,
				"The request landed without a stamp"
			);
			assertEquals(
				0L, collectionRegistry.resolve(CODE_FILTER).getRequestedCount(),
				"A query that named no collection was attributed to one anyway - the flag it protects is declared by " +
					"the catalog schema, and dropping it is a catalog schema mutation"
			);
		}

		@Test
		@DisplayName("An upsert writing the attribute counts one update on the catalog, whatever it maintained")
		void shouldCountOneUpdatePerEntityMutation() {
			final SchemaCapabilityUsageRegistry catalogRegistry = catalogOf(CATALOG).getUsageRegistry();

			upsertProduct(1);

			// one entity mutation, however many indexes maintaining `code` it fanned out over: the collection's own
			// unique and filter indexes, and the catalog's global unique index
			assertEquals(
				1L, catalogRegistry.resolve(CODE_UNIQUE).getUpdatedCount(),
				"One upsert of a globally-unique attribute must count one update, not one per index maintained"
			);
			assertEquals(
				1L, catalogRegistry.resolve(CODE_FILTER).getUpdatedCount(),
				"The FILTER entry a globally-unique attribute implies was not maintained alongside its UNIQUE one"
			);
			assertEquals(
				0L, catalogRegistry.resolve(CODE_UNIQUE).getRequestedCount(),
				"A write was counted as a request"
			);

			upsertProduct(2);

			assertEquals(
				2L, catalogRegistry.resolve(CODE_UNIQUE).getUpdatedCount(),
				"The second entity mutation was not counted"
			);
		}

	}

	@Nested
	@DisplayName("Catalog schema adoption prunes")
	class SchemaAdoption {

		@Test
		@DisplayName("A global attribute the new catalog schema no longer declares loses its entry")
		void shouldDropTheEntryOfADroppedGlobalAttribute() {
			final SchemaCapabilityUsageRegistry registry = catalogOf(CATALOG).getUsageRegistry();
			registry.resolve(CODE_FILTER).recordRequested(FIRST_MILLIS);
			registry.resolve(LEGACY_URL_UNIQUE).recordRequested(FIRST_MILLIS);
			assertTrue(
				holdsEntryFor(registry, LEGACY_URL_UNIQUE),
				"The fixture must count `legacyUrl` before the catalog schema drops it"
			);

			dropLegacyUrlAttribute();

			assertTrue(
				!holdsEntryFor(registry, LEGACY_URL_UNIQUE),
				"The catalog adopted a schema that no longer declares `legacyUrl`, yet the registry still counts " +
					"it - an entry no schema backs can never be reported against anything an operator could act on"
			);
			assertTrue(
				holdsEntryFor(registry, CODE_FILTER),
				"Pruning took a global attribute the new schema still declares with it"
			);
			assertEquals(
				1L, registry.resolve(CODE_FILTER).getRequestedCount(),
				"The surviving entry lost its counts, so pruning replaced the holder instead of keeping it"
			);
		}

		@Test
		@DisplayName("A global attribute dropped and added back starts from zero")
		void shouldStartFreshWhenAGlobalAttributeIsAddedBack() {
			final SchemaCapabilityUsageRegistry registry = catalogOf(CATALOG).getUsageRegistry();
			final SchemaCapabilityUsage before = registry.resolve(LEGACY_URL_UNIQUE);
			before.recordRequested(FIRST_MILLIS);
			before.recordUpdated(SECOND_MILLIS);

			dropLegacyUrlAttribute();
			addLegacyUrlAttribute();

			final SchemaCapabilityUsage after = catalogOf(CATALOG)
				.getUsageRegistry()
				.resolve(LEGACY_URL_UNIQUE);

			assertNotSame(before, after, "The re-added attribute inherited the holder of the one that was dropped");
			assertEquals(
				0L, after.getRequestedCount(),
				"A capability that was not maintained for an interval must not report the traffic it saw before that " +
					"interval as if it had been"
			);
			assertEquals(0L, after.getUpdatedCount());
			assertTrue(
				after.getObservedSinceMillis() >= before.getObservedSinceMillis(),
				"The observation window of the re-added attribute must open no earlier than the one it replaced"
			);
		}

	}

	/**
	 * Records one request and one update against `code` on the given catalog, which is what the carry-over assertions
	 * then look for on the far side of a rebuild.
	 *
	 * @param catalog the catalog to count against
	 * @return the registry the counts landed in
	 */
	@Nonnull
	private SchemaCapabilityUsageRegistry recordUsageOn(@Nonnull Catalog catalog) {
		final SchemaCapabilityUsageRegistry registry = catalog.getUsageRegistry();
		final SchemaCapabilityUsage usage = registry.resolve(CODE_FILTER);
		usage.recordRequested(FIRST_MILLIS);
		usage.recordUpdated(SECOND_MILLIS);
		return registry;
	}

	/**
	 * Asserts that a rebuild really happened and that the rebuilt catalog still counts into the very same registry.
	 *
	 * @param registry the registry the counts were recorded in, before the rebuild
	 * @param before   the catalog instance that held it
	 * @param catalog  name the rebuilt catalog is published under
	 * @param site     what rebuilt it, named the way the failure message should read
	 */
	private void assertRegistryCarriedOver(
		@Nonnull SchemaCapabilityUsageRegistry registry,
		@Nonnull Catalog before,
		@Nonnull String catalog,
		@Nonnull String site
	) {
		final Catalog after = catalogOf(catalog);
		assertNotSame(
			before, after,
			"`" + site + "` did not rebuild the catalog at all, so this test proves nothing - find the operation " +
				"that reaches the copy site again"
		);
		assertSame(registry, after.getUsageRegistry(), REGISTRY_LOST + site);

		// the same holder instance, not just equal counts: identity is what proves nothing was reset and re-counted
		final SchemaCapabilityUsage usage = after.getUsageRegistry().resolve(CODE_FILTER);
		assertSame(registry.resolve(CODE_FILTER), usage, REGISTRY_LOST + site);
		assertEquals(1L, usage.getRequestedCount(), "The carried-over registry lost the request it had counted");
		// at least, not exactly: a rebuild triggered by a real write counts that write's own maintenance of `code` on
		// top of the one recorded synthetically, and this test only cares that the synthetic one survived
		assertTrue(usage.getUpdatedCount() >= 1L, "The carried-over registry lost the update it had counted");
		assertEquals(FIRST_MILLIS, usage.getLastRequestedAtMillis());
	}

	/**
	 * @param registry the registry to search
	 * @param key      the capability to look for
	 * @return true when the registry currently holds an entry for that capability
	 */
	private static boolean holdsEntryFor(
		@Nonnull SchemaCapabilityUsageRegistry registry,
		@Nonnull SchemaCapabilityKey key
	) {
		for (final UsageEntry entry : registry.listUsages()) {
			if (key.equals(entry.key())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Looks the live catalog object up behind the public API - the registry is engine-internal state, and the
	 * diagnostic surface that will report it is later work.
	 *
	 * @param catalogName name of the catalog
	 * @return the catalog
	 */
	@Nonnull
	private Catalog catalogOf(@Nonnull String catalogName) {
		return (Catalog) this.evita.getCatalogInstanceOrThrowException(catalogName);
	}

	/**
	 * @return the product collection of the test catalog, whose own registry the attribution assertions contrast with
	 */
	@Nonnull
	private EntityCollection productCollection() {
		return catalogOf(CATALOG)
			.getCollectionForEntityInternal(ENTITY_PRODUCT)
			.orElseThrow(() -> new AssertionError("The test catalog holds no `" + ENTITY_PRODUCT + "` collection"));
	}

	/**
	 * Takes the catalog live, so what follows commits transactionally and rebuilds the catalog on every write.
	 */
	private void goLive() {
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
	}

	/**
	 * Writes one product carrying the globally-unique attribute.
	 *
	 * @param primaryKey primary key of the product to write
	 */
	private void upsertProduct(int primaryKey) {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, primaryKey)
						.setAttribute(ATTRIBUTE_CODE, "product-" + primaryKey)
				);
			}
		);
	}

	/**
	 * Drops `legacyUrl` from the catalog schema, which is the catalog schema adoption the pruning tests turn on.
	 */
	private void dropLegacyUrlAttribute() {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withoutAttribute(ATTRIBUTE_LEGACY_URL)
					.updateVia(session);
			}
		);
	}

	/**
	 * Declares `legacyUrl` again, exactly as the fixture originally did.
	 */
	private void addLegacyUrlAttribute() {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(
						ATTRIBUTE_LEGACY_URL, String.class,
						whichIs -> whichIs.uniqueGloballyInScope(Scope.LIVE).nullable()
					)
					.updateVia(session);
			}
		);
	}

	/**
	 * Closes the embedded instance and opens a new one over the same directories, so what follows reads state that was
	 * rebuilt from disk rather than state still held in memory.
	 */
	private void restart() {
		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(
				() -> !this.evita.management()
					.getCatalogStatistics(CATALOG, EnumSet.of(CatalogStatisticsComponent.IDENTITY))
					.identity()
					.unusable()
			);
	}

	/**
	 * Builds the smallest fixture the catalog registry needs: one globally-unique attribute a collection uses, and one
	 * nothing uses, so the pruning tests can take the second away without a collection objecting.
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
					.withAttribute(
						ATTRIBUTE_LEGACY_URL, String.class,
						whichIs -> whichIs.uniqueGloballyInScope(Scope.LIVE).nullable()
					)
					.updateVia(session);

				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(ATTRIBUTE_CODE)
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
