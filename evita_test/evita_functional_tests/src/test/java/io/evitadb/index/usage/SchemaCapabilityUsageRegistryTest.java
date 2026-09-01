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
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaEditor;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.ElementKind;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.GenericEvitaInternalError;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the three things {@link SchemaCapabilityUsageRegistry} promises and nothing else in the suite checks:
 * **one key means one holder**, however many threads ask for it at once; **every capability the schema declares has a
 * holder from the moment it is declared**; and **a capability the schema stopped declaring loses its holder**, so that
 * re-declaring it later starts a fresh observation window.
 *
 * The first is what lets every hot path resolve once and then increment a bare reference - if two threads could ever
 * receive two different holders for one key, half the recordings would land in an object nobody reads again.
 *
 * The second is what makes `observedSince` mean *"declared"* rather than *"first queried"*, and what lets an untouched
 * flag be reported with honest zeros instead of being absent - an absence an operator cannot tell apart from a flag
 * the schema never declared. Its dangerous direction is over-seeding: a row nothing can ever increment is permanently
 * zero and reads as *"unused, drop it"*, so the tests below assert the seeded set **exactly**, never merely that it
 * contains what they expect.
 *
 * The third is the whole reason the registry has an opinion about schemas at all. Counters that outlived the flag they
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
	/** Entity attribute carrying only `filterable()` - the one the alignment tests take away and give back. */
	private static final String ATTRIBUTE_EAN = "ean";
	/** Sortable compound the entity declares directly. */
	private static final String COMPOUND_CODE_WITH_EAN = "codeWithEan";
	/** Reference whose attributes stand in for the "declared on a reference" half of the key space. */
	private static final String REFERENCE_STOCKS = "stocks";
	private static final String HISTOGRAM_QUANTITY = "quantityHistogram";
	/**
	 * A histogram value expression, which is what separates a maintained histogram from a count-only one - see
	 * `SchemaCapabilityUsageRegistry#maintainsHistogramIn`. Its content is irrelevant here; only its presence is.
	 */
	private static final Expression VALUE_EXPRESSION = ExpressionFactory.parse("$reference.referencedPrimaryKey");
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
	/**
	 * Exactly what {@link #fullSchema()} declares - the set an alignment against it must end up holding, no more and
	 * no less. Written out by hand rather than derived from the schema, because a set computed the way the production
	 * code computes it would agree with any bug the production code has.
	 *
	 * Two entries are worth pointing at, since both are what a rule written against the wrong flag gets wrong:
	 * `code` never declares `filterable()` explicitly - it is unique, which implies it - and `warehouse` contributes
	 * only `SORTABLE`, so a rule that seeded a capability per flag *name* rather than per flag *held* would add entries
	 * here that nothing could ever increment.
	 */
	private static final List<SchemaCapabilityKey> EVERY_CAPABILITY_OF_THE_FULL_SCHEMA = List.of(
		SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.FILTERABLE, Scope.LIVE),
		SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.SORTABLE, Scope.LIVE),
		SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.UNIQUE, Scope.LIVE),
		SchemaCapabilityKey.entityAttribute(ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE),
		SchemaCapabilityKey.sortableCompound(null, COMPOUND_CODE_WITH_EAN, Scope.LIVE),
		SchemaCapabilityKey.referenceAttribute(REFERENCE_STOCKS, ATTRIBUTE_QUANTITY, Capability.FILTERABLE, Scope.LIVE),
		SchemaCapabilityKey.referenceAttribute(REFERENCE_STOCKS, ATTRIBUTE_QUANTITY, Capability.SORTABLE, Scope.LIVE),
		SchemaCapabilityKey.referenceAttribute(REFERENCE_STOCKS, ATTRIBUTE_WAREHOUSE, Capability.SORTABLE, Scope.LIVE),
		SchemaCapabilityKey.sortableCompound(REFERENCE_STOCKS, COMPOUND_QUANTITY_WITH_WAREHOUSE, Scope.LIVE),
		// the reference's own flag, not one of its attributes' - `stocks` is `indexed()`, which costs the reduced
		// entity index family the attribute rows above are maintained in. Neither `faceted()` nor `bucketed()`
		// appears because this schema declares neither, and the entity declares no hierarchy and no prices
		SchemaCapabilityKey.reference(REFERENCE_STOCKS, Capability.INDEXED, Scope.LIVE)
	);

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
	 * The schema every alignment test starts from - one element of each of the three kinds the phase covers, each
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
	 * create the very entry the alignment tests are asserting the absence of.
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
				ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE
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
				SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.FILTERABLE, Scope.LIVE)
			);
			final SchemaCapabilityUsage sortOnEntity = theRegistry.resolve(
				SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.SORTABLE, Scope.LIVE)
			);
			final SchemaCapabilityUsage filterInArchive = theRegistry.resolve(
				SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.FILTERABLE, Scope.ARCHIVED)
			);
			final SchemaCapabilityUsage filterOnReference = theRegistry.resolve(
				SchemaCapabilityKey.referenceAttribute(
					REFERENCE_STOCKS, ATTRIBUTE_CODE, Capability.FILTERABLE, Scope.LIVE
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
				ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE
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
				ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE
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
			theRegistry.resolve(SchemaCapabilityKey.entityAttribute(ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE));

			final List<UsageEntry> listed = theRegistry.listUsages();

			assertThrows(UnsupportedOperationException.class, listed::clear);
		}

	}

	@Nested
	@DisplayName("Aligning with an entity schema")
	class AlignmentTest {

		@Test
		@DisplayName("Every capability the schema declares gets a holder, even one nothing has touched")
		void shouldSeedEveryCapabilityTheSchemaDeclares() {
			// the whole reason alignment seeds rather than waiting for a resolve: a holder minted here is what makes
			// `observedSince` say "declared" instead of "first queried", and what lets an untouched flag be reported
			// with honest zeros rather than be invisible - which an operator cannot tell apart from "not declared"
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			final long beforeAlignment = System.currentTimeMillis();

			theRegistry.alignWith(fullSchema());

			assertEquals(
				EVERY_CAPABILITY_OF_THE_FULL_SCHEMA.size(), theRegistry.size(),
				"The registry holds a different number of capabilities than the schema declares - a seeded row " +
					"nothing can ever increment reads as `unused, drop it`, which is the one action this surface " +
					"exists to prevent. What it holds: " + theRegistry.listUsages()
			);
			for (final SchemaCapabilityKey key : EVERY_CAPABILITY_OF_THE_FULL_SCHEMA) {
				assertTrue(holds(key), "Capability " + key + " was declared by the schema but never seeded");
			}
			for (final UsageEntry entry : theRegistry.listUsages()) {
				assertEquals(0L, entry.usage().getRequestedCount(), "A seeded holder started with a request: " + entry);
				assertEquals(0L, entry.usage().getUpdatedCount(), "A seeded holder started with an update: " + entry);
				assertEquals(0L, entry.usage().getLastRequestedAtMillis(), entry.toString());
				assertEquals(0L, entry.usage().getLastUpdatedAtMillis(), entry.toString());
				assertTrue(
					entry.usage().getObservedSinceMillis() >= beforeAlignment,
					"The observation window opened before the schema was adopted: " + entry
				);
			}
		}

		@Test
		@DisplayName("Re-aligning keeps the holder of a surviving capability, counters and window included")
		void shouldNotReplaceHoldersOfSurvivingCapabilities() {
			// the failure this rules out is silent and total: alignment runs on every schema mutation, so a version
			// that replaced surviving holders would reset every counter in the collection each time anything about
			// the schema changed, and nothing downstream would look wrong
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			theRegistry.alignWith(fullSchema());
			final SchemaCapabilityKey eanFilter = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE
			);
			final SchemaCapabilityUsage seeded = theRegistry.resolve(eanFilter);
			seeded.recordRequested(System.currentTimeMillis());
			seeded.recordUpdated(System.currentTimeMillis());
			final long observedSince = seeded.getObservedSinceMillis();

			// a second adoption of a schema that changed nothing about `ean` - the common case by far
			theRegistry.alignWith(fullSchema());

			final SchemaCapabilityUsage afterRealignment = theRegistry.resolve(eanFilter);
			assertSame(seeded, afterRealignment, "Re-aligning replaced the holder of a capability that never changed");
			assertEquals(1L, afterRealignment.getRequestedCount(), "Re-aligning reset the request count");
			assertEquals(1L, afterRealignment.getUpdatedCount(), "Re-aligning reset the update count");
			assertEquals(
				observedSince, afterRealignment.getObservedSinceMillis(),
				"Re-aligning moved the observation window forward, which would understate every rate read from it"
			);
		}

		@Test
		@DisplayName("Everything the new schema still declares survives - attribute, reference attribute and compound")
		void shouldKeepEveryCapabilityTheSchemaStillDeclares() {
			// all three element kinds in one pass, because an alignment that handled only the entity's own attributes
			// would pass a test that exercised only those and quietly wipe the other two in production
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			for (final SchemaCapabilityKey key : EVERY_CAPABILITY_OF_THE_FULL_SCHEMA) {
				theRegistry.resolve(key).recordRequested(System.currentTimeMillis());
			}

			theRegistry.alignWith(fullSchema());

			assertEquals(
				EVERY_CAPABILITY_OF_THE_FULL_SCHEMA.size(), theRegistry.size(),
				"Aligning dropped a capability the schema still declares"
			);
			for (final SchemaCapabilityKey key : EVERY_CAPABILITY_OF_THE_FULL_SCHEMA) {
				assertTrue(holds(key), "Capability " + key + " was dropped although the schema still declares it");
			}
		}

		@Test
		@DisplayName("An attribute the new schema no longer declares loses its holder")
		void shouldDropACapabilityOfARemovedAttribute() {
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			final SchemaCapabilityKey eanFilter = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE
			);
			final SchemaCapabilityKey codeFilter = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_CODE, Capability.FILTERABLE, Scope.LIVE
			);
			theRegistry.resolve(eanFilter);
			theRegistry.resolve(codeFilter);

			theRegistry.alignWith(schemaWithoutEan());

			assertFalse(holds(eanFilter), "The schema stopped declaring `ean`, yet its holder survived");
			assertTrue(holds(codeFilter), "Aligning took the surviving attribute's holder with it");
			// `code` is unique and sortable in the live scope and the reduced schema declares nothing else, so those
			// three capabilities - uniqueness implying filterability - are the whole of what may be left standing
			assertEquals(3, theRegistry.size(), "What it holds: " + theRegistry.listUsages());
		}

		@Test
		@DisplayName("An attribute that keeps its name but loses the flag loses its holder too")
		void shouldDropACapabilityTheAttributeNoLongerCarries() {
			// the element still exists, so a prune that only checked for the name would keep this entry alive and go on
			// reporting maintenance cost for an index the schema stopped asking for
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			final SchemaCapabilityKey eanFilter = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE
			);
			theRegistry.resolve(eanFilter);

			theRegistry.alignWith(
				schemaWith(
					builder -> builder.withAttribute(
						ATTRIBUTE_EAN, String.class, AttributeSchemaEditor::nonFilterable
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
				ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE
			);
			final SchemaCapabilityKey archivedFilter = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.ARCHIVED
			);
			theRegistry.resolve(liveFilter);
			theRegistry.resolve(archivedFilter);

			// the full schema declares `ean` filterable in the live scope only
			theRegistry.alignWith(fullSchema());

			assertTrue(holds(liveFilter));
			assertFalse(
				holds(archivedFilter),
				"The archive's holder survived a schema that never declared it there"
			);
			assertEquals(
				EVERY_CAPABILITY_OF_THE_FULL_SCHEMA.size(), theRegistry.size(),
				"What it holds: " + theRegistry.listUsages()
			);
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
					REFERENCE_STOCKS, ATTRIBUTE_QUANTITY, Capability.FILTERABLE, Scope.LIVE
				)
			);
			theRegistry.resolve(
				SchemaCapabilityKey.sortableCompound(REFERENCE_STOCKS, COMPOUND_QUANTITY_WITH_WAREHOUSE, Scope.LIVE)
			);
			final SchemaCapabilityKey survivor = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE
			);
			theRegistry.resolve(survivor);

			// the reference is simply absent from this schema version
			theRegistry.alignWith(
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

			theRegistry.alignWith(
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
							SortableAttributeCompoundSchemaEditor::nonIndexed
						)
				)
			);

			assertEquals(0, theRegistry.size());
		}

		@Test
		@DisplayName("A re-added attribute gets a brand new holder starting at zero")
		void shouldGiveAReAddedAttributeAFreshHolder() {
			// counters must not survive the interval during which the capability was not maintained, because a rate
			// computed over the old window would describe traffic the flag never served. The holder read back at the
			// end is the one the re-declaring alignment seeded, so this also pins where the new window opens: at the
			// schema mutation that brought the capability back, not at the next query that happens to name it
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			final SchemaCapabilityKey eanFilter = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE
			);
			final SchemaCapabilityUsage before = theRegistry.resolve(eanFilter);
			before.recordRequested(System.currentTimeMillis());
			before.recordUpdated(System.currentTimeMillis());

			theRegistry.alignWith(schemaWithoutEan());
			final long reAddedAtMillis = System.currentTimeMillis();
			theRegistry.alignWith(fullSchema());
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
		void shouldRefuseToAlignACompoundKeyThatCannotExist() {
			// only the canonical record constructor can mint this - SchemaCapabilityKey#sortableCompound fixes the
			// capability at SORTABLE precisely because a compound has nothing to filter or be unique by. Dropping
			// such a key quietly would look exactly like an ordinary alignment and hide whoever built it
			final SchemaCapabilityKey impossible = new SchemaCapabilityKey(
				ElementKind.SORTABLE_COMPOUND, null, COMPOUND_CODE_WITH_EAN, Capability.FILTERABLE, Scope.LIVE
			);
			SchemaCapabilityUsageRegistryTest.this.registry.resolve(impossible);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> SchemaCapabilityUsageRegistryTest.this.registry.alignWith(fullSchema())
			);
		}

		@Test
		@DisplayName("A reflected reference that is not attached yet is skipped rather than asked")
		void shouldSkipAReflectedReferenceThatIsNotAttachedYet() {
			// a reflection may be declared before the reference it mirrors exists, and until it is attached it can
			// answer nothing - asking for an inherited flag throws outright. Alignment runs on every schema adoption,
			// so an enumeration that asked anyway would abort the very mutation declaring the reflection, and the
			// failure would surface as a rolled-back transaction with nothing in it pointing back here
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			final SchemaCapabilityKey eanFilter = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE
			);

			theRegistry.alignWith(
				schemaWith(
					builder -> builder
						.withAttribute(ATTRIBUTE_EAN, String.class, thatIs -> thatIs.filterableInScope(Scope.LIVE))
						// `product` is the only entity type this fixture's catalog knows and it declares no reference
						// at all, so this reflection has nothing to attach to
						.withReflectedReferenceToEntity(
							REFERENCE_STOCKS, Entities.PRODUCT, REFERENCE_STOCKS,
							ReflectedReferenceSchemaEditor::withAttributesInherited
						)
				)
			);

			assertTrue(holds(eanFilter), "The alignment did not get past the unattached reflection");
			assertEquals(
				1, theRegistry.size(),
				"An unattached reflected reference contributed rows of its own - nothing is known about what it will " +
					"inherit, so every one of them would be a row nothing could increment: " + theRegistry.listUsages()
			);
		}

		@Test
		@DisplayName("A schema declaring no capability leaves the registry empty")
		void shouldHoldNothingWhenTheSchemaDeclaresNothing() {
			// the eager seeding must be driven by what the schema *declares*, not by what it *contains* - a schema with
			// attributes none of which carry an indexed flag has nothing to report and must not manufacture rows
			SchemaCapabilityUsageRegistryTest.this.registry.alignWith(
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

		@Nested
		@DisplayName("The substring filter capability")
		class SubstringFilterCapabilityTest {

			@Test
			@DisplayName("An accelerated attribute is seeded for both `filterable()` and the capability")
			void shouldSeedSubstringAlongsideFilterable() {
				// two rows, not one: the acceleration costs an index of its own *on top of* the filter index, and an
				// operator can drop the capability while keeping the attribute filterable - so the two have to be
				// separately reportable, exactly as a reference's `faceted()` is separate from its `indexed()`
				final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;

				theRegistry.alignWith(schemaWithAcceleratedEan());

				assertTrue(
					holds(SchemaCapabilityKey.entityAttribute(ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE)),
					"The filter index the acceleration sits on top of got no row: " + theRegistry.listUsages()
				);
				assertTrue(
					holds(
						SchemaCapabilityKey.entityAttribute(
							ATTRIBUTE_EAN, Capability.SUBSTRING_ACCELERATED, Scope.LIVE
						)
					),
					"The declared acceleration got no row of its own: " + theRegistry.listUsages()
				);
				assertEquals(
					2, theRegistry.size(),
					"An attribute declaring one acceleration seeded more than the two indexes it pays for: " +
						theRegistry.listUsages()
				);
			}

			@Test
			@DisplayName("Dropping the capability drops its row and leaves the filterable one alone")
			void shouldDropOnlySubstringWhenTheCapabilityGoesAway() {
				// the direct consequence of the two rows above being separate: dropping the acceleration must not
				// reset the filter index's counters, because that index kept being maintained the whole time
				final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
				theRegistry.alignWith(schemaWithAcceleratedEan());
				final SchemaCapabilityKey filterable = SchemaCapabilityKey.entityAttribute(
					ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE
				);
				final SchemaCapabilityUsage filterableHolder = theRegistry.resolve(filterable);

				theRegistry.alignWith(
					schemaWith(
						builder -> builder.withAttribute(
							ATTRIBUTE_EAN, String.class, thatIs -> thatIs.filterableInScope(Scope.LIVE)
						)
					)
				);

				assertFalse(
					holds(
						SchemaCapabilityKey.entityAttribute(
							ATTRIBUTE_EAN, Capability.SUBSTRING_ACCELERATED, Scope.LIVE
						)
					),
					"The row of an acceleration the schema stopped declaring survived"
				);
				assertSame(
					filterableHolder, theRegistry.resolve(filterable),
					"Dropping the acceleration replaced the filter index's holder, resetting its counters"
				);
			}

			@Test
			@DisplayName("The capability is seeded only in the scope that declares it")
			void shouldSeedSubstringOnlyInTheScopeThatDeclaresIt() {
				// the acceleration is declared per scope exactly as filterability is, so an attribute filterable in
				// both scopes but accelerated in one pays for - and must be reported as having - one such index
				final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;

				theRegistry.alignWith(
					schemaWith(
						builder -> builder.withAttribute(
							ATTRIBUTE_EAN, String.class,
							thatIs -> thatIs
								.filterableInScope(Scope.LIVE, Scope.ARCHIVED)
								.acceleratedForInScope(
									Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH
								)
						)
					)
				);

				assertTrue(
					holds(
						SchemaCapabilityKey.entityAttribute(
							ATTRIBUTE_EAN, Capability.SUBSTRING_ACCELERATED, Scope.LIVE
						)
					),
					"The scope declaring the acceleration got no row: " + theRegistry.listUsages()
				);
				assertTrue(
					holds(SchemaCapabilityKey.entityAttribute(ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.ARCHIVED)),
					"The archived scope is filterable and must still be reported as such"
				);
				assertFalse(
					holds(
						SchemaCapabilityKey.entityAttribute(
							ATTRIBUTE_EAN, Capability.SUBSTRING_ACCELERATED, Scope.ARCHIVED
						)
					),
					"A scope declaring no acceleration was seeded one anyway: " + theRegistry.listUsages()
				);
			}

			@Test
			@DisplayName("A substring key naming a reference or the entity itself is reported, not swallowed")
			void shouldRefuseToAlignASubstringKeyOnAReferenceOrEntity() {
				// neither `SchemaCapabilityKey#reference` nor `#entity` will mint such a key, so only the canonical
				// record constructor can produce one - and dropping it quietly at alignment would look exactly like
				// an ordinary alignment and hide whoever built it
				final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
				theRegistry.resolve(
					new SchemaCapabilityKey(
						ElementKind.REFERENCE, null, REFERENCE_STOCKS, Capability.SUBSTRING_ACCELERATED, Scope.LIVE
					)
				);

				assertThrows(GenericEvitaInternalError.class, () -> theRegistry.alignWith(fullSchema()));

				final SchemaCapabilityUsageRegistry entityRegistry = new SchemaCapabilityUsageRegistry();
				entityRegistry.resolve(
					new SchemaCapabilityKey(
						ElementKind.ENTITY, null, Entities.PRODUCT, Capability.SUBSTRING_ACCELERATED, Scope.LIVE
					)
				);

				assertThrows(GenericEvitaInternalError.class, () -> entityRegistry.alignWith(fullSchema()));
			}

			/**
			 * A schema whose sole attribute is filterable in the live scope and asks that scope's filter index for
			 * the substring acceleration.
			 *
			 * @return the schema
			 */
			@Nonnull
			private EntitySchemaContract schemaWithAcceleratedEan() {
				return schemaWith(
					builder -> builder.withAttribute(
						ATTRIBUTE_EAN, String.class,
						thatIs -> thatIs
							.filterableInScope(Scope.LIVE)
							.acceleratedForInScope(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)
					)
				);
			}

		}

	}

	@Nested
	@DisplayName("The flags a reference and the entity declare on themselves")
	class ReferenceAndEntityCapabilityTest {

		@Test
		@DisplayName("A faceted reference is seeded for both `indexed()` and `faceted()`")
		void shouldSeedFacetedAlongsideIndexed() {
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;

			theRegistry.alignWith(
				schemaWith(
					builder -> builder.withReferenceTo(
						REFERENCE_STOCKS, EXTERNAL_STOCK_TYPE, Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs.indexedInScope(Scope.LIVE).facetedInScope(Scope.LIVE)
					)
				)
			);

			// both, not one: `faceted()` costs the facet index *on top of* the reduced index family `indexed()`
			// costs, and an operator can drop either flag independently
			assertTrue(holds(SchemaCapabilityKey.reference(REFERENCE_STOCKS, Capability.INDEXED, Scope.LIVE)));
			assertTrue(holds(SchemaCapabilityKey.reference(REFERENCE_STOCKS, Capability.FACETED, Scope.LIVE)));
			assertFalse(
				holds(SchemaCapabilityKey.reference(REFERENCE_STOCKS, Capability.BUCKETED, Scope.LIVE)),
				"A flag the schema never declared was seeded anyway"
			);
		}

		@Test
		@DisplayName("A histogram with a value expression is seeded for `BUCKETED`, independently of `faceted()`")
		void shouldSeedBucketed() {
			// worth its own test rather than riding along with `faceted()`: `bucketed()` is the one flag of the five
			// that no query path consults by name - the histogram is reached through its declared definition - so it
			// is the easiest of them to wire up in one direction only and never notice
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;

			theRegistry.alignWith(
				schemaWith(
					builder -> builder.withReferenceTo(
						REFERENCE_STOCKS, EXTERNAL_STOCK_TYPE, Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs
							.indexedInScope(Scope.LIVE)
							.bucketedInScope(Scope.LIVE, HISTOGRAM_QUANTITY, VALUE_EXPRESSION, null)
					)
				)
			);

			assertTrue(
				holds(SchemaCapabilityKey.reference(REFERENCE_STOCKS, Capability.BUCKETED, Scope.LIVE)),
				"A maintained histogram got no `BUCKETED` row: " + theRegistry.listUsages()
			);
			assertTrue(
				holds(SchemaCapabilityKey.reference(REFERENCE_STOCKS, Capability.INDEXED, Scope.LIVE)),
				"The histogram hangs off the reduced index family, so `indexed()` is reported alongside it"
			);
			assertFalse(
				holds(SchemaCapabilityKey.reference(REFERENCE_STOCKS, Capability.FACETED, Scope.LIVE)),
				"`bucketed()` seeded a facet row - the two are independent flags"
			);
		}

		@Test
		@DisplayName("A count-only histogram is seeded for nothing - nothing maintains it")
		void shouldNotSeedACountOnlyHistogram() {
			// `isBucketedInScope` says only that a histogram is *declared*. One without a value expression - a count
			// histogram, which the public builder allows - yields no HistogramExpressionTrigger, and every histogram
			// maintenance site is gated on the trigger collection being non-empty. Its update count could therefore
			// never leave zero, and a permanently-zero row is read as `nothing maintains this, drop it` about a
			// perfectly valid schema. This is the sharpest instance of that trap in the whole surface.
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;

			theRegistry.alignWith(
				schemaWith(
					builder -> builder.withReferenceTo(
						REFERENCE_STOCKS, EXTERNAL_STOCK_TYPE, Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs
							.indexedInScope(Scope.LIVE)
							.bucketedInScope(Scope.LIVE, HISTOGRAM_QUANTITY, null, null)
					)
				)
			);

			assertFalse(
				holds(SchemaCapabilityKey.reference(REFERENCE_STOCKS, Capability.BUCKETED, Scope.LIVE)),
				"A count-only histogram was seeded a row nothing can ever increment: " + theRegistry.listUsages()
			);
			assertTrue(
				holds(SchemaCapabilityKey.reference(REFERENCE_STOCKS, Capability.INDEXED, Scope.LIVE)),
				"Suppressing the histogram row must not suppress the reference's own `indexed()` row"
			);
		}

		@Test
		@DisplayName("A reference that is not indexed is seeded for nothing at all")
		void shouldSeedNothingForAnUnindexedReference() {
			// the seed-narrower-than-survive rule at its sharpest: without `indexed()` there is no reduced index
			// family, so nothing maintains the reference and no write can ever raise its update count. A row here
			// would sit at `0 / 0` for ever and read as *"nothing uses this, drop it"* - about a flag that is off
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;

			// no attributes on it: the schema refuses a filterable attribute on a non-indexed reference outright, so
			// the only way such a reference exists at all is bare
			theRegistry.alignWith(
				schemaWith(
					builder -> builder.withReferenceTo(
						REFERENCE_STOCKS, EXTERNAL_STOCK_TYPE, Cardinality.ZERO_OR_MORE, thatIs -> {
						}
					)
				)
			);

			assertEquals(
				0, theRegistry.size(),
				"An unindexed reference seeded rows nothing could ever increment: " + theRegistry.listUsages()
			);
		}

		@Test
		@DisplayName("An entity with an indexed hierarchy is seeded for `HIERARCHY_INDEXED`")
		void shouldSeedIndexedHierarchy() {
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;

			theRegistry.alignWith(
				schemaWith(builder -> builder.withHierarchy().withHierarchyIndexedInScope(Scope.LIVE))
			);

			assertTrue(
				holds(SchemaCapabilityKey.entity(Entities.PRODUCT, Capability.HIERARCHICAL, Scope.LIVE)),
				"The entity's own flag was not seeded: " + theRegistry.listUsages()
			);
		}

		@Test
		@DisplayName("A hierarchy is seeded only in the scope that actually indexes it")
		void shouldSeedHierarchyOnlyWhereItIsIndexed() {
			// the entity-level twin of the unindexed-reference case: an index that exists in one scope costs nothing
			// in the other, so only the scope paying for it gets a row
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;

			theRegistry.alignWith(
				schemaWith(builder -> builder.withHierarchy().withHierarchyIndexedInScope(Scope.ARCHIVED))
			);

			assertTrue(
				holds(SchemaCapabilityKey.entity(Entities.PRODUCT, Capability.HIERARCHICAL, Scope.ARCHIVED)),
				"The scope that indexes the hierarchy got no row: " + theRegistry.listUsages()
			);
			assertFalse(
				holds(SchemaCapabilityKey.entity(Entities.PRODUCT, Capability.HIERARCHICAL, Scope.LIVE)),
				"A scope that indexes no hierarchy was seeded anyway: " + theRegistry.listUsages()
			);
		}

		@Test
		@DisplayName("An entity with indexed prices is seeded for `PRICE_INDEXED`")
		void shouldSeedIndexedPrices() {
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;

			theRegistry.alignWith(
				schemaWith(builder -> builder.withPrice().withPriceIndexedInScope(Scope.LIVE))
			);

			assertTrue(
				holds(SchemaCapabilityKey.entity(Entities.PRODUCT, Capability.PRICED, Scope.LIVE)),
				"The entity's own flag was not seeded: " + theRegistry.listUsages()
			);
		}

		@Test
		@DisplayName("Dropping `faceted()` drops its row and leaves `indexed()` alone")
		void shouldDropOnlyTheFlagThatWentAway() {
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			theRegistry.alignWith(
				schemaWith(
					builder -> builder.withReferenceTo(
						REFERENCE_STOCKS, EXTERNAL_STOCK_TYPE, Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs.indexedInScope(Scope.LIVE).facetedInScope(Scope.LIVE)
					)
				)
			);
			final SchemaCapabilityKey indexed = SchemaCapabilityKey.reference(
				REFERENCE_STOCKS, Capability.INDEXED, Scope.LIVE
			);
			final SchemaCapabilityUsage indexedHolder = theRegistry.resolve(indexed);

			theRegistry.alignWith(
				schemaWith(
					builder -> builder.withReferenceTo(
						REFERENCE_STOCKS, EXTERNAL_STOCK_TYPE, Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs.indexedInScope(Scope.LIVE)
					)
				)
			);

			assertFalse(
				holds(SchemaCapabilityKey.reference(REFERENCE_STOCKS, Capability.FACETED, Scope.LIVE)),
				"The row of a flag the schema stopped declaring survived"
			);
			assertSame(
				indexedHolder, theRegistry.resolve(indexed),
				"Dropping one flag of a reference replaced the holder of another, resetting its counters"
			);
		}

	}

	@Nested
	@DisplayName("Aligning with a catalog schema")
	class CatalogAlignmentTest {

		@Test
		@DisplayName("A globally-unique attribute is seeded with the two capabilities the catalog maintains")
		void shouldSeedFilterAndUniqueOfAGloballyUniqueAttribute() {
			// exactly the pair EntityIndexLocalMutationExecutor#reportAttributeTouched files into a catalog registry,
			// and exactly what the catalog's own GlobalUniqueIndex costs - so both rows can be raised by any upsert
			// writing the attribute, and neither is a row stuck at zero by construction
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;

			theRegistry.alignWith(catalogSchemaWithGlobalCode());

			assertTrue(
				holds(SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.FILTERABLE, Scope.LIVE)),
				"A globally-unique attribute was not seeded with the FILTERABLE entry its uniqueness implies"
			);
			assertTrue(
				holds(SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.UNIQUE, Scope.LIVE)),
				"A globally-unique attribute was not seeded with its UNIQUE entry"
			);
			assertEquals(2, theRegistry.size(), "What it holds: " + theRegistry.listUsages());
		}

		@Test
		@DisplayName("A global attribute the catalog keeps no index for is not seeded at all")
		void shouldNotSeedAGlobalAttributeTheCatalogMaintainsNoIndexFor() {
			// the one place seeding is deliberately narrower than the survival rule. `filterable()` and `sortable()` on
			// a global attribute are maintained by every collection declaring it and are seeded in *their* registries;
			// no site can ever file either against the catalog, so a row here would sit at zero forever and read to an
			// operator as a flag nothing uses - the misreading this whole surface exists to prevent
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;

			theRegistry.alignWith(
				new InternalCatalogSchemaBuilder(SchemaCapabilityUsageRegistryTest.this.catalogSchema)
					.withAttribute(
						ATTRIBUTE_EAN, String.class,
						thatIs -> thatIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE)
					)
					.toInstance()
			);

			assertEquals(
				0, theRegistry.size(),
				"The catalog registry seeded a capability no catalog-level index maintains: " + theRegistry.listUsages()
			);
		}

		@Test
		@DisplayName("A globally-unique attribute the catalog schema still declares keeps every entry it has")
		void shouldKeepTheCapabilitiesOfAGloballyUniqueAttribute() {
			// `uniqueGlobally()` never declares `filterable()` or collection-level `unique()` explicitly - it implies
			// both - so these two entries are exactly the ones a survival rule written against the wrong flag drops
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			final SchemaCapabilityKey codeFilter = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_CODE, Capability.FILTERABLE, Scope.LIVE
			);
			final SchemaCapabilityKey codeUnique = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_CODE, Capability.UNIQUE, Scope.LIVE
			);
			theRegistry.resolve(codeFilter).recordRequested(System.currentTimeMillis());
			theRegistry.resolve(codeUnique).recordUpdated(System.currentTimeMillis());

			theRegistry.alignWith(catalogSchemaWithGlobalCode());

			assertTrue(
				holds(codeFilter), "A globally-unique attribute lost the FILTERABLE entry its uniqueness implies"
			);
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

			theRegistry.alignWith(catalogSchemaWithGlobalCode());

			assertFalse(
				holds(eanUnique),
				"The catalog adopted a schema declaring no `ean`, yet the registry still counts it"
			);
		}

		@Test
		@DisplayName("An entry no catalog schema could ever back is reported, not swallowed")
		void shouldRefuseToAlignAnEntryNoCatalogCanDeclare() {
			// a catalog declares neither references nor compounds, so both of these keys could only have been minted
			// by a resolve site aiming at the wrong registry - and a silent drop would look like an ordinary alignment
			final SchemaCapabilityUsageRegistry theRegistry = SchemaCapabilityUsageRegistryTest.this.registry;
			theRegistry.resolve(
				SchemaCapabilityKey.referenceAttribute(
					REFERENCE_STOCKS, ATTRIBUTE_QUANTITY, Capability.FILTERABLE, Scope.LIVE
				)
			);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> theRegistry.alignWith(catalogSchemaWithGlobalCode())
			);

			final SchemaCapabilityUsageRegistry compoundRegistry = new SchemaCapabilityUsageRegistry();
			compoundRegistry.resolve(SchemaCapabilityKey.sortableCompound(null, COMPOUND_CODE_WITH_EAN, Scope.LIVE));

			assertThrows(
				GenericEvitaInternalError.class,
				() -> compoundRegistry.alignWith(catalogSchemaWithGlobalCode())
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
