/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.transactionalMemory;

import io.evitadb.core.Transaction;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.transactionalMemory.exception.StaleTransactionMemoryException;
import lombok.Getter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This class verifies {@link TransactionalMemory} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
class TransactionalMemoryTest {
	private static final UUID MOCK_SESSION_ID = UUID.randomUUID();
	private HashMap<String, Integer> underlyingData1;
	private HashMap<String, Integer> underlyingData2;
	private HashMap<String, Map<String, Integer>> underlyingData3;
	private HashMap<String, Integer> underlyingData3A;
	private HashMap<String, Integer> underlyingData3B;
	private TransactionalMap<String, Integer> tested1;
	private TransactionalMap<String, Integer> tested2;
	private TransactionalMap<String, Map<String, Integer>> tested3;

	@BeforeEach
	void setUp() {
		underlyingData1 = new LinkedHashMap<>();
		underlyingData1.put("a", 1);
		underlyingData1.put("b", 2);
		tested1 = new TransactionalMap<>(underlyingData1);

		underlyingData2 = new LinkedHashMap<>();
		underlyingData2.put("a", 1);
		underlyingData2.put("b", 2);
		tested2 = new TransactionalMap<>(underlyingData2);

		underlyingData3 = new LinkedHashMap<>();
		underlyingData3A = new LinkedHashMap<>();
		underlyingData3.put("a", underlyingData3A);
		underlyingData3B = new LinkedHashMap<>();
		underlyingData3.put("b", underlyingData3B);
		underlyingData3A.put("c", 3);
		underlyingData3B.put("d", 4);

		tested3 = new TransactionalMap<>(new LinkedHashMap<>());
		for (Entry<String, Map<String, Integer>> entry : underlyingData3.entrySet()) {
			tested3.put(entry.getKey(), new TransactionalMap<>(entry.getValue()));
		}

		TransactionalMemory.open(MOCK_SESSION_ID);
	}

	@AfterEach
	void tearDown() {
		TransactionalMemory.rollback(MOCK_SESSION_ID);
	}

	@Test
	void shouldControlCommitAtomicity() {
		TransactionalMemory.bindSession(
			MOCK_SESSION_ID,
			() -> {
				tested1.put("c", 3);
				tested2.put("c", 4);

				final TestTransactionalLayerConsumer consumer = new TestTransactionalLayerConsumer();
				TransactionalMemory.addTransactionCommitHandler(MOCK_SESSION_ID, consumer);
				TransactionalMemory.commit(MOCK_SESSION_ID);

				assertNull(tested1.get("c"));
				assertNull(underlyingData1.get("c"));
				assertEquals(Integer.valueOf(3), consumer.getCommited1().get("c"));

				assertNull(tested2.get("c"));
				assertNull(underlyingData2.get("c"));
				assertEquals(Integer.valueOf(4), consumer.getCommited2().get("c"));
			}
		);
	}

	@Test
	void shouldControlCommitAtomicityDeepWise() {
		TransactionalMemory.bindSession(
			MOCK_SESSION_ID,
			() -> {
				tested3.get("a").put("a", 1);
				tested3.get("b").put("b", 2);

				final TestTransactionalLayerConsumer consumer = new TestTransactionalLayerConsumer();
				TransactionalMemory.addTransactionCommitHandler(MOCK_SESSION_ID, consumer);
				TransactionalMemory.commit(MOCK_SESSION_ID);

				assertNull(tested3.get("a").get("a"));
				assertNull(underlyingData3A.get("a"));
				final Map<String, Integer> committed3A = consumer.getCommited3().get("a");
				assertFalse(committed3A instanceof TransactionalMap);
				assertEquals(Integer.valueOf(1), committed3A.get("a"));
				assertEquals(Integer.valueOf(3), committed3A.get("c"));

				assertNull(tested3.get("b").get("b"));
				assertNull(underlyingData3B.get("b"));
				final Map<String, Integer> committed3B = consumer.getCommited3().get("b");
				assertFalse(committed3B instanceof TransactionalMap);
				assertEquals(Integer.valueOf(2), committed3B.get("b"));
				assertEquals(Integer.valueOf(4), committed3B.get("d"));
			}
		);
	}

	@Test
	void shouldControlCommitAtomicityDeepWiseWithChangesToPrimaryMap() {
		TransactionalMemory.bindSession(
			MOCK_SESSION_ID,
			() -> {
				final TransactionalMap<String, Integer> newMap = new TransactionalMap<>(new HashMap<>());
				tested3.put("a", newMap);
				newMap.put("a", 99);
				tested3.remove("b");

				final TestTransactionalLayerConsumer consumer = new TestTransactionalLayerConsumer();
				TransactionalMemory.addTransactionCommitHandler(MOCK_SESSION_ID, consumer);
				TransactionalMemory.commit(MOCK_SESSION_ID);

				assertNull(tested3.get("a").get("a"));
				assertNull(underlyingData3A.get("a"));
				final Map<String, Integer> committed3A = consumer.getCommited3().get("a");
				assertFalse(committed3A instanceof TransactionalMap);
				assertEquals(Integer.valueOf(99), committed3A.get("a"));

				assertNull(tested3.get("b").get("b"));
				assertNull(underlyingData3B.get("b"));
				final Map<String, Integer> committed3B = consumer.getCommited3().get("b");
				assertNull(committed3B);
			}
		);
	}

	@Test
	void shouldCheckStaleItems() {
		TransactionalMemory.bindSession(
			MOCK_SESSION_ID,
			() -> {
				final TestTransactionalMemoryProducer testProducer = new TestTransactionalMemoryProducer();
				testProducer.changeState();
				assertThrows(StaleTransactionMemoryException.class, () -> TransactionalMemory.commit(MOCK_SESSION_ID));
			}
		);
	}

	private static class TestTransactionalMemoryProducer implements TransactionalLayerProducer<FakeLayer, TestTransactionalMemoryProducer> {
		@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

		@Nonnull
		@Override
		public TestTransactionalMemoryProducer createCopyWithMergedTransactionalMemory(FakeLayer layer, @Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Transaction transaction) {
			return new TestTransactionalMemoryProducer();
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {

		}

		@Override
		public FakeLayer createLayer() {
			return new FakeLayer();
		}

		public void changeState() {
			TransactionalMemory.getTransactionalMemoryLayer(this);
		}
	}

	private static class FakeLayer {

	}

	private class TestTransactionalLayerConsumer implements TransactionalLayerConsumer {
		@Getter private Map<String, Integer> commited1;
		@Getter private Map<String, Integer> commited2;
		@Getter private Map<String, Map<String, Integer>> commited3;

		@Override
		public void collectTransactionalChanges(TransactionalLayerMaintainer transactionalLayer) {
			this.commited1 = transactionalLayer.getStateCopyWithCommittedChanges(tested1, null);
			this.commited2 = transactionalLayer.getStateCopyWithCommittedChanges(tested2, null);
			this.commited3 = transactionalLayer.getStateCopyWithCommittedChanges(tested3, null);
		}

	}

}
