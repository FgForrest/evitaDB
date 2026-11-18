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

package io.evitadb.index;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.time.Duration;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Bounds the cost of allocating the ids of {@link VirtualReferencePartitionIndex} representative tuples by wall-clock
 * time. The functional `VirtualReferencePartitionIndexTest` covers what the ids are; this class covers that allocating
 * them does not walk the tuple dictionary. A wall-clock bound under the parallel forks of the fast loop is a flake
 * risk - it failed there on loaded CI runners - so it runs in this module.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition index allocates tuple ids")
@Tag(INDEXING)
@Tag(REFERENCE)
class LongRunningVirtualReferencePartitionIndexTest {

	/**
	 * Allocating an id by a walk over the dictionary costs `O(T)` per new tuple, so building `N` distinct tuples
	 * costs `O(N²)` - around 2·10¹⁰ map visits here, which takes minutes. An `O(1)` allocation builds them in well
	 * under a second, so the bound sits more than an order of magnitude away from both. There is no seam to count
	 * the visits through, hence the wall-clock bound.
	 */
	@Test
	@DisplayName("builds many distinct tuples in linear time")
	void shouldBuildManyDistinctTuplesInLinearTime() {
		final int tupleCount = 200_000;
		final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
		assertTimeoutPreemptively(
			Duration.ofSeconds(10),
			() -> {
				for (int partition = 1; partition <= tupleCount; partition++) {
					index.putRepresentativeTuple(partition, new Serializable[]{partition});
				}
			},
			"Building distinct tuples must not walk the dictionary per new tuple"
		);
		assertEquals(tupleCount, index.getTupleId(new Serializable[]{tupleCount}));
	}

}
