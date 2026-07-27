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

import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the small leaf types of the software transactional memory: {@link TransactionalLayerEntry} state
 * transitions, the {@link VoidTransactionMemoryProducer} contract and {@link TransactionalContainerChanges} clean-up
 * semantics.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(ENGINE)
@Tag(TRANSACTION)
@DisplayName("Software transactional memory leaf types")
class TransactionalLeafTypesTest {

	@Nested
	@DisplayName("TransactionalLayerEntry")
	class TransactionalLayerEntryTest {

		@Test
		@DisplayName("exposes the owning creator, the diff item and starts in the ALIVE state")
		void shouldExposeCreatorAndItemAndStartAlive() {
			final Object item = new Object();
			final TransactionalLayerCreator<Object> creator = createLayerCreator(item);
			final TransactionalLayerEntry<Object> entry = new TransactionalLayerEntry<>(creator, item);

			// the registry is keyed by the creator's id alone, so the entry is the only thing keeping the creator
			// reachable for StaleTransactionMemoryException reporting and the one-id-one-creator assertion
			assertSame(creator, entry.getCreator());
			assertSame(item, entry.getItem());
			assertEquals(TransactionalLayerState.ALIVE, entry.getState());
		}

		@Test
		@DisplayName("transitions to DISCARDED on the first discard")
		void shouldTransitionToDiscardedOnce() {
			final TransactionalLayerEntry<Object> entry = createEntry();

			entry.discard();

			assertEquals(TransactionalLayerState.DISCARDED, entry.getState());
		}

		@Test
		@DisplayName("throws on a repeated discard")
		void shouldThrowOnDoubleDiscard() {
			final TransactionalLayerEntry<Object> entry = createEntry();
			entry.discard();

			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				entry::discard
			);
			assertTrue(ex.getPrivateMessage().contains("already discarded"));
		}

		/**
		 * Creates an entry over a throw-away diff item and its creator.
		 *
		 * @return newly created entry in the ALIVE state
		 */
		@Nonnull
		private static TransactionalLayerEntry<Object> createEntry() {
			final Object item = new Object();
			return new TransactionalLayerEntry<>(createLayerCreator(item), item);
		}

		/**
		 * Creates a minimal layer creator handing out a real sequence id and the passed diff layer.
		 *
		 * @param layer the diff layer the creator reports
		 * @return newly created layer creator
		 */
		@Nonnull
		private static TransactionalLayerCreator<Object> createLayerCreator(@Nonnull Object layer) {
			final long id = TransactionalObjectVersion.SEQUENCE.nextId();
			return new TransactionalLayerCreator<>() {
				@Override
				public long getId() {
					return id;
				}

				@Override
				public Object createLayer() {
					return layer;
				}
			};
		}
	}

	@Nested
	@DisplayName("VoidTransactionMemoryProducer")
	class VoidTransactionMemoryProducerTest {

		@Test
		@DisplayName("owns no layer, so it is not a layer creator at all")
		void shouldNotBeLayerCreator() {
			// VoidTransactionMemoryProducer is NOT a functional interface (it leaves both
			// removeLayer(TransactionalLayerMaintainer) and createCopyWithMergedTransactionalMemory abstract),
			// so it must be implemented with an anonymous class rather than a lambda.
			final VoidTransactionMemoryProducer<Object> producer = new VoidTransactionMemoryProducer<>() {
				@Nonnull
				@Override
				public Object createCopyWithMergedTransactionalMemory(
					@Nonnull TransactionalLayerMaintainer transactionalLayer
				) {
					return new Object();
				}

				@Override
				public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
					// no diff layer is ever created, so there is nothing to remove
				}
			};

			// having no identity at all is what makes it impossible for such an object to be handed a diff layer
			// belonging to another object - it cannot be looked up in the id-keyed registry in the first place,
			// which is stronger than the previous arrangement of reporting a constant id and throwing on createLayer()
			assertFalse(
				producer instanceof TransactionalLayerCreator<?>,
				"a producer that owns no layer must not be a TransactionalLayerCreator!"
			);
		}
	}

	@Nested
	@DisplayName("TransactionalContainerChanges")
	class TransactionalContainerChangesTest {

		@Test
		@DisplayName("cleans only items that were both created and removed")
		void shouldCleanOnlyItemsBothCreatedAndRemoved() {
			final RecordingProducer createdOnly = new RecordingProducer();
			final RecordingProducer removedOnly = new RecordingProducer();
			final RecordingProducer both = new RecordingProducer();

			final TransactionalContainerChanges<Object, RecordingProducer> changes =
				new TransactionalContainerChanges<>();
			changes.addCreatedItem(createdOnly);
			changes.addCreatedItem(both);
			changes.addRemovedItem(removedOnly);
			changes.addRemovedItem(both);

			changes.clean(null);

			assertEquals(0, createdOnly.getRemoveLayerCount());
			assertEquals(0, removedOnly.getRemoveLayerCount());
			assertEquals(1, both.getRemoveLayerCount());
		}

		@Test
		@DisplayName("cleans every created and removed item")
		void shouldCleanAllCreatedAndRemovedItems() {
			final RecordingProducer createdOnly = new RecordingProducer();
			final RecordingProducer removedOnly = new RecordingProducer();
			final RecordingProducer both = new RecordingProducer();

			final TransactionalContainerChanges<Object, RecordingProducer> changes =
				new TransactionalContainerChanges<>();
			changes.addCreatedItem(createdOnly);
			changes.addCreatedItem(both);
			changes.addRemovedItem(removedOnly);
			changes.addRemovedItem(both);

			changes.cleanAll(null);

			assertEquals(1, createdOnly.getRemoveLayerCount());
			assertEquals(1, removedOnly.getRemoveLayerCount());
			// `both` is present in both lists, so cleanAll removes its layer twice
			assertEquals(2, both.getRemoveLayerCount());
		}

		@Test
		@DisplayName("does nothing when no items were registered")
		void shouldDoNothingWhenNoItemsRegistered() {
			final TransactionalContainerChanges<Object, RecordingProducer> changes =
				new TransactionalContainerChanges<>();

			// neither call must throw when nothing was registered
			assertDoesNotThrow(() -> changes.clean(null));
			assertDoesNotThrow(() -> changes.cleanAll(null));
		}
	}

	/**
	 * Test {@link TransactionalLayerProducer} that records how many times {@link #removeLayer(TransactionalLayerMaintainer)}
	 * was invoked so that {@link TransactionalContainerChanges} clean-up can be asserted without a real maintainer.
	 */
	private static class RecordingProducer implements TransactionalLayerProducer<Void, Object> {
		private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		private int removeLayerCount;

		int getRemoveLayerCount() {
			return this.removeLayerCount;
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

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.removeLayerCount++;
		}

		@Nonnull
		@Override
		public Object createCopyWithMergedTransactionalMemory(
			@Nullable Void layer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			return this;
		}
	}

}
