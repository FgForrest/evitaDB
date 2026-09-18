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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the invariant that actually protects range queries inside a transaction.
 *
 * A range formula built inside an open transaction carries the SAME {@link Formula#getHash()} as the one built
 * over the committed view - `TransactionalBitmap#getId()` is a per-instance sequence value the overlay does not
 * change, so neither `includeAdditionalHash` nor `gatherBitmapIdsInternal` can tell the two apart. That collision
 * is demonstrated below and is NOT a defect, because the formula cache is never consulted in that situation:
 * `HeapMemoryCacheSupervisor#analyse` returns the formula untouched for any session that is not read-only, and
 * `EvitaSession#createTransaction` asserts `!isReadOnly()`, so an open transaction implies a read-write session
 * implies no cache.
 *
 * The single `if (evitaSession.isReadOnly())` in `HeapMemoryCacheSupervisor#analyse` is therefore the whole
 * defence: remove it and the hashes would silently agree, and a write session would be served its own
 * pre-transaction answer.
 *
 * **What this test does and does not cover.** It pins the collision - the premise that makes that guard
 * load-bearing - at the formula level. It does NOT exercise `HeapMemoryCacheSupervisor` and would therefore NOT
 * fail if the read-only gate were deleted. Closing that gap needs a session-level test driving a real
 * `Evita` instance with the cache enabled, and it is worth writing precisely because one `if` is all that stands
 * between here and a wrong answer.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(CACHE)
@Tag(TRANSACTION)
@DisplayName("Range query formula-cache key across the transactional boundary")
class RangeFormulaCacheKeyProbeTest {

	@Test
	@DisplayName("The formula cache is bypassed for read-write sessions, which is what makes the hash collision harmless")
	void shouldBypassFormulaCacheForReadWriteSessions() {
		final RangeIndex tested = new RangeIndex();
		// enough points on both sides of the threshold that the prefix carries several starts AND several ends,
		// so the query really builds Join -> Disentangle rather than collapsing to a ConstantFormula
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

				System.out.println("[probe] committed formula   = " + committed);
				System.out.println("[probe] in-tx formula       = " + inTx);
				System.out.println("[probe] committed hash      = " + committedHash);
				System.out.println("[probe] in-tx     hash      = " + inTx.getHash());
				System.out.println("[probe] committed txIdHash  = " + committedTxIdHash);
				System.out.println("[probe] in-tx     txIdHash  = " + inTx.getTransactionalIdHash());
				System.out.println("[probe] committed result    = " + Arrays.toString(committedResult));
				System.out.println("[probe] in-tx     result    = " + Arrays.toString(inTxResult));

				assertFalse(
					Arrays.equals(committedResult, inTxResult),
					"The fixture is wrong if the transaction did not change the answer - nothing is being probed! " +
						"committed=" + Arrays.toString(committedResult) + " inTx=" + Arrays.toString(inTxResult)
				);
				// documents the collision rather than forbidding it - the hashes DO agree, and that is exactly why
				// the read-only gate below is load-bearing
				assertEquals(
					committedHash, inTx.getHash(),
					"Expected the transactional and committed views to hash identically (TransactionalBitmap ids are " +
						"stable across the overlay). If this ever stops holding, the reasoning in this test's javadoc " +
						"needs revisiting - it is not automatically good news."
				);
			},
			(original, committedVersion) -> assertNull(committedVersion)
		);
	}
}
