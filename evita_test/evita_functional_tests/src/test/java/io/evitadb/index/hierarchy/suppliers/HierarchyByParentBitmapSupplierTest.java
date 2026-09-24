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

package io.evitadb.index.hierarchy.suppliers;

import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the cost-metric contract of {@link HierarchyByParentBitmapSupplier}: that nothing is computed before the
 * subtree listing has actually been paid for, and that an EMPTY listing is priced rather than fatal.
 *
 * An empty listing is ordinary, not exceptional — a leaf parent has no children, and a parent all of whose children
 * are excluded lists none — so a cost metric that divides by the result size has to survive it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(HIERARCHY)
@Tag(CACHE)
@DisplayName("Hierarchy-by-parent supplier cost metrics")
class HierarchyByParentBitmapSupplierTest {

	/**
	 * Names the parent whose subtree is listed; on an empty index it has no children, which is the case under test.
	 */
	private static final int PARENT_NODE = 1;

	/**
	 * @return a supplier over an empty hierarchy, whose listing is therefore empty
	 */
	@Nonnull
	private static HierarchyByParentBitmapSupplier supplierOverEmptyHierarchy() {
		return new HierarchyByParentBitmapSupplier(
			new HierarchyIndex(), new long[]{1L}, PARENT_NODE,
			HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE
		);
	}

	@Test
	@DisplayName("An empty subtree listing is priced instead of throwing")
	void shouldNotDivideByZeroOnAnEmptyListing() {
		final HierarchyByParentBitmapSupplier supplier = supplierOverEmptyHierarchy();
		assertTrue(supplier.get().isEmpty());
		// `cost / (size * operationCost)` divides by zero here: the memoized-result guard establishes only that the
		// listing HAS been computed, never that it came back non-empty
		assertDoesNotThrow(supplier::getCostToPerformanceRatio);
	}

	@Test
	@DisplayName("The ratio reports the not-yet-computed sentinel until the listing is paid for")
	void shouldReportSentinelBeforeComputation() {
		final HierarchyByParentBitmapSupplier supplier = supplierOverEmptyHierarchy();
		assertEquals(Long.MAX_VALUE, supplier.getCostToPerformanceRatio());
		assertEquals(Long.MAX_VALUE, supplier.getCost());
	}

}
