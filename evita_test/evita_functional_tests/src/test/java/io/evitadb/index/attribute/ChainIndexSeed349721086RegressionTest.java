/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.index.attribute;

import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.dataType.Predecessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Deterministic regression distilled from {@code LongRunningChainIndexTest#generationalProofTest} seed 349721086
 * (generation 686). It guards against a copy-on-write violation in the value index ({@code
 * TransactionalIntToLongBPlusTree}): when a transaction-local leaf node (created by a split during the same
 * transaction, {@code transactionalLayer == false}) underflowed and stole from a committed (shared) right sibling,
 * {@code stealFromRight} shifted the sibling's backing arrays in place without decoupling them - silently dropping a
 * run of records from the committed (pre-transaction) index instance. The relocation batch below reproduces exactly
 * that scenario; the committed base must stay byte-identical and consistent after the transaction commits.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class ChainIndexSeed349721086RegressionTest {

	private static final int[] INITIAL_STATE = {24, 145, 288, 239, 128, 85, 94, 250, 126, 174, 236, 209, 7, 92, 112, 19, 150, 156, 185, 42, 17, 137, 33, 281, 193, 49, 285, 105, 101, 221, 175, 23, 170, 184, 228, 48, 18, 226, 269, 167, 238, 154, 247, 120, 157, 102, 31, 186, 56, 164, 206, 231, 187, 82, 115, 218, 50, 65, 182, 216, 62, 152, 72, 81, 73, 210, 74, 39, 297, 283, 26, 38, 172, 29, 113, 195, 44, 263, 131, 267, 46, 179, 284, 249, 45, 47, 260, 32, 148, 176, 79, 219, 60, 146, 134, 30, 264, 223, 181, 220, 243, 235, 55, 41, 3, 282, 40, 177, 76, 153, 130, 64, 262, 275, 9, 214, 16, 133, 295, 69};

	/** Each row: {0, predecessorPk, pk} = upsert; {1, _, pk} = remove. */
	private static final int[][] OPS = {
		{0, 24, 145},
		{0, 145, 63},
		{0, 63, 141},
		{0, 141, 85},
		{1, 0, 130},
		{0, 85, 148},
		{0, 148, 262},
		{0, 262, 217},
		{0, 217, 174},
		{0, 174, 19},
		{0, 19, 31},
		{0, 31, 92},
		{1, 0, 30},
		{0, 92, 33},
		{0, 33, 236},
		{0, 236, 74},
		{1, 0, 263},
		{0, 74, 156},
		{0, 156, 185},
		{0, 185, 288},
		{1, 0, 39},
		{0, 288, 195},
		{0, 195, 137},
		{0, 137, 172},
		{0, 172, 282},
		{0, 282, 120},
		{0, 120, 285},
		{0, 285, 17},
		{0, 17, 101},
		{0, 101, 175},
		{0, 175, 176},
		{0, 176, 231},
		{0, 231, 184},
		{0, 184, 228},
		{0, 228, 69},
		{0, 69, 18},
		{0, 18, 226},
		{0, 226, 73},
		{0, 73, 238},
		{0, 238, 42},
		{0, 42, 193},
		{0, 193, 131},
		{0, 131, 152},
		{0, 152, 7},
		{0, 7, 41},
		{0, 41, 284},
		{0, 284, 164},
		{0, 164, 267},
		{0, 267, 187},
		{0, 187, 115},
		{0, 115, 105},
		{0, 105, 50},
		{0, 50, 210},
		{0, 210, 26},
		{0, 26, 216},
		{0, 216, 102},
		{1, 0, 62},
		{0, 102, 72},
		{0, 72, 167},
		{1, 0, 82},
		{0, 167, 81},
		{0, 81, 138},
		{0, 138, 100},
		{1, 0, 206},
		{0, 100, 150},
		{0, 150, 283},
		{0, 283, 170},
		{0, 170, 38},
		{1, 0, 154},
		{0, 38, 112},
		{0, 112, 29},
		{0, 29, 113},
		{0, 113, 218},
		{1, 0, 269},
		{0, 218, 44},
		{0, 44, 157},
		{0, 157, 182},
		{0, 182, 46},
		{0, 46, 179},
		{0, 179, 56},
		{1, 0, 221},
		{0, 56, 249},
		{0, 249, 45},
		{0, 45, 47},
		{0, 47, 260},
		{0, 260, 32},
		{0, 32, 94},
		{0, 94, 23},
		{0, 23, 79},
		{0, 79, 219},
		{1, 0, 49},
		{0, 219, 60},
		{1, 0, 209},
		{0, 60, 146},
		{0, 146, 134},
		{0, 134, 264},
		{0, 264, 223},
		{0, 223, 181},
		{0, 181, 220},
		{0, 220, 243},
		{0, 243, 235},
		{0, 235, 55},
		{0, 55, 186},
		{1, 0, 128},
		{0, 186, 3},
		{0, 3, 281},
		{0, 281, 40},
		{0, 40, 177},
		{0, 177, 76},
		{0, 76, 153},
		{0, 153, 64},
		{0, 64, 250},
		{0, 250, 275},
		{0, 275, 9},
		{0, 9, 214},
		{0, 214, 16},
		{0, 16, 133},
		{0, 133, 295},
		{0, 295, 48},
		{0, 48, 126},
		{0, 126, 163},
		{0, 163, 159},
		{0, 159, 280},
		{0, 280, 297},
		{0, 297, 239},
		{0, 239, 75},
		{0, 75, 247},
		{0, 247, 278},
		{0, 278, 97},
		{0, 97, 88},
		{0, 88, 211},
		{0, 211, 65},
	};

	@Test
	@DisplayName("Committed base index must survive a transactional relocation batch without in-place corruption")
	void shouldNotCorruptCommittedBaseWhenTransactionStealsFromCommittedSibling() {
		final ChainIndex index = new ChainIndex(new AttributeIndexKey(null, "a", null));
		for (int i = 0; i < INITIAL_STATE.length; i++) {
			final int pk = INITIAL_STATE[i];
			final Predecessor predecessor = i == 0 ? Predecessor.HEAD : new Predecessor(INITIAL_STATE[i - 1]);
			index.upsertPredecessor(predecessor, pk);
		}
		assertEquals(ConsistencyState.CONSISTENT, index.getConsistencyReport().state(), "base built inconsistently");
		final int[] baseArrayBefore = index.getUnorderedLookup().getArray();

		assertStateAfterCommit(
			index,
			original -> {
				for (final int[] op : OPS) {
					if (op[0] == 0) {
						original.upsertPredecessor(new Predecessor(op[1]), op[2]);
					} else {
						original.removePredecessor(op[2]);
					}
				}
			},
			(original, committed) -> {
				// the committed base (original) must NOT have been mutated in place by the transaction
				assertArrayEquals(baseArrayBefore, original.getUnorderedLookup().getArray(),
					"committed base array mutated in place by the transaction");
				assertEquals(ConsistencyState.CONSISTENT, original.getConsistencyReport().state(),
					"committed base corrupted by the transaction: " + original.getConsistencyReport());
				// the committed result itself must be consistent too
				assertEquals(ConsistencyState.CONSISTENT, committed.getConsistencyReport().state(),
					"committed result inconsistent: " + committed.getConsistencyReport());
			}
		);
	}
}
