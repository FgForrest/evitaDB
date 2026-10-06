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

package io.evitadb.index.virtual;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.MutationHandle;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.TransitionStage;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins where the switch maintaining virtual reference partitions lives and how long: it belongs to one catalog
 * lineage, it reaches every instance of a collection - after a commit, going live, a rename, a replacement, and the
 * instance a commit replay creates for a collection created inside the transaction - and it does not survive the
 * catalog being loaded from disk again, unless the engine is told before it starts loading.
 *
 * Each survival assertion is paired with a check that the collection really was re-instantiated, so that a switch
 * held by the collection instance itself could not pass for one that survives.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition maintenance switch")
@Tag(ENGINE)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class VirtualReferencePartitionMaintenanceTest implements EvitaTestSupport {
	private static final String CATALOG = "virtualReferencePartitionMaintenanceTest";
	private static final String OTHER_CATALOG = "virtualReferencePartitionMaintenanceTestOther";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String ENTITY_ITEM = "item";
	private static final String ENTITY_OTHER = "other";
	private static final String ENTITY_FRESH = "fresh";
	private static final String REFERENCE_CATEGORIES = "categories";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("VirtualReferencePartitionMaintenanceTest");
		this.evita = newEvita();
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				defineSchemas(session, ENTITY_PRODUCT);
				session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, 1));
			}
		);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("the switch is off until it is turned on, and off again once it is turned off")
	void shouldBeOffUntilTurnedOn() {
		assertFalse(collectionOf(CATALOG, ENTITY_PRODUCT).isVirtualReferencePartitionsMaintained());
		collectionOf(CATALOG, ENTITY_PRODUCT).setVirtualReferencePartitionsMaintained(true);
		assertTrue(collectionOf(CATALOG, ENTITY_PRODUCT).isVirtualReferencePartitionsMaintained());
		assertFalse(
			collectionOf(CATALOG, ENTITY_CATEGORY).isVirtualReferencePartitionsMaintained(),
			"The switch belongs to one collection"
		);
		collectionOf(CATALOG, ENTITY_PRODUCT).setVirtualReferencePartitionsMaintained(false);
		assertFalse(collectionOf(CATALOG, ENTITY_PRODUCT).isVirtualReferencePartitionsMaintained());
	}

	@Test
	@DisplayName("the read switch is off until it is turned on, and only a maintained collection can be read virtually")
	void shouldReadVirtuallyOnlyAMaintainedCollection() {
		final EntityCollection products = collectionOf(CATALOG, ENTITY_PRODUCT);
		assertFalse(products.isVirtualReferencePartitionsReadVirtually());
		assertThrows(GenericEvitaInternalError.class, () -> products.setVirtualReferencePartitionsReadVirtually(true));
		assertFalse(products.isVirtualReferencePartitionsReadVirtually());

		products.setVirtualReferencePartitionsMaintained(true);
		products.setVirtualReferencePartitionsReadVirtually(true);
		assertTrue(products.isVirtualReferencePartitionsReadVirtually());
		assertFalse(
			collectionOf(CATALOG, ENTITY_CATEGORY).isVirtualReferencePartitionsReadVirtually(),
			"The read switch belongs to one collection"
		);
		assertThrows(
			GenericEvitaInternalError.class, () -> products.setVirtualReferencePartitionsMaintained(false),
			"A collection read virtually must keep its registry up to date"
		);
		assertTrue(products.isVirtualReferencePartitionsMaintained());

		products.setVirtualReferencePartitionsReadVirtually(false);
		assertFalse(products.isVirtualReferencePartitionsReadVirtually());
		products.setVirtualReferencePartitionsMaintained(false);
		assertFalse(products.isVirtualReferencePartitionsMaintained());
	}

	@Test
	@DisplayName("the read switch moves with a rename, together with the maintenance")
	void shouldCarryTheReadSwitchOverARename() {
		collectionOf(CATALOG, ENTITY_PRODUCT).setVirtualReferencePartitionsMaintained(true);
		collectionOf(CATALOG, ENTITY_PRODUCT).setVirtualReferencePartitionsReadVirtually(true);
		this.evita.updateCatalog(CATALOG, session -> {
			session.renameCollection(ENTITY_PRODUCT, ENTITY_ITEM);
		});
		assertTrue(collectionOf(CATALOG, ENTITY_ITEM).isVirtualReferencePartitionsMaintained());
		assertTrue(collectionOf(CATALOG, ENTITY_ITEM).isVirtualReferencePartitionsReadVirtually());
	}

	@Test
	@DisplayName("the switch survives writes, going live, commits, a rename and a replacement")
	void shouldSurviveEveryReInstantiationOfTheCollection() {
		collectionOf(CATALOG, ENTITY_PRODUCT).setVirtualReferencePartitionsMaintained(true);
		this.evita.updateCatalog(CATALOG, session -> {
			upsertProduct(session, ENTITY_PRODUCT, 1);
		});
		assertMaintained(ENTITY_PRODUCT, "after a warm-up write");

		final EntityCollection warmUp = collectionOf(CATALOG, ENTITY_PRODUCT);
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		final EntityCollection alive = collectionOf(CATALOG, ENTITY_PRODUCT);
		assertNotSame(warmUp, alive, "Going live must have rebuilt the collection");
		assertMaintained(ENTITY_PRODUCT, "after going live");

		this.evita.updateCatalog(CATALOG, session -> {
			upsertProduct(session, ENTITY_PRODUCT, 2);
		});
		final EntityCollection committed = collectionOf(CATALOG, ENTITY_PRODUCT);
		assertNotSame(alive, committed, "The commit must have rebuilt the collection");
		assertMaintained(ENTITY_PRODUCT, "after a commit");

		this.evita.updateCatalog(CATALOG, session -> {
			session.renameCollection(ENTITY_PRODUCT, ENTITY_ITEM);
		});
		assertNotSame(committed, collectionOf(CATALOG, ENTITY_ITEM), "The rename must have rebuilt the collection");
		assertMaintained(ENTITY_ITEM, "after a rename");

		this.evita.updateCatalog(CATALOG, session -> {
			defineSchemas(session, ENTITY_OTHER);
		});
		assertFalse(
			collectionOf(CATALOG, ENTITY_OTHER).isVirtualReferencePartitionsMaintained(),
			"The collection about to be replaced starts with the switch off"
		);
		this.evita.updateCatalog(CATALOG, session -> {
			session.replaceCollection(ENTITY_OTHER, ENTITY_ITEM);
		});
		assertMaintained(ENTITY_OTHER, "after the maintained collection replaced another one");
		this.evita.updateCatalog(CATALOG, session -> {
			upsertProduct(session, ENTITY_OTHER, 3);
		});
		assertMaintained(ENTITY_OTHER, "after a commit into the replaced collection");
	}

	@Test
	@DisplayName("the switch survives a rename of a warming-up collection")
	void shouldSurviveARenameInWarmUp() {
		collectionOf(CATALOG, ENTITY_PRODUCT).setVirtualReferencePartitionsMaintained(true);
		this.evita.updateCatalog(CATALOG, session -> {
			upsertProduct(session, ENTITY_PRODUCT, 1);
		});
		final EntityCollection beforeRename = collectionOf(CATALOG, ENTITY_PRODUCT);
		this.evita.updateCatalog(CATALOG, session -> {
			session.renameCollection(ENTITY_PRODUCT, ENTITY_ITEM);
		});
		assertNotSame(beforeRename, collectionOf(CATALOG, ENTITY_ITEM), "The rename must have rebuilt the collection");
		assertMaintained(ENTITY_ITEM, "after a warm-up rename");
	}

	@Test
	@DisplayName("the switch reaches a collection created inside a transaction and created again by its replay")
	void shouldReachACollectionTheCommitReplayCreates() {
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		// turned on by name before the collection exists - the only way to reach a collection created in a transaction
		// from its very first write
		maintenanceOf(CATALOG).setMaintained(ENTITY_FRESH, true);

		final EntityCollection inTransaction = this.evita.updateCatalog(
			CATALOG,
			session -> {
				defineSchemas(session, ENTITY_FRESH);
				upsertProduct(session, ENTITY_FRESH, 1);
				final EntityCollection collection = collectionOf(CATALOG, ENTITY_FRESH);
				assertTrue(
					collection.isVirtualReferencePartitionsMaintained(),
					"The collection the transaction created must be maintained from its first write"
				);
				return collection;
			}
		);

		final EntityCollection published = collectionOf(CATALOG, ENTITY_FRESH);
		assertNotSame(
			inTransaction, published,
			"The commit must have published a collection the replay created, not the transaction's one"
		);
		assertMaintained(ENTITY_FRESH, "after the commit replayed the collection's creation");
	}

	@Test
	@DisplayName("the switch is not persisted - a catalog loaded from disk maintains nothing")
	void shouldNotSurviveARestart() {
		collectionOf(CATALOG, ENTITY_PRODUCT).setVirtualReferencePartitionsMaintained(true);
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		this.evita.updateCatalog(CATALOG, session -> {
			upsertProduct(session, ENTITY_PRODUCT, 1);
		});
		assertMaintained(ENTITY_PRODUCT, "before the restart");

		this.evita.close();
		this.evita = newEvita();
		awaitCatalogLoaded(CATALOG);

		assertFalse(
			collectionOf(CATALOG, ENTITY_PRODUCT).isVirtualReferencePartitionsMaintained(),
			"A reloaded catalog must start with the switch off - it is a test instrument, never catalog state"
		);
	}

	@Test
	@DisplayName("the switch of one catalog does not reach a collection of the same name in another catalog")
	void shouldBelongToOneCatalog() {
		this.evita.defineCatalog(OTHER_CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(OTHER_CATALOG, session -> {
			defineSchemas(session, ENTITY_PRODUCT);
		});
		collectionOf(CATALOG, ENTITY_PRODUCT).setVirtualReferencePartitionsMaintained(true);

		assertTrue(collectionOf(CATALOG, ENTITY_PRODUCT).isVirtualReferencePartitionsMaintained());
		assertFalse(collectionOf(OTHER_CATALOG, ENTITY_PRODUCT).isVirtualReferencePartitionsMaintained());
	}

	@Test
	@DisplayName("the switch for the catalog load is refused once the catalog loading was scheduled")
	void shouldRefuseTheLoadSwitchOnceLoadingWasScheduled() {
		this.evita.updateCatalog(CATALOG, session -> {
			upsertProduct(session, ENTITY_PRODUCT, 1);
		});
		this.evita.close();
		this.evita = new Evita(configuration(), false);
		this.evita.maintainVirtualReferencePartitionsOnLoad(CATALOG, Set.of(ENTITY_PRODUCT));
		this.evita.scheduleInitialCatalogLoading();
		awaitCatalogLoaded(CATALOG);

		assertMaintained(ENTITY_PRODUCT, "after a load the switch was set for");
		assertFalse(
			collectionOf(CATALOG, ENTITY_CATEGORY).isVirtualReferencePartitionsMaintained(),
			"The load switch reaches only the entity types it names"
		);
		assertThrows(
			GenericEvitaInternalError.class,
			() -> this.evita.maintainVirtualReferencePartitionsOnLoad(CATALOG, Set.of(ENTITY_CATEGORY)),
			"A switch set after the loading started would miss the mutations replayed at load, so it must be refused"
		);
	}

	@Test
	@DisplayName("a mutation without a probe gets one of two shared handles, so the switch allocates nothing")
	void shouldHandOutSharedHandlesWithoutAProbe() {
		final VirtualReferencePartitionMaintenance maintenance = new VirtualReferencePartitionMaintenance();

		final MutationHandle off = maintenance.forMutation(ENTITY_PRODUCT, new Object());
		assertSame(MutationHandle.OFF, off, "a mutation of a collection the switch is off for gets the shared handle");
		assertSame(off, maintenance.forMutation(ENTITY_PRODUCT, new Object()), "every one of them gets the same one");
		assertFalse(off.maintained());
		assertNull(off.probe(), "nothing can be reported through a handle without a probe");

		maintenance.setMaintained(ENTITY_PRODUCT, true);
		assertSame(MutationHandle.MAINTAINED, maintenance.forMutation(ENTITY_PRODUCT, new Object()));
		assertSame(MutationHandle.OFF, maintenance.forMutation(ENTITY_CATEGORY, new Object()));

		final TransitionHistory probe = new TransitionHistory();
		maintenance.setProbe(probe);
		final Object root = new Object();
		final MutationHandle observed = maintenance.forMutation(ENTITY_CATEGORY, root);
		assertNotSame(MutationHandle.OFF, observed, "only a probe makes a mutation get a handle of its own");
		assertFalse(observed.maintained());
		assertSame(probe, observed.probe());
		assertSame(root, observed.rootMutation());

		maintenance.setProbe(null);
		assertSame(MutationHandle.OFF, maintenance.forMutation(ENTITY_CATEGORY, new Object()));
	}

	@Test
	@DisplayName("with the switch off, an installed probe sees the transitions while the registry stays empty")
	void shouldReportTransitionsWithTheSwitchOffOnlyToAnInstalledProbe() {
		final TransitionHistory probe = new TransitionHistory();
		maintenanceOf(CATALOG).setProbe(probe);
		this.evita.updateCatalog(CATALOG, session -> {
			upsertProduct(session, ENTITY_PRODUCT, 1);
			upsertProduct(session, ENTITY_PRODUCT, 2);
		});
		probe.sessionClosed(true, false);

		assertEquals(
			List.of(TransitionStage.ENTERED, TransitionStage.SHARED),
			probe.getCommitted().stream().map(TransitionHistory.Transition::stage).toList(),
			"the first product makes the category partition a single-owner one, the second a shared one"
		);
		final EntityIndex typeIndex = collectionOf(CATALOG, ENTITY_PRODUCT).getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_CATEGORIES)
		);
		final VirtualReferencePartitionIndex registry =
			assertInstanceOf(ReferencedTypeEntityIndex.class, typeIndex).getVirtualReferencePartitionIndex();
		assertEquals(0, registry.getEntryCount(), "the switch is off, so nothing writes the registry");
		assertTrue(registry.isEmpty());
	}

	/**
	 * Starts an engine over the test directories.
	 *
	 * @return the engine
	 */
	@Nonnull
	private Evita newEvita() {
		return new Evita(configuration());
	}

	/**
	 * Builds the configuration of an engine over the test directories.
	 *
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration configuration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * Defines the referenced category type, unless it exists, and an entity type referencing it.
	 *
	 * @param session    the session to write in
	 * @param entityType the entity type to define
	 */
	private static void defineSchemas(@Nonnull EvitaSessionContract session, @Nonnull String entityType) {
		session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
		session.defineEntitySchema(entityType)
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFiltering()
			)
			.updateVia(session);
	}

	/**
	 * Upserts an entity referencing the category.
	 *
	 * @param session    the session to write in
	 * @param entityType the entity type
	 * @param primaryKey the primary key
	 */
	private static void upsertProduct(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		int primaryKey
	) {
		session.upsertEntity(session.createNewEntity(entityType, primaryKey).setReference(REFERENCE_CATEGORIES, 1));
	}

	/**
	 * Asserts the currently published collection of the entity type has the switch on.
	 *
	 * @param entityType the entity type
	 * @param when       names the moment in the failure message
	 */
	private void assertMaintained(@Nonnull String entityType, @Nonnull String when) {
		assertTrue(
			collectionOf(CATALOG, entityType).isVirtualReferencePartitionsMaintained(),
			"The switch of `" + entityType + "` must still be on " + when
		);
	}

	/**
	 * Returns the switch holder of the current version of a catalog.
	 *
	 * @param catalogName the catalog
	 * @return the switch holder
	 */
	@Nonnull
	private VirtualReferencePartitionMaintenance maintenanceOf(@Nonnull String catalogName) {
		return ((Catalog) this.evita.getCatalogInstance(catalogName).orElseThrow())
			.getVirtualReferencePartitionMaintenance();
	}

	/**
	 * Returns the collection of an entity type in the current version of a catalog - inside a transaction, the one
	 * the transaction sees.
	 *
	 * @param catalogName the catalog
	 * @param entityType  the entity type
	 * @return the collection
	 */
	@Nonnull
	private EntityCollection collectionOf(@Nonnull String catalogName, @Nonnull String entityType) {
		return (EntityCollection) this.evita.getCatalogInstance(catalogName)
			.orElseThrow()
			.getCollectionForEntity(entityType)
			.orElseThrow();
	}

	/**
	 * Blocks until a reopened engine has finished loading the catalog.
	 *
	 * @param catalogName the catalog
	 */
	private void awaitCatalogLoaded(@Nonnull String catalogName) {
		this.evita.waitUntilFullyInitialized();
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(
				() -> !this.evita.management()
					.getCatalogStatistics(catalogName, EnumSet.of(CatalogStatisticsComponent.IDENTITY))
					.identity()
					.unusable()
			);
	}
}
