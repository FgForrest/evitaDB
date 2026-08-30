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

package io.evitadb.core.query.filter.translator.attribute;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.query.filter.AttributeContains;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedFilterCapabilities;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.trigram.TrigramSubstringSearch;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.stubbing.Answer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the composition that lets a query plan whose targets are {@link ReducedEntityIndex}es be answered by the
 * substring accelerator hosted on the {@link GlobalEntityIndex} - one verified global formula, intersected with each
 * target index's own primary keys and OR-ed across the fan-out.
 *
 * Every case asserts a property of the PLAN as well as of the answer, and that is the reason this suite exists: the
 * accelerated path and the scan agree by construction, so a test comparing only their answers stays green when the
 * acceleration silently stops happening. The plan-level observable is the number of hoisted global computations the
 * translator asks the planning context for - one per scope of the target set when the path is taken, none when it
 * declines.
 *
 * The fixture is built directly on index objects rather than through a catalog, exactly as
 * {@link io.evitadb.index.trigram.TrigramSubstringSearchTest} builds its own, so the number of distinct values per
 * index - the quantity the gate is priced against - can be dictated instead of hoped for.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reduced-index substring acceleration")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FILTER)
@Tag(ATTRIBUTE)
class ReducedIndexSubstringAccelerationTest {

	private static final String ENTITY_TYPE = "Product";
	private static final String ATTRIBUTE_TITLE = "title";
	private static final String REFERENCE_NAME = "brand";

	/**
	 * How many reduced indexes the corpus is carved into.
	 */
	private static final int PARTITION_COUNT = 8;

	/**
	 * Values carrying the searched `zebra` pattern, which is also the candidate bound the gate prices - every trigram
	 * of `zebra` posts against exactly these and no filler.
	 *
	 * Chosen as the widest bound that still leaves {@link #GATE_THRESHOLD} sitting on the FLOOR rather than on the
	 * selectivity ratio, rounded down to a multiple of {@link #PARTITION_COUNT} so the matches spread evenly and a
	 * strict subset of the partitions holds a strict subset of them, and never below one per partition. Derived this
	 * way the corpus stays the same size whatever
	 * {@link TrigramSubstringSearch#CANDIDATE_SELECTIVITY_DIVISOR} is retuned to, instead of growing with it until a
	 * partition would clear the floor on its own and stop testing what it claims.
	 */
	private static final int ZEBRA_VALUES = Math.max(
		PARTITION_COUNT,
		(TrigramSubstringSearch.MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT
			/ TrigramSubstringSearch.CANDIDATE_SELECTIVITY_DIVISOR
			/ PARTITION_COUNT) * PARTITION_COUNT
	);

	/**
	 * The size of displaced scan this fixture's pattern has to reach before the gate admits it.
	 *
	 * EVERY size below is derived from this rather than written down, because
	 * {@link TrigramSubstringSearch#CANDIDATE_SELECTIVITY_DIVISOR} is a measured constant that is expected to move.
	 * A fixture sized against today's value would, at a larger divisor, stop clearing the gate - and since the
	 * accelerated and scanning paths agree by construction, the cases would go on passing while testing nothing.
	 */
	private static final long GATE_THRESHOLD = TrigramSubstringSearch.accelerationThreshold(ZEBRA_VALUES);

	/**
	 * Distinct values per partition, sized so the whole fan-out carries twice the threshold. That leaves each
	 * partition below {@link TrigramSubstringSearch#MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT} on its own - asserted
	 * rather than assumed, in `Gate` - while the eight together clear it with a factor of two to spare, and the
	 * early exit stops at half the fan-out.
	 */
	private static final int VALUES_PER_PARTITION =
		(int) ((2 * GATE_THRESHOLD + PARTITION_COUNT - 1) / PARTITION_COUNT);

	/**
	 * The whole corpus of one scope.
	 */
	private static final int DISTINCT_VALUES = VALUES_PER_PARTITION * PARTITION_COUNT;

	/**
	 * Filler values sharing no trigram with the searched pattern, making up the rest of the corpus.
	 */
	private static final int FILLER_VALUES = DISTINCT_VALUES - ZEBRA_VALUES;

	/**
	 * The pattern every case searches for.
	 */
	private static final String PATTERN = "zebra";

	/**
	 * Primary key of the entity before the first ARCHIVED one. The two scopes are given DISJOINT primary key ranges
	 * on purpose: a composition pairing an archived partition with the LIVE global answer then produces an empty
	 * intersection rather than a plausible-looking one. Derived from the corpus size rather than written down, or a
	 * corpus that outgrew it would overlap the two ranges and quietly restore the plausibility.
	 */
	private static final int ARCHIVED_PK_OFFSET = DISTINCT_VALUES;

	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG, NamingConvention.generate(APITestConstants.TEST_CATALOG),
		null,
		EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
	);

	/**
	 * `title` declares the SUBSTRING capability in BOTH scopes, so an archived global index maintains an accelerator
	 * of its own and the per-scope hoisting has something to be wrong about.
	 */
	private static final EntitySchemaContract SCHEMA = new InternalEntitySchemaBuilder(
		CATALOG_SCHEMA, EntitySchema._internalBuild(ENTITY_TYPE)
	)
		.withAttribute(
			ATTRIBUTE_TITLE, String.class,
			thatIs -> thatIs.filterableInScope(
				new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING),
				new ScopedFilterCapabilities(Scope.ARCHIVED, FilterIndexCapability.SUBSTRING)
			)
		)
		.toInstance();

	/**
	 * The reference a reduced index partitions by. A reduced index refuses to file an entity-level attribute unless
	 * the reference it belongs to is `FOR_FILTERING_AND_PARTITIONING` in its scope
	 * (`AbstractReducedEntityIndex#assertPartitioningIndex`), which is also exactly the condition
	 * `IndexSelectionVisitor` requires before a reduced-index plan is eligible at all.
	 *
	 * @return a reference schema the reduced indexes accept writes for
	 */
	@Nonnull
	private static ReferenceSchemaContract partitioningReference() {
		final ReferenceSchemaContract referenceSchema = mock(ReferenceSchemaContract.class);
		when(referenceSchema.getName()).thenReturn(REFERENCE_NAME);
		when(referenceSchema.getReferenceIndexType(any(Scope.class)))
			.thenReturn(ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING);
		return referenceSchema;
	}

	/**
	 * @return the attribute schema every case filters on
	 */
	@Nonnull
	private static EntityAttributeSchemaContract titleSchema() {
		return SCHEMA.getAttribute(ATTRIBUTE_TITLE).orElseThrow();
	}

	/**
	 * Builds the corpus, in one deterministic order: fillers first, then the `zebra` values.
	 *
	 * @return the distinct attribute values
	 */
	@Nonnull
	private static List<String> corpus() {
		final List<String> values = new ArrayList<>(FILLER_VALUES + ZEBRA_VALUES);
		for (int i = 0; i < FILLER_VALUES; i++) {
			values.add(String.format("item-%04d", i));
		}
		for (int i = 0; i < ZEBRA_VALUES; i++) {
			values.add(String.format("widget zebra %03d", i));
		}
		return values;
	}

	/**
	 * @param scope    the scope the entity lives in
	 * @param position the position of the value in {@link #corpus()}
	 * @return the primary key that value is written for
	 */
	private static int primaryKeyOf(@Nonnull Scope scope, int position) {
		return (scope == Scope.ARCHIVED ? ARCHIVED_PK_OFFSET : 0) + position + 1;
	}

	/**
	 * One scope's whole index world: the global index hosting the accelerator, and the reduced indexes the corpus was
	 * carved into.
	 *
	 * @param scope          the scope both sides belong to
	 * @param globalIndex    the global index holding every value of that scope
	 * @param reducedIndexes the partitions, in ascending partition order
	 */
	private record IndexWorld(
		@Nonnull Scope scope,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull List<ReducedEntityIndex> reducedIndexes
	) {
	}

	/**
	 * Builds one scope's world: every corpus value goes into the global index, and into the reduced index of the
	 * partition its position falls in.
	 *
	 * The entity-level attribute is written into the reduced indexes with a NON-null reference schema, which is what
	 * the production fan-out does - `AttributeIndex#createAttributeKey` nulls the reference name for any
	 * `EntityAttributeSchemaContract` regardless of the surrounding index, so both writes file the value under the
	 * identical key and the read side finds it under the entity-level one.
	 *
	 * @param scope the scope to build
	 * @return the populated world
	 */
	@Nonnull
	private static IndexWorld buildWorld(@Nonnull Scope scope) {
		final ReferenceSchemaContract referenceSchema = partitioningReference();
		final GlobalEntityIndex globalIndex = new GlobalEntityIndex(
			1, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL, scope)
		);
		final List<ReducedEntityIndex> reducedIndexes = new ArrayList<>(PARTITION_COUNT);
		for (int partition = 0; partition < PARTITION_COUNT; partition++) {
			reducedIndexes.add(
				new ReducedEntityIndex(
					partition + 1, ENTITY_TYPE,
					new EntityIndexKey(
						EntityIndexType.REFERENCED_ENTITY, scope,
						new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, partition + 1))
					)
				)
			);
		}

		final List<String> values = corpus();
		for (int i = 0; i < values.size(); i++) {
			final int primaryKey = primaryKeyOf(scope, i);
			globalIndex.insertPrimaryKeyIfMissing(primaryKey);
			globalIndex.upsertAttribute(
				null, titleSchema(), Set.of(), scope, null, values.get(i), primaryKey
			);
			final ReducedEntityIndex partition = reducedIndexes.get(i % PARTITION_COUNT);
			partition.insertPrimaryKeyIfMissing(primaryKey);
			partition.upsertAttribute(
				referenceSchema, titleSchema(), Set.of(), scope, null, values.get(i), primaryKey
			);
		}
		return new IndexWorld(scope, globalIndex, reducedIndexes);
	}

	/**
	 * The scan every accelerated answer must agree with: each index resolves the pattern against its own filter index
	 * and the results are unioned, which is precisely what `FilterByVisitor#applyOnIndexes` produces with the trigram
	 * path switched off.
	 *
	 * @param indexes the indexes to scan
	 * @return the matching primary keys, ascending
	 */
	@Nonnull
	private static int[] scannedPrimaryKeys(@Nonnull List<? extends EntityIndex> indexes) {
		final BaseBitmap union = new BaseBitmap();
		for (final EntityIndex index : indexes) {
			final FilterIndex filterIndex = index.getFilterIndex(null, titleSchema(), null);
			assertNotNull(filterIndex, "every index of this fixture holds the attribute");
			final Bitmap matched = filterIndex.getRecordsWhoseValuesContains(PATTERN).compute();
			union.addAll(matched);
		}
		return union.getArray();
	}

	/**
	 * Collects every {@link AndFormula} of a formula tree - the shape a reduced index's accelerated answer takes, and
	 * the shape its scan never takes.
	 *
	 * @param formula the tree to walk
	 * @return the conjunctions found, in traversal order
	 */
	@Nonnull
	private static List<AndFormula> conjunctionsOf(@Nonnull Formula formula) {
		final List<AndFormula> result = new ArrayList<>();
		collectConjunctions(formula, result);
		return result;
	}

	/**
	 * Depth-first half of {@link #conjunctionsOf}.
	 *
	 * @param formula the node being visited
	 * @param result  the accumulator
	 */
	private static void collectConjunctions(@Nonnull Formula formula, @Nonnull List<AndFormula> result) {
		if (formula instanceof AndFormula andFormula) {
			result.add(andFormula);
		}
		for (final Formula innerFormula : formula.getInnerFormulas()) {
			collectConjunctions(innerFormula, result);
		}
	}

	/**
	 * The harness that stands in for the query planner: it drives {@link AttributeContainsTranslator} over a chosen
	 * target index set and records how many times the translator asked the planning context to compute a hoisted
	 * global formula.
	 *
	 * That count is the observable the whole suite turns on. A translator that hoists correctly asks ONCE per scope
	 * present in the target set, however many indexes that scope contributes; one that declines asks not at all.
	 */
	private static final class TranslationHarness {
		/**
		 * How many times {@link QueryPlanningContext#computeOnlyOnce} was invoked, i.e. how many global substring
		 * computations the translation paid for.
		 */
		private final AtomicInteger hoistedComputations = new AtomicInteger();
		/**
		 * How many target indexes the gate's summation actually pulled off the index stream. Only the gate consumes
		 * that stream - the per-index fan-out below walks `targetIndexes` directly - so this counts the summation and
		 * nothing else, which is what makes the early exit observable.
		 */
		private final AtomicInteger gateWalkVisits = new AtomicInteger();
		/**
		 * The indexes the plan targets, in the order `applyOnIndexes` walks them.
		 */
		private final List<EntityIndex> targetIndexes;
		/**
		 * The mocked visitor the translator is driven through.
		 */
		private final FilterByVisitor filterByVisitor;

		/**
		 * @param targetIndexes   the indexes the plan targets
		 * @param worlds          the scopes' worlds, whose global indexes are resolved by scope exactly as the query
		 *                        context resolves them
		 * @param referenceSchema the reference schema of the processing scope, or `null` for an entity-level filter
		 */
		private TranslationHarness(
			@Nonnull List<EntityIndex> targetIndexes,
			@Nonnull List<IndexWorld> worlds,
			@Nullable ReferenceSchemaContract referenceSchema
		) {
			this.targetIndexes = targetIndexes;

			// `computeOnlyOnce` and `getAttributeSchema` are VARARGS methods, and how an argument matcher binds to a
			// vararg parameter has shifted across Mockito majors. Both are therefore answered by the mock's default
			// answer, dispatching on the method name, so this harness depends on no matcher semantics at all - the
			// stubs below are all for fixed-arity methods, where `any()` has never been ambiguous.
			final Answer<Object> memoAnswer = invocation -> {
				if ("computeOnlyOnce".equals(invocation.getMethod().getName())) {
					// deliberately NOT memoising: were the hoist to slip back inside the per-index lambda, a memoising
					// stub would hand every index the same instance and hide the regression this harness exists to
					// catch. Index 2 is the formula supplier, which precedes the varargs and is unaffected by how they
					// are packed
					this.hoistedComputations.incrementAndGet();
					final Supplier<Formula> formulaSupplier = invocation.getArgument(2);
					return formulaSupplier.get();
				}
				return Answers.RETURNS_DEFAULTS.answer(invocation);
			};
			final QueryPlanningContext queryContext = mock(QueryPlanningContext.class, memoAnswer);
			when(queryContext.getGlobalEntityIndexIfExists(anyString(), any(Scope.class)))
				.thenAnswer(
					invocation -> {
						final Scope requested = invocation.getArgument(1);
						return worlds.stream()
							.filter(world -> world.scope() == requested)
							.map(IndexWorld::globalIndex)
							.findFirst();
					}
				);

			// the real visitor filters its index stream by the allowed scopes, so the scopes of the target set are
			// always a subset of these - deriving them from the set keeps the two consistent
			final Set<Scope> scopesOfTargetSet = EnumSet.noneOf(Scope.class);
			for (final EntityIndex entityIndex : targetIndexes) {
				scopesOfTargetSet.add(entityIndex.getIndexKey().scope());
			}
			final ProcessingScope<?> processingScope = mock(ProcessingScope.class);
			when(processingScope.getScopes()).thenReturn(scopesOfTargetSet);
			when(processingScope.getReferenceSchema()).thenReturn(referenceSchema);
			when(processingScope.getEntitySchemaOrThrowException()).thenReturn(SCHEMA);

			// the second varargs method, answered the same matcher-free way - both of its overloads return an
			// `AttributeSchemaContract`, so one answer covers either
			final Answer<Object> attributeSchemaAnswer = invocation ->
				"getAttributeSchema".equals(invocation.getMethod().getName()) ?
					titleSchema() : Answers.RETURNS_DEFAULTS.answer(invocation);
			this.filterByVisitor = mock(FilterByVisitor.class, attributeSchemaAnswer);
			doReturn(processingScope).when(this.filterByVisitor).getProcessingScope();
			when(this.filterByVisitor.getQueryContext()).thenReturn(queryContext);
			// stubbed rather than left to the mock's default: this attribute is entity-level, and the translator must
			// take the `getAttributeSchema` path rather than the global-attribute one
			final SealedCatalogSchema catalogSchema = mock(SealedCatalogSchema.class);
			when(catalogSchema.getAttribute(anyString())).thenReturn(Optional.empty());
			when(this.filterByVisitor.getCatalogSchema()).thenReturn(catalogSchema);
			when(this.filterByVisitor.isEntityTypeKnown()).thenReturn(true);
			when(this.filterByVisitor.isPrefetchPossible()).thenReturn(false);
			// a FRESH stream per call - the gate consumes one per scope it prices, and a stream handed out twice would
			// already be exhausted. `peek` counts what the gate actually pulled, which is how the early exit is seen
			when(this.filterByVisitor.getEntityIndexStream()).thenAnswer(
				invocation -> this.targetIndexes.stream().peek(entityIndex -> this.gateWalkVisits.incrementAndGet())
			);
			when(this.filterByVisitor.applyOnIndexes(any())).thenAnswer(
				invocation -> {
					final Function<EntityIndex, Formula> perIndex = invocation.getArgument(0);
					final List<Formula> formulas = new ArrayList<>(this.targetIndexes.size());
					for (final EntityIndex entityIndex : this.targetIndexes) {
						final Formula formula = perIndex.apply(entityIndex);
						if (!(formula instanceof EmptyFormula)) {
							formulas.add(formula);
						}
					}
					return FormulaFactory.or(formulas.toArray(Formula[]::new));
				}
			);
		}

		/**
		 * @return the formula the translator produced for `attributeContains(title, "zebra")`
		 */
		@Nonnull
		private Formula translate() {
			return new AttributeContainsTranslator()
				.translate(new AttributeContains(ATTRIBUTE_TITLE, PATTERN), this.filterByVisitor);
		}

		/**
		 * @return how many hoisted global computations the translation asked for
		 */
		private int hoistedComputations() {
			return this.hoistedComputations.get();
		}

		/**
		 * @return how many target indexes the gate's summation pulled off the index stream
		 */
		private int gateWalkVisits() {
			return this.gateWalkVisits.get();
		}
	}

	@Nested
	@DisplayName("a fan-out of reduced indexes answers exactly what its own scan would")
	class Agreement {

		@Test
		@DisplayName("the accelerated fan-out and the per-index scan return the same primary keys")
		void shouldReturnTheSamePrimaryKeysAsThePerIndexScanWhenAccelerated() {
			final IndexWorld live = buildWorld(Scope.LIVE);
			final TranslationHarness harness = new TranslationHarness(
				List.copyOf(live.reducedIndexes()), List.of(live), null
			);

			final Formula formula = harness.translate();
			assertEquals(
				1, harness.hoistedComputations(),
				"the fan-out must have been accelerated - comparing the scan against itself would prove nothing"
			);
			final int[] scanned = scannedPrimaryKeys(live.reducedIndexes());
			assertEquals(
				ZEBRA_VALUES, scanned.length,
				"the corpus must plant exactly " + ZEBRA_VALUES + " matching entities, or the agreement is vacuous"
			);
			assertArrayEquals(scanned, formula.compute().getArray(), "the accelerated fan-out and the scan disagree");
		}

		@Test
		@DisplayName("a fan-out covering only part of the corpus is restricted to the partitions it targets")
		void shouldRestrictTheGlobalAnswerToTheTargetedPartitions() {
			// the whole-fan-out case above cannot see the intersection at all: eight partitions ARE the corpus, so
			// dropping the AND and returning the global answer verbatim would produce the identical set. Targeting a
			// STRICT subset is what makes the restriction load-bearing
			final IndexWorld live = buildWorld(Scope.LIVE);
			final int targetedPartitions = PARTITION_COUNT - 2;
			final List<EntityIndex> partialTarget = List.copyOf(
				live.reducedIndexes().subList(0, targetedPartitions)
			);
			final TranslationHarness harness = new TranslationHarness(partialTarget, List.of(live), null);

			final Formula formula = harness.translate();
			assertEquals(1, harness.hoistedComputations(), "six partitions still sum past the floor");

			final int[] scanned = scannedPrimaryKeys(partialTarget);
			final int[] globalAnswer = scannedPrimaryKeys(List.of(live.globalIndex()));
			assertTrue(
				scanned.length < globalAnswer.length,
				"the targeted partitions must hold FEWER matches than the collection, or the intersection is invisible"
			);
			assertArrayEquals(
				scanned, formula.compute().getArray(),
				"the answer must be the global one restricted to the targeted partitions, not the global one"
			);
		}

		@Test
		@DisplayName("a plan targeting the global index alone is unchanged by the reduced-index path")
		void shouldLeaveTheGlobalIndexPlanUntouchedWhenItIsTheOnlyTarget() {
			final IndexWorld live = buildWorld(Scope.LIVE);
			final TranslationHarness harness = new TranslationHarness(
				List.of(live.globalIndex()), List.of(live), null
			);

			final Formula formula = harness.translate();
			assertEquals(1, harness.hoistedComputations(), "the global plan must still be accelerated");
			assertTrue(
				conjunctionsOf(formula).isEmpty(),
				"the global index IS the whole primary key universe - intersecting it with its own keys would only "
					+ "add a node"
			);
			assertArrayEquals(
				scannedPrimaryKeys(List.of(live.globalIndex())),
				formula.compute().getArray(),
				"the global plan's accelerated answer diverges from its scan"
			);
		}
	}

	@Nested
	@DisplayName("the gate prices against the scan the ONE computation displaces")
	class Gate {

		@Test
		@DisplayName("a target set too small to be worth accelerating takes the scan")
		void shouldDeclineWhenTheTargetSetIsBelowTheFloor() {
			final IndexWorld live = buildWorld(Scope.LIVE);
			// two of eight partitions hold a quarter of the corpus, which is half the threshold by construction -
			// even though the GLOBAL tree the pattern would be resolved against holds the whole of it, which is what
			// the pre-C5 gate priced against
			final List<EntityIndex> narrowTarget = List.copyOf(live.reducedIndexes().subList(0, 2));
			final TranslationHarness harness = new TranslationHarness(narrowTarget, List.of(live), null);

			final Formula formula = harness.translate();
			assertEquals(
				0, harness.hoistedComputations(),
				"a two-partition target set is below the floor and must not pay for a global computation"
			);
			assertTrue(conjunctionsOf(formula).isEmpty(), "a declined plan builds no intersection");
			assertArrayEquals(
				scannedPrimaryKeys(narrowTarget),
				formula.compute().getArray(),
				"the declined plan must still answer what the scan answers"
			);
		}

		@Test
		@DisplayName("a fan-out wide enough in total is accelerated even though every partition is tiny")
		void shouldAccelerateAWideFanOutOfPartitionsThatAreIndividuallyTooSmall() {
			final IndexWorld live = buildWorld(Scope.LIVE);
			for (final ReducedEntityIndex reducedIndex : live.reducedIndexes()) {
				final FilterIndex filterIndex = reducedIndex.getFilterIndex(null, titleSchema(), null);
				assertNotNull(filterIndex, "every partition holds the attribute");
				assertTrue(
					filterIndex.getInvertedIndex().getBucketCount()
						< TrigramSubstringSearch.MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT,
					"the fixture is calibrated so that summation is the ONLY thing that can clear the floor"
				);
			}

			final List<EntityIndex> wideTarget = List.copyOf(live.reducedIndexes());
			final TranslationHarness harness = new TranslationHarness(wideTarget, List.of(live), null);

			final Formula formula = harness.translate();
			assertEquals(
				1, harness.hoistedComputations(),
				"the whole fan-out sums to twice the threshold, well past it"
			);
			assertEquals(
				PARTITION_COUNT, conjunctionsOf(formula).size(),
				"each partition contributes one intersection with the hoisted global answer"
			);
		}

		@Test
		@DisplayName("the threshold itself admits the summed fan-out and refuses a single partition")
		void shouldClearTheFloorOnlyBySummation() {
			assertFalse(
				TrigramSubstringSearch.isWorthAccelerating(ZEBRA_VALUES, VALUES_PER_PARTITION),
				"one partition alone is below the floor"
			);
			assertTrue(
				TrigramSubstringSearch.isWorthAccelerating(ZEBRA_VALUES, DISTINCT_VALUES),
				"the whole fan-out is above it, and is what the one hoisted computation displaces"
			);
		}

		@Test
		@DisplayName("the summation stops at the threshold instead of walking the whole fan-out")
		void shouldStopSummingOnceTheThresholdIsReached() {
			final IndexWorld live = buildWorld(Scope.LIVE);
			final TranslationHarness harness = new TranslationHarness(
				List.copyOf(live.reducedIndexes()), List.of(live), null
			);

			harness.translate();
			assertEquals(1, harness.hoistedComputations(), "the fan-out must have been accelerated");
			// `zebra` occurs in exactly ZEBRA_VALUES values, so that is the candidate bound the threshold derives from
			final int expectedVisits = (int) Math.min(
				PARTITION_COUNT, (GATE_THRESHOLD + VALUES_PER_PARTITION - 1) / VALUES_PER_PARTITION
			);
			assertTrue(
				expectedVisits < PARTITION_COUNT,
				"the fixture must be able to tell an early exit apart from a full walk"
			);
			assertEquals(
				expectedVisits, harness.gateWalkVisits(),
				"the gate must stop as soon as the running total reaches the threshold - an eager sum would visit all "
					+ PARTITION_COUNT + " partitions"
			);
		}
	}

	@Nested
	@DisplayName("the global computation is paid once per scope, not once per index")
	class Hoisting {

		@Test
		@DisplayName("every partition is intersected with the very same global operand instance")
		void shouldShareOneGlobalOperandInstanceAcrossThePartitions() {
			final IndexWorld live = buildWorld(Scope.LIVE);
			final TranslationHarness harness = new TranslationHarness(
				List.copyOf(live.reducedIndexes()), List.of(live), null
			);

			final List<AndFormula> conjunctions = conjunctionsOf(harness.translate());
			assertEquals(
				1, harness.hoistedComputations(),
				"the intersection and its verification must be paid once for the whole fan-out"
			);
			assertEquals(PARTITION_COUNT, conjunctions.size(), "one intersection per partition");
			final Formula sharedOperand = conjunctions.get(0).getInnerFormulas()[1];
			for (final AndFormula conjunction : conjunctions) {
				assertEquals(2, conjunction.getInnerFormulas().length, "primary keys AND the global answer");
				assertSame(
					sharedOperand, conjunction.getInnerFormulas()[1],
					"a per-index computation would hand each partition an operand of its own"
				);
			}
		}

		@Test
		@DisplayName("two scopes in one target set are each served by their own global index")
		void shouldHoistOneGlobalAnswerPerScopeWhenTheTargetSetSpansBoth() {
			final IndexWorld live = buildWorld(Scope.LIVE);
			final IndexWorld archived = buildWorld(Scope.ARCHIVED);
			final List<EntityIndex> bothScopes = new ArrayList<>(2 * PARTITION_COUNT);
			bothScopes.addAll(live.reducedIndexes());
			bothScopes.addAll(archived.reducedIndexes());
			final TranslationHarness harness = new TranslationHarness(
				List.copyOf(bothScopes), List.of(live, archived), null
			);

			final Formula formula = harness.translate();
			assertEquals(
				2, harness.hoistedComputations(),
				"a trigram index is hosted per global index, and a global index per scope"
			);
			// the two scopes hold disjoint primary key ranges, so pairing an archived partition with the LIVE global
			// answer would silently drop half the result rather than produce a wrong-looking one
			assertArrayEquals(
				scannedPrimaryKeys(bothScopes),
				formula.compute().getArray(),
				"a cross-scope composition lost or invented entities"
			);
		}
	}

	/**
	 * The guard these two cases exercise is deliberately stricter than anything reachable today: with a reference in
	 * the processing scope, `AttributeSchemaAccessor` resolves the attribute against the reference schema alone, so a
	 * production query can never reach the translator with an entity-level attribute AND a reference scope. The
	 * fixture forces exactly that pairing, because what has to be pinned is the GUARD - the day the schema layer
	 * lets a reference attribute declare the SUBSTRING capability, global postings would mean "on SOME reference of
	 * that type" while a reduced index means one specific reference, and this is the branch that must take the scan
	 * instead of composing an over-broad answer.
	 */
	@Nested
	@DisplayName("the trigram path is confined to entity-level attributes")
	class Confinement {

		@Test
		@DisplayName("a reference-scoped processing scope refuses the accelerator and scans")
		void shouldRefuseToAccelerateWhenTheProcessingScopeNamesAReference() {
			final IndexWorld live = buildWorld(Scope.LIVE);
			final List<EntityIndex> wideTarget = List.copyOf(live.reducedIndexes());
			final TranslationHarness harness = new TranslationHarness(
				wideTarget, List.of(live), partitioningReference()
			);

			final Formula formula = harness.translate();
			assertEquals(
				0, harness.hoistedComputations(),
				"global postings of a reference attribute would mean `on SOME reference of that type`, which is not "
					+ "what a reduced index means - the path must decline rather than compose an over-broad answer"
			);
			assertTrue(conjunctionsOf(formula).isEmpty(), "a declined plan builds no intersection");
			assertArrayEquals(
				scannedPrimaryKeys(wideTarget),
				formula.compute().getArray(),
				"declining must change how fast the answer arrives and nothing else"
			);
		}

		@Test
		@DisplayName("the same target set IS accelerated once the reference scope is gone")
		void shouldAccelerateTheVerySameTargetSetWithoutAReferenceScope() {
			// the twin of the case above, differing in the processing scope's reference schema alone - without it the
			// confinement case could pass because the fixture never qualified for acceleration in the first place
			final IndexWorld live = buildWorld(Scope.LIVE);
			final TranslationHarness harness = new TranslationHarness(
				List.copyOf(live.reducedIndexes()), List.of(live), null
			);

			harness.translate();
			assertEquals(1, harness.hoistedComputations(), "the very same target set qualifies without the scope");
		}
	}

}
