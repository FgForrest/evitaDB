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
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.facet.ScopeContainerFormula;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.hierarchy.HierarchyIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

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

	/** Separately allocated equal parents and leaves resolve to the first complete subtree. */
	@Test
	@DisplayName("should intern separately built equal subtrees")
	void shouldInternSeparatelyBuiltEqualSubtrees() {
		final Formula first = new AndFormula(
			new ConstantFormula(new BaseBitmap(1, 2)), new ConstantFormula(new BaseBitmap(2, 3))
		);
		final Formula second = new AndFormula(
			new ConstantFormula(new BaseBitmap(1, 2)), new ConstantFormula(new BaseBitmap(2, 3))
		);
		assertEquals(first.getKey(), second.getKey());
		assertNotSame(first.getInnerFormulas()[0], second.getInnerFormulas()[0]);

		final Formula result = deduplicate(new OrFormula(first, second));

		assertSame(first, result);
		assertArrayEquals(new int[]{2}, result.compute().getArray());
	}

	/** Distinct enclosing computations retain the same canonical parent and its canonical children. */
	@Test
	@DisplayName("should share canonical parents and children across different scopes")
	void shouldShareCanonicalParentsAndChildrenAcrossDifferentScopes() {
		final Formula firstLeaf = new ConstantFormula(new BaseBitmap(1, 2));
		final Formula secondLeaf = new ConstantFormula(new BaseBitmap(2, 3));
		final Formula first = new AndFormula(firstLeaf, secondLeaf);
		final Formula second = new AndFormula(
			new ConstantFormula(new BaseBitmap(1, 2)), new ConstantFormula(new BaseBitmap(2, 3))
		);
		final Formula result = deduplicate(new OrFormula(
			new ScopeContainerFormula(Scope.LIVE, first), new ScopeContainerFormula(Scope.ARCHIVED, second)
		));

		final Formula liveParent = result.getInnerFormulas()[0].getInnerFormulas()[0];
		final Formula archivedParent = result.getInnerFormulas()[1].getInnerFormulas()[0];
		assertSame(first, liveParent);
		assertSame(liveParent, archivedParent);
		assertSame(firstLeaf, archivedParent.getInnerFormulas()[0]);
		assertSame(secondLeaf, archivedParent.getInnerFormulas()[1]);
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
