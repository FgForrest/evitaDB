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

package io.evitadb.core.transaction.memory;

import io.evitadb.core.transaction.Transaction;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.facet.FacetGroupIndex;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.index.price.PriceRefIndex;
import io.evitadb.index.price.PriceSuperIndex;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;

/**
 * Generational randomized proof that {@link TransactionalContainerChanges} and every aggregate `*Changes` diff layer
 * that delegates to it ({@code AttributeIndexChanges}, {@code CatalogIndexChanges}, both {@code PriceIndexChanges},
 * {@code FacetIndexChanges}, {@code FacetEntityTypeIndexChanges}, {@code FacetGroupIndexChanges}) snapshot and restore
 * correctly under a per-entity savepoint. This is the "easy aggregate" tier — the created/removed
 * bookkeeping containers — complementing the delicate tree-node tier already proven.
 *
 * Each generation builds a fresh layer behind a minimal {@link LayerHolder} {@link TransactionalLayerProducer}, then
 * within one real transaction applies a random baseline batch of created/removed registrations (must survive) and a
 * random in-savepoint batch (must revert on rollback / be kept on commit). The savepoint snapshot is driven through the
 * real {@link TransactionalLayerMaintainer} hooks, so the maintainer itself invokes each layer's
 * {@link Snapshotable#snapshot()} / {@link Snapshotable#restore(Object)}. The framework asserts the layer's logical
 * content (the ordered identities tracked by every container, read reflectively) against the oracle captured at
 * savepoint open, then commits the transaction so the layer-sweep verification proves the restore left no stale layer.
 *
 * Registrations use lightweight {@link RecordingProducer} stand-ins that hold no transactional layer of their own — only
 * the container *membership* is under test here, the producers' own diffs being the responsibility of their own
 * {@link Snapshotable} (verified by their own tiers). Giving each container distinct members and reading state
 * per-container catches any cross-wiring between an aggregate's containers and its memento fields. A marker registration
 * outside the baseline guarantees the in-savepoint batch is never a no-op. The run is time-bounded; the random seed is
 * echoed on failure for deterministic reproduction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Container-changes savepoint rollback/commit (generational fuzz)")
@Tag(INDEXING)
@Tag(TRANSACTION)
class LongRunningSavepointContainerChangesTest implements TimeBoundedTestSupport {
	private static final int MAX_OPS = 8;
	private static final int REUSE_PERCENT = 30;

	@ParameterizedTest(name = "Bare TransactionalContainerChanges rolls back / commits its created-removed bookkeeping")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Bare TransactionalContainerChanges rolls back / commits its created-removed bookkeeping")
	void shouldRoundTripBareContainer(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			runBothFuzz(TransactionalContainerChanges::new, random);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "AttributeIndexChanges (five containers) rolls back / commits its bookkeeping")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("AttributeIndexChanges (five containers) rolls back / commits its bookkeeping")
	void shouldRoundTripAttributeIndexChanges(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			runBothFuzz(AttributeIndex.AttributeIndexChanges::new, random);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "CatalogIndexChanges rolls back / commits its bookkeeping")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("CatalogIndexChanges rolls back / commits its bookkeeping")
	void shouldRoundTripCatalogIndexChanges(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			runBothFuzz(CatalogIndex.CatalogIndexChanges::new, random);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "PriceRefIndex.PriceIndexChanges rolls back / commits its bookkeeping")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("PriceRefIndex.PriceIndexChanges rolls back / commits its bookkeeping")
	void shouldRoundTripPriceRefIndexChanges(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			runBothFuzz(PriceRefIndex.PriceIndexChanges::new, random);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "PriceSuperIndex.PriceIndexChanges rolls back / commits its bookkeeping")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("PriceSuperIndex.PriceIndexChanges rolls back / commits its bookkeeping")
	void shouldRoundTripPriceSuperIndexChanges(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			runBothFuzz(PriceSuperIndex.PriceIndexChanges::new, random);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "FacetIndexChanges rolls back / commits its bookkeeping")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("FacetIndexChanges rolls back / commits its bookkeeping")
	void shouldRoundTripFacetIndexChanges(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			runBothFuzz(FacetIndex.FacetIndexChanges::new, random);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "FacetEntityTypeIndexChanges rolls back / commits its bookkeeping")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("FacetEntityTypeIndexChanges rolls back / commits its bookkeeping")
	void shouldRoundTripFacetEntityTypeIndexChanges(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			runBothFuzz(FacetReferenceIndex.FacetEntityTypeIndexChanges::new, random);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "FacetGroupIndexChanges rolls back / commits its bookkeeping")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("FacetGroupIndexChanges rolls back / commits its bookkeeping")
	void shouldRoundTripFacetGroupIndexChanges(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			runBothFuzz(FacetGroupIndex.FacetGroupIndexChanges::new, random);
			return iteration + 1;
		});
	}

	/**
	 * Runs both the rollback-fidelity and commit-fidelity generational fuzz for the given layer factory.
	 */
	private static void runBothFuzz(@Nonnull Supplier<Object> layerFactory, @Nonnull Random random) {
		runRollbackFuzz(layerFactory, random);
		runCommitFuzz(layerFactory, random);
	}

	/**
	 * Per-generation: baseline registrations must survive the savepoint rollback; in-savepoint registrations (plus a
	 * non-vacuous marker) must be reverted to the exact pre-savepoint membership.
	 */
	private static void runRollbackFuzz(@Nonnull Supplier<Object> layerFactory, @Nonnull Random random) {
		final List<RecordingProducer> pool = new ArrayList<>();
		final LayerHolder<Object> holder = new LayerHolder<>(layerFactory);
		assertSavepointRollbackRestores(
			holder,
			tested -> applyRandomOps(tested, random, 1 + random.nextInt(MAX_OPS), pool),
			LongRunningSavepointContainerChangesTest::readState,
			tested -> {
				// a marker registration guarantees a non-vacuous in-savepoint batch
				addMarker(tested, pool);
				applyRandomOps(tested, random, 1 + random.nextInt(MAX_OPS), pool);
			}
		);
	}

	/**
	 * Per-generation: registrations made while the savepoint is open must be kept once it is committed.
	 */
	private static void runCommitFuzz(@Nonnull Supplier<Object> layerFactory, @Nonnull Random random) {
		final List<RecordingProducer> pool = new ArrayList<>();
		final LayerHolder<Object> holder = new LayerHolder<>(layerFactory);
		assertSavepointCommitKeeps(
			holder,
			tested -> applyRandomOps(tested, random, 1 + random.nextInt(MAX_OPS), pool),
			LongRunningSavepointContainerChangesTest::readState,
			tested -> applyRandomOps(tested, random, 1 + random.nextInt(MAX_OPS), pool)
		);
	}

	/**
	 * Applies `count` random created/removed registrations across the layer's containers. Items are mostly fresh, but
	 * sometimes reuse an existing producer to exercise the created∩removed churn the production cleanup relies on.
	 */
	private static void applyRandomOps(@Nonnull LayerHolder<?> holder, @Nonnull Random random, int count, @Nonnull List<RecordingProducer> pool) {
		final Object layer = Transaction.getOrCreateTransactionalMemoryLayer(holder);
		final List<TransactionalContainerChanges<?, ?, ?>> containers = containersOf(layer);
		for (int i = 0; i < count; i++) {
			final TransactionalContainerChanges<?, ?, ?> container = containers.get(random.nextInt(containers.size()));
			final boolean created = random.nextBoolean();
			final RecordingProducer item = (!pool.isEmpty() && random.nextInt(100) < REUSE_PERCENT)
				? pool.get(random.nextInt(pool.size()))
				: newProducer(pool);
			register(container, created, item);
		}
	}

	/**
	 * Registers a dedicated fresh marker producer as created in the first container, ensuring the in-savepoint batch
	 * always changes the layer state.
	 */
	private static void addMarker(@Nonnull LayerHolder<?> holder, @Nonnull List<RecordingProducer> pool) {
		final Object layer = Transaction.getOrCreateTransactionalMemoryLayer(holder);
		register(containersOf(layer).get(0), true, newProducer(pool));
	}

	/**
	 * Reads the layer's logical state — for every container, the ordered identities tracked as created and as removed —
	 * into an `.equals`-comparable map. Returns an empty map when the layer was never created.
	 */
	@Nonnull
	private static Map<String, List<Long>> readState(@Nonnull LayerHolder<Object> holder) {
		final Object layer = Transaction.getTransactionalMemoryLayerIfExists(holder);
		if (layer == null) {
			return Map.of();
		}
		final Map<String, List<Long>> result = new LinkedHashMap<>();
		final List<String> names = containerNamesOf(layer);
		final List<TransactionalContainerChanges<?, ?, ?>> containers = containersOf(layer);
		for (int i = 0; i < containers.size(); i++) {
			result.put(names.get(i) + "#created", idsOf(containers.get(i), "createdItems"));
			result.put(names.get(i) + "#removed", idsOf(containers.get(i), "removedItems"));
		}
		return result;
	}

	/**
	 * Collects the {@link TransactionalContainerChanges} instances held by the layer — either the layer itself when it
	 * is one, or every declared field of that type on an aggregate.
	 */
	@Nonnull
	private static List<TransactionalContainerChanges<?, ?, ?>> containersOf(@Nonnull Object layer) {
		if (layer instanceof final TransactionalContainerChanges<?, ?, ?> self) {
			return List.of(self);
		}
		final List<TransactionalContainerChanges<?, ?, ?>> result = new ArrayList<>();
		for (final Field field : layer.getClass().getDeclaredFields()) {
			if (field.getType() == TransactionalContainerChanges.class) {
				field.setAccessible(true);
				try {
					result.add((TransactionalContainerChanges<?, ?, ?>) field.get(layer));
				} catch (IllegalAccessException e) {
					throw new IllegalStateException(e);
				}
			}
		}
		if (result.isEmpty()) {
			throw new IllegalStateException("No container fields found on " + layer.getClass());
		}
		return result;
	}

	/**
	 * Stable per-container names matching {@link #containersOf}, used as oracle map keys.
	 */
	@Nonnull
	private static List<String> containerNamesOf(@Nonnull Object layer) {
		if (layer instanceof TransactionalContainerChanges<?, ?, ?>) {
			return List.of("self");
		}
		final List<String> names = new ArrayList<>();
		for (final Field field : layer.getClass().getDeclaredFields()) {
			if (field.getType() == TransactionalContainerChanges.class) {
				names.add(field.getName());
			}
		}
		return names;
	}

	/**
	 * Reads the producer ids tracked in the named list field of a container (empty when the lazy list is still null).
	 */
	@Nonnull
	private static List<Long> idsOf(@Nonnull TransactionalContainerChanges<?, ?, ?> container, @Nonnull String listField) {
		try {
			final Field field = TransactionalContainerChanges.class.getDeclaredField(listField);
			field.setAccessible(true);
			final List<?> list = (List<?>) field.get(container);
			if (list == null) {
				return List.of();
			}
			final List<Long> ids = new ArrayList<>(list.size());
			for (final Object item : list) {
				ids.add(((RecordingProducer) item).getId());
			}
			return ids;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Invokes the container's real {@code addCreatedItem} / {@code addRemovedItem} (reflectively, to bypass the
	 * per-container generic producer type — erased to {@link TransactionalLayerProducer}).
	 */
	private static void register(@Nonnull TransactionalContainerChanges<?, ?, ?> container, boolean created, @Nonnull RecordingProducer item) {
		try {
			final Method method = TransactionalContainerChanges.class.getMethod(
				created ? "addCreatedItem" : "addRemovedItem", TransactionalLayerProducer.class
			);
			method.invoke(container, item);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Creates a fresh uniquely-identified recording producer and tracks it in the per-generation pool for reuse.
	 */
	@Nonnull
	private static RecordingProducer newProducer(@Nonnull List<RecordingProducer> pool) {
		final RecordingProducer producer = new RecordingProducer(TransactionalObjectVersion.SEQUENCE.nextId());
		pool.add(producer);
		return producer;
	}

	/**
	 * Lightweight {@link TransactionalLayerProducer} stand-in registered as a created/removed item. It holds no diff
	 * layer of its own, so it never participates in the transactional-memory sweep — only its identity matters here.
	 */
	private static final class RecordingProducer implements TransactionalLayerProducer<Void, RecordingProducer> {
		private final long id;

		RecordingProducer(long id) {
			this.id = id;
		}

		@Override
		public long getId() {
			return this.id;
		}

		@Nullable
		@Override
		public Void createLayer() {
			return null;
		}

		@Nonnull
		@Override
		public RecordingProducer createCopyWithMergedTransactionalMemory(@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
			return this;
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		}
	}

	/**
	 * Minimal {@link TransactionalLayerProducer} whose diff layer is an arbitrary {@link Snapshotable} produced on
	 * demand. It performs no merge of its own (the test reads the layer directly), so the maintainer's savepoint
	 * snapshot / restore is exercised purely against the held layer.
	 *
	 * @param <L> the held diff-layer type
	 */
	private static final class LayerHolder<L> implements TransactionalLayerProducer<L, LayerHolder<L>> {
		private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		private final Supplier<L> layerFactory;

		LayerHolder(@Nonnull Supplier<L> layerFactory) {
			this.layerFactory = layerFactory;
		}

		@Override
		public long getId() {
			return this.id;
		}

		@Nullable
		@Override
		public L createLayer() {
			return Transaction.isTransactionAvailable() ? this.layerFactory.get() : null;
		}

		@Nonnull
		@Override
		public LayerHolder<L> createCopyWithMergedTransactionalMemory(@Nullable L layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
			return this;
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		}
	}

}
