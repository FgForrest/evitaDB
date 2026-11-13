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


package io.evitadb.core.query.filter;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.deferred.FormulaWrapper;
import io.evitadb.core.query.algebra.facet.ScopeContainerFormula;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.HierarchyIndex;
import net.openhft.hashing.LongHashFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what {@link FormulaDeduplicator} treats as one formula: the same computation over the same data. Structurally
 * equal formulas over different indexes - the hierarchy nodes from the roots of two separate trees - are kept apart,
 * while equal formulas over one index are merged into a single instance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula deduplication")
@Tag(ENGINE)
@Tag(QUERY)
class FormulaDeduplicatorTest {

	@Test
	@DisplayName("should keep structurally equal formulas over different indexes apart")
	void shouldKeepStructurallyEqualFormulasOverDifferentIndexesApart() {
		final HierarchyIndex liveTree = createTree(1, 2);
		final HierarchyIndex archivedTree = createTree(11, 12);
		final Formula liveNodes = liveTree.getListHierarchyNodesFromRootFormula();
		final Formula archivedNodes = archivedTree.getListHierarchyNodesFromRootFormula();
		final Formula union = new OrFormula(liveNodes, archivedNodes);
		// the premise that makes the test non-vacuous: a hash-only identity would merge the two
		assertEquals(liveNodes.getHash(), archivedNodes.getHash(), "both trees must yield the same computation");
		assertNotEquals(liveNodes.getTransactionalIdHash(), archivedNodes.getTransactionalIdHash());
		assertNotEquals(liveNodes.getKey(), archivedNodes.getKey());

		final Formula deduplicated = deduplicate(union);

		// the deduplicator hands the original tree back untouched when it merged nothing
		assertSame(union, deduplicated, "formulas over different indexes must not be merged");
		assertArrayEquals(new int[]{1, 2, 11, 12}, deduplicated.compute().getArray());
	}

	@Test
	@DisplayName("should merge equal formulas over the same index")
	void shouldMergeEqualFormulasOverSameIndex() {
		final HierarchyIndex tree = createTree(1, 2);
		final Formula union = new OrFormula(
			tree.getListHierarchyNodesFromRootFormula(), tree.getListHierarchyNodesFromRootFormula()
		);
		assertEquals(union.getInnerFormulas()[0].getKey(), union.getInnerFormulas()[1].getKey());

		final Formula deduplicated = deduplicate(union);

		// a merge rebuilds the tree: the second branch is replaced by the instance seen first, and a union of that
		// single instance is the instance itself
		assertNotSame(union, deduplicated, "equal formulas over one index must be merged");
		assertSame(union.getInnerFormulas()[0], deduplicated, "both branches must be merged into the first one");
		assertArrayEquals(new int[]{1, 2}, deduplicated.compute().getArray());
	}

	/** A seeded leaf rebuilds the first parent, whose completed replacement is reused along every DAG edge. */
	@Test
	@DisplayName("should intern separately built equal subtrees over a seeded canonical leaf")
	void shouldInternSeparatelyBuiltEqualSubtrees() {
		final Formula canonicalLeaf = new ConstantFormula(new BaseBitmap(1, 2));
		final Formula first = new AndFormula(
			new ConstantFormula(new BaseBitmap(1, 2)), new ConstantFormula(new BaseBitmap(2, 3))
		);
		final Formula second = new AndFormula(
			new ConstantFormula(new BaseBitmap(1, 2)), new ConstantFormula(new BaseBitmap(2, 3))
		);
		assertEquals(first.getKey(), second.getKey());
		assertNotSame(canonicalLeaf, first.getInnerFormulas()[0]);

		final Formula result = deduplicate(new OrFormula(
			canonicalLeaf, first, new NotFormula(new ConstantFormula(new BaseBitmap(9)), first), second
		));

		final Formula canonicalParent = result.getInnerFormulas()[1];
		assertNotSame(first, canonicalParent, "the first parent must be rebuilt over the seeded leaf");
		assertSame(canonicalLeaf, canonicalParent.getInnerFormulas()[0]);
		assertSame(first.getInnerFormulas()[1], canonicalParent.getInnerFormulas()[1]);
		assertSame(canonicalParent, result.getInnerFormulas()[2].getInnerFormulas()[1],
			"a repeated original parent must resolve to its completed replacement");
		assertEquals(3, result.getInnerFormulas().length, "the separately built equal parent must merge");
		assertArrayEquals(new int[]{1, 2}, result.compute().getArray());
	}

	/** Equal scope identities isolate post-order rebuilding from scope discrimination. */
	@Test
	@DisplayName("should share rebuilt canonical parents across equal scopes")
	void shouldShareCanonicalParentsAcrossEqualScopes() {
		final Formula canonicalLeaf = new ConstantFormula(new BaseBitmap(1, 2));
		final Formula first = new AndFormula(
			new ConstantFormula(new BaseBitmap(1, 2)), new ConstantFormula(new BaseBitmap(2, 3))
		);
		final Formula second = new AndFormula(
			new ConstantFormula(new BaseBitmap(1, 2)), new ConstantFormula(new BaseBitmap(2, 3))
		);
		final Formula firstScope = new ScopeContainerFormula(Scope.LIVE, first);
		final Formula secondScope = new ScopeContainerFormula(Scope.LIVE, second);
		assertEquals(firstScope.getKey(), secondScope.getKey(), "the scope identity must stay fixed");

		final Formula result = deduplicate(new OrFormula(canonicalLeaf, firstScope, secondScope));

		assertEquals(2, result.getInnerFormulas().length);
		final Formula canonicalScope = result.getInnerFormulas()[1];
		final Formula canonicalParent = canonicalScope.getInnerFormulas()[0];
		assertNotSame(firstScope, canonicalScope);
		assertNotSame(first, canonicalParent);
		assertSame(canonicalLeaf, canonicalParent.getInnerFormulas()[0]);
		assertSame(first.getInnerFormulas()[1], canonicalParent.getInnerFormulas()[1]);
	}

	/** Shared positional children collapse to the retained constant when cloning a union. */
	@Test
	@DisplayName("should return the collapsed union of one shared instance")
	void shouldReturnCollapsedUnionOfSharedInstance() {
		final Formula shared = new ConstantFormula(new BaseBitmap(1));
		assertSame(shared, deduplicate(new OrFormula(shared, shared)),
			"the completed union clone must be the shared child");
	}

	/** Subtracting a shared instance from itself collapses to the empty singleton. */
	@Test
	@DisplayName("should return the collapsed difference of one shared instance")
	void shouldReturnCollapsedDifferenceOfSharedInstance() {
		final Formula shared = new ConstantFormula(new BaseBitmap(1));
		assertSame(EmptyFormula.INSTANCE, deduplicate(new NotFormula(shared, shared)),
			"the completed difference clone must be empty");
	}

	/** An unchanged completed tree preserves the caller's root instance. */
	@Test
	@DisplayName("should return the same root instance when nothing changes")
	void shouldReturnSameRootWhenNothingChanges() {
		final Formula root = new OrFormula(
			new ConstantFormula(new BaseBitmap(1)), new ConstantFormula(new BaseBitmap(2))
		);
		assertSame(root, deduplicate(root));
	}

	/**
	 * Separately built copies of one subtree carry the key of its first occurrence, so they resolve to that instance
	 * without their inner formulas being rebuilt.
	 */
	@Test
	@DisplayName("should not rebuild separately built copies of an interned subtree")
	void shouldNotRebuildCopiesOfInternedSubtree() {
		final int copies = 64;
		final int depth = 4;
		final Formula[] subtrees = new Formula[copies];
		for (int i = 0; i < copies; i++) {
			subtrees[i] = countingSubtree(1, depth);
		}
		final Formula root = new OrFormula(subtrees);
		assertEquals(subtrees[0].getKey(), subtrees[copies - 1].getKey(), "the copies must be equal");
		final int[] clones = CloneCountingFormula.CLONES.get();

		clones[0] = 0;
		final Formula fullDescent = deduplicateWithFullDescent(root);
		final int fullDescentClones = clones[0];
		clones[0] = 0;
		final Formula deduplicated = deduplicate(root);
		final int deduplicatedClones = clones[0];

		// visiting every copy rebuilds each of its inner nodes, so the fixture offers that much work to skip
		assertEquals((copies - 1) * ((1 << depth) - 1), fullDescentClones);
		assertSame(subtrees[0], fullDescent);
		assertSame(subtrees[0], deduplicated, "every copy must merge into the first one");
		assertEquals(0, deduplicatedClones, "a copy of an interned subtree must not be rebuilt");
	}

	/**
	 * Skipping the subtree of a formula whose key is interned already must not change the deduplicated tree.
	 *
	 * @param formula the tree to deduplicate
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("treesToDeduplicate")
	@DisplayName("should produce the tree a visit of every duplicate subtree produces")
	void shouldProduceTheTreeOfFullDescent(@Nonnull String name, @Nonnull Formula formula) {
		final Set<Formula> inputFormulas = Collections.newSetFromMap(new IdentityHashMap<>());
		collect(formula, inputFormulas);

		final Formula expected = deduplicateWithFullDescent(formula);
		final Formula actual = deduplicate(formula);

		assertSameTree(inputFormulas, expected, actual, new IdentityHashMap<>(), new IdentityHashMap<>());
	}

	@Test
	@Timeout(60)
	@DisplayName("probe")
	void probeSharedDagComparisonWork() {
		for (final int depth : new int[]{10, 16, 20}) {
			final Formula first = sharedDag(depth);
			final Formula second = sharedDag(depth);
			final int[] comparisons = ComparisonCountingLeafFormula.COMPARISONS.get();
			comparisons[0] = 0;
			final Formula fullDescent = deduplicateWithFullDescent(new OrFormula(first, second));
			final int fullDescentComparisons = comparisons[0];
			comparisons[0] = 0;
			final Formula deduplicated = deduplicate(new OrFormula(first, second));
			final int deduplicatedComparisons = comparisons[0];
			System.out.println("PROBE depth=" + depth + " fullDescent=" + fullDescentComparisons +
				" production=" + deduplicatedComparisons + " sameRoot=" + (fullDescent == first) + "/" +
				(deduplicated == first));
		}
	}

	private static Formula sharedDag(int depth) {
		Formula formula = new ComparisonCountingLeafFormula(new BaseBitmap(1000));
		for (int i = 0; i < depth; i++) {
			formula = new AndFormula(formula, new OrFormula(formula, new ComparisonCountingLeafFormula(new BaseBitmap(i))));
		}
		return formula;
	}

	/** Side formulas remain outside traversal, so nested ordinary child keys must share the comparison memo. */
	@Test
	@Timeout(30)
	@DisplayName("should bound equality work for deferred doubling formula DAGs")
	void shouldBoundEqualityWorkForDeferredDoublingDags() {
		final int depth = 24;
		final int distinctFormulas = 2 * (depth + 1) + 3;
		Formula left = new ComparisonCountingLeafFormula(new BaseBitmap(1));
		Formula right = new ComparisonCountingLeafFormula(new BaseBitmap(1));
		for (int i = 0; i < depth; i++) {
			left = new NotFormula(left, left);
			right = new NotFormula(right, right);
		}
		final Formula first = new DeferredFormula(new FormulaWrapper(left, () -> "left"));
		final Formula second = new DeferredFormula(new FormulaWrapper(right, () -> "right"));
		assertEquals(0, first.getInnerFormulas().length);
		assertEquals(0, second.getInnerFormulas().length);
		final int[] comparisons = ComparisonCountingLeafFormula.COMPARISONS.get();
		comparisons[0] = 0;

		final Formula result = deduplicate(new OrFormula(first, second));

		assertTrue(comparisons[0] > 0, "the leaf identity counter must be engaged");
		assertTrue(comparisons[0] <= 4 * distinctFormulas,
			"Leaf comparisons: " + comparisons[0] + ", linear bound: " + 4 * distinctFormulas);
		assertSame(first, result, "the two deferred formulas must merge");
	}

	/** The same unowned large bitmap cannot make distinct opaque holders interchangeable. */
	@Test
	@DisplayName("should keep opaque holders apart even when their operand is shared")
	void shouldKeepOpaqueHoldersApartEvenWhenTheirOperandIsShared() {
		final BaseBitmap bitmap = opaqueBitmap();
		final Formula first = new ConstantFormula(bitmap);
		final Formula second = new ConstantFormula(bitmap);
		final Formula union = new OrFormula(first, second);
		assertTrue(first.getKey().isOpaque());
		assertNotEquals(first.getKey(), second.getKey());

		assertSame(union, deduplicate(union));
	}

	/** Query-local value parents may still share when their opaque child is the same holder. */
	@Test
	@DisplayName("should intern equal query-local parents sharing an opaque child")
	void shouldInternEqualQueryLocalParentsSharingAnOpaqueChild() {
		final Formula opaque = new ConstantFormula(opaqueBitmap());
		final Formula first = new AndFormula(opaque, new ConstantFormula(new BaseBitmap(1)));
		final Formula second = new AndFormula(opaque, new ConstantFormula(new BaseBitmap(1)));
		assertTrue(first.getKey().isQueryLocal());
		assertEquals(first.getKey(), second.getKey());

		assertSame(first, deduplicate(new OrFormula(first, second)));
	}

	/**
	 * Creates a hierarchy index holding one root with one child.
	 *
	 * @param root  the primary key of the root
	 * @param child the primary key of the child
	 * @return the hierarchy index
	 */
	@Nonnull
	private static HierarchyIndex createTree(int root, int child) {
		final HierarchyIndex tree = new HierarchyIndex();
		tree.addNode(root, null);
		tree.addNode(child, root);
		return tree;
	}

	/**
	 * Runs the deduplication over the formula tree.
	 *
	 * @param formula the formula tree
	 * @return the deduplicated tree
	 */
	@Nonnull
	private static Formula deduplicate(@Nonnull Formula formula) {
		final FormulaDeduplicator deduplicator = new FormulaDeduplicator(formula);
		deduplicator.visit(formula);
		return deduplicator.getPostProcessedFormula();
	}

	/**
	 * Runs the deduplication over the formula tree, visiting the inner formulas of every formula.
	 *
	 * @param formula the formula tree
	 * @return the deduplicated tree
	 */
	@Nonnull
	private static Formula deduplicateWithFullDescent(@Nonnull Formula formula) {
		final FormulaDeduplicator deduplicator = new FullDescentDeduplicator(formula);
		deduplicator.visit(formula);
		return deduplicator.getPostProcessedFormula();
	}

	/**
	 * Supplies trees that share instances, repeat equal subtrees, collapse on rebuild, or hold opaque formulas.
	 *
	 * @return the name and the tree of each case
	 */
	@Nonnull
	static Stream<Arguments> treesToDeduplicate() {
		final Formula sharedLeaf = constant(1);
		final Formula sharedOperand = constant(2);
		final Formula seededParent = new AndFormula(constant(1, 2), constant(2, 3));
		final Bitmap opaqueBitmap = opaqueBitmap();
		final Formula opaqueLeaf = new ConstantFormula(opaqueBitmap());
		final HierarchyIndex tree = createTree(1, 2);
		final Formula[] copies = new Formula[8];
		for (int i = 0; i < copies.length; i++) {
			copies[i] = countingSubtree(1, 3);
		}
		return Stream.of(
			Arguments.of("union of one shared instance", new OrFormula(sharedLeaf, sharedLeaf)),
			Arguments.of("difference of one shared instance", new NotFormula(sharedOperand, sharedOperand)),
			Arguments.of("separately built parents over a seeded leaf", new OrFormula(
				constant(1, 2), seededParent, new NotFormula(constant(9), seededParent),
				new AndFormula(constant(1, 2), constant(2, 3))
			)),
			Arguments.of("equal subtrees whose inner union collapses", new OrFormula(
				new ScopeContainerFormula(Scope.LIVE,
					new AndFormula(constant(1, 2, 3), new OrFormula(constant(2, 3, 4), constant(2, 3, 4)))),
				new ScopeContainerFormula(Scope.ARCHIVED,
					new AndFormula(constant(1, 2, 3), new OrFormula(constant(2, 3, 4), constant(2, 3, 4))))
			)),
			Arguments.of("equal subtrees at different depths", new OrFormula(
				new AndFormula(constant(1), constant(2)),
				new NotFormula(new AndFormula(constant(1), constant(2)), constant(3)),
				new AndFormula(new AndFormula(constant(1), constant(2)), constant(3))
			)),
			Arguments.of("separately built copies of one subtree", new OrFormula(copies)),
			Arguments.of("opaque holders over one operand", new OrFormula(
				new ConstantFormula(opaqueBitmap), new ConstantFormula(opaqueBitmap)
			)),
			Arguments.of("query-local parents sharing an opaque child", new OrFormula(
				new AndFormula(opaqueLeaf, constant(1)), new AndFormula(opaqueLeaf, constant(1))
			)),
			Arguments.of("equal formulas over different indexes", new OrFormula(
				createTree(1, 2).getListHierarchyNodesFromRootFormula(),
				createTree(11, 12).getListHierarchyNodesFromRootFormula()
			)),
			Arguments.of("equal formulas over one index", new OrFormula(
				tree.getListHierarchyNodesFromRootFormula(), tree.getListHierarchyNodesFromRootFormula()
			))
		);
	}

	/**
	 * Builds a constant formula over its own bitmap.
	 *
	 * @param records the records of the bitmap
	 * @return the constant formula
	 */
	@Nonnull
	private static Formula constant(int... records) {
		return new ConstantFormula(new BaseBitmap(records));
	}

	/**
	 * Builds a bitmap holding one record more than {@link IdentityToken#CONTENT_LIMIT}, so a formula over it gets an
	 * opaque key.
	 *
	 * @return a fresh bitmap
	 */
	@Nonnull
	private static BaseBitmap opaqueBitmap() {
		final int[] records = new int[IdentityToken.CONTENT_LIMIT + 1];
		for (int i = 0; i < records.length; i++) {
			records[i] = i + 1;
		}
		return new BaseBitmap(records);
	}

	/**
	 * Builds a complete binary tree of counting conjunctions over constant leaves with distinct records.
	 *
	 * @param firstRecord the record of the leftmost leaf
	 * @param depth       the number of conjunction levels
	 * @return a separately built tree with `2^depth - 1` conjunctions
	 */
	@Nonnull
	private static Formula countingSubtree(int firstRecord, int depth) {
		if (depth == 0) {
			return new ConstantFormula(new BaseBitmap(firstRecord));
		}
		final int half = 1 << (depth - 1);
		return new CloneCountingFormula(
			countingSubtree(firstRecord, depth - 1), countingSubtree(firstRecord + half, depth - 1)
		);
	}

	/**
	 * Collects every formula of the tree.
	 *
	 * @param formula  the root of the tree
	 * @param formulas the collected formulas
	 */
	private static void collect(@Nonnull Formula formula, @Nonnull Set<Formula> formulas) {
		if (formulas.add(formula)) {
			for (final Formula innerFormula : formula.getInnerFormulas()) {
				collect(innerFormula, formulas);
			}
		}
	}

	/**
	 * Asserts that two deduplicated trees are the same tree: input formulas at the same positions, rebuilt formulas
	 * of the same class and key in their place, and one formula shared at exactly the positions where the other one
	 * is shared.
	 *
	 * @param inputFormulas    every formula of the deduplicated input
	 * @param expected         a node of the expected tree
	 * @param actual           the node of the actual tree at the same position
	 * @param expectedToActual nodes matched so far, by the expected node
	 * @param actualToExpected nodes matched so far, by the actual node
	 */
	private static void assertSameTree(
		@Nonnull Set<Formula> inputFormulas,
		@Nonnull Formula expected,
		@Nonnull Formula actual,
		@Nonnull Map<Formula, Formula> expectedToActual,
		@Nonnull Map<Formula, Formula> actualToExpected
	) {
		final Formula matched = expectedToActual.get(expected);
		if (matched != null) {
			assertSame(matched, actual, "one shared formula must stay shared at the same positions");
			return;
		}
		assertFalse(actualToExpected.containsKey(actual), "two formulas must not merge into one");
		expectedToActual.put(expected, actual);
		actualToExpected.put(actual, expected);
		if (inputFormulas.contains(expected) || inputFormulas.contains(actual)) {
			assertSame(expected, actual, "an input formula must stay at the same position");
			return;
		}
		assertSame(expected.getClass(), actual.getClass());
		assertEquals(expected.getKey(), actual.getKey());
		final Formula[] expectedChildren = expected.getInnerFormulas();
		final Formula[] actualChildren = actual.getInnerFormulas();
		assertEquals(expectedChildren.length, actualChildren.length);
		for (int i = 0; i < expectedChildren.length; i++) {
			assertSameTree(inputFormulas, expectedChildren[i], actualChildren[i], expectedToActual, actualToExpected);
		}
	}

	/**
	 * Deduplicator that visits the inner formulas of every formula, including one whose key is interned already, so
	 * each duplicate subtree is rebuilt over canonical children before it merges.
	 */
	private static class FullDescentDeduplicator extends FormulaDeduplicator {

		FullDescentDeduplicator(@Nonnull Formula originalFormula) {
			super(originalFormula);
		}

		@Nonnull
		@Override
		protected Formula preProcess(@Nonnull Formula formula) {
			return formula;
		}

	}

	/**
	 * Executable conjunction that counts its rebuilds over new inner formulas on the rebuilding thread.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public static class CloneCountingFormula extends AbstractFormula {
		/** Stable discriminator of this test formula. */
		private static final long CLASS_ID = 2431352956093174974L;
		/** Thread-confined count of rebuilds over new inner formulas. */
		private static final ThreadLocal<int[]> CLONES = ThreadLocal.withInitial(() -> new int[1]);

		/** Captures the operands of the conjunction. */
		public CloneCountingFormula(@Nonnull Formula... innerFormulas) {
			this.initFields(innerFormulas);
		}

		/** Counts one rebuild before performing it. */
		@Nonnull
		@Override
		public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
			CLONES.get()[0]++;
			return new CloneCountingFormula(innerFormulas);
		}

		@Override
		public long getOperationCost() {
			return 1;
		}

		@Override
		public int getEstimatedCardinality() {
			return getMinEstimatedCardinality(this.innerFormulas);
		}

		@Nonnull
		@Override
		protected Bitmap computeInternal() {
			return computeConjunctionResult(
				computeSortedConjunctionBitmaps(sortFormulasByComplexity(this.innerFormulas))
			);
		}

		@Override
		protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
			return CLASS_ID;
		}

		@Override
		protected long getClassId() {
			return CLASS_ID;
		}

		/** Captures the inputs of the conjunction, which are its inner formulas only. */
		@Nonnull
		@Override
		protected IdentityRecord identity() {
			return new Identity();
		}

		@Override
		public String toString() {
			return "CLONE COUNTING AND";
		}

		/**
		 * Detached identity of the conjunction, whose inputs are its inner formulas.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		public record Identity() implements IdentityRecord {
			/** Returns the conjunction discriminator. */
			@Override
			public long classId() {
				return CLASS_ID;
			}
		}
	}

	/**
	 * Executable constant leaf whose detached record counts comparisons on the comparing thread.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public static class ComparisonCountingLeafFormula extends ConstantFormula {
		/** Stable discriminator of this executable test leaf. */
		private static final long CLASS_ID = -5964583216623163460L;
		/** Thread-confined count of leaf record comparisons. */
		private static final ThreadLocal<int[]> COMPARISONS = ThreadLocal.withInitial(() -> new int[1]);

		/** Captures a real bitmap operand with the constant-formula computation. */
		public ComparisonCountingLeafFormula(@Nonnull Bitmap bitmap) {
			super(bitmap);
		}

		/** Captures the same detached bitmap identity as the constant computation. */
		@Nonnull
		@Override
		protected IdentityRecord identity() {
			return new Identity(getDelegateIdentityToken());
		}

		/** Returns this leaf's discriminator. */
		@Override
		protected long getClassId() {
			return CLASS_ID;
		}

		/**
		 * Detached bitmap input with observable equality work.
		 *
		 * @param delegateIdentity detached bitmap operand
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		public record Identity(@Nonnull IdentityToken delegateIdentity) implements IdentityRecord {
			/** Returns the leaf discriminator. */
			@Override
			public long classId() {
				return CLASS_ID;
			}

			/** Describes the entire bitmap operand. */
			@Override
			public void describeTo(@Nonnull KeyAccumulator accumulator) {
				accumulator.add(this.delegateIdentity);
			}

			/** Counts one comparison before comparing the detached operand. */
			@Override
			public boolean equals(@Nullable Object other) {
				COMPARISONS.get()[0]++;
				return other instanceof Identity that && this.delegateIdentity.equals(that.delegateIdentity);
			}
		}
	}

}
