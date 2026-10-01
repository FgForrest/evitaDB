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

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.OrFormula;
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
import static org.mockito.Mockito.mock;

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
		final Formula union = initialize(new OrFormula(liveNodes, archivedNodes));
		// the premise that makes the test non-vacuous: a hash-only identity would merge the two
		assertEquals(liveNodes.getHash(), archivedNodes.getHash(), "both trees must yield the same computation");
		assertNotEquals(liveNodes.getTransactionalIdHash(), archivedNodes.getTransactionalIdHash());

		final Formula deduplicated = initialize(deduplicate(union));

		assertSame(union, deduplicated, "formulas over different indexes must not be merged");
		assertArrayEquals(new int[]{1, 2, 11, 12}, deduplicated.compute().getArray());
	}

	@Test
	@DisplayName("should merge equal formulas over the same index")
	void shouldMergeEqualFormulasOverSameIndex() {
		final HierarchyIndex tree = createTree(1, 2);
		final Formula union = initialize(
			new OrFormula(tree.getListHierarchyNodesFromRootFormula(), tree.getListHierarchyNodesFromRootFormula())
		);

		final Formula deduplicated = initialize(deduplicate(union));

		// the deduplicator hands the original tree back untouched when it merged nothing
		assertNotSame(union, deduplicated, "equal formulas over one index must be merged");
		assertArrayEquals(new int[]{1, 2}, deduplicated.compute().getArray());
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
	 * Initializes the formula with an execution context the computation of a hierarchy formula never reads.
	 *
	 * @param formula the formula to initialize
	 * @return the same formula
	 */
	@Nonnull
	private static Formula initialize(@Nonnull Formula formula) {
		formula.initialize(mock(QueryExecutionContext.class));
		return formula;
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
