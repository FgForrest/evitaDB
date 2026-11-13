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

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.deferred.FormulaWrapper;
import io.evitadb.core.query.algebra.facet.ScopeContainerFormula;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.test.identity.FormulaKeyComparisonCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.Nonnull;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

	/** Side formulas remain outside traversal, so nested ordinary child keys must share the comparison memo. */
	@Test
	@Timeout(30)
	@DisplayName("should bound equality work for deferred doubling formula DAGs")
	void shouldBoundEqualityWorkForDeferredDoublingDags() {
		final int depth = 24;
		final int distinctFormulas = 2 * (depth + 1) + 3;
		final AtomicInteger comparisons = new AtomicInteger();
		final ConstantFormula leftLeaf = new ConstantFormula(new BaseBitmap(1));
		final ConstantFormula rightLeaf = new ConstantFormula(new BaseBitmap(1));
		FormulaKeyComparisonCounter.observe(leftLeaf, comparisons);
		FormulaKeyComparisonCounter.observe(rightLeaf, comparisons);
		Formula left = leftLeaf;
		Formula right = rightLeaf;
		for (int i = 0; i < depth; i++) {
			left = new NotFormula(left, left);
			right = new NotFormula(right, right);
		}
		final Formula first = new DeferredFormula(new FormulaWrapper(left, () -> "left"));
		final Formula second = new DeferredFormula(new FormulaWrapper(right, () -> "right"));
		assertEquals(0, first.getInnerFormulas().length);
		assertEquals(0, second.getInnerFormulas().length);

		final Formula result = deduplicate(new OrFormula(first, second));

		assertTrue(comparisons.get() > 0, "the leaf identity counter must be engaged");
		assertTrue(comparisons.get() <= 4 * distinctFormulas,
			"Leaf comparisons: " + comparisons.get() + ", linear bound: " + 4 * distinctFormulas);
		assertSame(first, result, "the two deferred formulas must merge");
	}

	/** The same unowned large bitmap cannot make distinct opaque holders interchangeable. */
	@Test
	@DisplayName("should keep opaque holders apart even when their operand is shared")
	void shouldKeepOpaqueHoldersApartEvenWhenTheirOperandIsShared() {
		final int[] records = new int[IdentityToken.CONTENT_LIMIT + 1];
		for (int i = 0; i < records.length; i++) {
			records[i] = i + 1;
		}
		final BaseBitmap bitmap = new BaseBitmap(records);
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
		final int[] records = new int[IdentityToken.CONTENT_LIMIT + 1];
		for (int i = 0; i < records.length; i++) {
			records[i] = i + 1;
		}
		final Formula opaque = new ConstantFormula(new BaseBitmap(records));
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

}
