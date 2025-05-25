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

package io.evitadb.index.transactionalMemory;

import io.evitadb.core.exception.StaleTransactionMemoryException;
import io.evitadb.core.transaction.memory.TransactionalMemory;
import io.evitadb.index.map.TransactionalMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This class verifies {@link TransactionalMemory} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
class TransactionalMemoryTest {
	private HashMap<String, Integer> underlyingData1;
	private HashMap<String, Integer> underlyingData2;
	private HashMap<String, Map<String, Integer>> underlyingData3;
	private HashMap<String, Integer> underlyingData3A;
	private HashMap<String, Integer> underlyingData3B;
	private TransactionalMap<String, Integer> tested1;
	private TransactionalMap<String, Integer> tested2;
	private TransactionalMap<String, TransactionalMap<String, Integer>> tested3;

	@BeforeEach
	void setUp() {
		this.underlyingData1 = new LinkedHashMap<>();
		this.underlyingData1.put("a", 1);
		this.underlyingData1.put("b", 2);
		this.tested1 = new TransactionalMap<>(this.underlyingData1);

		this.underlyingData2 = new LinkedHashMap<>();
		this.underlyingData2.put("a", 1);
		this.underlyingData2.put("b", 2);
		this.tested2 = new TransactionalMap<>(this.underlyingData2);

		this.underlyingData3 = new LinkedHashMap<>();
		this.underlyingData3A = new LinkedHashMap<>();
		this.underlyingData3.put("a", this.underlyingData3A);
		this.underlyingData3B = new LinkedHashMap<>();
		this.underlyingData3.put("b", this.underlyingData3B);
		this.underlyingData3A.put("c", 3);
		this.underlyingData3B.put("d", 4);

		//noinspection unchecked
		this.tested3 = new TransactionalMap<>(new LinkedHashMap<>(), it -> new TransactionalMap<>((Map<String, Integer>) it));
		for (Entry<String, Map<String, Integer>> entry : this.underlyingData3.entrySet()) {
			this.tested3.put(entry.getKey(), new TransactionalMap<>(entry.getValue()));
		}
	}

	@Test
	void shouldControlCommitAtomicity() {
		assertStateAfterCommit(
			List.of(this.tested1, this.tested2),
			(original) -> {
				original.get(0).put("c", 3);
				original.get(1).put("c", 4);
			},
			(original, committed) -> {
				assertNull(this.tested1.get("c"));
				assertNull(this.underlyingData1.get("c"));
				assertEquals(3, committed.get(0).get("c"));

				assertNull(this.tested2.get("c"));
				assertNull(this.underlyingData2.get("c"));
				assertEquals(4, committed.get(1).get("c"));
			}
		);
	}

	@Test
	void shouldControlCommitAtomicityDeepWise() {
		assertStateAfterCommit(
			this.tested3,
			original -> {
				original.get("a").put("a", 1);
				original.get("b").put("b", 2);
			},
			(original, committed) -> {
				assertNull(this.tested3.get("a").get("a"));
				assertNull(this.underlyingData3A.get("a"));
				final Map<String, Integer> committed3A = committed.get("a");
				assertInstanceOf(TransactionalMap.class, committed3A);
				assertEquals(Integer.valueOf(1), committed3A.get("a"));
				assertEquals(Integer.valueOf(3), committed3A.get("c"));

				assertNull(this.tested3.get("b").get("b"));
				assertNull(this.underlyingData3B.get("b"));
				final Map<String, Integer> committed3B = committed.get("b");
				assertInstanceOf(TransactionalMap.class, committed3B);
				assertEquals(Integer.valueOf(2), committed3B.get("b"));
				assertEquals(Integer.valueOf(4), committed3B.get("d"));
			}
		);
	}

	@Test
	void shouldControlCommitAtomicityDeepWiseWithChangesToPrimaryMap() {
		assertStateAfterCommit(
			this.tested3,
			original -> {
				final TransactionalMap<String, Integer> newMap = new TransactionalMap<>(new HashMap<>());
				original.put("a", newMap);
				newMap.put("a", 99);
				original.remove("b");
			},
			(original, committed) -> {
				assertNull(this.tested3.get("a").get("a"));
				assertNull(this.underlyingData3A.get("a"));
				final Map<String, Integer> committed3A = committed.get("a");
				assertInstanceOf(TransactionalMap.class, committed3A);
				assertEquals(Integer.valueOf(99), committed3A.get("a"));

				assertNull(this.tested3.get("b").get("b"));
				assertNull(this.underlyingData3B.get("b"));
				final Map<String, Integer> committed3B = committed.get("b");
				assertNull(committed3B);
			}
		);
	}

	@Test
	void shouldCheckStaleItems() {
		assertThrows(StaleTransactionMemoryException.class, () -> {
			assertStateAfterCommit(
				this.tested1,
				original -> {
					original.put("c", 3);
					// this should make stale transaction memory exception since it made transactional changes
					// which are not tracked (tested2 was not passed in the first argument of assertStateAfterCommit)
					this.tested2.put("c", 4);
				},
				(original, committed) -> {
					fail("Should not be committed");
				}
			);
		});
	}

}
