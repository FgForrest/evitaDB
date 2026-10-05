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
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.StoragePartUsage;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static io.evitadb.index.virtual.DifferentialEngines.CATALOG;
import static io.evitadb.index.virtual.DifferentialEngines.EXCLUDED_STORAGE_PART_TYPES;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that loading a catalog and taking it live write nothing into the registry of single-owner partitions, with
 * the maintenance on from the moment the catalog is loaded. Both run `initSchema` over every collection - at load and
 * in the constructor rebuilding the catalog when it goes live - and a schema-driven path writing the registry there
 * would pass {@link io.evitadb.index.mutation.local.VirtualReferencePartitionWriterGuardTest}, which restricts direct
 * callers only, as long as it went through an allowed helper. This is the behavioural half of that guarantee.
 *
 * The maintenance is switched on and the probe installed **before** the catalog loads (see
 * {@link Evita#maintainVirtualReferencePartitionsOnLoad(String, Set,
 * io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.TransitionProbe)}): switched on afterwards, a write made
 * during the load would happen with the maintenance off and nothing would see it.
 *
 * The catalog carries a reflected reference, whose schema the load resolves against the reflected one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition maintenance through load and going live")
@Tag(ENGINE)
@Tag(REFERENCE)
@Tag(SCHEMA)
class VirtualReferencePartitionEnabledLoadTest implements EvitaTestSupport {
	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("VirtualReferencePartitionEnabledLoadTest");
		this.evita = new Evita(configuration());
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		final TransitionHistory history = new TransitionHistory();
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(CATALOG).orElseThrow();
		catalog.getVirtualReferencePartitionMaintenance().setProbe(history);
		for (final String entityType : ENTITY_TYPES) {
			catalog.getVirtualReferencePartitionMaintenance().setMaintained(entityType, true);
		}
		this.evita.updateCatalog(CATALOG, SingleOwnerPartitionFixture::setUpWithReflectedProducts);
		history.sessionClosed(true, false);
		// the history that the load must not extend: the fixture registered rows on both sides of the reflection
		assertTrue(
			history.getCommitted().stream().anyMatch(it -> it.entityType().equals(ENTITY_PRODUCT)) &&
				history.getCommitted().stream().anyMatch(it -> it.entityType().equals(ENTITY_CATEGORY)),
			"the fixture must have registered rows on both sides of the reflected reference"
		);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("a load and going live with the maintenance on pass no transition and leave the registry as it was")
	void shouldWriteNothingAtLoadAndWhenGoingLive() {
		final SortedMap<String, String> written = describeRegistries();
		assertFalse(written.isEmpty());

		final TransitionHistory loadHistory = restartWithMaintenanceOn();
		assertNothingPassed(loadHistory, "loading the warming-up catalog");
		assertEquals(written, describeRegistries(), "loading the catalog must leave the registry as it was");
		assertConsistent("after the load");

		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		assertNothingPassed(loadHistory, "going live");
		assertEquals(written, describeRegistries(), "going live must leave the registry as it was");
		assertConsistent("after going live");

		final TransitionHistory aliveLoadHistory = restartWithMaintenanceOn();
		assertNothingPassed(aliveLoadHistory, "loading the live catalog");
		assertEquals(written, describeRegistries(), "loading the live catalog must leave the registry as it was");
		assertConsistent("after loading the live catalog");
	}

	/**
	 * Closes the engine and starts it again with the maintenance switched on and a fresh probe installed before the
	 * catalog loads.
	 *
	 * @return the probe, which has seen everything the load did
	 */
	@Nonnull
	private TransitionHistory restartWithMaintenanceOn() {
		this.evita.close();
		final TransitionHistory history = new TransitionHistory();
		this.evita = new Evita(configuration(), false);
		this.evita.maintainVirtualReferencePartitionsOnLoad(CATALOG, ENTITY_TYPES, history);
		this.evita.scheduleInitialCatalogLoading();
		DifferentialEngines.awaitCatalogLoaded(this.evita);
		for (final String entityType : ENTITY_TYPES) {
			assertTrue(
				collectionOf(entityType).isVirtualReferencePartitionsMaintained(),
				"the maintenance of `" + entityType + "` must be on from the load"
			);
		}
		return history;
	}

	/**
	 * Asserts the probe has seen no transition and no root entity mutation at all.
	 *
	 * @param history the probe
	 * @param when    names the moment in the failure message
	 */
	private static void assertNothingPassed(@Nonnull TransitionHistory history, @Nonnull String when) {
		assertEquals(
			0,
			history.getCommitted().size() + history.getSession().size() + history.getRolledBack().size(),
			when + " must pass no transition: committed " + history.getCommitted() + ", in a session " +
				history.getSession() + ", rolled back " + history.getRolledBack()
		);
	}

	/**
	 * Checks the registry of every collection against its partitions.
	 *
	 * @param label names the checkpoint in failure messages
	 */
	private void assertConsistent(@Nonnull String label) {
		for (final String entityType : ENTITY_TYPES) {
			VirtualReferencePartitionOracle.assertConsistent(label, collectionOf(entityType));
		}
	}

	/**
	 * Describes the registry of every reference of every collection - its rows and tuples per scope, and the records
	 * and bytes its storage parts take on disk.
	 *
	 * @return the description, by collection and reference
	 */
	@Nonnull
	private SortedMap<String, String> describeRegistries() {
		final SortedMap<String, String> description = new TreeMap<>();
		for (final String entityType : ENTITY_TYPES) {
			for (final String referenceName : collectionOf(entityType).getSchema().getReferences().keySet()) {
				description.put(
					entityType + "/" + referenceName,
					VirtualReferencePartitionTransitionTest.describeRows(this.evita, entityType, referenceName)
				);
			}
			final StoragePartUsage[] parts = this.evita.management()
				.getEntityCollectionStatistics(
					CATALOG, entityType, EnumSet.of(CatalogStatisticsComponent.STORAGE_COMPOSITION)
				)
				.storageCompositionIfPresent()
				.orElseThrow()
				.parts();
			for (final StoragePartUsage part : parts) {
				if (EXCLUDED_STORAGE_PART_TYPES.contains(part.storagePartType())) {
					description.put(
						entityType + "/storage/" + part.storagePartType(),
						part.count() + " records, " + part.totalBytes() + " bytes"
					);
				}
			}
		}
		return description;
	}

	/**
	 * Returns a collection of the current catalog.
	 *
	 * @param entityType the entity type
	 * @return the collection
	 */
	@Nonnull
	private EntityCollection collectionOf(@Nonnull String entityType) {
		return VirtualReferencePartitionTransitionTest.collectionOf(this.evita, entityType);
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
}
