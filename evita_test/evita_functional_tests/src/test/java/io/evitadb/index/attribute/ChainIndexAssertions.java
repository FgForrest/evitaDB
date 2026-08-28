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

package io.evitadb.index.attribute;

import io.evitadb.index.array.TransactionalUnorderedIntArray;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assertions over {@link ChainIndex} internals that its public read paths cannot express, shared by every suite that
 * needs them. Lives in the index's own package because it reads {@link ChainIndex#elements} and
 * {@link ChainIndex#chains} directly, which is the whole reason it cannot sit in a generic assertion utility.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see ChainIndex
 */
final class ChainIndexAssertions {

	private ChainIndexAssertions() {
		throw new UnsupportedOperationException("Assertion holder must not be instantiated.");
	}

	/**
	 * Cross-checks the element array's internal chain-head bitmask against the authoritative head set
	 * ({@link ChainIndex#chains} keys): every chain head must be marked at its own position and no other position may
	 * be marked. This is the one invariant the index's read paths cannot see - getUnorderedLookup and
	 * getConsistencyReport resolve heads by {@code indexOf} and never consult the bitmask - so a missing or stray
	 * {@code markAsHead}/{@code unmarkAsHead} at any mutation site would stay invisible until a later {@code findRun}
	 * trips over it. Asserting it directly pins a head-mark desync to the operation that caused it. A single `O(N·log N)`
	 * walk catches both directions: a missing mark makes {@code findHeadCovering} return an earlier head; a stray mark
	 * makes it return the stray position.
	 *
	 * @param index the index whose head marks are to be cross-checked
	 */
	static void assertHeadMarksMatchChains(@Nonnull ChainIndex index) {
		final int length = index.elements.getLength();
		if (length == 0) {
			assertEquals(0, index.chains.size(), "Empty element array must carry no chain descriptors.");
			return;
		}
		final boolean[] expectedHead = new boolean[length];
		for (final Integer headPk : index.chains.keySet()) {
			expectedHead[index.elements.indexOf(headPk)] = true;
		}
		assertTrue(expectedHead[0], "Logical position 0 must be a chain head.");
		int expectedCovering = -1;
		for (int p = 0; p < length; p++) {
			if (expectedHead[p]) {
				expectedCovering = p;
			}
			final TransactionalUnorderedIntArray.HeadLocation head = index.elements.findHeadCovering(p);
			assertEquals(
				expectedCovering, head.headPosition(),
				"Head-mark bitmask disagrees with chains.keySet() at position " + p
			);
			assertEquals(
				index.elements.get(expectedCovering), head.recordId(),
				"Covering-head record id mismatch at position " + p
			);
		}
	}

}
