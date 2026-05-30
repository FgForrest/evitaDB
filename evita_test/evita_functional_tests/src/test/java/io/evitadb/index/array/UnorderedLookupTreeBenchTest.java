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

package io.evitadb.index.array;

import io.evitadb.index.bPlusTree.TransactionalIntToLongBPlusTree;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Random;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Direct, no-evita-stack micro-benchmark of the new two-tree backing (the count-augmented position tree
 * {@link UnorderedLookupTree} paired with the no-boxing `int → long` value index
 * {@link TransactionalIntToLongBPlusTree}) — exactly the pair that will sit behind `TransactionalUnorderedIntArray`
 * once wiring is complete. Drives a single 10M chain build followed by 10M predecessor-churn updates to demonstrate
 * the linear (non `O(N²)`) write behaviour the array delegate cannot achieve.
 *
 * Tagged {@link io.evitadb.test.TestTags#SLOW} so it stays out of the default suite; run explicitly with
 * `-Dtest=UnorderedLookupTreeBenchTest`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Slf4j
class UnorderedLookupTreeBenchTest {

	/**
	 * Pairs the position tree with the real value index, mirroring the non-transactional coordination the composite
	 * façade will perform. The order-key consumer writes every assignment into the value index (overwrite semantics).
	 */
	private static final class CompositeIndex implements OrderKeyConsumer {
		@Nonnull final UnorderedLookupTree positionTree = new UnorderedLookupTree();
		@Nonnull final TransactionalIntToLongBPlusTree valueIndex = new TransactionalIntToLongBPlusTree();

		@Override
		public void accept(int recordId, long orderKey) {
			this.valueIndex.insert(recordId, orderKey);
		}

		void addHead(int recordId) {
			this.positionTree.insertAtPosition(0, recordId, this);
		}

		void addAfter(int previousRecordId, int recordId) {
			this.positionTree.insertAfter(this.valueIndex.search(previousRecordId).orElseThrow(), previousRecordId, recordId, this);
		}

		void remove(int recordId) {
			this.positionTree.removeByOrderKey(this.valueIndex.search(recordId).orElseThrow(), recordId, this);
			this.valueIndex.delete(recordId);
		}

		int findPosition(int recordId) {
			return this.positionTree.findPositionByOrderKey(this.valueIndex.search(recordId).orElseThrow(), recordId);
		}

		int size() {
			return this.positionTree.size();
		}
	}

	@Tag(SLOW)
	@Test
	void shouldBuildAndChurnTenMillionChain() {
		final int recordCount = 10_000_000;
		final int churnOperations = 10_000_000;
		final CompositeIndex index = new CompositeIndex();

		// 1. build a single chain 1 -> 2 -> ... -> N via individual "writes"
		final long buildStart = System.nanoTime();
		index.addHead(1);
		for (int recordId = 2; recordId <= recordCount; recordId++) {
			index.addAfter(recordId - 1, recordId);
		}
		final long buildNanos = System.nanoTime() - buildStart;
		assertEquals(recordCount, index.size());

		// 2. churn: repeatedly move a random record to sit after another random record (a predecessor update)
		final Random random = new Random(42);
		final long churnStart = System.nanoTime();
		for (int op = 0; op < churnOperations; op++) {
			final int moved = 1 + random.nextInt(recordCount);
			int anchor = 1 + random.nextInt(recordCount);
			if (anchor == moved) {
				anchor = moved == recordCount ? moved - 1 : moved + 1;
			}
			index.remove(moved);
			index.addAfter(anchor, moved);
		}
		final long churnNanos = System.nanoTime() - churnStart;

		// 3. correctness sanity - the set is invariant, positions remain coherent
		assertEquals(recordCount, index.size());
		assertEquals(recordCount, index.positionTree.getArray().length);
		final int probe = index.positionTree.getRecordAt(recordCount / 2);
		assertEquals(recordCount / 2, index.findPosition(probe));

		final double buildSeconds = buildNanos / 1_000_000_000.0;
		final double churnSeconds = churnNanos / 1_000_000_000.0;
		log.info(
			"\n=== UnorderedLookupTree + value index 10M bench ===\n" +
				String.format("Build  : %,d records in %.2fs  (%,.0f writes/s)%n", recordCount, buildSeconds, recordCount / buildSeconds) +
				String.format("Churn  : %,d updates in %.2fs  (%,.0f updates/s)%n", churnOperations, churnSeconds, churnOperations / churnSeconds)
		);
	}

}
