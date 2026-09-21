/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.ReferenceContract;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row-scoped semantics of the `referenceHaving` body (issue #1585 and the defects found alongside it).
 *
 * `referenceHaving(R, body)` binds a single reference ROW: the owner matches when **some** row of `R`
 * satisfies the whole body. Every constraint in the body therefore has to be answered against one row at
 * a time, never against values pooled across the owner's rows or across owners sharing a target.
 *
 * This suite pins the shapes where that used to fail. Each test covers exactly one analyzed case, and each
 * is written so that it goes red when the corresponding guard is removed - a green row here is only
 * evidence if it has been shown capable of failing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference having row semantics")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(CONTRACT)
@Tag(REFERENCE)
@Tag(FILTER)
@Tag(FACET)
public class ReferenceHavingRowSemanticsFunctionalTest extends AbstractBidirectionalReferenceRewriteFunctionalTest {

	/**
	 * The category the `entityPrimaryKeyInSet` cases are anchored on - an ordinary live category that carries
	 * a round-robin slice of the products.
	 */
	private static final int TARGET_CATEGORY_PK = 5;

	/**
	 * The brand the facet cases exclude. Every product carries exactly one brand, round-robin over the brands, so
	 * excluding one leaves a strict, non-empty subset of the collection on either reading.
	 */
	private static final int EXCLUDED_BRAND_PK = 1;

	/**
	 * Runs the given body inside `referenceHaving(categories, ...)` on the PRODUCT collection and returns the
	 * matched primary keys in ascending order.
	 *
	 * @param evita the evitaDB instance holding the shared dataset
	 * @param body  the constraint to place inside the reference body
	 * @return ordered set of matched PRODUCT primary keys
	 */
	@Nonnull
	private static Set<Integer> matchingProducts(@Nonnull Evita evita, @Nonnull FilterConstraint body) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(referenceHaving(REF_PRODUCT_CATEGORIES, body)),
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
	 * Behavioural guard, **not** a defect regression: this shape answers correctly on an unfixed engine too,
	 * because index discovery narrows the scope to the constraint's own reduced index and the empty body then
	 * falls through to the superset formula. Verified by counterfactual - neither restoring the suppression nor
	 * removing the index filter from the adapter makes it fail. It becomes discriminating once index selection
	 * stops coinciding with the constraint, at which point the visited-set assertions take over.
	 */
	@DisplayName("`entityPrimaryKeyInSet` inside the body selects owners referencing that entity")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldSelectOwnersReferencingTheGivenEntity(Evita evita, List<SealedEntity> originalProducts) {
		final Set<Integer> expected = originalProducts.stream()
			.filter(
				it -> it.getReferences(REF_PRODUCT_CATEGORIES)
					.stream()
					.anyMatch(ref -> ref.getReferencedPrimaryKey() == TARGET_CATEGORY_PK)
			)
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));

		assertFalse(expected.isEmpty(), "Fixture must contain products referencing the target category.");
		assertEquals(
			expected,
			matchingProducts(evita, entityPrimaryKeyInSet(TARGET_CATEGORY_PK)),
			"`entityPrimaryKeyInSet` must select exactly the owners referencing the target category."
		);
	}

	@DisplayName("`or` keeps the `entityPrimaryKeyInSet` branch instead of dropping it")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldNotDropTheEntityPrimaryKeyInSetBranchOfADisjunction(Evita evita) {
		final Set<Integer> byPrimaryKey = matchingProducts(evita, entityPrimaryKeyInSet(TARGET_CATEGORY_PK));
		final Set<Integer> byAttribute = matchingProducts(evita, attributeEquals(REF_ATTR_RELEVANCE, 1L));
		final Set<Integer> union = new TreeSet<>(byPrimaryKey);
		union.addAll(byAttribute);

		// the branches must not be subsets of one another, or the disjunction could pass by accident
		assertFalse(byPrimaryKey.isEmpty(), "The primary-key branch must match something.");
		assertFalse(byAttribute.isEmpty(), "The attribute branch must match something.");
		assertNotEquals(
			union, byAttribute,
			"The primary-key branch must contribute owners the attribute branch does not, or this test is vacuous."
		);

		assertEquals(
			union,
			matchingProducts(
				evita,
				or(entityPrimaryKeyInSet(TARGET_CATEGORY_PK), attributeEquals(REF_ATTR_RELEVANCE, 1L))
			),
			"`or` inside the reference body must return the union of both branches."
		);
	}

	/**
	 * Behavioural guard, on the same footing as the positive case above: conjunctions already behave per-row
	 * today because discovery prunes to the indexes matching every conjunct. Kept because that accident
	 * disappears when index selection changes, and this is the row that will notice.
	 */
	@DisplayName("`and` with `entityPrimaryKeyInSet` binds both conjuncts to the same reference row")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldBindEntityPrimaryKeyInSetAndAttributeToTheSameRow(Evita evita, List<SealedEntity> originalProducts) {
		final Set<Integer> expected = originalProducts.stream()
			.filter(
				it -> it.getReferences(REF_PRODUCT_CATEGORIES)
					.stream()
					.anyMatch(
						ref -> ref.getReferencedPrimaryKey() == TARGET_CATEGORY_PK &&
							ref.getAttributeValue(REF_ATTR_RELEVANCE)
								.map(av -> Long.valueOf(0L).equals(av.value()))
								.orElse(false)
					)
			)
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));

		assertFalse(expected.isEmpty(), "Fixture must contain a matching (category, relevance) row.");
		assertEquals(
			expected,
			matchingProducts(
				evita,
				and(entityPrimaryKeyInSet(TARGET_CATEGORY_PK), attributeEquals(REF_ATTR_RELEVANCE, 0L))
			),
			"Both conjuncts must hold on one and the same reference row."
		);
	}

	/**
	 * Runs a `referenceHaving` over the duplicated `variants` reference.
	 *
	 * @param evita the engine
	 * @param body  the body of the reference constraint
	 * @return primary keys of matching products
	 */
	@Nonnull
	private static Set<Integer> matchingProductsByVariants(@Nonnull Evita evita, @Nonnull FilterConstraint body) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(referenceHaving(REF_PRODUCT_VARIANTS, body)),
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
	 * The defect regression for the cross-row conjunction.
	 *
	 * `referenceHaving` binds a single reference ROW: the owner matches when one row satisfies the whole body.
	 * Every product up to {@link #LAST_VARIANT_PRODUCT_PK} carries three `variants` rows - `(1, "a")`, `(1, "b")`
	 * and `(VARIANT_CATEGORY_COUNT, "a")` - so asking for category `VARIANT_CATEGORY_COUNT` **and** tag `"b"`
	 * must match nothing: the owner holds a row for each half of the conjunction and no row holding both.
	 *
	 * Evaluating the body once across the whole index family answers the weaker question - "some row matches the
	 * first conjunct and some row matches the second".
	 *
	 * This shape needs duplicates to exist at all, which is why it rides on `variants` rather than on
	 * `categories`: with at most one row per target there is no second row to cross to.
	 *
	 * **Behavioural guard, not a defect regression - verified by counterfactual.** Disabling the per-index
	 * rebuild leaves this row green, because both conjuncts here are part of the reduced-index *key* (the target
	 * and the representative attribute), so index discovery intersects them at type level and prunes the scope
	 * to nothing before the body is ever evaluated. A discriminating case needs conjuncts that discovery cannot
	 * prune - non-representative attributes, whose values do not appear in the index key - and one is owed here.
	 * Kept meanwhile because it pins the answer this shape must keep giving when discovery changes.
	 */
	@DisplayName("`and` must not be satisfied by two different rows of the same owner")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldNotSatisfyConjunctionAcrossTwoDifferentRowsOfOneOwner(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		// the witness has to be in the data, or the assertion below passes for the wrong reason
		final Set<Integer> ownersHoldingBothHalvesSeparately = originalProducts.stream()
			.filter(it -> holdsVariantRow(it, VARIANT_CATEGORY_COUNT, null))
			.filter(it -> holdsVariantRow(it, null, "b"))
			.filter(it -> !holdsVariantRow(it, VARIANT_CATEGORY_COUNT, "b"))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		assertFalse(
			ownersHoldingBothHalvesSeparately.isEmpty(),
			"Fixture must contain an owner holding each half of the conjunction on a different row."
		);

		assertEquals(
			new TreeSet<Integer>(),
			matchingProductsByVariants(
				evita,
				and(
					entityPrimaryKeyInSet(VARIANT_CATEGORY_COUNT),
					attributeEquals(REF_ATTR_VARIANT_TAG, "b")
				)
			),
			"No single `" + REF_PRODUCT_VARIANTS + "` row is both category " + VARIANT_CATEGORY_COUNT +
				" and tag `b`, so the conjunction must match no owner - the owners in " +
				ownersHoldingBothHalvesSeparately + " hold the two halves on different rows."
		);
	}

	/**
	 * Answers whether the product holds a `variants` row matching the given target and/or tag.
	 *
	 * @param product     product to examine
	 * @param categoryPk  required referenced category, or null when any target will do
	 * @param variantTag  required representative tag, or null when any tag will do
	 * @return true when such a row exists
	 */
	private static boolean holdsVariantRow(
		@Nonnull SealedEntity product,
		@Nullable Integer categoryPk,
		@Nullable String variantTag
	) {
		return product.getReferences(REF_PRODUCT_VARIANTS)
			.stream()
			.anyMatch(
				ref -> (categoryPk == null || ref.getReferencedPrimaryKey() == categoryPk) &&
					(variantTag == null || ref.getAttributeValue(REF_ATTR_VARIANT_TAG)
						.map(av -> variantTag.equals(av.value()))
						.orElse(false))
			);
	}

	/**
	 * Runs a `referenceHaving` over the `crossRowCategories` reference on the index-scan path.
	 *
	 * The debug mode is not decoration: on a fixture this small the planner answers from prefetched entity bodies
	 * unless it is told not to, and the prefetch path answers a different question about the body - see
	 * {@link #shouldNotSatisfyConjunctionOfNonRepresentativeAttributesAcrossTwoRows} for which one this row pins.
	 *
	 * @param evita the engine
	 * @param body  the body of the reference constraint
	 * @return primary keys of matching products
	 */
	@Nonnull
	private static Set<Integer> matchingProductsByCrossRowCategories(
		@Nonnull Evita evita,
		@Nonnull FilterConstraint body
	) {
		return matchingProductsByCrossRowCategories(evita, body, DebugMode.PREFER_INDEX_SCAN);
	}

	/**
	 * Runs a `referenceHaving` over the `crossRowCategories` reference on the requested plan.
	 *
	 * @param evita     the engine
	 * @param body      the body of the reference constraint
	 * @param debugMode the plan the query is forced onto
	 * @return primary keys of matching products
	 */
	@Nonnull
	private static Set<Integer> matchingProductsByCrossRowCategories(
		@Nonnull Evita evita,
		@Nonnull FilterConstraint body,
		@Nonnull DebugMode debugMode
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(referenceHaving(REF_PRODUCT_CROSS_ROW_CATEGORIES, body)),
						require(debug(debugMode), page(1, Integer.MAX_VALUE))
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
	 * The discriminating regression for the cross-row conjunction - the row that actually separates the two
	 * readings of a `referenceHaving` body.
	 *
	 * The two conjuncts name `tier` and `mark`, the fixture's only pair of **non-representative** reference
	 * attributes. That is what makes this row discriminating and its siblings above merely behavioural: a
	 * representative attribute's values are part of the reduced index *key*, so the type-level pass intersects the
	 * leaves and prunes the scope to nothing before the body is ever evaluated, and both readings then agree. Here
	 * the values live only inside the indexes, the type-level intersection keeps **both** categories in scope, and
	 * the readings part company:
	 *
	 * - **row-scoped** - each index contributes the conjunction of its own leaves: only the owner holding both
	 *   values on one row;
	 * - **pooled** - each leaf is OR-ed across the whole family and the conjunction applied afterwards: that owner
	 *   plus every owner holding one value in one index and the other value in another.
	 *
	 * The oracle is derived from the fetched entity bodies rather than written down, and the pooled reading is
	 * computed alongside it purely so the test can assert the two differ - without that guard a fixture change
	 * could make this row pass while proving nothing.
	 *
	 * @param evita            the engine
	 * @param originalProducts all products, fully fetched, as the dataset built them
	 */
	@DisplayName("`and` over two non-representative attributes must not combine two rows of one owner")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldNotSatisfyConjunctionOfNonRepresentativeAttributesAcrossTwoRows(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final Predicate<ReferenceContract> matchedTier =
			row -> longAttributeIs(row, REF_ATTR_TIER, CROSS_ROW_MATCHED_TIER);
		final Predicate<ReferenceContract> matchedMark =
			row -> stringAttributeIs(row, REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK);

		// both reduced indexes must survive the type-level pass, or the body is evaluated inside a single index
		// and answers per row for reasons that have nothing to do with the rebuild under test
		final Set<Integer> targetsCarryingTier = crossRowTargetsOfRowsMatching(originalProducts, matchedTier);
		final Set<Integer> targetsCarryingMark = crossRowTargetsOfRowsMatching(originalProducts, matchedMark);
		final Set<Integer> survivingTargets = new TreeSet<>(targetsCarryingTier);
		survivingTargets.retainAll(targetsCarryingMark);
		assertTrue(
			survivingTargets.size() >= 2,
			"At least two `" + REF_PRODUCT_CROSS_ROW_CATEGORIES + "` targets must carry both values - across " +
				"owners, not within one row - or the type-level pass narrows the scope to a single index and this " +
				"row cannot tell the two readings apart. Carrying the tier: " + targetsCarryingTier +
				", carrying the mark: " + targetsCarryingMark + "."
		);

		final Set<Integer> rowScopedReading = originalProducts.stream()
			.filter(it -> holdsCrossRowRow(it, matchedTier.and(matchedMark)))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		final Set<Integer> pooledReading = originalProducts.stream()
			.filter(it -> holdsCrossRowRow(it, matchedTier))
			.filter(it -> holdsCrossRowRow(it, matchedMark))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		assertFalse(rowScopedReading.isEmpty(), "Fixture must contain an owner holding both values on one row.");
		assertNotEquals(
			pooledReading, rowScopedReading,
			"Fixture must contain an owner holding the two values on two different rows, or the pooled and the " +
				"row-scoped readings coincide and this row proves nothing."
		);

		assertEquals(
			rowScopedReading,
			matchingProductsByCrossRowCategories(
				evita,
				and(
					attributeEquals(REF_ATTR_TIER, CROSS_ROW_MATCHED_TIER),
					attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK)
				)
			),
			"Both conjuncts must hold on one and the same `" + REF_PRODUCT_CROSS_ROW_CATEGORIES + "` row. Pooling " +
				"the owner's rows would additionally return " + pooledReading + "."
		);
	}

	/**
	 * The same negation with the index-scan preference removed, so the planner picks the plan it would pick on
	 * its own.
	 *
	 * Two plans answer a `referenceHaving` body - one from the reduced index family, one from prefetched entity
	 * bodies - and a row-scoped reading has to come out of both. This row asserts they agree; it deliberately does
	 * **not** assert which plan ran, because that is the planner's decision and not the contract. Its twin
	 * {@link #shouldComplementNotAgainstTheReferenceRowRatherThanTheCollection} pins the index side by forcing it.
	 *
	 * @param evita            the engine
	 * @param originalProducts all products, fully fetched, as the dataset built them
	 */
	@DisplayName("`not` gives the same row-scoped answer when the planner chooses the plan")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldComplementNotAgainstTheReferenceRowWhicheverPlanIsChosen(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final Predicate<ReferenceContract> matchedMark =
			row -> stringAttributeIs(row, REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK);
		final Set<Integer> expected = originalProducts.stream()
			.filter(it -> holdsCrossRowRow(it, matchedMark.negate()))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		assertFalse(expected.isEmpty(), "Fixture must contain an owner holding a row the negation is true of.");

		final Set<Integer> actual = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								REF_PRODUCT_CROSS_ROW_CATEGORIES,
								not(attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK))
							)
						),
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

		assertEquals(
			expected, actual,
			"Both plans must answer the negation per reference row - a plan that complements against the whole " +
				"collection would additionally return every owner holding no row of the reference at all."
		);
	}

	/**
	 * Issue #1585 itself: a `not` inside the body must be complemented against the reference **row**, not against
	 * the enclosing collection.
	 *
	 * `referenceHaving(R, not(x))` binds one row like every other body: the owner matches when it holds a row of
	 * `R` on which `x` does **not** hold. The complement therefore runs inside that row's own index - an owner with
	 * no row of `R` at all cannot match, because there is no row for the negation to be true of.
	 *
	 * Complementing against the enclosing super set instead answers a different question entirely: every owner in
	 * the collection except those whose rows match `x`, which sweeps in the 227 products carrying no
	 * `crossRowCategories` row whatsoever.
	 *
	 * @param evita            the engine
	 * @param originalProducts all products, fully fetched, as the dataset built them
	 */
	@DisplayName("`not` inside the body is complemented against the reference row, not the collection")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldComplementNotAgainstTheReferenceRowRatherThanTheCollection(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final Predicate<ReferenceContract> matchedMark =
			row -> stringAttributeIs(row, REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK);

		final Set<Integer> expected = originalProducts.stream()
			.filter(it -> holdsCrossRowRow(it, matchedMark.negate()))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		assertFalse(expected.isEmpty(), "Fixture must contain an owner holding a row the negation is true of.");
		assertTrue(
			originalProducts.stream().anyMatch(it -> it.getReferences(REF_PRODUCT_CROSS_ROW_CATEGORIES).isEmpty()),
			"Fixture must contain owners with no row of the reference at all, or complementing against the " +
				"collection and complementing against the row give the same answer."
		);

		assertEquals(
			expected,
			matchingProductsByCrossRowCategories(evita, not(attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK))),
			"`not` must select the owners holding a `" + REF_PRODUCT_CROSS_ROW_CATEGORIES + "` row that does not " +
				"carry the mark - never an owner holding no row of that reference at all."
		);
	}

	/**
	 * Absence is an ordinary value, not a third truth value: a row that does not carry the attribute at all
	 * **satisfies** `not(attributeEquals(a, v))`.
	 *
	 * The semantics record states this (`documentation/adr/2026-09-17-row-scoped-reference-having-body/
	 * row-scoped-semantics.md` section 2) and nothing pinned it. Every other negation row in this suite runs on
	 * `crossRowCategories`, whose `mark` is set on every row it writes, so "the negation is true of this row"
	 * has only ever meant "the row carries a different value" - the `⊥` half of the disjunction was never
	 * exercised. This row uses `categories.refSometimesSet`, which the fixture writes only for products whose
	 * primary key is divisible by three.
	 *
	 * Two readings are distinguished. Under the two-valued rule the engine implements, `att(r, a) = ⊥` makes
	 * `attributeEquals` false and the negation true. Under the SQL-style three-valued rule, `a = v` is UNKNOWN
	 * for such a row, `NOT UNKNOWN` is UNKNOWN, and the row drops out. The fixture guard below asserts the two
	 * answers differ before the query runs, so a green result here cannot be an accident of the data.
	 *
	 * The second assertion is identity **I1** from that record - `RH(φ) ∪ RH(¬φ) = RH()` - which is what
	 * two-valuedness buys and what fails the moment any row evaluates to neither true nor false.
	 *
	 * @param evita            the engine
	 * @param originalProducts all products, fully fetched, as the dataset built them
	 */
	@DisplayName("a row lacking the attribute entirely satisfies the negation")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldSatisfyANegatedAttributeWithARowThatDoesNotCarryIt(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final long excludedValue = originalProducts.stream()
			.flatMap(it -> it.getReferences(REF_PRODUCT_CATEGORIES).stream())
			.filter(row -> row.getAttributeValue(REF_ATTR_SOMETIMES_SET).isPresent())
			.mapToLong(row -> (Long) row.getAttributeValue(REF_ATTR_SOMETIMES_SET).orElseThrow().value())
			.min()
			.orElseThrow(
				() -> new IllegalStateException(
					"Fixture must write `" + REF_ATTR_SOMETIMES_SET + "` on at least one row!"
				)
			);
		final Predicate<ReferenceContract> carriesExcludedValue =
			row -> longAttributeIs(row, REF_ATTR_SOMETIMES_SET, excludedValue);
		final Predicate<ReferenceContract> carriesTheAttribute =
			row -> row.getAttributeValue(REF_ATTR_SOMETIMES_SET).isPresent();
		// the query runs in the default scope, and products 231-240 are archived - a scope-blind expectation
		// would charge the engine with dropping them
		final Predicate<SealedEntity> live = it -> it.getScope() == Scope.LIVE;

		// two-valued: a row satisfies the negation when its value differs OR when it carries no value at all
		final Set<Integer> expected = originalProducts.stream()
			.filter(live)
			.filter(it -> holdsCategoriesRow(it, carriesExcludedValue.negate()))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		// three-valued: only a row that carries some other value satisfies it
		final Set<Integer> threeValuedReading = originalProducts.stream()
			.filter(live)
			.filter(it -> holdsCategoriesRow(it, carriesTheAttribute.and(carriesExcludedValue.negate())))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));

		assertFalse(expected.isEmpty(), "Fixture must contain an owner the negation is true of.");
		assertNotEquals(
			threeValuedReading, expected,
			"Fixture must contain an owner ALL of whose `" + REF_PRODUCT_CATEGORIES + "` rows lack `" +
				REF_ATTR_SOMETIMES_SET + "`, or the two-valued and three-valued readings coincide and this row " +
				"proves nothing."
		);

		final Set<Integer> actual = matchingProducts(
			evita, not(attributeEquals(REF_ATTR_SOMETIMES_SET, excludedValue))
		);
		assertEquals(
			expected, actual,
			"A row carrying no `" + REF_ATTR_SOMETIMES_SET + "` at all must satisfy the negation. Treating " +
				"absence as a third truth value would answer " + threeValuedReading + " instead."
		);

		// I1: the positive and the negated body together account for every owner holding a row of the reference
		final Set<Integer> positive = matchingProducts(
			evita, attributeEquals(REF_ATTR_SOMETIMES_SET, excludedValue)
		);
		final Set<Integer> union = new TreeSet<>(positive);
		union.addAll(actual);
		final Set<Integer> holdingAnyRow = originalProducts.stream()
			.filter(live)
			.filter(it -> !it.getReferences(REF_PRODUCT_CATEGORIES).isEmpty())
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		assertEquals(
			holdingAnyRow, union,
			"`RH(x) ∪ RH(not(x))` must account for every owner holding a `" + REF_PRODUCT_CATEGORIES + "` row - " +
				"an owner missing from both would be one whose row evaluated to neither true nor false."
		);
	}

	/**
	 * The negated target constraint - `not(entityPrimaryKeyInSet(t))` - which needs the leaf to know which index
	 * produced it.
	 *
	 * Inside the body this constraint speaks about the **referenced** entity, and it is constant across a reduced
	 * index: either the whole index targets `t` or none of it does. So the row-scoped answer is the owners holding
	 * a row in some index other than `t` - which is only reachable if the leaf carries its index, because the
	 * complement is taken one index at a time. An untagged leaf is complemented against every index at once and
	 * answers the per-owner question instead: "this owner references nothing but `t`", which drops every owner
	 * holding a row on `t` **and** a row elsewhere.
	 *
	 * @param evita            the engine
	 * @param originalProducts all products, fully fetched, as the dataset built them
	 */
	@DisplayName("`not(entityPrimaryKeyInSet)` selects owners holding a row pointing somewhere else")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldComplementEntityPrimaryKeyInSetAgainstTheReferenceRow(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final Predicate<ReferenceContract> targetsCategoryA =
			row -> row.getReferencedPrimaryKey() == CROSS_ROW_CATEGORY_A_PK;

		final Set<Integer> expected = originalProducts.stream()
			.filter(it -> holdsCrossRowRow(it, targetsCategoryA.negate()))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		final Set<Integer> perOwnerReading = originalProducts.stream()
			.filter(it -> !it.getReferences(REF_PRODUCT_CROSS_ROW_CATEGORIES).isEmpty())
			.filter(it -> !holdsCrossRowRow(it, targetsCategoryA))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		assertFalse(expected.isEmpty(), "Fixture must contain an owner holding a row pointing elsewhere.");
		assertNotEquals(
			perOwnerReading, expected,
			"Fixture must contain an owner holding a row on the excluded category AND a row elsewhere, or the " +
				"row-scoped and the per-owner readings coincide and this row proves nothing."
		);

		assertEquals(
			expected,
			matchingProductsByCrossRowCategories(
				evita, not(entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_A_PK))
			),
			"The negation must be taken inside each reference row. Complementing across the whole family would " +
				"return " + perOwnerReading + " instead."
		);
	}

	/**
	 * The mixed shape - a positive conjunct beside a negated one - which has to hold on **one and the same** row.
	 *
	 * Resolving the negation per row is not enough on its own: the positive leaf and the complement have to be
	 * taken inside the same index, or an owner qualifies by carrying the tier on one row and the absent mark on
	 * another.
	 *
	 * @param evita            the engine
	 * @param originalProducts all products, fully fetched, as the dataset built them
	 */
	@DisplayName("`and` of a positive and a negated conjunct binds both to the same reference row")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldBindAPositiveAndANegatedConjunctToTheSameRow(Evita evita, List<SealedEntity> originalProducts) {
		final Predicate<ReferenceContract> matchedTier =
			row -> longAttributeIs(row, REF_ATTR_TIER, CROSS_ROW_MATCHED_TIER);
		final Predicate<ReferenceContract> matchedMark =
			row -> stringAttributeIs(row, REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK);

		final Set<Integer> expected = originalProducts.stream()
			.filter(it -> holdsCrossRowRow(it, matchedTier.and(matchedMark.negate())))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		final Set<Integer> pooledReading = originalProducts.stream()
			.filter(it -> holdsCrossRowRow(it, matchedTier))
			.filter(it -> holdsCrossRowRow(it, matchedMark.negate()))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		assertFalse(expected.isEmpty(), "Fixture must contain an owner whose single row satisfies both conjuncts.");

		assertEquals(
			expected,
			matchingProductsByCrossRowCategories(
				evita,
				and(
					attributeEquals(REF_ATTR_TIER, CROSS_ROW_MATCHED_TIER),
					not(attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK))
				)
			),
			"The tier and the absent mark must be carried by one and the same row. Pooling the owner's rows would " +
				"return " + pooledReading + "."
		);
	}

	/**
	 * A doubly negated body - `not(not(x))` - has to answer exactly what `x` answers.
	 *
	 * `NotTranslator` emits every negation as a `FutureNotFormula` placeholder, so the inner `not` hands the outer
	 * one a placeholder rather than a computable formula and the outer one wraps it in a second placeholder. Nothing
	 * unwraps the pair: the transposer resolves the outer placeholder into a real subtraction whose subtrahend is
	 * still the inner placeholder, and computing it reaches `FutureNotFormula#computeInternal`, which throws.
	 *
	 * The assertion is written against the singly-positive constraint rather than against a hand-written set,
	 * because the two are the same question and a fixture change cannot make them disagree.
	 *
	 * @param evita the engine
	 */
	@DisplayName("double negation inside the body answers what the positive constraint answers")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldResolveADoublyNegatedBody(Evita evita) {
		final Set<Integer> positiveReading = matchingProductsByCrossRowCategories(
			evita, attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK)
		);
		assertFalse(
			positiveReading.isEmpty(),
			"Fixture must contain an owner holding a row carrying the mark, or this row cannot tell a correct " +
				"answer from an empty one."
		);

		assertEquals(
			positiveReading,
			matchingProductsByCrossRowCategories(
				evita, not(not(attributeEquals(REF_ATTR_MARK, CROSS_ROW_MATCHED_MARK)))
			),
			"`not(not(x))` must answer exactly what `x` answers - the two negations cancel."
		);
	}

	/**
	 * `not(entityHaving(...))` has to be complemented against the reference **row**, exactly like
	 * {@link #shouldComplementEntityPrimaryKeyInSetAgainstTheReferenceRow} requires of
	 * `not(entityPrimaryKeyInSet(...))`.
	 *
	 * Inside the body `entityHaving` speaks about the referenced entity, and a reduced entity index carries exactly
	 * one of those - so the constraint is constant across an index and the row-scoped answer is the owners holding
	 * a row in some index whose target does **not** match. The formula the nested query produces is built from the
	 * reduced indexes of the whole collection and carries no index tag, so the transposer treats it as
	 * index-independent and keeps it whole for every index; complementing it then answers the per-owner question
	 * "this owner references nothing matching", which drops every owner holding a matching row **and** a
	 * non-matching one.
	 *
	 * @param evita            the engine
	 * @param originalProducts all products, fully fetched, as the dataset built them
	 */
	@DisplayName("`not(entityHaving)` selects owners holding a row pointing somewhere else")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldComplementEntityHavingAgainstTheReferenceRow(Evita evita, List<SealedEntity> originalProducts) {
		final Predicate<ReferenceContract> targetsCategoryA =
			row -> row.getReferencedPrimaryKey() == CROSS_ROW_CATEGORY_A_PK;

		final Set<Integer> expected = originalProducts.stream()
			.filter(it -> holdsCrossRowRow(it, targetsCategoryA.negate()))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		final Set<Integer> perOwnerReading = originalProducts.stream()
			.filter(it -> !it.getReferences(REF_PRODUCT_CROSS_ROW_CATEGORIES).isEmpty())
			.filter(it -> !holdsCrossRowRow(it, targetsCategoryA))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		assertFalse(expected.isEmpty(), "Fixture must contain an owner holding a row pointing elsewhere.");
		assertNotEquals(
			perOwnerReading, expected,
			"Fixture must contain an owner holding a row on the excluded category AND a row elsewhere, or the " +
				"row-scoped and the per-owner readings coincide and this row proves nothing."
		);

		assertEquals(
			expected,
			matchingProductsByCrossRowCategories(
				evita, not(entityHaving(entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_A_PK)))
			),
			"The negation must be taken inside each reference row. Complementing across the whole family would " +
				"return " + perOwnerReading + " instead."
		);
	}

	/**
	 * `facetHaving(R, not(x))` has to keep the negation.
	 *
	 * Facet filtering quantifies the REFERENCED entity rather than the reference row: `FacetHavingTranslator` asks
	 * `FilterByVisitor#getReferencedRecordIdFormula` which referenced primary keys the body selects and consumes the
	 * answer as it stands, with nothing re-examining rows afterwards. That formula is built inside a reference
	 * type-level scope - the same kind of scope in which a `referenceHaving` body is allowed to answer a negation
	 * with the whole super set, because there the caller settles the negation per row later on. Nothing settles it
	 * here, so a negation widened in this scope selects every facet of the reference and the filter stops filtering.
	 *
	 * The expectation is derived from the engine's own positive answers rather than from counted fixture rows, so
	 * that a change to the fixture cannot make the two readings agree silently.
	 *
	 * @param evita the engine
	 */
	@DisplayName("`facetHaving` keeps a negation in its body")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldKeepTheNegationInsideFacetHaving(Evita evita) {
		final int[] everyBrand = IntStream.rangeClosed(1, BRAND_COUNT).toArray();
		final int[] otherBrands = IntStream.rangeClosed(1, BRAND_COUNT)
			.filter(it -> it != EXCLUDED_BRAND_PK)
			.toArray();

		final Set<Integer> expected = productsSelectedByBrandFacet(evita, entityPrimaryKeyInSet(otherBrands));
		// exactly what dropping the negation answers: the body widens to every reduced index of the reference,
		// which translates back into every brand the collection references
		final Set<Integer> droppedNegationReading = productsSelectedByBrandFacet(
			evita, entityPrimaryKeyInSet(everyBrand)
		);
		assertFalse(expected.isEmpty(), "Fixture must contain products carrying a brand other than the excluded one.");
		assertNotEquals(
			droppedNegationReading, expected,
			"Fixture must contain a product carrying the excluded brand, or dropping the negation answers " +
				"correctly by accident and this row proves nothing."
		);

		assertEquals(
			expected,
			productsSelectedByBrandFacet(evita, not(entityPrimaryKeyInSet(EXCLUDED_BRAND_PK))),
			"`not` inside `facetHaving` must subtract the excluded brand. Widening it to the whole reference " +
				"family would select every product carrying any brand instead."
		);
	}

	/**
	 * The positive `entityPrimaryKeyInSet` body, answered on the prefetch plan.
	 *
	 * The reduced-index leaf this constraint emits inside a `referenceHaving` body is built from the reference's
	 * reduced index family, and it stays that way on the prefetch plan: `FilterByVisitor#isPrefetchPossible()` is
	 * `scope.size() == 1`, so it is false inside the scope pushed for the body and no prefetched alternative is
	 * ever substituted there. What the prefetch plan changes is the enclosing query, not the body - and this row is
	 * what pins that the two agree, since every other row in this suite steers onto the index-scan plan.
	 *
	 * @param evita            the engine
	 * @param originalProducts all products, fully fetched, as the dataset built them
	 */
	@DisplayName("`entityPrimaryKeyInSet` inside the body selects the same owners on the prefetch plan")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldSelectOwnersReferencingTheGivenEntityOnThePrefetchPlan(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final Predicate<ReferenceContract> targetsCategoryA =
			row -> row.getReferencedPrimaryKey() == CROSS_ROW_CATEGORY_A_PK;

		final Set<Integer> expected = originalProducts.stream()
			.filter(it -> holdsCrossRowRow(it, targetsCategoryA))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		assertFalse(expected.isEmpty(), "Fixture must contain an owner holding a row on the target category.");

		assertEquals(
			expected,
			matchingProductsByCrossRowCategories(
				evita, entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_A_PK), DebugMode.PREFER_PREFETCHING
			),
			"The prefetch plan must select exactly the owners holding a row on the target category."
		);
	}

	/**
	 * The row-scoped complement, answered on the prefetch plan.
	 *
	 * The twin of {@link #shouldComplementEntityPrimaryKeyInSetAgainstTheReferenceRow}, which pins the same shape on
	 * the index-scan plan. Both readings are derived from the fetched bodies and the row asserts they differ, so a
	 * plan that quietly answers the per-owner question fails here rather than agreeing by coincidence.
	 *
	 * @param evita            the engine
	 * @param originalProducts all products, fully fetched, as the dataset built them
	 */
	@DisplayName("the negated body is complemented per reference row on the prefetch plan")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldComplementEntityPrimaryKeyInSetAgainstTheReferenceRowOnThePrefetchPlan(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final Predicate<ReferenceContract> targetsCategoryA =
			row -> row.getReferencedPrimaryKey() == CROSS_ROW_CATEGORY_A_PK;

		final Set<Integer> expected = originalProducts.stream()
			.filter(it -> holdsCrossRowRow(it, targetsCategoryA.negate()))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		final Set<Integer> perOwnerReading = originalProducts.stream()
			.filter(it -> !it.getReferences(REF_PRODUCT_CROSS_ROW_CATEGORIES).isEmpty())
			.filter(it -> !holdsCrossRowRow(it, targetsCategoryA))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		assertFalse(expected.isEmpty(), "Fixture must contain an owner holding a row pointing elsewhere.");
		assertNotEquals(
			perOwnerReading, expected,
			"Fixture must contain an owner holding a row on the excluded category AND a row elsewhere, or the " +
				"row-scoped and the per-owner readings coincide and this row proves nothing."
		);

		assertEquals(
			expected,
			matchingProductsByCrossRowCategories(
				evita, not(entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_A_PK)), DebugMode.PREFER_PREFETCHING
			),
			"The prefetch plan must take the negation inside each reference row. Complementing across the whole " +
				"family would return " + perOwnerReading + " instead."
		);
	}

	/**
	 * `not(entityHaving(...))` inside a **`referenceContent` filter** has to be complemented per reference row,
	 * exactly as {@link #shouldComplementEntityHavingAgainstTheReferenceRow} requires of the filtering path.
	 *
	 * The two paths reach the same helper by different routes. `ReferenceHavingTranslator` establishes its scope
	 * as `ReducedEntityIndex.class`, while `ReferencedEntityFetcher#computeResultWithPassedIndex` establishes one
	 * as `AbstractReducedEntityIndex.class` - the **superclass** - even though the index it hands over is a
	 * `ReducedEntityIndex`. A dispatch testing `ReducedEntityIndex.class.isAssignableFrom(scope.getIndexType())`
	 * is therefore false on the fetch path, which sends it to the collection-wide lookup: that answers "does this
	 * owner reference anything matching" and, once negated, drops an owner's row to B because a different row of
	 * the same owner points at A.
	 *
	 * @param evita            the engine
	 * @param originalProducts all products, fully fetched, as the dataset built them
	 */
	@DisplayName("`not(entityHaving)` filtering reference content keeps the rows pointing elsewhere")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldComplementEntityHavingPerRowWhenFilteringReferenceContent(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final Map<Integer, Set<Integer>> expected = new TreeMap<>();
		final Map<Integer, Set<Integer>> perOwnerReading = new TreeMap<>();
		for (final SealedEntity product : originalProducts) {
			if (product.getReferences(REF_PRODUCT_CROSS_ROW_CATEGORIES).isEmpty()) {
				continue;
			}
			final Set<Integer> targets = product.getReferences(REF_PRODUCT_CROSS_ROW_CATEGORIES)
				.stream()
				.map(ReferenceContract::getReferencedPrimaryKey)
				.collect(Collectors.toCollection(TreeSet::new));
			// row-scoped: each row is judged on its own target
			expected.put(
				Objects.requireNonNull(product.getPrimaryKey()),
				targets.stream()
					.filter(it -> it != CROSS_ROW_CATEGORY_A_PK)
					.collect(Collectors.toCollection(TreeSet::new))
			);
			// per-owner: one row on the excluded category silences every row the owner holds
			perOwnerReading.put(
				Objects.requireNonNull(product.getPrimaryKey()),
				targets.contains(CROSS_ROW_CATEGORY_A_PK) ? new TreeSet<>() : new TreeSet<>(targets)
			);
		}
		assertNotEquals(
			perOwnerReading, expected,
			"Fixture must contain an owner holding a row on the excluded category AND a row elsewhere, or the " +
				"row-scoped and the per-owner readings coincide and this row proves nothing."
		);

		assertEquals(
			expected,
			crossRowReferencesFilteredBy(
				evita, not(entityHaving(entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_A_PK)))
			),
			"Each reference row must be judged on its own target. Complementing across the owner would return " +
				perOwnerReading + " instead."
		);
	}

	/**
	 * `not(entityPrimaryKeyInSet(...))` inside a **`referenceContent` filter** has to be complemented per reference
	 * row, exactly as {@link #shouldComplementEntityPrimaryKeyInSetAgainstTheReferenceRow} requires of the filtering
	 * path.
	 *
	 * The fetch path reaches its per-index evaluation through
	 * `ReferencedEntityFetcher#computeResultWithPassedIndex`, which suppresses `EntityPrimaryKeyInSet` while the
	 * body is translated - the constraint is expected to have been applied already, by the index discovery that
	 * chose which reduced indexes to visit. Under a negation that expectation does not hold: discovery widens a
	 * negated leaf to the whole candidate set, so the complement has to be taken inside each index, and there is
	 * nothing left there to take it against.
	 *
	 * @param evita            the engine
	 * @param originalProducts all products, fully fetched, as the dataset built them
	 */
	@DisplayName("`not(entityPrimaryKeyInSet)` filtering reference content keeps the rows pointing elsewhere")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldComplementEntityPrimaryKeyInSetPerRowWhenFilteringReferenceContent(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final Map<Integer, Set<Integer>> expected = new TreeMap<>();
		final Map<Integer, Set<Integer>> perOwnerReading = new TreeMap<>();
		for (final SealedEntity product : originalProducts) {
			if (product.getReferences(REF_PRODUCT_CROSS_ROW_CATEGORIES).isEmpty()) {
				continue;
			}
			final Set<Integer> targets = product.getReferences(REF_PRODUCT_CROSS_ROW_CATEGORIES)
				.stream()
				.map(ReferenceContract::getReferencedPrimaryKey)
				.collect(Collectors.toCollection(TreeSet::new));
			expected.put(
				Objects.requireNonNull(product.getPrimaryKey()),
				targets.stream()
					.filter(it -> it != CROSS_ROW_CATEGORY_A_PK)
					.collect(Collectors.toCollection(TreeSet::new))
			);
			perOwnerReading.put(
				Objects.requireNonNull(product.getPrimaryKey()),
				targets.contains(CROSS_ROW_CATEGORY_A_PK) ? new TreeSet<>() : new TreeSet<>(targets)
			);
		}
		assertNotEquals(
			perOwnerReading, expected,
			"Fixture must contain an owner holding a row on the excluded category AND a row elsewhere, or the " +
				"row-scoped and the per-owner readings coincide and this row proves nothing."
		);

		assertEquals(
			expected,
			crossRowReferencesFilteredBy(evita, not(entityPrimaryKeyInSet(CROSS_ROW_CATEGORY_A_PK))),
			"Each reference row must be judged on its own target. Complementing across the owner would return " +
				perOwnerReading + " instead."
		);
	}

	/**
	 * Fetches `crossRowCategories` reference content narrowed by the given filter and returns, per owner, the
	 * categories its surviving rows point at.
	 *
	 * @param evita  the engine
	 * @param filter the constraint narrowing the fetched rows
	 * @return owner primary key to the targets of its surviving rows
	 */
	@Nonnull
	private static Map<Integer, Set<Integer>> crossRowReferencesFilteredBy(
		@Nonnull Evita evita,
		@Nonnull FilterConstraint filter
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(
								CROSS_ROW_SPLIT_PRODUCT_PK,
								CROSS_ROW_MATCHED_PRODUCT_PK,
								CROSS_ROW_SCOPE_PRODUCT_PK
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContent(REF_PRODUCT_CROSS_ROW_CATEGORIES, filterBy(filter))
							)
						)
					),
					SealedEntity.class
				);
				final Map<Integer, Set<Integer>> fetched = new TreeMap<>();
				for (final SealedEntity product : result.getRecordData()) {
					fetched.put(
						Objects.requireNonNull(product.getPrimaryKey()),
						product.getReferences(REF_PRODUCT_CROSS_ROW_CATEGORIES)
							.stream()
							.map(ReferenceContract::getReferencedPrimaryKey)
							.collect(Collectors.toCollection(TreeSet::new))
					);
				}
				return fetched;
			}
		);
	}

	/**
	 * A `groupHaving` conjunct has to bind to the **same reference row** as its sibling, exactly as
	 * {@link #shouldBindEntityPrimaryKeyInSetAndAttributeToTheSameRow} requires of an attribute conjunct.
	 *
	 * There is no negation anywhere in this shape, which is what makes it worth pinning: the conjunction must
	 * not collapse into "this owner has some row in a matching group" AND "this owner has some row carrying the
	 * grade", which two different rows of one owner can satisfy between them.
	 *
	 * What holds it row-exact is `HavingTranslatorHelper#createIndexLocalGroupFormula`, which asks each reduced
	 * index about the row IT holds rather than asking the collection whether the owner is in the group anywhere -
	 * and which has to wrap its contribution in an `IndexTaggedFormula` for that to survive the rebuild. It did
	 * not, and this row is what caught it: an untagged subtree is one `ReferenceBodyTransposer#project` returns
	 * whole for every index, so the group conjunct stopped constraining the row it belongs to. Remove the tag
	 * again and this row answers `[1]` where `[]` is correct.
	 *
	 * The decoy row on category A is load bearing - see the dataset comment beside it. Without it the grade leaf
	 * reaches only category B, the type-level pass narrows the candidate set to that one index, and the body is
	 * answered inside a single index where the two readings cannot differ.
	 *
	 * @param evita            the engine
	 * @param originalProducts all products, fully fetched, as the dataset built them
	 */
	@DisplayName("`groupHaving` binds to the same reference row as its sibling conjunct")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldBindGroupHavingAndAttributeToTheSameRow(Evita evita, List<SealedEntity> originalProducts) {
		final Predicate<ReferenceContract> inMatchedGroup =
			row -> groupIs(row, GROUPED_MATCHED_GROUP_PK);
		final Predicate<ReferenceContract> carriesMatchedGrade =
			row -> longAttributeIs(row, REF_ATTR_GRADE, GROUPED_MATCHED_GRADE);

		final Set<Integer> expected = originalProducts.stream()
			.filter(it -> holdsGroupedRow(it, inMatchedGroup.and(carriesMatchedGrade)))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		final Set<Integer> pooledReading = originalProducts.stream()
			.filter(it -> holdsGroupedRow(it, inMatchedGroup) && holdsGroupedRow(it, carriesMatchedGrade))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		assertTrue(
			expected.isEmpty(),
			"No single row may carry both the group and the grade - that is what makes this shape cross-row."
		);
		assertFalse(
			pooledReading.isEmpty(),
			"Fixture must contain an owner carrying the group on one row and the grade on another, or the " +
				"row-scoped and the pooled readings coincide and this row proves nothing."
		);

		assertEquals(
			expected,
			matchingProductsByGroupedCategories(
				evita,
				and(
					groupHaving(entityPrimaryKeyInSet(GROUPED_MATCHED_GROUP_PK)),
					attributeEquals(REF_ATTR_GRADE, GROUPED_MATCHED_GRADE)
				)
			),
			"Both conjuncts must hold on one and the same reference row. Pooling them across the owner's rows " +
				"would return " + pooledReading + " instead."
		);
	}

	/**
	 * The negated group body - `not(groupHaving(...))` - has to be complemented per reference row.
	 *
	 * Row-scoped, an owner matches when it holds a row whose group is NOT the excluded one. The per-owner
	 * reading instead asks whether the owner belongs to that group anywhere, and so drops an owner holding a
	 * matching row alongside a non-matching one. Remove the `IndexTaggedFormula` from
	 * `HavingTranslatorHelper#createIndexLocalGroupFormula` and this row answers `[3]` where `[1, 3]` is correct.
	 *
	 * Both numbers depend on the fixture actually maintaining group indexes - see the indexed-components note
	 * beside the `groupedCategories` declaration. Without them every `groupHaving` answers empty, `not` of empty
	 * is everything, and this row passes while proving nothing.
	 *
	 * @param evita            the engine
	 * @param originalProducts all products, fully fetched, as the dataset built them
	 */
	@DisplayName("`not(groupHaving)` selects owners holding a row in some other group")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldComplementGroupHavingAgainstTheReferenceRow(Evita evita, List<SealedEntity> originalProducts) {
		final Predicate<ReferenceContract> inMatchedGroup = row -> groupIs(row, GROUPED_MATCHED_GROUP_PK);

		final Set<Integer> expected = originalProducts.stream()
			.filter(it -> holdsGroupedRow(it, inMatchedGroup.negate()))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		final Set<Integer> perOwnerReading = originalProducts.stream()
			.filter(it -> !it.getReferences(REF_PRODUCT_GROUPED_CATEGORIES).isEmpty())
			.filter(it -> !holdsGroupedRow(it, inMatchedGroup))
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		assertFalse(expected.isEmpty(), "Fixture must contain an owner holding a row in another group.");
		assertNotEquals(
			perOwnerReading, expected,
			"Fixture must contain an owner holding a row in the excluded group AND a row elsewhere, or the " +
				"row-scoped and the per-owner readings coincide and this row proves nothing."
		);

		assertEquals(
			expected,
			matchingProductsByGroupedCategories(
				evita, not(groupHaving(entityPrimaryKeyInSet(GROUPED_MATCHED_GROUP_PK)))
			),
			"The negation must be taken inside each reference row. Complementing across the owner would " +
				"return " + perOwnerReading + " instead."
		);
	}

	/**
	 * `groupHaving` inside a **`referenceContent` filter** has to be answered per reference row, exactly as
	 * {@link #shouldBindGroupHavingAndAttributeToTheSameRow} requires of the filtering path.
	 *
	 * The reference group is a property of the ROW, not of the referenced entity: two owners may reference the
	 * same category and put that row in different groups. `ReferencedEntityFetcher` answers the constraint by
	 * translating the matching groups into referenced entity primary keys and then dropping whole reduced indexes
	 * whose target is not among them, which cannot tell those two owners apart.
	 *
	 * The fixture is built to tell them apart: product 1 holds category A in the matched group, product 3 holds
	 * category A in the other one. A target-scoped reading admits category A for both.
	 *
	 * @param evita            the engine
	 * @param originalProducts all products, fully fetched, as the dataset built them
	 */
	@DisplayName("`groupHaving` filtering reference content keeps only the rows in the matching group")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldBindGroupHavingPerRowWhenFilteringReferenceContent(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final Predicate<ReferenceContract> inMatchedGroup = row -> groupIs(row, GROUPED_MATCHED_GROUP_PK);
		final Map<Integer, Set<Integer>> expected = groupedTargetsSatisfying(originalProducts, inMatchedGroup);
		final Map<Integer, Set<Integer>> targetScopedReading = groupedTargetsReachableFromGroup(
			originalProducts, inMatchedGroup
		);
		assertNotEquals(
			targetScopedReading, expected,
			"Fixture must contain two owners referencing one category from different groups, or the row-scoped " +
				"and the target-scoped readings coincide and this row proves nothing."
		);

		assertEquals(
			expected,
			groupedReferencesFilteredBy(evita, groupHaving(entityPrimaryKeyInSet(GROUPED_MATCHED_GROUP_PK))),
			"Each reference row must be judged on its own group. Reading the group off the referenced entity " +
				"would return " + targetScopedReading + " instead."
		);
	}

	/**
	 * `not(groupHaving(...))` inside a **`referenceContent` filter** has to be complemented per reference row.
	 *
	 * Row-scoped, a row survives when its own group is not the excluded one - so product 3's category A row
	 * survives, because that row sits in the other group, even though product 1 puts the same category in the
	 * excluded one.
	 *
	 * @param evita            the engine
	 * @param originalProducts all products, fully fetched, as the dataset built them
	 */
	@DisplayName("`not(groupHaving)` filtering reference content keeps the rows grouped elsewhere")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldComplementGroupHavingPerRowWhenFilteringReferenceContent(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final Predicate<ReferenceContract> inMatchedGroup = row -> groupIs(row, GROUPED_MATCHED_GROUP_PK);
		final Map<Integer, Set<Integer>> expected = groupedTargetsSatisfying(
			originalProducts, inMatchedGroup.negate()
		);
		final Map<Integer, Set<Integer>> targetScopedReading = new TreeMap<>();
		final Map<Integer, Set<Integer>> reachable = groupedTargetsReachableFromGroup(
			originalProducts, inMatchedGroup
		);
		for (final Map.Entry<Integer, Set<Integer>> entry : groupedTargetsSatisfying(
			originalProducts, row -> true
		).entrySet()) {
			final Set<Integer> surviving = new TreeSet<>(entry.getValue());
			surviving.removeAll(reachable.getOrDefault(entry.getKey(), Set.of()));
			targetScopedReading.put(entry.getKey(), surviving);
		}
		assertNotEquals(
			targetScopedReading, expected,
			"Fixture must contain two owners referencing one category from different groups, or the row-scoped " +
				"and the target-scoped readings coincide and this row proves nothing."
		);

		assertEquals(
			expected,
			groupedReferencesFilteredBy(evita, not(groupHaving(entityPrimaryKeyInSet(GROUPED_MATCHED_GROUP_PK)))),
			"Each reference row must be complemented on its own group. Subtracting the categories reachable " +
				"from the excluded group would return " + targetScopedReading + " instead."
		);
	}

	/**
	 * Returns, per owner of a `groupedCategories` row, the targets of the rows satisfying the predicate.
	 *
	 * @param originalProducts all products, fully fetched
	 * @param rowPredicate     the predicate a row must satisfy
	 * @return owner primary key to the targets of its satisfying rows
	 */
	@Nonnull
	private static Map<Integer, Set<Integer>> groupedTargetsSatisfying(
		@Nonnull List<SealedEntity> originalProducts,
		@Nonnull Predicate<ReferenceContract> rowPredicate
	) {
		final Map<Integer, Set<Integer>> result = new TreeMap<>();
		for (final SealedEntity product : originalProducts) {
			if (product.getReferences(REF_PRODUCT_GROUPED_CATEGORIES).isEmpty()) {
				continue;
			}
			result.put(
				Objects.requireNonNull(product.getPrimaryKey()),
				product.getReferences(REF_PRODUCT_GROUPED_CATEGORIES)
					.stream()
					.filter(rowPredicate)
					.map(ReferenceContract::getReferencedPrimaryKey)
					.collect(Collectors.toCollection(TreeSet::new))
			);
		}
		return result;
	}

	/**
	 * Returns, per owner, the targets its rows point at that ANY owner reaches through a matching group - the
	 * target-scoped reading the row-scoped tests exist to rule out.
	 *
	 * @param originalProducts all products, fully fetched
	 * @param rowPredicate     the predicate selecting the matching group
	 * @return owner primary key to the targets admitted by the target-scoped reading
	 */
	@Nonnull
	private static Map<Integer, Set<Integer>> groupedTargetsReachableFromGroup(
		@Nonnull List<SealedEntity> originalProducts,
		@Nonnull Predicate<ReferenceContract> rowPredicate
	) {
		final Set<Integer> reachableTargets = originalProducts.stream()
			.flatMap(it -> it.getReferences(REF_PRODUCT_GROUPED_CATEGORIES).stream())
			.filter(rowPredicate)
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
		final Map<Integer, Set<Integer>> result = new TreeMap<>();
		for (final Map.Entry<Integer, Set<Integer>> entry : groupedTargetsSatisfying(
			originalProducts, row -> true
		).entrySet()) {
			result.put(
				entry.getKey(),
				entry.getValue().stream()
					.filter(reachableTargets::contains)
					.collect(Collectors.toCollection(TreeSet::new))
			);
		}
		return result;
	}

	/**
	 * Fetches `groupedCategories` reference content narrowed by the given filter and returns, per owner, the
	 * categories its surviving rows point at.
	 *
	 * @param evita  the engine
	 * @param filter the constraint narrowing the fetched rows
	 * @return owner primary key to the targets of its surviving rows
	 */
	@Nonnull
	private static Map<Integer, Set<Integer>> groupedReferencesFilteredBy(
		@Nonnull Evita evita,
		@Nonnull FilterConstraint filter
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(CROSS_ROW_SPLIT_PRODUCT_PK, CROSS_ROW_SCOPE_PRODUCT_PK)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContent(REF_PRODUCT_GROUPED_CATEGORIES, filterBy(filter))
							)
						)
					),
					SealedEntity.class
				);
				final Map<Integer, Set<Integer>> fetched = new TreeMap<>();
				for (final SealedEntity product : result.getRecordData()) {
					fetched.put(
						Objects.requireNonNull(product.getPrimaryKey()),
						product.getReferences(REF_PRODUCT_GROUPED_CATEGORIES)
							.stream()
							.map(ReferenceContract::getReferencedPrimaryKey)
							.collect(Collectors.toCollection(TreeSet::new))
					);
				}
				return fetched;
			}
		);
	}

	/**
	 * Runs a `referenceHaving` over the `groupedCategories` reference on the index-scan path.
	 *
	 * @param evita the engine
	 * @param body  the body of the reference constraint
	 * @return primary keys of matching products
	 */
	@Nonnull
	private static Set<Integer> matchingProductsByGroupedCategories(
		@Nonnull Evita evita,
		@Nonnull FilterConstraint body
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(referenceHaving(REF_PRODUCT_GROUPED_CATEGORIES, body)),
						require(debug(DebugMode.PREFER_INDEX_SCAN), page(1, Integer.MAX_VALUE))
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
	 * Answers whether the product holds a `groupedCategories` row satisfying the predicate.
	 *
	 * @param product      product to examine
	 * @param rowPredicate predicate the row must satisfy
	 * @return true when such a row exists
	 */
	private static boolean holdsGroupedRow(
		@Nonnull SealedEntity product,
		@Nonnull Predicate<ReferenceContract> rowPredicate
	) {
		return product.getReferences(REF_PRODUCT_GROUPED_CATEGORIES).stream().anyMatch(rowPredicate);
	}

	/**
	 * Answers whether the reference row belongs to the given group.
	 *
	 * @param row     row to examine
	 * @param groupPk primary key the row's group must carry
	 * @return true when the row carries that group
	 */
	private static boolean groupIs(@Nonnull ReferenceContract row, int groupPk) {
		return row.getGroup().map(it -> it.getPrimaryKey() == groupPk).orElse(false);
	}

	/**
	 * Runs `userFilter(facetHaving(brand, body))` on the PRODUCT collection and returns the matched primary keys.
	 *
	 * @param evita     the engine
	 * @param facetBody the constraint selecting the facets
	 * @return ordered set of matched PRODUCT primary keys
	 */
	@Nonnull
	private static Set<Integer> productsSelectedByBrandFacet(
		@Nonnull Evita evita,
		@Nonnull FilterConstraint facetBody
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(userFilter(facetHaving(REF_PRODUCT_BRAND, facetBody))),
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
	 * Returns the categories reached by a `crossRowCategories` row satisfying the predicate, across all owners.
	 *
	 * @param products     products to examine
	 * @param rowPredicate predicate the row must satisfy
	 * @return ordered set of referenced category primary keys
	 */
	@Nonnull
	private static Set<Integer> crossRowTargetsOfRowsMatching(
		@Nonnull List<SealedEntity> products,
		@Nonnull Predicate<ReferenceContract> rowPredicate
	) {
		return products.stream()
			.flatMap(it -> it.getReferences(REF_PRODUCT_CROSS_ROW_CATEGORIES).stream())
			.filter(rowPredicate)
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
	}

	/**
	 * Answers whether the product holds a `crossRowCategories` row satisfying the predicate.
	 *
	 * @param product      product to examine
	 * @param rowPredicate predicate the row must satisfy
	 * @return true when such a row exists
	 */
	private static boolean holdsCrossRowRow(
		@Nonnull SealedEntity product,
		@Nonnull Predicate<ReferenceContract> rowPredicate
	) {
		return product.getReferences(REF_PRODUCT_CROSS_ROW_CATEGORIES).stream().anyMatch(rowPredicate);
	}

	/**
	 * Answers whether the product holds a `categories` row satisfying the predicate.
	 *
	 * @param product      product to examine
	 * @param rowPredicate predicate the row must satisfy
	 * @return true when such a row exists
	 */
	private static boolean holdsCategoriesRow(
		@Nonnull SealedEntity product,
		@Nonnull Predicate<ReferenceContract> rowPredicate
	) {
		return product.getReferences(REF_PRODUCT_CATEGORIES).stream().anyMatch(rowPredicate);
	}

	/**
	 * Answers whether the reference row carries the given `Long` attribute value.
	 *
	 * @param row           row to examine
	 * @param attributeName name of the attribute
	 * @param expected      value the attribute must carry
	 * @return true when the attribute is present and equal
	 */
	private static boolean longAttributeIs(
		@Nonnull ReferenceContract row,
		@Nonnull String attributeName,
		long expected
	) {
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
	private static boolean stringAttributeIs(
		@Nonnull ReferenceContract row,
		@Nonnull String attributeName,
		@Nonnull String expected
	) {
		return row.getAttributeValue(attributeName)
			.map(av -> expected.equals(av.value()))
			.orElse(false);
	}

}
