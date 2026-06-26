/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.buffer;

import com.carrotsearch.hppc.LongObjectMap;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.Scope;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;

/**
 * Generational randomized backfill proof that {@link DataStoreChanges} — the storage diff layer produced by
 * {@code Catalog} / {@code EntityCollection} — snapshots and restores its revertable in-memory state correctly under a
 * per-entity savepoint. Only two fields are revertable inside a savepoint: the dirty-index tracking
 * (populated as the index executor marks indexes dirty via {@code getOrCreateIndexForModification}) and the
 * trapped-storage-part cache (populated via {@code trapPutStoragePart}); actual storage parts are written by the storage
 * executor only at its `commit()`, after the savepoint has already committed, so they never need reverting here. This
 * test drives exactly those two paths.
 *
 * Lightweight stand-ins are used — a marker {@link IndexKey} / {@link Index} (the layer captures only *which* indexes are
 * dirty, by reference, never their contents) and a minimal {@link StoragePart} — behind a minimal {@link LayerHolder}
 * {@link TransactionalLayerProducer} so the maintainer's savepoint snapshot / restore is exercised against a real
 * {@link DataStoreChanges}. Each generation applies a random baseline batch (must survive), then a random in-savepoint
 * batch with a guaranteed-new marker (must revert on rollback / be kept on commit). The oracle pairs the dirty-index key
 * set with the trapped-part keys (read reflectively). The transaction is committed at the end so the layer-sweep
 * verification proves the restore left no dangling layer. The run is time-bounded; the random seed is echoed on failure
 * for deterministic reproduction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("DataStoreChanges savepoint rollback/commit backfill (generational fuzz)")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(TRANSACTION)
class LongRunningSavepointDataStoreChangesTest implements TimeBoundedTestSupport {
	private static final int KEY_SPACE = 32;
	private static final int MARKER = 100_000;
	private static final int MAX_OPS = 8;

	@ParameterizedTest(name = "Savepoint rollback restores dirty-index tracking and trapped storage parts")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint rollback restores dirty-index tracking and trapped storage parts")
	void shouldRollBackDataStoreChanges(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final LayerHolder holder = new LayerHolder();
			assertSavepointRollbackRestores(
				holder,
				tested -> applyRandomOps(tested, random, 1 + random.nextInt(MAX_OPS)),
				LongRunningSavepointDataStoreChangesTest::readState,
				tested -> {
					// a marker dirty index + trapped part guarantees a non-vacuous in-savepoint batch
					final DataStoreChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(tested);
					markIndexDirty(layer, MARKER);
					trapPart(layer, MARKER);
					applyRandomOps(tested, random, 1 + random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "Savepoint commit keeps dirty-index tracking and trapped storage parts")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint commit keeps dirty-index tracking and trapped storage parts")
	void shouldCommitDataStoreChanges(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final LayerHolder holder = new LayerHolder();
			assertSavepointCommitKeeps(
				holder,
				tested -> applyRandomOps(tested, random, 1 + random.nextInt(MAX_OPS)),
				LongRunningSavepointDataStoreChangesTest::readState,
				tested -> applyRandomOps(tested, random, 1 + random.nextInt(MAX_OPS))
			);
			return iteration + 1;
		});
	}

	/**
	 * Applies `count` random operations: mark a random index dirty, or trap a random storage part.
	 */
	private static void applyRandomOps(@Nonnull LayerHolder holder, @Nonnull Random random, int count) {
		final DataStoreChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(holder);
		for (int i = 0; i < count; i++) {
			final int id = random.nextInt(KEY_SPACE);
			if (random.nextBoolean()) {
				markIndexDirty(layer, id);
			} else {
				trapPart(layer, id);
			}
		}
	}

	private static void markIndexDirty(@Nonnull DataStoreChanges layer, int id) {
		final StubIndexKey key = new StubIndexKey(id);
		layer.getOrCreateIndexForModification(key, StubIndex::new);
	}

	private static void trapPart(@Nonnull DataStoreChanges layer, int id) {
		layer.trapPutStoragePart(new StubStoragePart((long) id));
	}

	/**
	 * Reads the layer's revertable state — the dirty-index keys and the trapped-part keys — into an `.equals`-comparable
	 * value (empty when the layer was never created).
	 */
	@Nonnull
	private static DataStoreState readState(@Nonnull LayerHolder holder) {
		final DataStoreChanges layer = Transaction.getTransactionalMemoryLayerIfExists(holder);
		if (layer == null) {
			return new DataStoreState(List.of(), List.of());
		}
		return new DataStoreState(dirtyIndexKeys(layer), trappedPartKeys(layer));
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	private static List<Integer> dirtyIndexKeys(@Nonnull DataStoreChanges layer) {
		try {
			final Field field = DataStoreChanges.class.getDeclaredField("dirtyEntityIndexes");
			field.setAccessible(true);
			final Map<IndexKey, ?> map = (Map<IndexKey, ?>) field.get(layer);
			final List<Integer> ids = new ArrayList<>(map.size());
			for (final IndexKey key : map.keySet()) {
				ids.add(((StubIndexKey) key).id());
			}
			ids.sort(Integer::compareTo);
			return ids;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	private static List<Long> trappedPartKeys(@Nonnull DataStoreChanges layer) {
		try {
			final Field field = DataStoreChanges.class.getDeclaredField("trappedChanges");
			field.setAccessible(true);
			final Map<Class<? extends StoragePart>, LongObjectMap<StoragePart>> trapped =
				(Map<Class<? extends StoragePart>, LongObjectMap<StoragePart>>) field.get(layer);
			if (trapped == null) {
				return List.of();
			}
			final List<Long> keys = new ArrayList<>();
			for (final LongObjectMap<StoragePart> inner : trapped.values()) {
				for (final com.carrotsearch.hppc.cursors.LongObjectCursor<StoragePart> cursor : inner) {
					keys.add(cursor.key);
				}
			}
			keys.sort(Long::compareTo);
			return keys;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * `.equals`-comparable snapshot of the two revertable fields.
	 *
	 * @param dirtyIndexKeys the sorted dirty-index key ids
	 * @param trappedKeys    the sorted trapped storage-part keys
	 */
	private record DataStoreState(@Nonnull List<Integer> dirtyIndexKeys, @Nonnull List<Long> trappedKeys) {
	}

	/**
	 * Minimal {@link TransactionalLayerProducer} whose diff layer is a real {@link DataStoreChanges} (its persistence
	 * service is a mock, never touched by the dirty-index / trapped-part paths under test).
	 */
	private static final class LayerHolder implements TransactionalLayerProducer<DataStoreChanges, LayerHolder> {
		@SuppressWarnings("unchecked")
		private static final Supplier<DataStoreChanges> FACTORY = () -> new DataStoreChanges(
			Mockito.mock(StoragePartPersistenceService.class)
		);
		private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

		@Override
		public long getId() {
			return this.id;
		}

		@Nullable
		@Override
		public DataStoreChanges createLayer() {
			return Transaction.isTransactionAvailable() ? FACTORY.get() : null;
		}

		@Nonnull
		@Override
		public LayerHolder createCopyWithMergedTransactionalMemory(@Nullable DataStoreChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
			return this;
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		}
	}

	/**
	 * Marker index key identified by an integer; the layer tracks only which keys are dirty.
	 *
	 * @param id the key id
	 */
	private record StubIndexKey(int id) implements IndexKey {
		@Nonnull
		@Override
		public Scope scope() {
			return Scope.LIVE;
		}
	}

	/**
	 * Layer-less stand-in {@link Index} stored by reference in the dirty-index map; its contents are never read.
	 */
	private record StubIndex(@Nonnull StubIndexKey key) implements Index<StubIndexKey> {
		@Nonnull
		@Override
		public StubIndexKey getIndexKey() {
			return this.key;
		}

		@Override
		public void getModifiedStorageParts(@Nonnull TrappedChanges trappedChanges) {
			// no-op: this stub holds no modifiable storage parts
		}
	}

	/**
	 * Minimal {@link StoragePart} identified by its storage-part primary key.
	 *
	 * @param pk the storage-part primary key
	 */
	private record StubStoragePart(@Nonnull Long pk) implements StoragePart {
		@Serial private static final long serialVersionUID = 1L;

		@Nonnull
		@Override
		public Long getStoragePartPK() {
			return this.pk;
		}

		@Override
		public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
			return this.pk;
		}
	}

}
