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

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import io.evitadb.index.virtual.DifferentialEngines.EngineLoad;
import io.evitadb.index.virtual.DifferentialEngines.Reload;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.index.virtual.DifferentialEngines.CATALOG;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that the switch maintaining virtual reference partitions is on from the moment a catalog is loaded from disk,
 * so that the mutations its write-ahead log replays at load are maintained like any other. The switch is not persisted:
 * the engine is told which collections to maintain before it starts loading, and the catalog seeds its fresh switch
 * from that before it attaches its first collection.
 *
 * Both cases run through {@link DifferentialEngines}, whose maintaining engine is booted that way on every restart, so
 * each one also compares the two engines after the load.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition maintenance across a catalog load")
@Tag(ENGINE)
@Tag(REFERENCE)
@Tag(WAL)
class VirtualReferencePartitionRecoveryTest implements EvitaTestSupport {

	/**
	 * Returns the switch holder of the currently published catalog of an engine.
	 *
	 * @param evita the engine
	 * @return the switch holder
	 */
	@Nonnull
	private static VirtualReferencePartitionMaintenance maintenanceOf(@Nonnull Evita evita) {
		return ((Catalog) evita.getCatalogInstance(CATALOG).orElseThrow()).getVirtualReferencePartitionMaintenance();
	}

	/**
	 * Lists the live products referencing a category.
	 *
	 * @param evita    the engine
	 * @param category the category primary key
	 * @return the primary keys of the products, ascending
	 */
	@Nonnull
	private static List<Integer> ownersOf(@Nonnull Evita evita, int category) {
		return evita.queryCatalog(
			CATALOG,
			(Function<EvitaSessionContract, List<Integer>>) session -> session.query(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(category)))
					),
					EntityReference.class
				)
				.getRecordData()
				.stream()
				.map(EntityReference::getPrimaryKey)
				.sorted()
				.toList()
		);
	}

	/**
	 * Asserts both engines see the same products referencing a category, and that they are the expected ones.
	 *
	 * @param engines  the engines
	 * @param category the category primary key
	 * @param expected the expected primary keys of the products, ascending
	 * @param when     names the moment in the failure message
	 */
	private static void assertOwners(
		@Nonnull DifferentialEngines engines,
		int category,
		@Nonnull List<Integer> expected,
		@Nonnull String when
	) {
		assertAll(
			() -> assertEquals(
				expected, ownersOf(engines.getReference(), category),
				"Owners of category " + category + " in the reference engine " + when
			),
			() -> assertEquals(
				expected, ownersOf(engines.getMaintaining(), category),
				"Owners of category " + category + " in the maintaining engine " + when
			)
		);
	}

	/**
	 * A catalog closed cleanly leaves nothing in its write-ahead log that its last bootstrap record does not cover, so
	 * loading it again replays nothing - and the maintaining engine must still come up with the switch on, without
	 * anything turning it on after the load.
	 */
	@Nested
	@DisplayName("Initialization without a write-ahead log tail")
	class InitializationWithoutWalTail {
		/**
		 * The engines of the test, closed after it.
		 */
		@Nullable private DifferentialEngines engines;

		@AfterEach
		void tearDown() {
			if (this.engines != null) {
				this.engines.close();
				this.engines = null;
			}
		}

		@Test
		@DisplayName("a clean close leaves no tail, the load replays nothing and the switch is on from the load")
		void shouldLoadWithoutReplayWithTheSwitchOn() {
			final DifferentialEngines theEngines = new DifferentialEngines(
				VirtualReferencePartitionRecoveryTest.this, "recoveryWithoutTail", ENTITY_TYPES
			);
			this.engines = theEngines;
			theEngines.prepare(CatalogState.ALIVE, SingleOwnerPartitionFixture::setUp);
			// a commit in ALIVE, so the write-ahead log is not empty - it is merely covered by the bootstrap record
			theEngines.step(
				"a product joins a new category", updateProduct(1, it -> it.setReference(REFERENCE_CATEGORIES, 11))
			);

			final Reload reload = theEngines.restart();

			assertNoReplay("reference", reload.reference());
			assertNoReplay("maintaining", reload.maintaining());
			final VirtualReferencePartitionMaintenance maintaining = maintenanceOf(theEngines.getMaintaining());
			final VirtualReferencePartitionMaintenance reference = maintenanceOf(theEngines.getReference());
			for (final String entityType : ENTITY_TYPES) {
				assertTrue(
					maintaining.isMaintained(entityType),
					"The switch of `" + entityType + "` must be on in the maintaining engine as soon as it is loaded"
				);
				assertFalse(
					reference.isMaintained(entityType),
					"The switch of `" + entityType + "` must stay off in the reference engine"
				);
			}
			assertOwners(theEngines, 11, List.of(1), "after the restart");
		}

		/**
		 * Asserts that the load of one engine had nothing to replay and replayed nothing.
		 *
		 * @param engine names the engine in failure messages
		 * @param load   what the load found and did
		 */
		private static void assertNoReplay(@Nonnull String engine, @Nonnull EngineLoad load) {
			assertAll(
				() -> assertEquals(
					load.appliedVersion(), load.imageVersion(),
					"The clean close of the " + engine + " engine must have published every version it applied"
				),
				() -> assertEquals(
					load.imageVersion(), load.loadedVersion(),
					"The " + engine + " engine must load the last checkpointed version and replay nothing on top of it"
				),
				() -> assertEquals(
					0L, load.maintainedMutationCount(),
					"No entity mutation may run while the " + engine + " engine loads a catalog with no tail"
				)
			);
		}
	}

	/**
	 * A catalog whose engine stopped without a clean close - simulated by booting from a copy of its storage taken
	 * while it ran - holds the transactions committed after its last checkpoint in its write-ahead log only, and
	 * replays them when it is loaded. The replay must run with the switch already on.
	 *
	 * The fixture makes the tail real rather than assuming it:
	 *
	 * - checkpoints are deferred by an interval no test outlives, with sync writes on (the interval is ignored without
	 *   them), so every commit in `ALIVE` stays in the write-ahead log until something forces a checkpoint;
	 * - the single-owner partitions the tail changes are checkpointed first - by a clean restart, which publishes what
	 *   going live left deferred - and verified;
	 * - the tail takes the last owner of one checkpointed single-owner partition away (one owner to none) and adds a
	 *   second owner to another (one owner to two), each in its own commit;
	 * - the copy of each engine's storage is taken quiescent, after the engine incorporated its whole write-ahead log
	 *   and before it is closed, and the version its last bootstrap record names is read from the copy before the
	 *   copy is booted - replay itself checkpoints, so the version the engine reports afterwards cannot tell that a
	 *   replay happened.
	 *
	 * The switch is not read by anything that writes yet, so the replayed mutations cannot show what the switch did to
	 * them; what is asserted here is that the replay ran and that every replayed entity mutation found the switch on.
	 * Once the maintenance writes the registry of single-owner partitions, this test gains the registry assertions -
	 * the partition vacated by the tail gone from the registry, the partition joined by a second owner no longer
	 * registered as a single-owner one - together with the counterfactual that turns the switch on only after the
	 * load and must turn both of them red.
	 */
	@Nested
	@DisplayName("Recovery from a write-ahead log tail")
	class RecoveryFromWalTail {
		/**
		 * Long enough that no checkpoint falls due on its own during the test - every checkpoint observed here is one
		 * the test provoked.
		 */
		private static final long NEVER_ELAPSES_MILLIS = 3_600_000L;
		/**
		 * The category product 4 alone references - the single-owner partition the tail vacates.
		 */
		private static final int VACATED_CATEGORY = 4;
		/**
		 * The category product 5 alone references - the single-owner partition the tail gives a second owner.
		 */
		private static final int JOINED_CATEGORY = 5;
		/**
		 * The product the tail adds to {@link #JOINED_CATEGORY}.
		 */
		private static final int JOINING_PRODUCT = 6;
		/**
		 * How many entity mutations the tail commits - one product update per commit.
		 */
		private static final long TAIL_ENTITY_MUTATIONS = 2L;
		/**
		 * The engines of the test, closed after it.
		 */
		@Nullable private DifferentialEngines engines;

		@AfterEach
		void tearDown() {
			if (this.engines != null) {
				this.engines.close();
				this.engines = null;
			}
		}

		@Test
		@DisplayName("mutations committed after the last checkpoint are replayed at load with the switch on")
		void shouldReplayTheTailWithTheSwitchOn() {
			final DifferentialEngines theEngines = new DifferentialEngines(
				VirtualReferencePartitionRecoveryTest.this,
				"recoveryFromTail",
				ENTITY_TYPES,
				TransactionOptions.builder().checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS).build()
			);
			this.engines = theEngines;
			theEngines.prepare(CatalogState.ALIVE, SingleOwnerPartitionFixture::setUp);

			// going live is an ALIVE round of its own and is deferred like any other; the clean close publishes it
			final Reload checkpointed = theEngines.restart();
			assertCheckpointed("reference", checkpointed.reference(), theEngines.getReference());
			assertCheckpointed("maintaining", checkpointed.maintaining(), theEngines.getMaintaining());
			assertOwners(theEngines, VACATED_CATEGORY, List.of(VACATED_CATEGORY), "before the tail");
			assertOwners(theEngines, JOINED_CATEGORY, List.of(JOINED_CATEGORY), "before the tail");

			theEngines.step(
				"the last owner leaves a checkpointed single-owner partition",
				updateProduct(VACATED_CATEGORY, it -> it.removeReference(REFERENCE_CATEGORIES, VACATED_CATEGORY))
			);
			theEngines.step(
				"a second owner joins a checkpointed single-owner partition",
				updateProduct(JOINING_PRODUCT, it -> it.setReference(REFERENCE_CATEGORIES, JOINED_CATEGORY))
			);

			final Reload recovered = theEngines.crashAndRecover();

			assertReplayed("reference", recovered.reference());
			assertReplayed("maintaining", recovered.maintaining());
			assertEquals(
				TAIL_ENTITY_MUTATIONS, recovered.maintaining().maintainedMutationCount(),
				"Every entity mutation the maintaining engine replayed must have run with the switch on"
			);
			assertEquals(
				0L, recovered.reference().maintainedMutationCount(),
				"No entity mutation the reference engine replayed may run with the switch on"
			);
			assertOwners(theEngines, VACATED_CATEGORY, List.of(), "after the recovery");
			assertOwners(theEngines, JOINED_CATEGORY, List.of(JOINED_CATEGORY, JOINING_PRODUCT), "after the recovery");
		}

		/**
		 * Asserts that everything one engine applied before the restart is checkpointed after it.
		 *
		 * @param engine names the engine in failure messages
		 * @param load   what the load after the restart found and did
		 * @param evita  the restarted engine
		 */
		private static void assertCheckpointed(@Nonnull String engine, @Nonnull EngineLoad load, @Nonnull Evita evita) {
			final Catalog catalog = (Catalog) evita.getCatalogInstance(CATALOG).orElseThrow();
			assertAll(
				() -> assertEquals(
					load.appliedVersion(), load.imageVersion(),
					"The clean close of the " + engine + " engine must have checkpointed what going live deferred"
				),
				() -> assertEquals(
					catalog.getVersion(), catalog.getLastPersistedCatalogVersion(),
					"The restarted " + engine + " engine must start with nothing left to checkpoint"
				)
			);
		}

		/**
		 * Asserts that the copy of one engine held a write-ahead log tail and that loading the copy replayed it whole.
		 *
		 * @param engine names the engine in failure messages
		 * @param load   what the load from the copy found and did
		 */
		private static void assertReplayed(@Nonnull String engine, @Nonnull EngineLoad load) {
			assertAll(
				() -> assertTrue(
					load.persistedVersion() < load.appliedVersion(),
					"When the " + engine + " engine was copied, the tail must not have been checkpointed yet " +
						"(checkpointed " + load.persistedVersion() + ", applied " + load.appliedVersion() + ")"
				),
				() -> assertEquals(
					load.persistedVersion(), load.imageVersion(),
					"The copy of the " + engine + " engine must start from the last checkpoint the engine published"
				),
				() -> assertTrue(
					load.loadedVersion() > load.imageVersion(),
					"Loading the copy of the " + engine + " engine must have replayed the tail on top of version " +
						load.imageVersion() + " (loaded " + load.loadedVersion() + ")"
				),
				() -> assertEquals(
					load.appliedVersion(), load.loadedVersion(),
					"The replay in the " + engine + " engine must reach the version the engine had applied"
				)
			);
		}
	}
}
