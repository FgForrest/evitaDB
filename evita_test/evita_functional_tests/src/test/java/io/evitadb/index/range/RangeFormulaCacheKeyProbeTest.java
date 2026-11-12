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

package io.evitadb.index.range;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies range formula identity across a transactional overlay. A transactional bitmap retains its identity
 * while the overlay changes its contents, so formulas with the same hash can compute different transaction-local
 * results. Rollback restores the committed result.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(CACHE)
@Tag(TRANSACTION)
@DisplayName("Range query formula-cache key across the transactional boundary")
class RangeFormulaCacheKeyProbeTest {

	/**
	 * Verifies the value key's documented domain around transaction-local bitmap contents.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Declared formula key across a transactional overlay")
	class KeyTest {
		/** Stable producer ids name committed data, while an open overlay can compute another result. */
		@Test
		@DisplayName("Overlay changes the result while producer-based keys remain equal")
		void shouldRetainKeyAcrossTransactionalOverlay() {
			final RangeIndex index = new RangeIndex();
			index.addRecord(100L, 200L, 1);
			index.addRecord(110L, 150L, 2);
			index.addRecord(120L, 400L, 3);
			index.addRecord(130L, 140L, 4);
			final Formula committed = index.getRecordsTo(160L);
			final int[] before = committed.compute().getArray();
			final FormulaKey key = committed.getKey();
			assertStateAfterRollback(index, original -> {
				original.addRecord(100L, 300L, 6);
				final Formula overlay = original.getRecordsTo(160L);
				assertFalse(Arrays.equals(before, overlay.compute().getArray()));
				assertEquals(key, overlay.getKey());
				assertEquals(committed.getKey().getHash64(), overlay.getKey().getHash64());
			}, (original, discarded) -> {
				assertNull(discarded);
				assertEquals(key, original.getRecordsTo(160L).getKey());
				assertArrayEquals(before, original.getRecordsTo(160L).compute().getArray());
			});
		}
	}

	/**
	 * Verifies the hash and transactional ids consumed by production.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Legacy hash consumed by the formula deduplicator")
	class LegacyHashTest {
		@Test
		@DisplayName("Transactional overlays retain formula identity while computing transaction-local results")
		void shouldRetainFormulaIdentityAcrossTransactionalOverlay() {
			final RangeIndex tested = new RangeIndex();
			// enough points on both sides of the threshold that the prefix carries several starts AND several ends, so
			// the query really builds a RangeCountFormula rather than collapsing to a ConstantFormula or an OrFormula
			tested.addRecord(100L, 200L, 1);
			tested.addRecord(110L, 150L, 2);
			tested.addRecord(120L, 400L, 3);
			tested.addRecord(130L, 140L, 4);

			final Formula committed = tested.getRecordsTo(160L);
			final long committedHash = committed.getHash();
			final long committedTxIdHash = committed.getTransactionalIdHash();
			final int[] committedResult = committed.compute().getArray();

			assertStateAfterRollback(
				tested,
				original -> {
					// deliberately NOT a new threshold inside the scanned prefix: record 6 starts at the EXISTING point
					// 100 and ends at 300, which is past the 160 threshold and therefore outside the prefix entirely.
					// The prefix's operand LIST is thus unchanged in length and membership - only the contents of the
					// `starts` bitmap at point 100 differ. That is what isolates "does a mutated bitmap change the
					// formula key" from the trivial "did the operand count change".
					original.addRecord(100L, 300L, 6);
					final Formula inTx = original.getRecordsTo(160L);
					final int[] inTxResult = inTx.compute().getArray();

					assertFalse(
						Arrays.equals(committedResult, inTxResult),
						"The fixture is wrong if the transaction did not change the answer - nothing is being probed! " +
							"committed=" + Arrays.toString(committedResult) + " inTx=" + Arrays.toString(inTxResult)
					);
					// The overlay changes results while preserving bitmap identity.
					assertEquals(
						committedHash, inTx.getHash(),
						() -> "Expected the transactional and committed views to hash identically (TransactionalBitmap ids " +
							"are stable across the overlay). If this ever stops holding, the reasoning in this test's " +
							"javadoc needs revisiting - it is not automatically good news. committed=" + committed +
							" (hash " + committedHash + ", txIdHash " + committedTxIdHash + "), inTx=" + inTx +
							" (hash " + inTx.getHash() + ", txIdHash " + inTx.getTransactionalIdHash() + ")"
					);
				},
				(original, committedVersion) -> assertNull(committedVersion)
			);
		}
	}

}
