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

package io.evitadb.core.transaction.memory;

import io.evitadb.core.exception.StaleTransactionMemoryException;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.map.TransactionalMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This class verifies {@link TransactionalMemory} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@Tag(ENGINE)
@Tag(TRANSACTION)
@DisplayName("Transactional memory commit / rollback / suppression contract")
class TransactionalMemoryTest {
	private HashMap<String, Integer> underlyingData1;
	private HashMap<String, Integer> underlyingData2;
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

		final HashMap<String, Map<String, Integer>> underlyingData3 = new LinkedHashMap<>();
		this.underlyingData3A = new LinkedHashMap<>();
		underlyingData3.put("a", this.underlyingData3A);
		this.underlyingData3B = new LinkedHashMap<>();
		underlyingData3.put("b", this.underlyingData3B);
		this.underlyingData3A.put("c", 3);
		this.underlyingData3B.put("d", 4);

		//noinspection unchecked
		this.tested3 = new TransactionalMap<>(new LinkedHashMap<>(), it -> new TransactionalMap<>((Map<String, Integer>) it));
		for (Entry<String, Map<String, Integer>> entry : underlyingData3.entrySet()) {
			this.tested3.put(entry.getKey(), new TransactionalMap<>(entry.getValue()));
		}
	}

	@Test
	@DisplayName("Commit applies all transactional changes atomically")
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
	@DisplayName("Commit applies nested transactional changes atomically")
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
	@DisplayName("Commit applies nested changes together with primary-map changes atomically")
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
	@DisplayName("Stale uncommitted diff pieces are detected on commit")
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

	@Test
	@DisplayName("Rollback leaves the original baseline objects untouched")
	void shouldRollbackLeavingOriginalsUntouched() {
		assertStateAfterRollback(
			List.of(this.tested1, this.tested2),
			original -> {
				original.get(0).put("c", 3);
				original.get(1).put("c", 4);
			},
			(original, committed) -> {
				// originals must remain exactly as they were before the transaction
				assertNull(this.tested1.get("c"));
				assertNull(this.underlyingData1.get("c"));
				assertEquals(Integer.valueOf(1), this.underlyingData1.get("a"));
				assertEquals(Integer.valueOf(2), this.underlyingData1.get("b"));

				assertNull(this.tested2.get("c"));
				assertNull(this.underlyingData2.get("c"));
				assertEquals(Integer.valueOf(1), this.underlyingData2.get("a"));
				assertEquals(Integer.valueOf(2), this.underlyingData2.get("b"));

				// rollback handler reports null committed states
				assertNull(committed.get(0));
				assertNull(committed.get(1));
			}
		);
	}

	@Test
	@DisplayName("Transactional layer can be suppressed for a single object")
	void shouldSuppressTransactionalLayerForObject() {
		final TransactionalMemory transactionalMemory = new TransactionalMemory(new NoOpFinalizer());

		final String result = transactionalMemory.suppressTransactionalMemoryLayerForWithResult(
			this.tested1,
			suppressed -> {
				// suppressed object never yields a transactional layer inside the lambda
				assertNull(transactionalMemory.getTransactionalMemoryLayerIfExists(this.tested1));
				assertNull(transactionalMemory.getOrCreateTransactionalMemoryLayer(this.tested1));
				// a sibling object is unaffected and still gets its layer
				assertNotNull(transactionalMemory.getOrCreateTransactionalMemoryLayer(this.tested2));
				return "done";
			}
		);

		assertEquals("done", result);
		// once outside the suppress scope, the object may create its layer again
		assertNotNull(transactionalMemory.getOrCreateTransactionalMemoryLayer(this.tested1));
	}

	@Test
	@DisplayName("Maintained creators are suppressed via the creator maintainer")
	void shouldSuppressMaintainedCreatorsViaCreatorMaintainer() {
		final TransactionalMemory transactionalMemory = new TransactionalMemory(new NoOpFinalizer());
		final TransactionalMap<String, Integer> child = this.tested2;
		final MaintainingMap maintainer = new MaintainingMap(this.underlyingData1, List.of(child));

		transactionalMemory.suppressTransactionalMemoryLayerFor(
			maintainer,
			suppressed -> {
				// both the maintainer and its maintained child are suppressed
				assertNull(transactionalMemory.getOrCreateTransactionalMemoryLayer(maintainer));
				assertNull(transactionalMemory.getOrCreateTransactionalMemoryLayer(child));
			}
		);

		// outside the scope both can create their layers again
		assertNotNull(transactionalMemory.getOrCreateTransactionalMemoryLayer(child));
	}

	@Test
	@DisplayName("Suppressing a non-transactional-layer creator fails")
	void shouldFailSuppressWhenObjectIsNotTransactionalLayerCreator() {
		final TransactionalMemory transactionalMemory = new TransactionalMemory(new NoOpFinalizer());

		final GenericEvitaInternalError ex = assertThrows(
			GenericEvitaInternalError.class,
			() -> transactionalMemory.suppressTransactionalMemoryLayerFor(
				new Object(),
				suppressed -> fail("Lambda must not be executed.")
			)
		);
		assertTrue(ex.getPrivateMessage().contains("doesn't implement TransactionalLayerCreator nor TransactionalStateProducer"));
	}

	@Test
	@DisplayName("Suppressing a creator whose layer already exists fails")
	void shouldFailSuppressWhenLayerAlreadyExists() {
		final TransactionalMemory transactionalMemory = new TransactionalMemory(new NoOpFinalizer());
		// create the layer first so a transactional memory already exists for the object
		assertNotNull(transactionalMemory.getOrCreateTransactionalMemoryLayer(this.tested1));

		final GenericEvitaInternalError ex = assertThrows(
			GenericEvitaInternalError.class,
			() -> transactionalMemory.suppressTransactionalMemoryLayerFor(
				this.tested1,
				suppressed -> fail("Lambda must not be executed.")
			)
		);
		assertTrue(ex.getPrivateMessage().contains("already exists transactional memory"));
	}

	@Test
	@DisplayName("Layer creation is replayed after the transaction is extended")
	void shouldReplayLayerCreationAfterExtendTransaction() {
		final TransactionalMemory transactionalMemory = new TransactionalMemory(new NoOpFinalizer());
		// drive a commit so that further layer creation is forbidden
		transactionalMemory.commit();

		final GenericEvitaInternalError ex = assertThrows(
			GenericEvitaInternalError.class,
			() -> transactionalMemory.getOrCreateTransactionalMemoryLayer(this.tested1)
		);
		assertTrue(ex.getPrivateMessage().contains("already committed / rolled back"));

		// replay re-enables layer creation
		transactionalMemory.extendTransaction();
		assertNotNull(transactionalMemory.getOrCreateTransactionalMemoryLayer(this.tested1));
	}

	@Test
	@DisplayName("Nested state-copy without discarding the previous one is forbidden")
	void shouldForbidNestedCallToGetStateCopyWithoutDiscarding() {
		final TransactionalMemory transactionalMemory = new TransactionalMemory(new NoOpFinalizer());
		final TransactionalLayerMaintainer maintainer = transactionalMemory.getTransactionalLayerMaintainer();
		final NestedReentrantProducer producer = new NestedReentrantProducer(maintainer);

		final EvitaInvalidUsageException ex = assertThrows(
			EvitaInvalidUsageException.class,
			() -> maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(producer)
		);
		assertTrue(ex.getMessage().contains("nested way is not allowed"));
		// the inner re-entrant attempt is the one that failed
		assertTrue(producer.wasInnerCallAttempted());
	}

	@Test
	@DisplayName("Layer stays alive when its state is copied without discarding")
	void shouldKeepLayerAliveWhenStateCopiedWithoutDiscarding() {
		final TransactionalMemory transactionalMemory = new TransactionalMemory(new NoOpFinalizer());
		final TransactionalLayerMaintainer maintainer = transactionalMemory.getTransactionalLayerMaintainer();
		// register a transactional change so a diff layer exists for tested1
		assertNotNull(maintainer.getOrCreateTransactionalMemoryLayer(this.tested1));

		// copy without discarding must leave the layer ALIVE
		maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(this.tested1);

		// because the layer is still ALIVE, the sweep verification detects it as stale
		assertThrows(
			StaleTransactionMemoryException.class,
			maintainer::verifyLayerWasFullySwept
		);
	}

	@Test
	@DisplayName("A stale layer is detected by verifying the layer was fully swept")
	void shouldDetectStaleLayerViaVerifyLayerWasFullySwept() {
		final TransactionalMemory transactionalMemory = new TransactionalMemory(new NoOpFinalizer());
		final TransactionalLayerMaintainer maintainer = transactionalMemory.getTransactionalLayerMaintainer();
		// create a layer that is never copied / discarded
		assertNotNull(maintainer.getOrCreateTransactionalMemoryLayer(this.tested1));

		assertThrows(
			StaleTransactionMemoryException.class,
			maintainer::verifyLayerWasFullySwept
		);
	}

	/**
	 * No-op finalizer used to obtain a bare {@link TransactionalMemory} / {@link TransactionalLayerMaintainer} for
	 * the lower-level maintainer tests that do not assert commit / rollback behaviour.
	 */
	private static class NoOpFinalizer implements TransactionalLayerMaintainerFinalizer {

		@Override
		public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			// no-op
		}

		@Override
		public void rollback(@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause) {
			// no-op
		}
	}

	/**
	 * {@link TransactionalMap} variant that also declares maintained transactional creators so that the
	 * {@link TransactionalCreatorMaintainer} suppression branch can be exercised.
	 */
	private static class MaintainingMap extends TransactionalMap<String, Integer>
		implements TransactionalCreatorMaintainer {
		@Serial private static final long serialVersionUID = 1633083490173618827L;
		private final Collection<TransactionalLayerCreator<?>> maintained;

		MaintainingMap(
			@Nonnull Map<String, Integer> delegate,
			@Nonnull Collection<TransactionalLayerCreator<?>> maintained
		) {
			super(delegate);
			this.maintained = maintained;
		}

		@Nonnull
		@Override
		public Collection<TransactionalLayerCreator<?>> getMaintainedTransactionalCreators() {
			return this.maintained;
		}
	}

	/**
	 * Producer whose {@link #createCopyWithMergedTransactionalMemory(Void, TransactionalLayerMaintainer)} re-enters
	 * {@link TransactionalLayerMaintainer#getStateCopyWithCommittedChangesWithoutDiscardingState} on the same
	 * maintainer to trigger the nested-call guard.
	 */
	private static class NestedReentrantProducer implements TransactionalLayerProducer<Void, NestedReentrantProducer> {
		private static final long ID = TransactionalObjectVersion.SEQUENCE.nextId();
		private final TransactionalLayerMaintainer maintainer;
		private boolean innerCallAttempted;

		NestedReentrantProducer(@Nonnull TransactionalLayerMaintainer maintainer) {
			this.maintainer = maintainer;
		}

		boolean wasInnerCallAttempted() {
			return this.innerCallAttempted;
		}

		@Override
		public long getId() {
			return ID;
		}

		@Nullable
		@Override
		public Void createLayer() {
			return null;
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			// no diff layer is created for this producer, so there is nothing to remove
		}

		@Nonnull
		@Override
		public NestedReentrantProducer createCopyWithMergedTransactionalMemory(
			@Nullable Void layer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			this.innerCallAttempted = true;
			// re-entrant nested call must be rejected by the CAS guard
			this.maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(this);
			return this;
		}
	}

}
