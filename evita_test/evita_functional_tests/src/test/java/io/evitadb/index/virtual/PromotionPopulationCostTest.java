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
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionSlot;
import io.evitadb.index.mutation.local.dataAccess.ReferenceResolutionCounter;
import io.evitadb.index.virtual.PromotionRehearsalInspector.Outcome;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO JNO - remove at the end of #1615
/**
 * Counts the work the population of a promoted single-owner partition from its first owner costs, and proves the
 * population exact while counting. The partitions are still real - the catalog only maintains the registry of the
 * single-owner ones next to them - so every partition gaining its second owner is rehearsed: its first owner is written
 * into a partition no collection knows, through the production population, and the result is compared with the live
 * partition on the spot (see {@link PromotionRehearsalInspector}).
 *
 * A second owner copying the `k` references of the first one promotes `k` partitions of one first owner in one root
 * mutation. Each population reads the first owner's references, so the root mutation must fetch them from the storage
 * once and serve every later population from what it read; and each population resolves the first owner's reference of
 * its partition among all `n` of them, which must cost a binary search - `log2(n)` key comparisons - and a walk over
 * the `d` references of the partition's key, never a scan.
 *
 * The population writes the locales the first owner had before the running root mutation, so a first owner whose locale
 * the root mutation changes is rebuilt exactly as the live partition holds it until the owner's own mutation moves
 * both. A first owner whose parts one population has read before the root mutation starts mutating it must be read
 * anew by every population after that.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Population cost of a promoted partition")
@Tag(INDEXING)
@Tag(REFERENCE)
class PromotionPopulationCostTest implements EvitaTestSupport {
	private static final String CATALOG = "promotionPopulationCost";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_PARAMETER = "parameter";
	private static final String ENTITY_CATEGORY = "category";
	private static final String REFERENCE_PARAMETERS = "parameters";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String REFERENCE_PRODUCTS = "products";
	private static final String REFERENCE_MAIN_CATEGORY = "mainCategory";
	private static final String REFERENCE_MAIN_PRODUCTS = "mainProducts";
	private static final String REFERENCE_NOTE_CATEGORY = "noteCategory";
	private static final String REFERENCE_NOTE_PRODUCTS = "noteProducts";
	private static final String ATTRIBUTE_NOTE = "note";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String ATTRIBUTE_LABEL = "label";
	private static final String COMPOUND_LABEL_AND_ORDER = "labelAndOrder";

	@Nullable private TestPaths paths;
	@Nullable private Evita evita;
	@Nonnull private final TransitionHistory history = new TransitionHistory();
	@Nonnull private final PromotionRehearsalInspector inspector = new PromotionRehearsalInspector();

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("PromotionPopulationCostTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		if (this.paths != null) {
			cleanupTestPaths(this.paths);
		}
	}

	/**
	 * Returns the holder of the virtual reference partitions of the catalog.
	 *
	 * @return the holder
	 */
	@Nonnull
	private VirtualReferencePartitionMaintenance maintenance() {
		return ((Catalog) evita().getCatalogInstance(CATALOG).orElseThrow()).getVirtualReferencePartitionMaintenance();
	}

	/**
	 * Returns the engine of the test.
	 *
	 * @return the engine
	 */
	@Nonnull
	private Evita evita() {
		return Objects.requireNonNull(this.evita);
	}

	/**
	 * Creates the catalog keeping its partitions real and maintaining the registry of the single-owner ones next to
	 * them, runs the setup in its warm-up session and takes the catalog live when asked to.
	 *
	 * @param state the catalog state to end in
	 * @param setup the schema and the initial data
	 */
	private void prepare(@Nonnull CatalogState state, @Nonnull Consumer<EvitaSessionContract> setup) {
		prepare(state, false, setup);
	}

	/**
	 * Creates the catalog, runs the setup in its warm-up session and takes the catalog live when asked to. The catalog
	 * either keeps its partitions real and maintains the registry of the single-owner ones next to them, or writes its
	 * single-owner partitions virtually - as registry rows, so that a partition gaining its second owner is promoted to
	 * an index object for real.
	 *
	 * @param state            the catalog state to end in
	 * @param writtenVirtually TRUE to write the single-owner partitions virtually
	 * @param setup            the schema and the initial data
	 */
	private void prepare(
		@Nonnull CatalogState state,
		boolean writtenVirtually,
		@Nonnull Consumer<EvitaSessionContract> setup
	) {
		evita().createCatalogWithVirtualReferencePartitions(CATALOG, writtenVirtually);
		evita().defineCatalog(CATALOG).updateViaNewSession(evita());
		final VirtualReferencePartitionMaintenance maintenance = maintenance();
		// a virtual catalog writes its single-owner partitions virtually by default, one with objects is switched on
		if (!writtenVirtually) {
			for (final String entityType : List.of(ENTITY_PRODUCT, ENTITY_PARAMETER, ENTITY_CATEGORY)) {
				maintenance.setMaintained(entityType, true);
			}
		}
		maintenance.setProbe(this.history);
		this.history.setPromotionInspector(this.inspector);
		evita().updateCatalog(
			CATALOG,
			session -> {
				setup.accept(session);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			}
		);
		assertEquals(0, this.inspector.getOutcomes().size(), "the setup must promote no partition");
	}

	/**
	 * Counts the index objects of the partitions the reference advertises in the live scope of the committed catalog,
	 * and the rows of its registry of single-owner partitions there.
	 *
	 * @param entityType    the entity type owning the reference
	 * @param referenceName the reference
	 * @return the number of index objects and the number of rows
	 */
	@Nonnull
	private ObjectsAndRows countObjectsAndRows(@Nonnull String entityType, @Nonnull String referenceName) {
		final EntityCollection collection = (EntityCollection) evita().getCatalogInstance(CATALOG)
			.orElseThrow()
			.getCollectionForEntity(entityType)
			.orElseThrow();
		final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, referenceName)
		);
		if (!(typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex)) {
			return new ObjectsAndRows(0, 0);
		}
		final int[] objects = {0};
		referencedTypeIndex.forEachReferenceIndexPrimaryKey(
			partitionPrimaryKey -> {
				if (collection.getIndexByPrimaryKeyIfExists(partitionPrimaryKey) != null) {
					objects[0]++;
				}
			}
		);
		final int[] rows = {0};
		referencedTypeIndex.getVirtualReferencePartitionIndex().forEachEntry(
			VirtualReferencePartitionSlot.PARTITION_OWNER, (partition, payload) -> rows[0]++
		);
		return new ObjectsAndRows(objects[0], rows[0]);
	}

	/**
	 * Returns `ceil(log2(n + 1))` - the most key comparisons a binary search for the lower bound of a run takes among
	 * `n` sorted references.
	 *
	 * @param n the number of references
	 * @return the number of comparisons
	 */
	private static int binarySearchComparisons(int n) {
		return 32 - Integer.numberOfLeadingZeros(n);
	}

	/**
	 * The first owner holds `k` single-owner partitions and a second owner copies all its references at once.
	 */
	@Nested
	@DisplayName("a second owner copying every reference of the first one")
	class CopiedReferences {
		/**
		 * How many partitions the first owner holds alone.
		 */
		private static final int K = 300;
		/**
		 * The owner of every partition before the copy.
		 */
		private static final int FIRST_OWNER = 1;
		/**
		 * The owner copying the references of {@link #FIRST_OWNER}.
		 */
		private static final int SECOND_OWNER = 2;

		/**
		 * Defines products referencing parameters through a reference indexed for filtering, with a sortable order, a
		 * localized sortable label and a localized compound of both, and writes {@link #K} parameters, the first owner
		 * referencing all of them and the second owner referencing none.
		 *
		 * @param session the session to write in
		 */
		private static void setUpParameters(@Nonnull EvitaSessionContract session) {
			session.defineEntitySchema(ENTITY_PARAMETER).updateVia(session);
			session.defineEntitySchema(ENTITY_PRODUCT)
				.withLocale(Locale.ENGLISH, Locale.GERMAN)
				.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().nullable())
				.withReferenceToEntity(
					REFERENCE_PARAMETERS, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
						.withAttribute(
							ATTRIBUTE_ORDER, Integer.class,
							thatIs -> thatIs.sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
						)
						.withAttribute(
							ATTRIBUTE_LABEL, String.class,
							thatIs -> thatIs.localized().sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
						)
						.withSortableAttributeCompound(
							COMPOUND_LABEL_AND_ORDER,
							new AttributeElement[]{
								new AttributeElement(ATTRIBUTE_LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
								new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							},
							thatIs -> thatIs.indexedInScope(Scope.LIVE, Scope.ARCHIVED)
						)
				)
				.updateVia(session);
			for (int pk = 1; pk <= K; pk++) {
				session.upsertEntity(session.createNewEntity(ENTITY_PARAMETER, pk));
			}
			final EntityBuilder firstOwner = session.createNewEntity(ENTITY_PRODUCT, FIRST_OWNER)
				.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "First");
			for (int pk = 1; pk <= K; pk++) {
				final int parameter = pk;
				firstOwner.setReference(
					REFERENCE_PARAMETERS, parameter,
					whichIs -> whichIs.setAttribute(ATTRIBUTE_ORDER, parameter)
						.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "Label " + parameter)
				);
			}
			firstOwner.upsertVia(session);
			session.upsertEntity(
				session.createNewEntity(ENTITY_PRODUCT, SECOND_OWNER)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Second")
			);
		}

		/**
		 * Lets the second owner copy every reference of the first one in one upsert, counting the work of the session's
		 * root mutation on the session's thread - a live catalog replays it onto the trunk on another thread after the
		 * commit, which counts nothing here.
		 *
		 * @param before the work done in the same session before the copy, uncounted
		 * @return the work the root mutation did
		 */
		@Nonnull
		private CopyWork copyReferences(@Nonnull Consumer<EvitaSessionContract> before) {
			final CopyWork[] work = new CopyWork[1];
			evita().updateCatalog(
				CATALOG,
				session -> {
					before.accept(session);
					final int rehearsalsBefore = PromotionPopulationCostTest.this.inspector.getOutcomes().size();
					final long fetchesBefore = maintenance().getForeignReferencesFetchCount();
					final ReferenceResolutionCounter counter = ReferenceResolutionCounter.start();
					try {
						final EntityBuilder secondOwner = session.getEntity(
								ENTITY_PRODUCT, SECOND_OWNER, entityFetchAllContent()
							)
							.orElseThrow()
							.openForWrite();
						for (int pk = 1; pk <= K; pk++) {
							final int parameter = pk;
							secondOwner.setReference(
								REFERENCE_PARAMETERS, parameter,
								whichIs -> whichIs.setAttribute(ATTRIBUTE_ORDER, K + parameter)
									.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "Copy " + parameter)
							);
						}
						secondOwner.upsertVia(session);
					} finally {
						ReferenceResolutionCounter.stop();
					}
					work[0] = new CopyWork(
						PromotionPopulationCostTest.this.inspector.getOutcomes().size() - rehearsalsBefore,
						maintenance().getForeignReferencesFetchCount() - fetchesBefore,
						counter
					);
				}
			);
			assertNotNull(work[0]);
			return work[0];
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("fetches the first owner's references once and resolves each of its references by binary search")
		void shouldFetchTheFirstOwnersReferencesOnceWhenEveryPartitionIsPromoted(CatalogState state) {
			prepare(state, CopiedReferences::setUpParameters);

			final CopyWork work = copyReferences(session -> {});

			assertEquals(K, work.rehearsals(), "every partition of the first owner is promoted once: " + work);
			final List<Outcome> outcomes = PromotionPopulationCostTest.this.inspector.assertAllMatched("the copy", 0);
			for (int i = 0; i < K; i++) {
				assertEquals(FIRST_OWNER, outcomes.get(i).owner(), "the first owner is rebuilt");
				assertEquals(SECOND_OWNER, outcomes.get(i).joiningOwner());
			}
			assertEquals(
				1, work.foreignReferencesFetches(),
				"the first owner's references must be fetched once for all " + K + " populations: " + work
			);
			assertResolvedByBinarySearch(work.resolutions());
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("populates every partition with a locale the first owner gained in an earlier root mutation")
		void shouldPopulateWithALocaleTheFirstOwnerGainedInAnEarlierRootMutation(CatalogState state) {
			prepare(state, CopiedReferences::setUpParameters);

			// the first owner gains German in a root mutation of its own, earlier in the same session - in a live
			// catalog in the same transaction - so the populations must read it as that root mutation left it
			final CopyWork work = copyReferences(
				session -> session.getEntity(ENTITY_PRODUCT, FIRST_OWNER, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Erste")
					.upsertVia(session)
			);

			assertEquals(K, work.rehearsals(), "every partition of the first owner is promoted once: " + work);
			final List<Outcome> outcomes = PromotionPopulationCostTest.this.inspector.assertAllMatched(
				"the copy after the locale change", 0
			);
			for (int i = 0; i < K; i++) {
				assertTrue(
					outcomes.get(i).rebuilt().containsKey(
						"sort/" + REFERENCE_PARAMETERS + "." + COMPOUND_LABEL_AND_ORDER + "@de"
					),
					"the German compound of the first owner must be part of the comparison: " + outcomes.get(i)
				);
				assertTrue(outcomes.get(i).pendingLocales().isEmpty(), "the first owner is not mutated by the copy");
			}
			assertEquals(1, work.foreignReferencesFetches(), "the first owner's references are fetched once: " + work);
			assertResolvedByBinarySearch(work.resolutions());
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("promotes every partition to an index object from one fetch of the first owner's references")
		void shouldPromoteEveryPartitionFromOneFetchWhenWrittenVirtually(CatalogState state) {
			prepare(state, true, CopiedReferences::setUpParameters);
			assertEquals(
				new ObjectsAndRows(0, K), countObjectsAndRows(ENTITY_PRODUCT, REFERENCE_PARAMETERS),
				"every partition of the first owner is a row before the copy"
			);

			final CopyWork work = copyReferences(session -> {});

			assertEquals(K, work.rehearsals(), "every partition of the first owner is promoted once: " + work);
			final List<Outcome> outcomes = PromotionPopulationCostTest.this.inspector.assertAllMatched(
				"the copy promoting for real", 0
			);
			for (int i = 0; i < K; i++) {
				assertEquals(FIRST_OWNER, outcomes.get(i).owner(), "the first owner is written into the object");
				assertEquals(SECOND_OWNER, outcomes.get(i).joiningOwner());
			}
			assertEquals(
				new ObjectsAndRows(K, 0), countObjectsAndRows(ENTITY_PRODUCT, REFERENCE_PARAMETERS),
				"every promoted partition is an index object, none is a row"
			);
			assertEquals(
				1, work.foreignReferencesFetches(),
				"the first owner's references must be fetched once for all " + K + " promotions: " + work
			);
			assertResolvedByBinarySearch(work.resolutions());
		}

		/**
		 * Asserts that every reference resolved during the copy cost at most a binary search over the entity's
		 * references and one comparison of the single reference of its key - and that the first owner's references
		 * were among those searched.
		 *
		 * @param resolutions the resolutions counted during the copy
		 */
		private static void assertResolvedByBinarySearch(@Nonnull ReferenceResolutionCounter resolutions) {
			assertTrue(
				resolutions.getResolutions() >= K,
				"every population resolves the first owner's reference of its partition: " + resolutions
			);
			assertTrue(
				resolutions.getMaxReferences() >= K,
				"the resolutions must have searched the first owner's " + K + " references: " + resolutions
			);
			// one reference per key - the walk over the run compares one key
			final int bound = binarySearchComparisons(resolutions.getMaxReferences()) + 1;
			assertTrue(
				resolutions.getMaxComparisons() <= bound,
				"a resolution compared more than " + bound + " reference keys: " + resolutions
			);
			assertTrue(
				resolutions.getComparisons() <= resolutions.getResolutions() * bound,
				"the resolutions compared more than " + bound + " reference keys each: " + resolutions
			);
		}
	}

	/**
	 * One root mutation promotes two partitions of one first owner and changes the first owner's locales in between.
	 */
	@Nested
	@DisplayName("a first owner mutated between two promotions of its partitions")
	class FirstOwnerInFlight {
		/**
		 * The category whose root mutation promotes both partitions.
		 */
		private static final int CATEGORY = 1;
		/**
		 * The only owner of both partitions of {@link #CATEGORY} before the root mutation.
		 */
		private static final int FIRST_OWNER = 2;
		/**
		 * The product joining the main category partition first.
		 */
		private static final int MAIN_PRODUCT = 3;
		/**
		 * The product joining the category partition after the first owner was mutated.
		 */
		private static final int CATEGORY_PRODUCT = 4;

		/**
		 * Defines products referencing categories through a reference carrying a sortable order and a localized
		 * sortable label with a localized compound of both, through a main category reference, and through a note
		 * category reference carrying a localized note; all three are reflected on the category inheriting their
		 * attributes. The first owner is the only product of the category in the first two references and has no note
		 * category; two other products reference nothing.
		 *
		 * @param session the session to write in
		 */
		private static void setUpCategories(@Nonnull EvitaSessionContract session) {
			session.defineEntitySchema(ENTITY_CATEGORY).withLocale(Locale.ENGLISH, Locale.GERMAN).updateVia(session);
			session.defineEntitySchema(ENTITY_PRODUCT)
				.withLocale(Locale.ENGLISH, Locale.GERMAN)
				.withReferenceToEntity(
					REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
						.withAttribute(
							ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.filterable().sortable().nullable()
						)
						.withAttribute(
							ATTRIBUTE_LABEL, String.class, thatIs -> thatIs.localized().sortable().nullable()
						)
						.withSortableAttributeCompound(
							COMPOUND_LABEL_AND_ORDER,
							new AttributeElement(ATTRIBUTE_LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
							new AttributeElement(ATTRIBUTE_ORDER, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
						)
				)
				.withReferenceToEntity(
					REFERENCE_MAIN_CATEGORY, ENTITY_CATEGORY, Cardinality.ZERO_OR_ONE,
					whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
				)
				.withReferenceToEntity(
					REFERENCE_NOTE_CATEGORY, ENTITY_CATEGORY, Cardinality.ZERO_OR_ONE,
					whichIs -> whichIs
						.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
						.withAttribute(ATTRIBUTE_NOTE, String.class, thatIs -> thatIs.localized().nullable())
				)
				.updateVia(session);
			session.defineEntitySchema(ENTITY_CATEGORY)
				.withReflectedReferenceToEntity(
					REFERENCE_PRODUCTS, ENTITY_PRODUCT, REFERENCE_CATEGORIES,
					whichIs -> whichIs
						.withCardinality(Cardinality.ZERO_OR_MORE)
						.withAttributesInherited(ATTRIBUTE_ORDER, ATTRIBUTE_LABEL)
						.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
				)
				.withReflectedReferenceToEntity(
					REFERENCE_MAIN_PRODUCTS, ENTITY_PRODUCT, REFERENCE_MAIN_CATEGORY,
					whichIs -> whichIs
						.withCardinality(Cardinality.ZERO_OR_MORE)
						.withAttributesInherited()
						.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
				)
				.withReflectedReferenceToEntity(
					REFERENCE_NOTE_PRODUCTS, ENTITY_PRODUCT, REFERENCE_NOTE_CATEGORY,
					whichIs -> whichIs
						.withCardinality(Cardinality.ZERO_OR_MORE)
						.withAttributesInherited()
						.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
				)
				.updateVia(session);
			session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, CATEGORY));
			session.upsertEntity(
				session.createNewEntity(ENTITY_PRODUCT, FIRST_OWNER)
					.setReference(
						REFERENCE_CATEGORIES, CATEGORY,
						ref -> ref.setAttribute(ATTRIBUTE_ORDER, FIRST_OWNER)
							.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "label " + FIRST_OWNER)
					)
					.setReference(REFERENCE_MAIN_CATEGORY, CATEGORY)
			);
			session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, MAIN_PRODUCT));
			session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, CATEGORY_PRODUCT));
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("a population after the first owner started being mutated reads its staged state")
		void shouldReadTheStagedFirstOwnerWhenItStartedBeingMutatedAfterAnEarlierPopulation(CatalogState state) {
			assertPopulationsReadTheFirstOwnerAsTheRootMutationSeesIt(state, false);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("a promotion after the first owner started being mutated writes its staged state into the object")
		void shouldPromoteFromTheStagedFirstOwnerWhenWrittenVirtually(CatalogState state) {
			assertPopulationsReadTheFirstOwnerAsTheRootMutationSeesIt(state, true);
			assertEquals(
				new ObjectsAndRows(1, 0), countObjectsAndRows(ENTITY_PRODUCT, REFERENCE_CATEGORIES),
				"the category partition is promoted to an index object"
			);
			assertEquals(
				new ObjectsAndRows(1, 0), countObjectsAndRows(ENTITY_PRODUCT, REFERENCE_MAIN_CATEGORY),
				"the main category partition is promoted to an index object"
			);
		}

		/**
		 * Runs the category mutation promoting both partitions of the first owner - the first before the root mutation
		 * mutates the first owner, the second after it gave the first owner a German value - and asserts that each
		 * population read the first owner as the root mutation saw it then, writing the locales it had before the root
		 * mutation.
		 *
		 * @param state            the catalog state
		 * @param writtenVirtually TRUE to write the single-owner partitions virtually, so that the promotions are real
		 */
		private void assertPopulationsReadTheFirstOwnerAsTheRootMutationSeesIt(
			@Nonnull CatalogState state,
			boolean writtenVirtually
		) {
			prepare(state, writtenVirtually, FirstOwnerInFlight::setUpCategories);

			// the category inserts its references ordered by name, and the reflected mutations run in that order: the
			// main product joins the main category partition (a population reading the first owner, which the root
			// mutation does not mutate yet), the first owner gets a note category with its first German value, and only
			// then the other product joins the category partition - a population reading the first owner while its
			// German locale is pending
			final int[] rehearsalsInSession = new int[1];
			evita().updateCatalog(
				CATALOG,
				session -> {
					session.getEntity(ENTITY_CATEGORY, CATEGORY, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(REFERENCE_MAIN_PRODUCTS, MAIN_PRODUCT)
						.setReference(
							REFERENCE_NOTE_PRODUCTS, FIRST_OWNER,
							ref -> ref.setAttribute(ATTRIBUTE_NOTE, Locale.GERMAN, "Notiz")
						)
						.setReference(REFERENCE_PRODUCTS, CATEGORY_PRODUCT)
						.upsertVia(session);
					rehearsalsInSession[0] = PromotionPopulationCostTest.this.inspector.getOutcomes().size();
				}
			);

			assertEquals(2, rehearsalsInSession[0], "both partitions of the first owner are promoted in the session");
			final List<Outcome> outcomes = PromotionPopulationCostTest.this.inspector.assertAllMatched(
				"the category mutation", 0
			);
			final Outcome mainCategory = outcomes.get(0);
			assertEquals(FIRST_OWNER, mainCategory.owner());
			assertEquals(MAIN_PRODUCT, mainCategory.joiningOwner());
			assertTrue(
				mainCategory.pendingLocales().isEmpty(),
				"the first population must read the first owner before it is mutated: " + mainCategory
			);
			final Outcome category = outcomes.get(1);
			assertEquals(FIRST_OWNER, category.owner());
			assertEquals(CATEGORY_PRODUCT, category.joiningOwner());
			assertTrue(
				category.pendingLocales().stream().anyMatch(it -> it.startsWith("+de")),
				"the second population must read the first owner with its German locale pending: " + category
			);
			assertTrue(
				category.rebuilt()
					.containsKey("sort/" + REFERENCE_CATEGORIES + "." + COMPOUND_LABEL_AND_ORDER + "@en") &&
					category.rebuilt().keySet().stream().noneMatch(it -> it.endsWith("@de")),
				"the second population must write the locales before the root mutation: " + category
			);
		}
	}

	/**
	 * The representation of the partitions of one reference in one scope.
	 *
	 * @param objects how many partitions are index objects
	 * @param rows    how many partitions are registry rows
	 */
	private record ObjectsAndRows(int objects, int rows) {
	}

	/**
	 * The work one root mutation did.
	 *
	 * @param rehearsals               how many partitions it promoted
	 * @param foreignReferencesFetches how many times it fetched the references of another entity from the storage
	 * @param resolutions              the resolutions of references it made
	 */
	private record CopyWork(
		int rehearsals,
		long foreignReferencesFetches,
		@Nonnull ReferenceResolutionCounter resolutions
	) {
	}
}
