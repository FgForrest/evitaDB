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

package io.evitadb.api.functional.reference;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sweep over `referenceHaving` body shapes, each one run on every plan the planner can be pushed onto.
 *
 * `referenceHaving(R, body)` is the SQL `EXISTS` operator, so the body binds a single reference ROW and the owner
 * matches when **some one row** satisfies the whole body. That reading is a property of the query, not of the plan:
 * the reduced-index family, the prefetched entity bodies and whatever the cost model picks on the day all have to
 * answer it identically. This class asserts exactly that, over a table of shapes rather than one shape per method,
 * because the defects found here were only visible in the combinations - `not` beside a sibling, a nested query
 * beside a row attribute, a disjunction that hands a negation upwards.
 *
 * The oracle is derived from the fetched entity bodies (`originalProducts`) rather than written down, so a fixture
 * change cannot silently make a shape agree with a wrong engine.
 *
 * Two of these rows carry more weight than the rest and are the reason the class exists:
 *
 * - {@link #shouldSurviveDoubleNegationAtTheTopLevel} runs a doubly negated constraint **outside** any reference
 *   body. It is what separates "the reference rewrite broke `not(not(x))`" from "`not(not(x))` never worked",
 *   and it can only answer that because it never enters `referenceHaving` at all.
 * - the `not(entityHaving(...))` shapes, which fail on every plan and on two different references.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference having row semantics sweep")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(CONTRACT)
@Tag(REFERENCE)
@Tag(FILTER)
public class ReferenceHavingRowSemanticsSweepFunctionalTest
	extends AbstractBidirectionalReferenceRewriteFunctionalTest {

	/**
	 * Highest product primary key the prefetch-enabling `entityPrimaryKeyInSet` admits.
	 *
	 * A lone `referenceHaving` gives the planner no conjunctive entity ids, so `PrefetchFormulaVisitor` never finds
	 * prefetching possible and `PREFER_PREFETCHING` is a no-op. Naming a small set of primary keys beside the
	 * reference constraint is what supplies them - and it has to stay small, because the visitor abandons prefetch
	 * once the collected bitmap cardinality passes its own threshold.
	 */
	private static final int PREFETCH_CANDIDATE_LIMIT = 12;
	/**
	 * The primary keys handed to the prefetch-enabling `entityPrimaryKeyInSet`.
	 */
	private static final Integer[] PREFETCH_CANDIDATES = IntStream
		.rangeClosed(1, PREFETCH_CANDIDATE_LIMIT)
		.boxed()
		.toArray(Integer[]::new);
	/**
	 * The product the top-level double-negation row addresses, and the only one carrying that code.
	 */
	private static final int DOUBLE_NEGATION_PRODUCT_PK = 1;

	/**
	 * One examined shape - the body handed to `referenceHaving`, and the row predicate describing the row-scoped
	 * answer it must produce.
	 *
	 * @param name          human readable label used in the failure report
	 * @param referenceName reference whose rows the body is evaluated against
	 * @param body          the body of the reference constraint
	 * @param rowPredicate  predicate one of the owner's rows has to satisfy for the owner to match
	 */
	private record Shape(
		@Nonnull String name,
		@Nonnull String referenceName,
		@Nonnull FilterConstraint body,
		@Nonnull Predicate<ReferenceContract> rowPredicate
	) {
	}

	/**
	 * Runs every shape on every plan and reports each disagreement with the row-scoped expectation.
	 *
	 * Failures are accumulated rather than thrown one at a time, because a shape that throws would otherwise hide
	 * every shape after it - and the first defect found here did exactly that.
	 *
	 * @param evita            the engine
	 * @param originalProducts all products, fully fetched, as the dataset built them
	 */
	@DisplayName("every body shape answers row-scoped on every plan")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldAnswerRowScopedOnEveryPlan(Evita evita, List<SealedEntity> originalProducts) {
		final List<String> failures = new ArrayList<>(32);
		for (final Shape shape : shapes()) {
			final Set<Integer> expected = originalProducts.stream()
				.filter(it -> it.getPrimaryKey() < FIRST_ARCHIVED_PRODUCT_PK)
				.filter(it -> holdsRow(it, shape.referenceName(), shape.rowPredicate()))
				.map(SealedEntity::getPrimaryKey)
				.collect(Collectors.toCollection(TreeSet::new));
			for (final DebugMode debugMode : new DebugMode[]{
				null, DebugMode.PREFER_INDEX_SCAN, DebugMode.PREFER_PREFETCHING
			}) {
				collectDisagreement(
					evita, shape, expected, debugMode, false,
					debugMode == null ? "planner" : debugMode.name(), failures
				);
			}
			// the plans that can actually prefetch - see PREFETCH_CANDIDATE_LIMIT for why the constraint is needed
			final Set<Integer> narrowedExpectation = expected.stream()
				.filter(it -> it <= PREFETCH_CANDIDATE_LIMIT)
				.collect(Collectors.toCollection(TreeSet::new));
			for (final DebugMode debugMode : new DebugMode[]{null, DebugMode.PREFER_PREFETCHING}) {
				collectDisagreement(
					evita, shape, narrowedExpectation, debugMode, true,
					"pk-narrowed/" + (debugMode == null ? "planner" : debugMode.name()), failures
				);
			}
		}
		assertTrue(failures.isEmpty(), "Row-scoped semantics violated:\n" + String.join("\n", failures));
	}

	/**
	 * Establishes whether a doubly negated constraint survives OUTSIDE a `referenceHaving` body.
	 *
	 * `NotTranslator` emits every negation as a `FutureNotFormula` placeholder, so the inner `not` hands the outer
	 * one a placeholder rather than a computable formula and the outer one wraps it in a second placeholder. Nothing
	 * unwraps the pair, and whoever finally resolves the outer one hands the inner one to `Formula#compute`.
	 *
	 * This row deliberately names no reference at all. That is its whole point: it is the control that decides
	 * whether the failure belongs to the reference-body rewrite or to the boolean translator, and a row that went
	 * anywhere near `referenceHaving` could not decide it. The assertion is written against the singly-positive
	 * constraint, because `not(not(x))` and `x` are the same question.
	 *
	 * @param evita the engine
	 */
	@DisplayName("double negation at the top level of `filterBy` answers what the positive constraint answers")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldSurviveDoubleNegationAtTheTopLevel(Evita evita) {
		final String code = "product-" + DOUBLE_NEGATION_PRODUCT_PK;
		final Set<Integer> positiveReading = runTopLevel(evita, attributeEquals(ATTR_CODE, code));
		assertEquals(
			Set.of(DOUBLE_NEGATION_PRODUCT_PK), positiveReading,
			"The positive constraint must select exactly the product carrying the code, or this row cannot tell " +
				"a correct answer from an accidental one."
		);
		assertFalse(
			runTopLevel(evita, not(attributeEquals(ATTR_CODE, code))).isEmpty(),
			"A single negation must select the rest of the collection - if it does not, the failure below is not " +
				"about the SECOND negation."
		);

		assertEquals(
			positiveReading,
			runTopLevel(evita, not(not(attributeEquals(ATTR_CODE, code)))),
			"`not(not(x))` must answer exactly what `x` answers - the two negations cancel. This is asserted " +
				"outside any `referenceHaving`, so a failure here belongs to `NotTranslator` and not to the " +
				"reference body rewrite."
		);
	}

	/**
	 * Runs one shape on one plan and appends a line to the report when the answer disagrees.
	 *
	 * @param evita               the engine
	 * @param shape               the shape being examined
	 * @param expected            the row-scoped answer derived from the fetched entity bodies
	 * @param debugMode           plan forcing debug mode, NULL leaves the choice to the planner
	 * @param narrowByPrimaryKeys whether to add the prefetch-enabling `entityPrimaryKeyInSet`
	 * @param plan                label of the plan, for the report
	 * @param failures            accumulator of disagreements
	 */
	private static void collectDisagreement(
		@Nonnull Evita evita,
		@Nonnull Shape shape,
		@Nonnull Set<Integer> expected,
		@Nullable DebugMode debugMode,
		boolean narrowByPrimaryKeys,
		@Nonnull String plan,
		@Nonnull List<String> failures
	) {
		try {
			final Set<Integer> actual = run(
				evita, shape.referenceName(), shape.body(), debugMode, narrowByPrimaryKeys
			);
			if (!expected.equals(actual)) {
				failures.add(shape.name() + " [" + plan + "]: expected " + expected + " but got " + actual);
			}
		} catch (Exception ex) {
			failures.add(
				shape.name() + " [" + plan + "]: threw " + ex.getClass().getName() + ": " + ex.getMessage()
			);
		}
	}

	/**
	 * Returns the examined body shapes.
	 *
	 * @return list of shapes
	 */
	@Nonnull
	private static List<Shape> shapes() {
		final Predicate<ReferenceContract> tier7 = row -> longIs(row, REF_ATTR_TIER, CROSS_ROW_MATCHED_TIER);
		final Predicate<ReferenceContract> tier8 = row -> longIs(row, REF_ATTR_TIER, CROSS_ROW_OTHER_TIER);
		final Predicate<ReferenceContract> markQ = row -> stringIs(row, REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK);
		final Predicate<ReferenceContract> markP = row -> stringIs(row, REF_ATTR_MARK, CROSS_ROW_OTHER_MARK);
		final Predicate<ReferenceContract> toA = row -> row.getReferencedPrimaryKey() == CROSS_ROW_CATEGORY_A_PK;
		final Predicate<ReferenceContract> toB = row -> row.getReferencedPrimaryKey() == CROSS_ROW_CATEGORY_B_PK;

		return List.of(
			new Shape(
				"and(tier=7, mark=q)", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				and(
					attributeEquals(REF_ATTR_TIER, CROSS_ROW_MATCHED_TIER),
					attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK)
				),
				tier7.and(markQ)
			),
			new Shape(
				"or(tier=8, not(mark=q))", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				or(
					attributeEquals(REF_ATTR_TIER, CROSS_ROW_OTHER_TIER),
					not(attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK))
				),
				tier8.or(markQ.negate())
			),
			new Shape(
				"and(not(tier=8), not(mark=q))", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				and(
					not(attributeEquals(REF_ATTR_TIER, CROSS_ROW_OTHER_TIER)),
					not(attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK))
				),
				tier8.negate().and(markQ.negate())
			),
			new Shape(
				"not(not(mark=q))", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				not(not(attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK))),
				markQ
			),
			new Shape(
				"not(and(tier=7, mark=p))", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				not(
					and(
						attributeEquals(REF_ATTR_TIER, CROSS_ROW_MATCHED_TIER),
						attributeEquals(REF_ATTR_MARK, CROSS_ROW_OTHER_MARK)
					)
				),
				tier7.and(markP).negate()
			),
			new Shape(
				"not(or(tier=8, mark=q))", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				not(
					or(
						attributeEquals(REF_ATTR_TIER, CROSS_ROW_OTHER_TIER),
						attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK)
					)
				),
				tier8.or(markQ).negate()
			),
			new Shape(
				"and(epkInSet(A), mark=q)", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				and(
					entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_A_PK),
					attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK)
				),
				toA.and(markQ)
			),
			new Shape(
				"or(epkInSet(B), tier=8)", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				or(
					entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_B_PK),
					attributeEquals(REF_ATTR_TIER, CROSS_ROW_OTHER_TIER)
				),
				toB.or(tier8)
			),
			new Shape(
				"and(epkInSet(B), not(mark=q))", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				and(
					entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_B_PK),
					not(attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK))
				),
				toB.and(markQ.negate())
			),
			new Shape(
				"and(not(epkInSet(A)), tier=7)", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				and(
					not(entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_A_PK)),
					attributeEquals(REF_ATTR_TIER, CROSS_ROW_MATCHED_TIER)
				),
				toA.negate().and(tier7)
			),
			new Shape(
				"entityHaving(epkInSet(B))", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				entityHaving(entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_B_PK)),
				toB
			),
			new Shape(
				"and(entityHaving(epkInSet(B)), not(mark=q))", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				and(
					entityHaving(entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_B_PK)),
					not(attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK))
				),
				toB.and(markQ.negate())
			),
			new Shape(
				"and(entityHaving(epkInSet(A)), mark=q)", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				and(
					entityHaving(entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_A_PK)),
					attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK)
				),
				toA.and(markQ)
			),
			new Shape(
				"and(or(tier=7, tier=8), mark=q)", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				and(
					or(
						attributeEquals(REF_ATTR_TIER, CROSS_ROW_MATCHED_TIER),
						attributeEquals(REF_ATTR_TIER, CROSS_ROW_OTHER_TIER)
					),
					attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK)
				),
				tier7.or(tier8).and(markQ)
			),
			new Shape(
				"and(tier=7, or(mark=q, mark=z))", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				and(
					attributeEquals(REF_ATTR_TIER, CROSS_ROW_MATCHED_TIER),
					or(
						attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK),
						attributeEquals(REF_ATTR_MARK, "z")
					)
				),
				tier7.and(markQ)
			),
			new Shape(
				"attributeInSet(tier, 7, 8)", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				attributeInSet(REF_ATTR_TIER, CROSS_ROW_MATCHED_TIER, CROSS_ROW_OTHER_TIER),
				tier7.or(tier8)
			),
			new Shape(
				"or(not(tier=7), not(mark=p))", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				or(
					not(attributeEquals(REF_ATTR_TIER, CROSS_ROW_MATCHED_TIER)),
					not(attributeEquals(REF_ATTR_MARK, CROSS_ROW_OTHER_MARK))
				),
				tier7.negate().or(markP.negate())
			),
			new Shape(
				"and(entityHaving(code of B), tier=7)", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				and(
					entityHaving(attributeEquals(ATTR_CODE, "category-" + CROSS_ROW_CATEGORY_B_PK)),
					attributeEquals(REF_ATTR_TIER, CROSS_ROW_MATCHED_TIER)
				),
				toB.and(tier7)
			),
			new Shape(
				"inScope(LIVE, and(tier=7, mark=q))", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				QueryConstraints.inScope(
					Scope.LIVE,
					and(
						attributeEquals(REF_ATTR_TIER, CROSS_ROW_MATCHED_TIER),
						attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK)
					)
				),
				tier7.and(markQ)
			),
			new Shape(
				"and(inScope(LIVE, tier=7), mark=q)", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				and(
					QueryConstraints.inScope(
						Scope.LIVE, attributeEquals(REF_ATTR_TIER, CROSS_ROW_MATCHED_TIER)
					),
					attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK)
				),
				tier7.and(markQ)
			),
			new Shape(
				"and(tier=7, not(epkInSet(A)))", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				and(
					attributeEquals(REF_ATTR_TIER, CROSS_ROW_MATCHED_TIER),
					not(entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_A_PK))
				),
				tier7.and(toA.negate())
			),
			new Shape(
				"not(epkInSet(A, B))", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				not(entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_A_PK, CROSS_ROW_CATEGORY_B_PK)),
				toA.or(toB).negate()
			),
			new Shape(
				"not(entityHaving(epkInSet(A)))", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				not(entityHaving(entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_A_PK))),
				toA.negate()
			),
			new Shape(
				"not(entityHaving(epkInSet(B)))", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				not(entityHaving(entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_B_PK))),
				toB.negate()
			),
			new Shape(
				"and(not(entityHaving(epkInSet(A))), tier=7)", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				and(
					not(entityHaving(entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_A_PK))),
					attributeEquals(REF_ATTR_TIER, CROSS_ROW_MATCHED_TIER)
				),
				toA.negate().and(tier7)
			),
			new Shape(
				"or(entityHaving(epkInSet(A)), not(mark=q))", REF_PRODUCT_CROSS_ROW_CATEGORIES,
				or(
					entityHaving(entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_A_PK)),
					not(attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK))
				),
				toA.or(markQ.negate())
			),
			new Shape(
				"categories: and(epkInSet(1), epkInSet(2))", REF_PRODUCT_CATEGORIES,
				and(entityPrimaryKeyInSet(1), entityPrimaryKeyInSet(2)),
				row -> false
			),
			new Shape(
				"categories: and(epkInSet(1), epkInSet(12))", REF_PRODUCT_CATEGORIES,
				and(entityPrimaryKeyInSet(1), entityPrimaryKeyInSet(ARCHIVED_CATEGORY_PK)),
				row -> false
			),
			new Shape(
				"categories: not(epkInSet(1))", REF_PRODUCT_CATEGORIES,
				not(entityPrimaryKeyInSet(1)),
				row -> row.getReferencedPrimaryKey() != 1
			),
			new Shape(
				"categories: not(entityHaving(epkInSet(1)))", REF_PRODUCT_CATEGORIES,
				not(entityHaving(entityPrimaryKeyInSet(1))),
				row -> row.getReferencedPrimaryKey() != 1
			),
			new Shape(
				"variants: and(tag=a, tag=b)", REF_PRODUCT_VARIANTS,
				and(
					attributeEquals(REF_ATTR_VARIANT_TAG, "a"),
					attributeEquals(REF_ATTR_VARIANT_TAG, "b")
				),
				row -> false
			),
			new Shape(
				"variants: not(tag=a)", REF_PRODUCT_VARIANTS,
				not(attributeEquals(REF_ATTR_VARIANT_TAG, "a")),
				row -> !stringIs(row, REF_ATTR_VARIANT_TAG, "a")
			),
			new Shape(
				"variants: and(epkInSet(1), not(tag=a))", REF_PRODUCT_VARIANTS,
				and(
					entityPrimaryKeyInSet(1),
					not(attributeEquals(REF_ATTR_VARIANT_TAG, "a"))
				),
				row -> row.getReferencedPrimaryKey() == 1 && !stringIs(row, REF_ATTR_VARIANT_TAG, "a")
			),
			new Shape(
				"orphan: not(epkInSet(1))", REF_PRODUCT_ORPHAN_CATEGORIES,
				not(entityPrimaryKeyInSet(1)),
				row -> true
			),
			new Shape(
				"orphan: not(entityHaving(epkInSet(1)))", REF_PRODUCT_ORPHAN_CATEGORIES,
				not(entityHaving(entityPrimaryKeyInSet(1))),
				row -> true
			)
		);
	}

	/**
	 * Executes the reference query on the requested plan.
	 *
	 * @param evita               the engine
	 * @param referenceName       reference the body is evaluated against
	 * @param body                the body of the reference constraint
	 * @param debugMode           plan forcing debug mode, NULL leaves the choice to the planner
	 * @param narrowByPrimaryKeys whether to add the prefetch-enabling `entityPrimaryKeyInSet`
	 * @return ordered set of matched PRODUCT primary keys
	 */
	@Nonnull
	private static Set<Integer> run(
		@Nonnull Evita evita,
		@Nonnull String referenceName,
		@Nonnull FilterConstraint body,
		@Nullable DebugMode debugMode,
		boolean narrowByPrimaryKeys
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final RequireConstraint[] requirements = debugMode == null ?
					new RequireConstraint[]{page(1, Integer.MAX_VALUE)} :
					new RequireConstraint[]{debug(debugMode), page(1, Integer.MAX_VALUE)};
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						narrowByPrimaryKeys ?
							filterBy(
								and(
									entityPrimaryKeyInSet(PREFETCH_CANDIDATES),
									referenceHaving(referenceName, body)
								)
							) :
							filterBy(referenceHaving(referenceName, body)),
						require(requirements)
					),
					EntityReference.class
				);
				return result.getRecordData()
					.stream()
					.map(EntityReference::getPrimaryKey)
					.collect(Collectors.toCollection(TreeSet::new));
			}
		);
	}

	/**
	 * Runs the constraint straight under `filterBy`, outside any reference body.
	 *
	 * @param evita      the engine
	 * @param constraint the constraint to place under `filterBy`
	 * @return ordered set of matched PRODUCT primary keys
	 */
	@Nonnull
	private static Set<Integer> runTopLevel(@Nonnull Evita evita, @Nonnull FilterConstraint constraint) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(constraint),
						require(page(1, Integer.MAX_VALUE))
					),
					EntityReference.class
				);
				return result.getRecordData()
					.stream()
					.map(EntityReference::getPrimaryKey)
					.collect(Collectors.toCollection(TreeSet::new));
			}
		);
	}

	/**
	 * Answers whether the product holds a row of the given reference satisfying the predicate.
	 *
	 * @param product       product to examine
	 * @param referenceName reference whose rows are examined
	 * @param rowPredicate  predicate the row must satisfy
	 * @return true when such a row exists
	 */
	private static boolean holdsRow(
		@Nonnull SealedEntity product,
		@Nonnull String referenceName,
		@Nonnull Predicate<ReferenceContract> rowPredicate
	) {
		return product.getReferences(referenceName).stream().anyMatch(rowPredicate);
	}

	/**
	 * Answers whether the reference row carries the given `Long` attribute value.
	 *
	 * @param row           row to examine
	 * @param attributeName name of the attribute
	 * @param expected      value the attribute must carry
	 * @return true when the attribute is present and equal
	 */
	private static boolean longIs(@Nonnull ReferenceContract row, @Nonnull String attributeName, long expected) {
		return row.getAttributeValue(attributeName)
			.map(av -> Long.valueOf(expected).equals(av.value()))
			.orElse(false);
	}

	/**
	 * Answers whether the reference row carries the given `String` attribute value.
	 *
	 * @param row           row to examine
	 * @param attributeName name of the attribute
	 * @param expected      value the attribute must carry
	 * @return true when the attribute is present and equal
	 */
	private static boolean stringIs(
		@Nonnull ReferenceContract row,
		@Nonnull String attributeName,
		@Nonnull String expected
	) {
		return row.getAttributeValue(attributeName)
			.map(av -> expected.equals(av.value()))
			.orElse(false);
	}

}
