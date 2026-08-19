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

package io.evitadb.index.usage;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.usage.SchemaCapabilityKey.Capability;
import io.evitadb.index.usage.SchemaCapabilityKey.ElementKind;
import io.evitadb.index.usage.SchemaCapabilityUsageRegistry.UsageEntry;
import io.evitadb.test.Entities;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the two things {@link SchemaCapabilityUsageRegistry} promises and nothing else in the suite checks:
 * **one key means one holder**, however many threads ask for it at once, and **a capability the schema stopped
 * declaring loses its holder** so that re-declaring it later starts a fresh observation window.
 *
 * The first is what lets every hot path resolve once and then increment a bare reference - if two threads could ever
 * receive two different holders for one key, half the recordings would land in an object nobody reads again.
 *
 * The second is the whole reason the registry has an opinion about schemas at all. Counters that outlived the flag they
 * were counting would report a dropped-and-re-added attribute as busy for traffic that happened before it was
 * re-added, which is precisely the misreading this surface exists to prevent.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see SchemaCapabilityUsageRegistry
 */
@DisplayName("Schema capability usage registry")
@Tag(ENGINE)
@Tag(INDEXING)
@Tag(MANAGEMENT)
class SchemaCapabilityUsageRegistryTest {

	/** Entity attribute declared `unique()` and `sortable()` - and therefore filterable too, implicitly. */
	private static final String ATTRIBUTE_CODE = "code";
	/** Entity attribute carrying only `filterable()` - the one the pruning tests take away and give back. */
	private static final String ATTRIBUTE_EAN = "ean";
	/** Sortable compound the entity declares directly. */
	private static final String COMPOUND_CODE_WITH_EAN = "codeWithEan";
	/** Reference whose attributes stand in for the "declared on a reference" half of the key space. */
	private static final String REFERENCE_STOCKS = "stocks";
	/** Entity type the reference points at - external, so no second schema has to exist for it. */
	private static final String EXTERNAL_STOCK_TYPE = "stock";
	/** Reference attribute carrying `filterable()` and `sortable()`. */
	private static final String ATTRIBUTE_QUANTITY = "quantity";
	/** Second reference attribute, present so the reference compound has two elements to order by. */
	private static final String ATTRIBUTE_WAREHOUSE = "warehouse";
	/** Sortable compound declared on the reference rather than on the entity. */
	private static final String COMPOUND_QUANTITY_WITH_WAREHOUSE = "quantityWithWarehouse";
	/** Daemon threads, so a resolving thread that stalled cannot keep the surefire JVM alive after the test ends. */
	private static final ThreadFactory DAEMON_THREADS = runnable -> {
		final Thread thread = new Thread(runnable, "schema-capability-resolver");
		thread.setDaemon(true);
		return thread;
	};

	private EntitySchema emptySchema;
	private CatalogSchema catalogSchema;
	private SchemaCapabilityUsageRegistry registry;

	@BeforeEach
	void setUp() {
		this.emptySchema = EntitySchema._internalBuild(Entities.PRODUCT);
		this.catalogSchema = CatalogSchema._internalBuild(
			APITestConstants.TEST_CATALOG,
			NamingConvention.generate(APITestConstants.TEST_CATALOG),
			null,
			EnumSet.allOf(CatalogEvolutionMode.class),
			new EntitySchemaProvider() {
				@Nonnull
				@Override
				public Collection<EntitySchemaContract> getEntitySchemas() {
					return List.of(SchemaCapabilityUsageRegistryTest.this.emptySchema);
				}

				@Nonnull
				@Override
				public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
					return Entities.PRODUCT.equals(entityType) ?
						Optional.of(SchemaCapabilityUsageRegistryTest.this.emptySchema) : Optional.empty();
				}
			}
		);
		this.registry = new SchemaCapabilityUsageRegistry();
	}

	/**
	 * Builds an entity schema on top of the empty product schema.
	 *
	 * @param whichIs the definition the test needs
	 * @return the resulting immutable schema
	 */
	@Nonnull
	private EntitySchemaContract schemaWith(@Nonnull Consumer<EntitySchemaBuilder> whichIs) {
		final EntitySchemaBuilder builder = new InternalEntitySchemaBuilder(this.catalogSchema, this.emptySchema);
		whichIs.accept(builder);
		return builder.toInstance();
	}

	/**
	 * The schema every pruning test starts from - one element of each of the three kinds the phase covers, each
	 * declaring its capabilities in the live scope only.
	 *
	 * @return the full schema
	 */
	@Nonnull
	private EntitySchemaContract fullSchema() {
		return schemaWith(
			builder -> builder
				.withAttribute(
					ATTRIBUTE_CODE, String.class,
					// deliberately not also `filterableInScope` - the schema rejects declaring both, because
					// `unique()` already implies it
					thatIs -> thatIs.sortableInScope(Scope.LIVE).uniqueInScope(Scope.LIVE)
				)
				.withAttribute(ATTRIBUTE_EAN, String.class, thatIs -> thatIs.filterableInScope(Scope.LIVE))
				.withSortableAttributeCompound(
					COMPOUND_CODE_WITH_EAN,
					new AttributeElement[]{
						AttributeElement.attributeElement(ATTRIBUTE_CODE),
						AttributeElement.attributeElement(ATTRIBUTE_EAN)
					},
					thatIs -> thatIs.indexedInScope(Scope.LIVE)
				)
				.withReferenceTo(
					REFERENCE_STOCKS, EXTERNAL_STOCK_TYPE, Cardinality.ZERO_OR_MORE,
					thatIs -> thatIs
						.indexedInScope(Scope.LIVE)
						.withAttribute(
							ATTRIBUTE_QUANTITY, Integer.class,
							whichIs -> whichIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE)
						)
						.withAttribute(
							ATTRIBUTE_WAREHOUSE, String.class,
							whichIs -> whichIs.sortableInScope(Scope.LIVE)
						)
						.withSortableAttributeCompound(
							COMPOUND_QUANTITY_WITH_WAREHOUSE,
							new AttributeElement[]{
								AttributeElement.attributeElement(ATTRIBUTE_QUANTITY),
								AttributeElement.attributeElement(ATTRIBUTE_WAREHOUSE)
							},
							whichIs -> whichIs.indexedInScope(Scope.LIVE)
						)
				)
		);
	}

	/**
	 * Answers whether the registry currently holds an entry for the given key - without resolving it, which would
	 * create the very entry the pruning tests are asserting the absence of.
	 *
	 * @param key the capability to look for
	 * @return true when the registry holds a holder for it
	 */
	private boolean holds(@Nonnull SchemaCapabilityKey key) {
		for (final UsageEntry entry : this.registry.listUsages()) {
			if (entry.key().equals(key)) {
				return true;
			}
		}
		return false;
	}

	@Nested
	@DisplayName("Resolving")
	class ResolvingTest {

		@Test
		@DisplayName("The same key always yields the same holder")
		void shouldReturnTheSameHolderForOneKey() {
			// this is what makes "resolve once, keep the reference" a safe contract rather than an optimisation: a
			// caller that resolved early and a caller that resolved late are incrementing the same object
			final SchemaCapabilityKey key = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTER, Scope.LIVE
			);

			final SchemaCapabilityUsage first = SchemaCapabilityUsageRegistryTest.this.registry.resolve(key);
			final SchemaCapabilityUsage second = SchemaCapabilityUsageRegistryTest.this.registry.resolve(key);

			assertSame(first, second, "Two resolutions of one key produced two holders - half the counts are lost");
			assertEquals(1, SchemaCapabilityUsageRegistryTest.this.registry.size());
		}

		@Test
		@DisplayName("Keys differing only in capability, container or kind get their own holders")
		void shouldKeepDistinctKeysApart() {
			// the discriminators SchemaCapabilityKey documents as easy to overlook, asserted at the level that would
			// actually pool two capabilities' traffic if any of them were dropped from the key
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;

			final SchemaCapabilityUsage filterOnEntity = theRegistry.resolve(
				SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.FILTER, Scope.LIVE)
			);
			final SchemaCapabilityUsage sortOnEntity = theRegistry.resolve(
				SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.SORT, Scope.LIVE)
			);
			final SchemaCapabilityUsage filterInArchive = theRegistry.resolve(
				SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.FILTER, Scope.ARCHIVED)
			);
			final SchemaCapabilityUsage filterOnReference = theRegistry.resolve(
				SchemaCapabilityKey.referenceAttribute(
					REFERENCE_STOCKS, ATTRIBUTE_CODE, Capability.FILTER, Scope.LIVE
				)
			);
			final SchemaCapabilityUsage sortOfCompound = theRegistry.resolve(
				SchemaCapabilityKey.sortableCompound(null, ATTRIBUTE_CODE, Scope.LIVE)
			);

			assertNotSame(filterOnEntity, sortOnEntity, "Capability does not discriminate");
			assertNotSame(filterOnEntity, filterInArchive, "Scope does not discriminate");
			assertNotSame(filterOnEntity, filterOnReference, "Container does not discriminate");
			assertNotSame(sortOnEntity, sortOfCompound, "Element kind does not discriminate");
			assertEquals(5, theRegistry.size());
		}

		@Test
		@DisplayName("Concurrent resolution of one key loses no recording")
		void shouldLoseNoRecordingWhenManyThreadsResolveOneKey() throws InterruptedException {
			// the failure this rules out is a registry that hands out per-caller holders under a race - every thread
			// would still record successfully, and the total would silently come up short. The assertion is exact and
			// interleaving-independent, so it belongs in the fast loop
			final int threads = 8;
			final int recordingsPerThread = 2_000;
			final SchemaCapabilityKey key = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTER, Scope.LIVE
			);
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			final CountDownLatch start = new CountDownLatch(1);
			final CountDownLatch finished = new CountDownLatch(threads);
			final ExecutorService pool = Executors.newFixedThreadPool(threads, DAEMON_THREADS);
			try {
				for (int thread = 0; thread < threads; thread++) {
					pool.submit(
						() -> {
							try {
								// released together, so the resolutions genuinely race for the first insert instead of
								// one thread having created the holder long before the next one looks
								start.await();
								for (int recording = 0; recording < recordingsPerThread; recording++) {
									// deliberately resolved per recording rather than hoisted - the hot paths hoist,
									// but only a repeated resolve can prove the map never mints a second holder
									theRegistry.resolve(key).recordRequested(System.currentTimeMillis());
								}
							} catch (InterruptedException ex) {
								Thread.currentThread().interrupt();
							} finally {
								finished.countDown();
							}
						}
					);
				}
				start.countDown();
				assertTrue(
					finished.await(30, TimeUnit.SECONDS),
					"The resolving threads did not finish within the budget - the registry is blocking"
				);
			} finally {
				pool.shutdownNow();
			}

			assertEquals(1, theRegistry.size(), "The racing threads created more than one holder for one key");
			assertEquals(
				(long) threads * recordingsPerThread,
				theRegistry.resolve(key).getRequestedCount(),
				"A recording landed in a holder the registry no longer hands out"
			);
		}

	}

	@Nested
	@DisplayName("Reading back")
	class ReadingBackTest {

		@Test
		@DisplayName("A fresh registry lists nothing")
		void shouldListNothingWhenNothingWasResolved() {
			assertEquals(List.of(), SchemaCapabilityUsageRegistryTest.this.registry.listUsages());
			assertEquals(0, SchemaCapabilityUsageRegistryTest.this.registry.size());
		}

		@Test
		@DisplayName("Every resolved capability is listed with its live holder")
		void shouldListEachCapabilityWithTheHolderThatCountsIt() {
			final SchemaCapabilityKey filterKey = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTER, Scope.LIVE
			);
			final SchemaCapabilityKey sortKey = SchemaCapabilityKey.sortableCompound(
				null, COMPOUND_CODE_WITH_EAN, Scope.LIVE
			);
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			final SchemaCapabilityUsage filterUsage = theRegistry.resolve(filterKey);
			theRegistry.resolve(sortKey);
			filterUsage.recordRequested(System.currentTimeMillis());

			final List<UsageEntry> listed = theRegistry.listUsages();

			assertEquals(2, listed.size());
			for (final UsageEntry entry : listed) {
				if (filterKey.equals(entry.key())) {
					// the holder is the live one, not a copy - the count taken before the listing is visible through it
					assertSame(filterUsage, entry.usage());
					assertEquals(1L, entry.usage().getRequestedCount());
				} else {
					assertEquals(sortKey, entry.key());
					assertEquals(0L, entry.usage().getRequestedCount());
				}
			}
		}

		@Test
		@DisplayName("The listed view cannot be written through")
		void shouldReturnAnUnmodifiableList() {
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			theRegistry.resolve(SchemaCapabilityKey.entityAttribute(ATTRIBUTE_EAN, Capability.FILTER, Scope.LIVE));

			final List<UsageEntry> listed = theRegistry.listUsages();

			assertThrows(UnsupportedOperationException.class, listed::clear);
		}

	}

	@Nested
	@DisplayName("Pruning")
	class PruningTest {

		@Test
		@DisplayName("Everything the new schema still declares survives - attribute, reference attribute and compound")
		void shouldKeepEveryCapabilityTheSchemaStillDeclares() {
			// all three element kinds in one pass, because a prune predicate that handled only the entity's own
			// attributes would pass a test that exercised only those and quietly wipe the other two in production
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			final List<SchemaCapabilityKey> declared = List.of(
				// `code` never declares `filterable()` explicitly - it is unique, which implies it. This entry is what
				// pins that: prune it and the capability every filter on a unique attribute uses loses its counters
				SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.FILTER, Scope.LIVE),
				SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.SORT, Scope.LIVE),
				SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.UNIQUE, Scope.LIVE),
				SchemaCapabilityKey.entityAttribute(ATTRIBUTE_EAN, Capability.FILTER, Scope.LIVE),
				SchemaCapabilityKey.sortableCompound(null, COMPOUND_CODE_WITH_EAN, Scope.LIVE),
				SchemaCapabilityKey.referenceAttribute(
					REFERENCE_STOCKS, ATTRIBUTE_QUANTITY, Capability.FILTER, Scope.LIVE
				),
				SchemaCapabilityKey.referenceAttribute(
					REFERENCE_STOCKS, ATTRIBUTE_QUANTITY, Capability.SORT, Scope.LIVE
				),
				SchemaCapabilityKey.sortableCompound(
					REFERENCE_STOCKS, COMPOUND_QUANTITY_WITH_WAREHOUSE, Scope.LIVE
				)
			);
			for (final SchemaCapabilityKey key : declared) {
				theRegistry.resolve(key).recordRequested(System.currentTimeMillis());
			}

			theRegistry.pruneFor(fullSchema());

			assertEquals(declared.size(), theRegistry.size(), "Pruning dropped a capability the schema still declares");
			for (final SchemaCapabilityKey key : declared) {
				assertTrue(holds(key), "Capability " + key + " was dropped although the schema still declares it");
			}
		}

		@Test
		@DisplayName("An attribute the new schema no longer declares loses its holder")
		void shouldDropACapabilityOfARemovedAttribute() {
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			final SchemaCapabilityKey eanFilter = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTER, Scope.LIVE
			);
			final SchemaCapabilityKey codeFilter = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_CODE, Capability.FILTER, Scope.LIVE
			);
			theRegistry.resolve(eanFilter);
			theRegistry.resolve(codeFilter);

			theRegistry.pruneFor(schemaWithoutEan());

			assertEquals(1, theRegistry.size());
			assertTrue(holds(codeFilter), "Pruning took the surviving attribute's holder with it");
		}

		@Test
		@DisplayName("An attribute that keeps its name but loses the flag loses its holder too")
		void shouldDropACapabilityTheAttributeNoLongerCarries() {
			// the element still exists, so a prune that only checked for the name would keep this entry alive and go on
			// reporting maintenance cost for an index the schema stopped asking for
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			final SchemaCapabilityKey eanFilter = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTER, Scope.LIVE
			);
			theRegistry.resolve(eanFilter);

			theRegistry.pruneFor(
				schemaWith(
					builder -> builder.withAttribute(
						ATTRIBUTE_EAN, String.class, thatIs -> thatIs.nonFilterable()
					)
				)
			);

			assertEquals(0, theRegistry.size());
		}

		@Test
		@DisplayName("A flag declared in one scope only loses the other scope's holder")
		void shouldDropACapabilityOutsideTheScopesThatDeclareIt() {
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			final SchemaCapabilityKey liveFilter = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTER, Scope.LIVE
			);
			final SchemaCapabilityKey archivedFilter = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTER, Scope.ARCHIVED
			);
			theRegistry.resolve(liveFilter);
			theRegistry.resolve(archivedFilter);

			// the full schema declares `ean` filterable in the live scope only
			theRegistry.pruneFor(fullSchema());

			assertTrue(holds(liveFilter));
			assertEquals(1, theRegistry.size(), "The archive's holder survived a schema that never declared it there");
		}

		@Test
		@DisplayName("A removed reference takes the holders of everything declared on it")
		void shouldDropEverythingDeclaredOnARemovedReference() {
			// both element kinds a reference can own, dropped by the container disappearing rather than by the element
			// itself - the branch that resolves the container is separate from the one that resolves the element, so a
			// missing container has to be tested on its own
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			theRegistry.resolve(
				SchemaCapabilityKey.referenceAttribute(
					REFERENCE_STOCKS, ATTRIBUTE_QUANTITY, Capability.FILTER, Scope.LIVE
				)
			);
			theRegistry.resolve(
				SchemaCapabilityKey.sortableCompound(REFERENCE_STOCKS, COMPOUND_QUANTITY_WITH_WAREHOUSE, Scope.LIVE)
			);
			final SchemaCapabilityKey survivor = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTER, Scope.LIVE
			);
			theRegistry.resolve(survivor);

			// the reference is simply absent from this schema version
			theRegistry.pruneFor(
				schemaWith(
					builder -> builder.withAttribute(
						ATTRIBUTE_EAN, String.class, thatIs -> thatIs.filterableInScope(Scope.LIVE)
					)
				)
			);

			assertEquals(1, theRegistry.size());
			assertTrue(holds(survivor), "Losing the reference took the entity's own attribute with it");
		}

		@Test
		@DisplayName("A compound the new schema stopped indexing loses its holder")
		void shouldDropACompoundThatIsNoLongerIndexed() {
			// the compound still exists under the same name, so only its indexed-in-scope flag distinguishes the two
			// versions - the compound equivalent of an attribute losing `filterable()`
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			theRegistry.resolve(SchemaCapabilityKey.sortableCompound(null, COMPOUND_CODE_WITH_EAN, Scope.LIVE));

			theRegistry.pruneFor(
				schemaWith(
					builder -> builder
						.withAttribute(ATTRIBUTE_CODE, String.class)
						.withAttribute(ATTRIBUTE_EAN, String.class)
						.withSortableAttributeCompound(
							COMPOUND_CODE_WITH_EAN,
							new AttributeElement[]{
								AttributeElement.attributeElement(ATTRIBUTE_CODE),
								AttributeElement.attributeElement(ATTRIBUTE_EAN)
							},
							thatIs -> thatIs.nonIndexed()
						)
				)
			);

			assertEquals(0, theRegistry.size());
		}

		@Test
		@DisplayName("A re-added attribute resolves a brand new holder starting at zero")
		void shouldGiveAReAddedAttributeAFreshHolder() {
			// Decision 7 in one test: counters must not survive the interval during which the capability was not
			// maintained, because a rate computed over the old window would describe traffic the flag never served
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			final SchemaCapabilityKey eanFilter = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTER, Scope.LIVE
			);
			final SchemaCapabilityUsage before = theRegistry.resolve(eanFilter);
			before.recordRequested(System.currentTimeMillis());
			before.recordUpdated(System.currentTimeMillis());

			theRegistry.pruneFor(schemaWithoutEan());
			final long reAddedAtMillis = System.currentTimeMillis();
			theRegistry.pruneFor(fullSchema());
			final SchemaCapabilityUsage after = theRegistry.resolve(eanFilter);

			assertNotSame(before, after, "The re-added attribute inherited the holder it had before it was dropped");
			assertEquals(0L, after.getRequestedCount(), "The re-added attribute inherited its old request count");
			assertEquals(0L, after.getUpdatedCount(), "The re-added attribute inherited its old update count");
			assertEquals(0L, after.getLastRequestedAtMillis());
			assertEquals(0L, after.getLastUpdatedAtMillis());
			// asserted as "no earlier than the removal" rather than as "different from the old value" - both holders
			// can legitimately be constructed within one millisecond, and only the window's start is a real claim
			assertTrue(
				after.getObservedSinceMillis() >= reAddedAtMillis,
				"The fresh holder's observation window starts before the capability was re-declared"
			);
		}

		@Test
		@DisplayName("A compound key claiming a capability no compound can have is reported, not swallowed")
		void shouldRefuseToPruneACompoundKeyThatCannotExist() {
			// only the canonical record constructor can mint this - SchemaCapabilityKey#sortableCompound fixes the
			// capability at SORT precisely because a compound has nothing to filter or be unique by. Dropping such a
			// key quietly would look exactly like a legitimate prune and hide whoever built it
			final SchemaCapabilityKey impossible = new SchemaCapabilityKey(
				ElementKind.SORTABLE_COMPOUND, null, COMPOUND_CODE_WITH_EAN, Capability.FILTER, Scope.LIVE
			);
			SchemaCapabilityUsageRegistryTest.this.registry.resolve(impossible);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> SchemaCapabilityUsageRegistryTest.this.registry.pruneFor(fullSchema())
			);
		}

		@Test
		@DisplayName("Pruning an empty registry against an empty schema is a no-op")
		void shouldPruneNothingWhenNothingWasResolved() {
			SchemaCapabilityUsageRegistryTest.this.registry.pruneFor(
				SchemaCapabilityUsageRegistryTest.this.emptySchema
			);

			assertEquals(0, SchemaCapabilityUsageRegistryTest.this.registry.size());
		}

		/**
		 * The full schema minus the `ean` attribute - and therefore minus the compound built on top of it, which cannot
		 * outlive one of its elements.
		 *
		 * @return the reduced schema
		 */
		@Nonnull
		private EntitySchemaContract schemaWithoutEan() {
			return schemaWith(
				builder -> builder.withAttribute(
					ATTRIBUTE_CODE, String.class,
					// deliberately not also `filterableInScope` - the schema rejects declaring both, because
					// `unique()` already implies it
					thatIs -> thatIs.sortableInScope(Scope.LIVE).uniqueInScope(Scope.LIVE)
				)
			);
		}

	}

	@Nested
	@DisplayName("Pruning a catalog registry")
	class CatalogPruningTest {

		@Test
		@DisplayName("A globally-unique attribute the catalog schema still declares keeps every entry it has")
		void shouldKeepTheCapabilitiesOfAGloballyUniqueAttribute() {
			// `uniqueGlobally()` never declares `filterable()` or collection-level `unique()` explicitly - it implies
			// both - so these two entries are exactly the ones a prune rule written against the wrong flag drops
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			final SchemaCapabilityKey codeFilter = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_CODE, Capability.FILTER, Scope.LIVE
			);
			final SchemaCapabilityKey codeUnique = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_CODE, Capability.UNIQUE, Scope.LIVE
			);
			theRegistry.resolve(codeFilter).recordRequested(System.currentTimeMillis());
			theRegistry.resolve(codeUnique).recordUpdated(System.currentTimeMillis());

			theRegistry.pruneFor(catalogSchemaWithGlobalCode());

			assertTrue(holds(codeFilter), "A globally-unique attribute lost the FILTER entry its uniqueness implies");
			assertTrue(holds(codeUnique), "A globally-unique attribute lost its UNIQUE entry");
			assertEquals(1L, theRegistry.resolve(codeFilter).getRequestedCount(), "The surviving entry was replaced");
		}

		@Test
		@DisplayName("An attribute the catalog schema no longer declares loses its holder")
		void shouldDropTheEntryOfARemovedGlobalAttribute() {
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			final SchemaCapabilityKey eanUnique = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.UNIQUE, Scope.LIVE
			);
			theRegistry.resolve(eanUnique).recordRequested(System.currentTimeMillis());

			theRegistry.pruneFor(catalogSchemaWithGlobalCode());

			assertTrue(
				!holds(eanUnique),
				"The catalog adopted a schema declaring no `ean`, yet the registry still counts it"
			);
		}

		@Test
		@DisplayName("An entry no catalog schema could ever back is reported, not swallowed")
		void shouldRefuseToPruneAnEntryNoCatalogCanDeclare() {
			// a catalog declares neither references nor compounds, so both of these keys could only have been minted
			// by a resolve site aiming at the wrong registry - and a silent drop would look like an ordinary prune
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			theRegistry.resolve(
				SchemaCapabilityKey.referenceAttribute(
					REFERENCE_STOCKS, ATTRIBUTE_QUANTITY, Capability.FILTER, Scope.LIVE
				)
			);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> theRegistry.pruneFor(catalogSchemaWithGlobalCode())
			);

			final SchemaCapabilityUsageRegistry compoundRegistry = new SchemaCapabilityUsageRegistry();
			compoundRegistry.resolve(SchemaCapabilityKey.sortableCompound(null, COMPOUND_CODE_WITH_EAN, Scope.LIVE));

			assertThrows(
				GenericEvitaInternalError.class,
				() -> compoundRegistry.pruneFor(catalogSchemaWithGlobalCode())
			);
		}

		/**
		 * A catalog schema declaring one globally-unique attribute and nothing else.
		 *
		 * @return the catalog schema
		 */
		@Nonnull
		private CatalogSchemaContract catalogSchemaWithGlobalCode() {
			return new InternalCatalogSchemaBuilder(SchemaCapabilityUsageRegistryTest.this.catalogSchema)
				.withAttribute(
					ATTRIBUTE_CODE, String.class,
					thatIs -> thatIs.uniqueGloballyInScope(Scope.LIVE)
				)
				.toInstance();
		}

	}

}
