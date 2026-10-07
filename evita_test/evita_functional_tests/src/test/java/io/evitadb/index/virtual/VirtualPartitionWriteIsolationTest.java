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
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.PromotionRehearsal;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.TransitionProbe;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.TransitionStage;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.virtual.TransitionHistory.InjectedTransitionFailure;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ExceptionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.*;
import static io.evitadb.index.virtual.VirtualReferencePartitionMirrorTest.ATTRIBUTE_CATEGORY_ORDER;
import static io.evitadb.index.virtual.VirtualReferencePartitionMirrorTest.ATTRIBUTE_PRODUCT_ORDER;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that the write path never reaches a virtual reference partition: every collection is maintained and read
 * virtually from its first write on, and the writes of every transition driver - owner counts going 0 -> 1 ->
 * 2 -> 1 -> 0, churns within one mutation, reflected partitions including two new categories of one product, a
 * relocation, a replacement while empty, a scope move, an entity removal, the mirror of every sortable type and of both
 * kinds of chain, a demotion by extraction, a mutation failing on its own and failures injected right after every kind
 * of transition - and those of a reflection named as the reference it reflects run to the same end they run to with
 * the read switch off. Every exhaustive `switch` over a partition
 * on the write path throws on a virtual one, and every place there holding a partition asserts it is not virtual, so
 * any read resolution leaking into the write path fails one of these writes.
 *
 * The probe drives writes only - no query runs, so nothing here depends on which query routes read virtual partitions
 * yet. Every transition the engine passes is checked to pass with the read switch of its collection on, and the run
 * must pass every kind of transition, so the isolation cannot hold vacuously.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Write path isolation from virtual reference partitions")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class VirtualPartitionWriteIsolationTest implements EvitaTestSupport {
	private static final String CATALOG = "virtualPartitionWriteIsolation";
	private static final String TYPES_CATALOG = "virtualPartitionWriteIsolationTypes";
	private static final String CHAINS_CATALOG = "virtualPartitionWriteIsolationChains";
	private static final String HARD_REFLECTION_CATALOG = "virtualPartitionWriteIsolationHardReflection";
	/**
	 * Categories no product of the fixture references.
	 */
	private static final int FREE_CATEGORY = 11;
	/**
	 * A product the fixture does not create.
	 */
	private static final int NEW_PRODUCT = 9;

	private TestPaths paths;
	private Evita evita;

	@AfterEach
	void tearDown() {
		if (this.evita != null) {
			this.evita.close();
			this.evita = null;
		}
		cleanupTestPaths(this.paths);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("every transition driver writes with the read switch on, and every transition is passed")
	void shouldWriteEveryTransitionWithTheReadSwitchOn(CatalogState state) {
		this.paths = createTestPaths("VirtualPartitionWriteIsolationTest_" + state);
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		final ReadSwitchProbe probe = new ReadSwitchProbe();
		prepare(CATALOG, state, probe, SingleOwnerPartitionFixture::setUpWithReflectedProducts);
		prepare(TYPES_CATALOG, state, probe, VirtualReferencePartitionMirrorTest::setUpTypes);
		prepare(CHAINS_CATALOG, state, probe, VirtualReferencePartitionMirrorTest::setUpChains);
		prepare(HARD_REFLECTION_CATALOG, state, probe, VirtualPartitionWriteIsolationTest::setUpHardReflection);

		writeOwnerCountTransitions();
		writeChurns();
		writeReflectedPartitions();
		writeMovesAndRemovals();
		writeFailures(probe);
		writeMirroredValues();
		writeHardReflection();

		assertTrue(probe.violations.isEmpty(), "Transitions passed with the read switch off: " + probe.violations);
		for (final TransitionStage stage : List.of(
			TransitionStage.ENTERED, TransitionStage.SHARED, TransitionStage.DEMOTED, TransitionStage.VACATED,
			TransitionStage.MIRRORED
		)) {
			assertTrue(
				probe.passed.getOrDefault(stage, 0) > 0,
				"The drivers must pass the " + stage + " transition: " + probe.passed
			);
		}
		assertTrue(probe.promotionPoints > 0, "The drivers must pass a promotion point");
		assertEquals(5, probe.history.getInjectedFailures(), "Every injected failure must have struck");
		for (final String catalog : List.of(CATALOG, TYPES_CATALOG, CHAINS_CATALOG, HARD_REFLECTION_CATALOG)) {
			final Catalog theCatalog = (Catalog) this.evita.getCatalogInstance(catalog).orElseThrow();
			for (final String entityType : theCatalog.getEntityTypes()) {
				final EntityCollection collection =
					(EntityCollection) theCatalog.getCollectionForEntity(entityType).orElseThrow();
				assertTrue(
					collection.isVirtualReferencePartitionsReadVirtually(), entityType + " must be read virtually"
				);
				// the writes ended where they end with the read switch off - the registry follows its partitions
				VirtualReferencePartitionOracle.assertConsistent(catalog + " at the end", collection);
			}
		}
	}

	/**
	 * Defines a catalog maintained and read virtually from its first write on, writes its fixture and, for `ALIVE`,
	 * takes it live.
	 *
	 * @param catalog the catalog
	 * @param state   the catalog state to end in
	 * @param probe   the probe observing the transitions
	 * @param setup   the schema and the initial data
	 */
	private void prepare(
		@Nonnull String catalog,
		@Nonnull CatalogState state,
		@Nonnull ReadSwitchProbe probe,
		@Nonnull Consumer<EvitaSessionContract> setup
	) {
		this.evita.defineCatalog(catalog).updateViaNewSession(this.evita);
		final VirtualReferencePartitionMaintenance maintenance =
			((Catalog) this.evita.getCatalogInstance(catalog).orElseThrow()).getVirtualReferencePartitionMaintenance();
		for (final String entityType : ENTITY_TYPES) {
			maintenance.setMaintained(entityType, true);
			maintenance.setReadVirtually(entityType, true);
		}
		probe.watch(maintenance);
		maintenance.setProbe(probe);
		this.evita.updateCatalog(catalog, setup);
		if (state == CatalogState.ALIVE) {
			this.evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);
		}
	}

	/**
	 * A partition goes from no owner to one, two, one and none.
	 */
	private void writeOwnerCountTransitions() {
		write(updateProduct(1, it -> it.setReference(REFERENCE_CATEGORIES, FREE_CATEGORY)));
		write(updateProduct(2, it -> it.setReference(REFERENCE_CATEGORIES, FREE_CATEGORY)));
		write(updateProduct(2, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_CATEGORY)));
		write(updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_CATEGORY)));
	}

	/**
	 * Churns within one mutation: the only owner removing and re-inserting its reference, a reflected partition going
	 * from one owner to two and back, and one going from two owners to one and back.
	 */
	private void writeChurns() {
		write(
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 4, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(existingReferenceKey(session, 4, 4)),
					new InsertReferenceMutation(newReferenceKey(4)),
					new ReferenceAttributeMutation(newReferenceKey(4), new UpsertAttributeMutation(ATTRIBUTE_ORDER, 4))
				)
			)
		);
		write(
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 4, EntityExistence.MUST_EXIST,
					new InsertReferenceMutation(newReferenceKey(FREE_CATEGORY + 1)),
					new RemoveReferenceMutation(existingReferenceKey(session, 4, 4))
				)
			)
		);
		write(
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 1, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(existingReferenceKey(session, 1, SHARED_CATEGORY)),
					new InsertReferenceMutation(newReferenceKey(FREE_CATEGORY + 2))
				)
			)
		);
	}

	/**
	 * Reflected partitions: a new product in one category and a new product in two categories in one mutation.
	 */
	private void writeReflectedPartitions() {
		write(
			session -> {
				session.upsertEntity(newProduct(session, NEW_PRODUCT));
				session.upsertEntity(
					newProduct(session, NEW_PRODUCT + 1).setReference(REFERENCE_CATEGORIES, FREE_CATEGORY + 3)
				);
			}
		);
	}

	/**
	 * A relocation there and back, a replacement while the partition is empty, a scope move there and back and the
	 * removal of an entity.
	 */
	private void writeMovesAndRemovals() {
		write(
			updateProduct(
				5,
				it -> it.removeReference(REFERENCE_CATEGORIES, 5).setReference(REFERENCE_CATEGORIES, FREE_CATEGORY + 4)
			)
		);
		write(
			updateProduct(
				5,
				it -> it.removeReference(REFERENCE_CATEGORIES, FREE_CATEGORY + 4).setReference(REFERENCE_CATEGORIES, 5)
			)
		);
		write(
			session -> {
				updateProduct(6, it -> it.removeReference(REFERENCE_CATEGORIES, 6)).accept(session);
				updateProduct(3, it -> it.setReference(REFERENCE_CATEGORIES, 6)).accept(session);
			}
		);
		write(session -> session.archiveEntity(ENTITY_PRODUCT, 4));
		write(session -> session.restoreEntity(ENTITY_PRODUCT, 4));
		write(session -> session.deleteEntity(ENTITY_PRODUCT, 6));
	}

	/**
	 * A mutation failing on its own after entering a partition, and failures injected right after an entry, a share,
	 * a vacation, a demotion and a reflected entry.
	 *
	 * @param probe the probe injecting the failures
	 */
	private void writeFailures(@Nonnull ReadSwitchProbe probe) {
		failing(
			UniqueValueViolationException.class,
			session -> session.upsertEntity(
				new EntityUpsertMutation(
					ENTITY_PRODUCT, 4, EntityExistence.MUST_EXIST,
					new InsertReferenceMutation(newReferenceKey(FREE_CATEGORY + 5)),
					new UpsertAttributeMutation(ATTRIBUTE_CODE, "P1")
				)
			)
		);
		probe.history.failAfter(TransitionStage.ENTERED, ENTITY_PRODUCT);
		failing(
			InjectedTransitionFailure.class,
			updateProduct(4, it -> it.setReference(REFERENCE_CATEGORIES, FREE_CATEGORY + 6))
		);
		// category 5 is owned by product 5 alone - after the relocation there and back
		probe.history.failAfter(TransitionStage.SHARED, ENTITY_PRODUCT);
		failing(InjectedTransitionFailure.class, updateProduct(3, it -> it.setReference(REFERENCE_CATEGORIES, 5)));
		// the category product 4 took in the one-two-one churn is owned by it alone
		probe.history.failAfter(TransitionStage.VACATED, ENTITY_PRODUCT);
		failing(
			InjectedTransitionFailure.class,
			updateProduct(4, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_CATEGORY + 1))
		);
		write(
			session -> {
				updateProduct(1, it -> it.setReference(REFERENCE_CATEGORIES, FREE_CATEGORY + 7)).accept(session);
				updateProduct(2, it -> it.setReference(REFERENCE_CATEGORIES, FREE_CATEGORY + 7)).accept(session);
			}
		);
		probe.history.failAfter(TransitionStage.DEMOTED, ENTITY_PRODUCT);
		failing(
			InjectedTransitionFailure.class,
			updateProduct(2, it -> it.removeReference(REFERENCE_CATEGORIES, FREE_CATEGORY + 7))
		);
		probe.history.failAfter(TransitionStage.ENTERED, ENTITY_CATEGORY);
		failing(
			InjectedTransitionFailure.class, session -> session.upsertEntity(newProduct(session, NEW_PRODUCT + 2))
		);
	}

	/**
	 * The mirror of every sortable type and compound - values replaced and a partition demoted by extraction - and of
	 * both kinds of chain on both sides of a reflected reference.
	 */
	private void writeMirroredValues() {
		write(
			TYPES_CATALOG,
			updateProduct(
				1,
				it -> it.updateReference(
					REFERENCE_CATEGORIES, 1, ref -> VirtualReferencePartitionMirrorTest.fillReference(ref, 7)
				)
			)
		);
		write(
			TYPES_CATALOG,
			updateProduct(
				2, it -> it.removeReference(REFERENCE_CATEGORIES, VirtualReferencePartitionMirrorTest.SHARED_CATEGORY)
			)
		);
		write(
			CHAINS_CATALOG,
			updateProduct(
				3,
				it -> it.updateReference(
					REFERENCE_CATEGORIES, 3,
					ref -> ref.setAttribute(ATTRIBUTE_PRODUCT_ORDER, new Predecessor(1))
						.setAttribute(ATTRIBUTE_CATEGORY_ORDER, new ReferencedEntityPredecessor(1))
				)
			)
		);
		write(
			CHAINS_CATALOG,
			updateProduct(
				2,
				it -> it.updateReference(
					REFERENCE_CATEGORIES, 2,
					ref -> ref.removeAttribute(ATTRIBUTE_PRODUCT_ORDER)
				)
			)
		);
	}

	/**
	 * The writes of a reflection named as the reference it reflects - the shape whose counterpart partitions are
	 * looked up by the reflected entity's own primary key: products joining a category, sharing it and leaving it.
	 */
	private void writeHardReflection() {
		write(
			HARD_REFLECTION_CATALOG,
			session -> session.upsertEntity(
				session.createNewEntity(ENTITY_PRODUCT, 4).setReference(REFERENCE_CATEGORIES, 4)
			)
		);
		write(HARD_REFLECTION_CATALOG, updateProduct(1, it -> it.setReference(REFERENCE_CATEGORIES, 2)));
		write(HARD_REFLECTION_CATALOG, updateProduct(1, it -> it.removeReference(REFERENCE_CATEGORIES, 2)));
		write(HARD_REFLECTION_CATALOG, updateProduct(3, it -> it.removeReference(REFERENCE_CATEGORIES, 3)));
		write(HARD_REFLECTION_CATALOG, session -> session.deleteEntity(ENTITY_PRODUCT, 2));
	}

	/**
	 * Defines products in categories whose reflection on the category carries the very name of the reference it
	 * reflects, and three products each in a category of its own.
	 *
	 * @param session the session to write in
	 */
	private static void setUpHardReflection(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
			)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withReflectedReferenceToEntity(
				REFERENCE_CATEGORIES, ENTITY_PRODUCT, REFERENCE_CATEGORIES,
				whichIs -> whichIs
					.withCardinality(Cardinality.ZERO_OR_MORE)
					.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
			)
			.updateVia(session);
		for (int pk = 1; pk <= 4; pk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, pk));
		}
		for (int pk = 1; pk <= 3; pk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, pk).setReference(REFERENCE_CATEGORIES, pk));
		}
	}

	/**
	 * Runs the writes in one session of the main catalog.
	 *
	 * @param writes the writes
	 */
	private void write(@Nonnull Consumer<EvitaSessionContract> writes) {
		write(CATALOG, writes);
	}

	/**
	 * Runs the writes in one session of a catalog.
	 *
	 * @param catalog the catalog
	 * @param writes  the writes
	 */
	private void write(@Nonnull String catalog, @Nonnull Consumer<EvitaSessionContract> writes) {
		this.evita.updateCatalog(catalog, writes);
	}

	/**
	 * Runs writes in one session of the main catalog that must fail with the given exception. The failure is caught
	 * inside the session, which then closes normally - the failed mutation alone is rolled back.
	 *
	 * @param expected the exception the failure must carry in its cause chain
	 * @param writes   the writes
	 */
	private void failing(@Nonnull Class<? extends Throwable> expected, @Nonnull Consumer<EvitaSessionContract> writes) {
		write(
			session -> {
				try {
					writes.accept(session);
				} catch (RuntimeException ex) {
					if (ExceptionUtils.findInCauseChain(ex, expected) == null) {
						throw ex;
					}
					return;
				}
				fail("The writes were expected to fail with " + expected.getSimpleName());
			}
		);
	}

	/**
	 * Returns the key, with its internal primary key, of an existing category reference of a product.
	 *
	 * @param session  the session to read in
	 * @param product  the product
	 * @param category the category
	 * @return the reference key
	 */
	@Nonnull
	private static ReferenceKey existingReferenceKey(@Nonnull EvitaSessionContract session, int product, int category) {
		return session.getEntity(ENTITY_PRODUCT, product, referenceContentAll())
			.orElseThrow()
			.getReference(REFERENCE_CATEGORIES, category)
			.orElseThrow()
			.getReferenceKey();
	}

	/**
	 * Returns the key of a category reference created by the mutation that names it.
	 *
	 * @param category the category
	 * @return the reference key
	 */
	@Nonnull
	private static ReferenceKey newReferenceKey(int category) {
		return new ReferenceKey(REFERENCE_CATEGORIES, category, -1);
	}

	/**
	 * Counts every transition and promotion point the engine passes and checks that the collection passing it is read
	 * virtually at that moment; injects failures through the {@link TransitionHistory} it delegates to.
	 */
	private static final class ReadSwitchProbe implements TransitionProbe {
		/**
		 * The history the failures are injected through.
		 */
		private final TransitionHistory history = new TransitionHistory();
		/**
		 * The switch holders of the catalogs the probe watches.
		 */
		private final List<VirtualReferencePartitionMaintenance> holders = new ArrayList<>(3);
		/**
		 * How many transitions of each kind were passed.
		 */
		private final Map<TransitionStage, Integer> passed = new EnumMap<>(TransitionStage.class);
		/**
		 * The transitions passed while the collection was not read virtually.
		 */
		private final List<String> violations = new ArrayList<>(4);
		/**
		 * How many promotion points were passed.
		 */
		private int promotionPoints;

		/**
		 * Starts watching the switch holder of a catalog.
		 *
		 * @param holder the switch holder
		 */
		void watch(@Nonnull VirtualReferencePartitionMaintenance holder) {
			this.holders.add(holder);
		}

		@Override
		public synchronized void transitionPassed(
			@Nonnull TransitionStage stage,
			@Nonnull String entityType,
			@Nonnull String referenceName,
			@Nonnull Scope scope,
			int partitionPrimaryKey,
			@Nonnull Object rootMutation
		) {
			this.passed.merge(stage, 1, Integer::sum);
			if (this.holders.stream().noneMatch(it -> it.isReadVirtually(entityType))) {
				this.violations.add(
					stage + " of " + entityType + "." + referenceName + " partition " + partitionPrimaryKey
				);
			}
			this.history.transitionPassed(stage, entityType, referenceName, scope, partitionPrimaryKey, rootMutation);
		}

		@Override
		public void rootMutationFinished(
			@Nonnull TransitionStage stage,
			@Nonnull Object rootMutation,
			boolean trunkReplay
		) {
			this.history.rootMutationFinished(stage, rootMutation, trunkReplay);
		}

		@Override
		public synchronized void promotionPointPassed(
			@Nonnull PromotionRehearsal rehearsal,
			@Nonnull Object rootMutation
		) {
			this.promotionPoints++;
		}
	}

}
