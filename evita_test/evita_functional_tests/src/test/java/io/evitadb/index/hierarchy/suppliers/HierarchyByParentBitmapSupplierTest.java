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
import static org.junit.jupiter.api.Assertions.assertEquals;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the cost-metric contract of {@link HierarchyByParentBitmapSupplier}: that nothing is computed before the
 * subtree listing has actually been paid for.
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
	@DisplayName("The cost reports the not-yet-computed sentinel until the listing is paid for")
	void shouldReportSentinelBeforeComputation() {
		final HierarchyByParentBitmapSupplier supplier = supplierOverEmptyHierarchy();
		assertEquals(Long.MAX_VALUE, supplier.getCost());
	}

	/** Every supplier hands out one memoized owner token, also after the legacy computations and the key ran. */
	@Test
	@DisplayName("Memoizes the owner token of every hierarchy supplier")
	void shouldMemoizeOwnerTokenOfEverySupplier() {
		final HierarchyIndex index = new HierarchyIndex();
		index.addNode(PARENT_NODE, null);
		final long[] transactionalIds = {1L};
		final HierarchyFilteringPredicate predicate = HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE;
		final AbstractHierarchyBitmapSupplier[] suppliers = {
			new HierarchyByParentBitmapSupplier(index, transactionalIds, PARENT_NODE, predicate),
			new HierarchyByParentIncludingSelfBitmapSupplier(index, transactionalIds, PARENT_NODE, predicate),
			new HierarchyForParentBitmapSupplier(index, transactionalIds, PARENT_NODE, predicate),
			new HierarchyByParentDownToLevelBitmapSupplier(index, transactionalIds, PARENT_NODE, 2, predicate),
			new HierarchyByParentDownToLevelIncludingSelfBitmapSupplier(
				index, transactionalIds, PARENT_NODE, 2, predicate),
			new HierarchyRootsBitmapSupplier(index, transactionalIds, predicate),
			new HierarchyRootsDownBitmapSupplier(index, transactionalIds, predicate),
			new HierarchyRootsDownToLevelBitmapSupplier(index, transactionalIds, 2, predicate)
		};
		for (final AbstractHierarchyBitmapSupplier supplier : suppliers) {
			supplier.getHash();
			supplier.getTransactionalIdHash();
			supplier.get();
			supplier.getCost();
			final Object token = supplier.getHierarchyIndexIdentityToken();
			assertSame(token, supplier.getHierarchyIndexIdentityToken(), supplier.getClass().getSimpleName());
			supplier.getKey();
			assertSame(token, supplier.getHierarchyIndexIdentityToken(), supplier.getClass().getSimpleName());
		}
	}

	/** An item-index-only commit must refresh the supplier identity. */
	@Test
	@DisplayName("Includes the hierarchy owner version after an orphan-only write")
	void shouldRefreshKeyAfterOrphanOnlyCommit() {
		final HierarchyIndex original = new HierarchyIndex();
		original.addNode(1, null);
		original.resetDirty();
		final HierarchyByParentBitmapSupplier before = new HierarchyByParentBitmapSupplier(
			original, new long[]{1, 2}, 1, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
		assertStateAfterCommit(original, index -> index.addNode(10, 99), (orig, committed) -> {
			final HierarchyByParentBitmapSupplier after = new HierarchyByParentBitmapSupplier(
				committed, new long[]{1, 2}, 1, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
			assertNotEquals(before.getKey(), after.getKey());
			assertNotEquals(before.getKey().getHash64(), after.getKey().getHash64());
		});
	}

}
