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

package io.evitadb.core.collection;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.SchemaCapabilityUsageSnapshot.Capability;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
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

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies **the one invariant the collection-level usage counters rest on**: the
 * {@link SchemaCapabilityUsageRegistry} an {@link EntityCollection} holds survives every rebuild of that collection,
 * and it is thrown away exactly when the schema stops declaring what it counts.
 *
 * A collection is not mutated when it changes - it is rebuilt, from five distinct sites - so a registry allocated per
 * instance would reset on precisely the collections being written to, and it would do so silently: the numbers would
 * still look plausible, merely permanently small. Nothing else in the suite can see that, which is why the tests below
 * **enumerate the sites** rather than exercise a representative one. A sixth site added later has to appear here too:
 *
 * - the commit-time merge copy, **dirty** branch - reached by a transactional write to the collection itself
 * - the commit-time merge copy, **clean** branch, which ends in `createCopyForNewCatalogAttachment` - reached by a
 *   transaction that writes to a sibling collection
 * - `createCopyForNewCatalogAttachment`, reached directly - going live
 * - `createCopyWithNewPersistenceService`, called straight away - renaming a collection in warm-up
 * - `createCopyWithNewPersistenceService`, called from the commit - renaming a collection in a transaction
 * - the previous-collection constructor - renaming the catalog
 *
 * Every one of them first asserts that the collection instance really was replaced, because a test that silently
 * stopped rebuilding the collection would keep passing while proving nothing.
 *
 * The counters are written here **directly on the holder**, not through a query or a mutation: the accumulation sites
 * that will feed them are separate work, and pinning this invariant through them would make the test fail for reasons
 * that have nothing to do with the lifetime it is about.
 *
 * `IndexActivityTest` applies the same discipline one level down, to the per-index holder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see SchemaCapabilityUsageRegistry
 */
@DisplayName("Entity collection usage registry")
@Tag(ENGINE)
@Tag(SCHEMA)
@Tag(MANAGEMENT)
class EntityCollectionUsageRegistryTest implements EvitaTestSupport {
	private static final String CATALOG = "entityCollectionUsageRegistryTest";
	private static final String RENAMED_CATALOG = CATALOG + "Renamed";
	private static final String ENTITY_PRODUCT = "product";
	private static final String RENAMED_PRODUCT = "renamedProduct";
	/** A second collection, so a commit can rebuild one collection while leaving another untouched. */
	private static final String ENTITY_CATEGORY = "category";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_EAN = "ean";
	/** The capability every carry-over test records against - filtering by `code`, in the live scope. */
	private static final SchemaCapabilityKey CODE_FILTER = SchemaCapabilityKey.entityAttribute(
		ATTRIBUTE_CODE, Capability.FILTER, Scope.LIVE
	);
	/** The capability the pruning tests take away - filtering by `ean`, in the live scope. */
	private static final SchemaCapabilityKey EAN_FILTER = SchemaCapabilityKey.entityAttribute(
		ATTRIBUTE_EAN, Capability.FILTER, Scope.LIVE
	);
	/** An arbitrary but recognisable instant, and a later one, so a swapped stamp would be visible. */
	private static final long FIRST_MILLIS = 1_800_000_000_000L;
	private static final long SECOND_MILLIS = 1_800_000_060_000L;
	/** The message every identity assertion fails with - a sentence saying what broke, rather than "expected same". */
	private static final String REGISTRY_LOST =
		"The rebuilt collection carries a registry of its own, so every collection rebuilt this way silently resets " +
			"its capability counters - pass the existing registry through this copy site: ";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("EntityCollectionUsageRegistryTest");
		this.evita = new Evita(getEvitaConfiguration());
		buildCatalog();
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Registry survives the collection rebuild")
	class SurvivesRebuild {

		@Test
		@DisplayName("Going live")
		void shouldCarryTheRegistryThroughGoLive() {
			final EntityCollection before = collectionOf(CATALOG, ENTITY_PRODUCT);
			final SchemaCapabilityUsageRegistry registry = recordUsageOn(before);

			EntityCollectionUsageRegistryTest.this.evita.updateCatalog(
				CATALOG, EvitaSessionContract::goLiveAndClose
			);

			assertRegistryCarriedOver(registry, before, CATALOG, ENTITY_PRODUCT, "going live");
		}

		@Test
		@DisplayName("A transactional write to the collection itself")
		void shouldCarryTheRegistryThroughACommitThatDirtiesTheCollection() {
			goLive();
			final EntityCollection before = collectionOf(CATALOG, ENTITY_PRODUCT);
			final SchemaCapabilityUsageRegistry registry = recordUsageOn(before);

			upsertProduct(1);

			assertRegistryCarriedOver(registry, before, CATALOG, ENTITY_PRODUCT, "the commit-time merge copy");
		}

		@Test
		@DisplayName("A transactional write to a sibling collection")
		void shouldCarryTheRegistryOfACollectionTheCommitLeftClean() {
			goLive();
			final EntityCollection before = collectionOf(CATALOG, ENTITY_CATEGORY);
			final SchemaCapabilityUsageRegistry registry = recordUsageOn(before);

			// the write goes to `product`, so `category` is carried across the catalog version by the clean branch -
			// the one that never merges a transactional layer and is therefore easy to leave un-threaded
			upsertProduct(2);

			assertRegistryCarriedOver(
				registry, before, CATALOG, ENTITY_CATEGORY, "the commit-time copy of an unchanged collection"
			);
		}

		@Test
		@DisplayName("Renaming the collection in warm-up")
		void shouldCarryTheRegistryThroughACollectionRenameInWarmUp() {
			final EntityCollection before = collectionOf(CATALOG, ENTITY_PRODUCT);
			final SchemaCapabilityUsageRegistry registry = recordUsageOn(before);

			EntityCollectionUsageRegistryTest.this.evita.updateCatalog(
				// a block body, not an expression: a lambda returning a value matches both the consumer and the
				// function overload of `updateCatalog`
				CATALOG, session -> {
					session.renameCollection(ENTITY_PRODUCT, RENAMED_PRODUCT);
				}
			);

			// the rename adopts a new schema version on the way through, and `code` is declared by both - a prune that
			// dropped it here would take the counters with it
			assertRegistryCarriedOver(
				registry, before, CATALOG, RENAMED_PRODUCT, "the warm-up collection rename"
			);
		}

		@Test
		@DisplayName("Renaming the collection in a transaction")
		void shouldCarryTheRegistryThroughACollectionRenameInATransaction() {
			goLive();
			final EntityCollection before = collectionOf(CATALOG, ENTITY_PRODUCT);
			final SchemaCapabilityUsageRegistry registry = recordUsageOn(before);

			EntityCollectionUsageRegistryTest.this.evita.updateCatalog(
				CATALOG, session -> {
					session.renameCollection(ENTITY_PRODUCT, RENAMED_PRODUCT);
				}
			);

			assertRegistryCarriedOver(
				registry, before, CATALOG, RENAMED_PRODUCT, "the transactional collection rename"
			);
		}

		@Test
		@DisplayName("Renaming the catalog")
		void shouldCarryTheRegistryThroughACatalogRename() {
			final EntityCollection before = collectionOf(CATALOG, ENTITY_PRODUCT);
			final SchemaCapabilityUsageRegistry registry = recordUsageOn(before);

			// the whole catalog is rebuilt here, collection by collection, through a constructor of its own - the one
			// copy site no commit and no go-live ever reaches
			EntityCollectionUsageRegistryTest.this.evita.renameCatalog(CATALOG, RENAMED_CATALOG);

			assertRegistryCarriedOver(registry, before, RENAMED_CATALOG, ENTITY_PRODUCT, "the catalog rename");
		}

		@Test
		@DisplayName("Each collection counts into a registry of its own")
		void shouldGiveEachCollectionItsOwnRegistry() {
			// the mistake the identity assertions above cannot catch: one registry shared by every collection would
			// survive every rebuild and pool two collections' traffic under one key
			final SchemaCapabilityUsageRegistry productRegistry = collectionOf(CATALOG, ENTITY_PRODUCT)
				.getUsageRegistry();
			final SchemaCapabilityUsageRegistry categoryRegistry = collectionOf(CATALOG, ENTITY_CATEGORY)
				.getUsageRegistry();

			assertNotSame(productRegistry, categoryRegistry, "Two collections share one registry");

			productRegistry.resolve(CODE_FILTER).recordRequested(FIRST_MILLIS);

			assertEquals(
				0L, categoryRegistry.resolve(CODE_FILTER).getRequestedCount(),
				"A recording on one collection was visible on another"
			);
		}

		@Test
		@DisplayName("A restart starts the counters over, because they count since the catalog was loaded")
		void shouldStartOverAfterARestart() {
			recordUsageOn(collectionOf(CATALOG, ENTITY_PRODUCT));

			restart();

			// the other half of the contract: a collection loaded from disk mints a registry of its own, which is what
			// makes the counts "since catalog load" rather than "since this schema element was declared"
			final SchemaCapabilityUsage usage = collectionOf(CATALOG, ENTITY_PRODUCT)
				.getUsageRegistry()
				.resolve(CODE_FILTER);

			assertEquals(0L, usage.getRequestedCount(), "The counters are not persisted, by design");
			assertEquals(0L, usage.getUpdatedCount());
		}

	}

	@Nested
	@DisplayName("Schema adoption prunes")
	class SchemaAdoption {

		@Test
		@DisplayName("An element the new schema no longer declares loses its entry")
		void shouldDropTheEntryOfAnElementTheNewSchemaDropped() {
			final EntityCollection collection = collectionOf(CATALOG, ENTITY_PRODUCT);
			final SchemaCapabilityUsageRegistry registry = collection.getUsageRegistry();
			registry.resolve(CODE_FILTER).recordRequested(FIRST_MILLIS);
			registry.resolve(EAN_FILTER).recordRequested(FIRST_MILLIS);
			assertTrue(holdsEntryFor(registry, EAN_FILTER), "The fixture must count `ean` before the schema drops it");

			dropEanAttribute();

			assertTrue(
				!holdsEntryFor(registry, EAN_FILTER),
				"The collection adopted a schema that no longer declares `ean`, yet the registry still counts it - " +
					"an entry no schema backs can never be reported against anything an operator could act on"
			);
			assertTrue(
				holdsEntryFor(registry, CODE_FILTER),
				"Pruning took an element the new schema still declares with it"
			);
			assertEquals(
				1L, registry.resolve(CODE_FILTER).getRequestedCount(),
				"The surviving entry lost its counts, so pruning replaced the holder instead of keeping it"
			);
		}

		@Test
		@DisplayName("An element dropped and added back starts from zero")
		void shouldStartFreshWhenAnElementIsAddedBack() {
			final SchemaCapabilityUsageRegistry registry = collectionOf(CATALOG, ENTITY_PRODUCT).getUsageRegistry();
			final SchemaCapabilityUsage before = registry.resolve(EAN_FILTER);
			before.recordRequested(FIRST_MILLIS);
			before.recordUpdated(SECOND_MILLIS);

			dropEanAttribute();
			addEanAttribute();

			final SchemaCapabilityUsage after = collectionOf(CATALOG, ENTITY_PRODUCT)
				.getUsageRegistry()
				.resolve(EAN_FILTER);

			assertNotSame(before, after, "The re-added element inherited the holder of the element that was dropped");
			assertEquals(
				0L, after.getRequestedCount(),
				"A capability that was not maintained for an interval must not report the traffic it saw before that " +
					"interval as if it had been"
			);
			assertEquals(0L, after.getUpdatedCount());
			assertTrue(
				after.getObservedSinceMillis() >= before.getObservedSinceMillis(),
				"The observation window of the re-added element must open no earlier than the one it replaced"
			);
		}

	}

	/**
	 * Records one request and one update against `code` on the given collection, which is what the carry-over
	 * assertions then look for on the far side of a rebuild.
	 *
	 * @param collection the collection to count against
	 * @return the registry the counts landed in
	 */
	@Nonnull
	private SchemaCapabilityUsageRegistry recordUsageOn(@Nonnull EntityCollection collection) {
		final SchemaCapabilityUsageRegistry registry = collection.getUsageRegistry();
		final SchemaCapabilityUsage usage = registry.resolve(CODE_FILTER);
		usage.recordRequested(FIRST_MILLIS);
		usage.recordUpdated(SECOND_MILLIS);
		return registry;
	}

	/**
	 * Asserts that a rebuild really happened and that the rebuilt collection still counts into the very same registry.
	 *
	 * @param registry   the registry the counts were recorded in, before the rebuild
	 * @param before     the collection instance that held it
	 * @param catalog    name of the catalog to look the rebuilt collection up in
	 * @param entityType name the rebuilt collection is published under
	 * @param site       what rebuilt it, named the way the failure message should read
	 */
	private void assertRegistryCarriedOver(
		@Nonnull SchemaCapabilityUsageRegistry registry,
		@Nonnull EntityCollection before,
		@Nonnull String catalog,
		@Nonnull String entityType,
		@Nonnull String site
	) {
		final EntityCollection after = collectionOf(catalog, entityType);
		assertNotSame(
			before, after,
			"`" + site + "` did not rebuild the collection at all, so this test proves nothing - find the operation " +
				"that reaches the copy site again"
		);
		assertSame(registry, after.getUsageRegistry(), REGISTRY_LOST + site);

		// the same holder instance, not just equal counts: identity is what proves nothing was reset and re-counted
		final SchemaCapabilityUsage usage = after.getUsageRegistry().resolve(CODE_FILTER);
		assertSame(registry.resolve(CODE_FILTER), usage, REGISTRY_LOST + site);
		assertEquals(1L, usage.getRequestedCount(), "The carried-over registry lost the request it had counted");
		// at least, not exactly: a rebuild triggered by a real write counts that write's own maintenance of `code`
		// on top of the one recorded synthetically, and this test only cares that the synthetic one survived
		assertTrue(
			usage.getUpdatedCount() >= 1L,
			"The carried-over registry lost the update it had counted"
		);
		assertEquals(FIRST_MILLIS, usage.getLastRequestedAtMillis());
		assertTrue(
			usage.getLastUpdatedAtMillis() != 0L,
			"The carried-over registry lost the update stamp it had recorded"
		);
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
	 * Looks the live entity collection object up behind the public API - the registry is engine-internal state, and
	 * the diagnostic surface that will report it is later work.
	 *
	 * @param catalogName name of the catalog holding it
	 * @param entityType  name of the collection
	 * @return the collection
	 */
	@Nonnull
	private EntityCollection collectionOf(@Nonnull String catalogName, @Nonnull String entityType) {
		return ((Catalog) this.evita.getCatalogInstanceOrThrowException(catalogName))
			.getCollectionForEntityInternal(entityType)
			.orElseThrow(
				() -> new AssertionError("Catalog `" + catalogName + "` holds no collection `" + entityType + "`")
			);
	}

	/**
	 * Takes the catalog live, so what follows commits transactionally and rebuilds collections on every write.
	 */
	private void goLive() {
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
	}

	/**
	 * Writes one product, which dirties the product collection and nothing else.
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
						// both attributes are non-null by default, and an incomplete entity is refused before it ever
						// reaches the commit this test is about
						.setAttribute(ATTRIBUTE_EAN, "ean-" + primaryKey)
				);
			}
		);
	}

	/**
	 * Drops the `ean` attribute from the product schema, which is the schema adoption the pruning tests turn on.
	 */
	private void dropEanAttribute() {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.getEntitySchemaOrThrowException(ENTITY_PRODUCT)
					.openForWrite()
					.withoutAttribute(ATTRIBUTE_EAN)
					.updateVia(session);
			}
		);
	}

	/**
	 * Declares `ean` again, exactly as the fixture originally did.
	 */
	private void addEanAttribute() {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.getEntitySchemaOrThrowException(ENTITY_PRODUCT)
					.openForWrite()
					.withAttribute(ATTRIBUTE_EAN, String.class, AttributeSchemaEditor::filterable)
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
	 * Builds the smallest fixture the copy sites need: two collections, so one commit can rebuild one of them and
	 * carry the other, and two filterable attributes, so pruning one leaves a control behind.
	 */
	private void buildCatalog() {
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_CATEGORY).withoutGeneratedPrimaryKey().updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
					.withAttribute(ATTRIBUTE_EAN, String.class, AttributeSchemaEditor::filterable)
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
