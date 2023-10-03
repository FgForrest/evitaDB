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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
	private Transaction transaction;

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

		tested3 = new TransactionalMap<>(new LinkedHashMap<>(), TransactionalMap::new);
		for (Entry<String, Map<String, Integer>> entry : underlyingData3.entrySet()) {
			tested3.put(entry.getKey(), new TransactionalMap<>(entry.getValue()));
		}

		transaction = Transaction.createMockTransactionForTests();
	}

	@AfterEach
	void tearDown() {
		transaction.setRollbackOnly();
		transaction.close();
	}

	@Test
	void shouldControlCommitAtomicity() {
		final TestTransactionalLayerConsumer consumer = new TestTransactionalLayerConsumer();
		Transaction.executeInTransactionIfProvided(
			transaction,
			() -> {
				tested1.put("c", 3);
				tested2.put("c", 4);

				transaction.addTransactionCommitHandler(consumer);
			}
		);

		transaction.close();

		assertNull(tested1.get("c"));
		assertNull(underlyingData1.get("c"));
		assertEquals(Integer.valueOf(3), consumer.getCommited1().get("c"));

		assertNull(tested2.get("c"));
		assertNull(underlyingData2.get("c"));
		assertEquals(Integer.valueOf(4), consumer.getCommited2().get("c"));
	}

	@Test
	void shouldControlCommitAtomicityDeepWise() {
		final TestTransactionalLayerConsumer consumer = new TestTransactionalLayerConsumer();
		Transaction.executeInTransactionIfProvided(
			transaction,
			() -> {
				tested3.get("a").put("a", 1);
				tested3.get("b").put("b", 2);

				transaction.addTransactionCommitHandler(consumer);
			}
		);

		transaction.close();

		assertNull(tested3.get("a").get("a"));
		assertNull(underlyingData3A.get("a"));
		final Map<String, Integer> committed3A = consumer.getCommited3().get("a");
		assertTrue(committed3A instanceof TransactionalMap);
		assertEquals(Integer.valueOf(1), committed3A.get("a"));
		assertEquals(Integer.valueOf(3), committed3A.get("c"));

		assertNull(tested3.get("b").get("b"));
		assertNull(underlyingData3B.get("b"));
		final Map<String, Integer> committed3B = consumer.getCommited3().get("b");
		assertTrue(committed3B instanceof TransactionalMap);
		assertEquals(Integer.valueOf(2), committed3B.get("b"));
		assertEquals(Integer.valueOf(4), committed3B.get("d"));
	}

	@Test
	void shouldControlCommitAtomicityDeepWiseWithChangesToPrimaryMap() {
		final TestTransactionalLayerConsumer consumer = new TestTransactionalLayerConsumer();
		Transaction.executeInTransactionIfProvided(
			transaction,
			() -> {
				final TransactionalMap<String, Integer> newMap = new TransactionalMap<>(new HashMap<>());
				tested3.put("a", newMap);
				newMap.put("a", 99);
				tested3.remove("b");

				transaction.addTransactionCommitHandler(consumer);
			}
		);

		transaction.close();

		assertNull(tested3.get("a").get("a"));
		assertNull(underlyingData3A.get("a"));
		final Map<String, Integer> committed3A = consumer.getCommited3().get("a");
		assertTrue(committed3A instanceof TransactionalMap);
		assertEquals(Integer.valueOf(99), committed3A.get("a"));

		assertNull(tested3.get("b").get("b"));
		assertNull(underlyingData3B.get("b"));
		final Map<String, Integer> committed3B = consumer.getCommited3().get("b");
		assertNull(committed3B);
	}

	@Test
	void shouldCheckStaleItems() {
		Transaction.executeInTransactionIfProvided(
			transaction,
			() -> {
				final TestTransactionalMemoryProducer testProducer = new TestTransactionalMemoryProducer();
				testProducer.changeState();
			}
		);

		assertThrows(StaleTransactionMemoryException.class, () -> transaction.close());
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
			Transaction.getTransactionalMemoryLayer(this);
		}
	}

	private static class FakeLayer {

	}

	private class TestTransactionalLayerConsumer implements TransactionalLayerConsumer {
		@Getter private Map<String, Integer> commited1;
		@Getter private Map<String, Integer> commited2;
		@Getter private Map<String, TransactionalMap<String, Integer>> commited3;

		@Override
		public void collectTransactionalChanges(TransactionalLayerMaintainer transactionalLayer) {
			this.commited1 = transactionalLayer.getStateCopyWithCommittedChanges(tested1, null);
			this.commited2 = transactionalLayer.getStateCopyWithCommittedChanges(tested2, null);
			this.commited3 = transactionalLayer.getStateCopyWithCommittedChanges(tested3, null);
		}

	}

}
