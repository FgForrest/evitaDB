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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart.ChainPredecessorSlot;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart.SortValueSlot;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TreeMap;

import static io.evitadb.index.VirtualReferencePartitionIndexChainPredecessorTest.CHAIN;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.INTEGER_ASC;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.ORDER;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.snapshotOf;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link VirtualReferencePartitionIndex} refuses to load a persisted state that contradicts itself - a
 * tuple or sort value dictionary out of step with the entries referring to it, declared slots out of order or shared
 * between a sort key and a chain key, entries of a slot nobody declared - and names what is wrong instead of loading a
 * broken index or failing somewhere deeper.
 *
 * Every refused state is the valid baseline with exactly one thing corrupted, and the baseline itself is proven to
 * load, so each test fails only on the corruption it introduces.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition index load validation")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(STORAGE)
class VirtualReferencePartitionIndexLoadValidationTest {
	private static final int OWNER_SLOT = VirtualReferencePartitionSlot.PARTITION_OWNER.getId();
	private static final int TUPLE_SLOT = VirtualReferencePartitionSlot.REPRESENTATIVE_TUPLE.getId();
	private static final int SORT_SLOT = VirtualReferencePartitionSlot.FIRST_DECLARED_SLOT_ID;
	private static final int CHAIN_SLOT = SORT_SLOT + 1;
	private static final Serializable[] GALLERY = {"gallery"};

	/**
	 * A persisted state of the index, built up as the valid baseline and then corrupted by a single change.
	 */
	private static final class PersistedState {
		/**
		 * The `key -> payload` entries, kept in ascending key order as they are persisted.
		 */
		private final Map<Long, Long> entries = new TreeMap<>();
		private int[] tupleIds = {1};
		private Serializable[][] tuples = {GALLERY};
		private SortValueSlot[] sortValueSlots = {sortSlot(SORT_SLOT, new int[]{1, 2}, 5, 7)};
		private ChainPredecessorSlot[] chainSlots = {new ChainPredecessorSlot(CHAIN_SLOT, CHAIN)};

		/**
		 * Creates the valid baseline: partition 1 owned by entity 10 referencing entity 100 with the tuple `gallery`,
		 * partitions 1 and 2 sorted by the values `5` and `7`, and partition 1 chained to the predecessor `5`.
		 */
		PersistedState() {
			entry(OWNER_SLOT, 1, NumberUtils.pack(10, 100));
			entry(TUPLE_SLOT, 1, 1);
			entry(SORT_SLOT, 1, 1);
			entry(SORT_SLOT, 2, 2);
			entry(CHAIN_SLOT, 1, 5);
		}

		/**
		 * Creates a sort value slot of the integer sort key.
		 *
		 * @param slotId   the slot id
		 * @param valueIds the value ids
		 * @param values   the values, aligned with `valueIds`
		 * @return the slot
		 */
		@Nonnull
		static SortValueSlot sortSlot(int slotId, @Nonnull int[] valueIds, @Nonnull Serializable... values) {
			return new SortValueSlot(slotId, ORDER, INTEGER_ASC, 0, valueIds, values);
		}

		/**
		 * Records (or replaces) the entry of the partition in the slot.
		 *
		 * @param slotId              the slot id
		 * @param partitionPrimaryKey the partition primary key
		 * @param payload             the payload
		 * @return this state
		 */
		@Nonnull
		PersistedState entry(int slotId, int partitionPrimaryKey, long payload) {
			this.entries.put(NumberUtils.pack(slotId, partitionPrimaryKey), payload);
			return this;
		}

		/**
		 * Replaces the tuple dictionary.
		 *
		 * @param tupleIds the tuple ids
		 * @param tuples   the tuples, aligned with `tupleIds`
		 * @return this state
		 */
		@Nonnull
		PersistedState tuples(@Nonnull int[] tupleIds, @Nonnull Serializable[]... tuples) {
			this.tupleIds = tupleIds;
			this.tuples = tuples;
			return this;
		}

		/**
		 * Replaces the sort value slots.
		 *
		 * @param sortValueSlots the sort value slots
		 * @return this state
		 */
		@Nonnull
		PersistedState sortSlots(@Nonnull SortValueSlot... sortValueSlots) {
			this.sortValueSlots = sortValueSlots;
			return this;
		}

		/**
		 * Replaces the chain predecessor slots by slots of the given ids, each of its own chained attribute.
		 *
		 * @param slotIds the slot ids
		 * @return this state
		 */
		@Nonnull
		PersistedState chainSlots(@Nonnull int... slotIds) {
			this.chainSlots = new ChainPredecessorSlot[slotIds.length];
			for (int i = 0; i < slotIds.length; i++) {
				this.chainSlots[i] = new ChainPredecessorSlot(
					slotIds[i], new AttributeIndexKey(CHAIN.referenceName(), CHAIN.attributeName() + i, null)
				);
			}
			return this;
		}

		/**
		 * Loads the index from the state through the inline constructor.
		 *
		 * @return the loaded index
		 */
		@Nonnull
		VirtualReferencePartitionIndex load() {
			final long[] keys = this.entries.keySet().stream().mapToLong(Long::longValue).toArray();
			final long[] payloads = this.entries.values().stream().mapToLong(Long::longValue).toArray();
			return new VirtualReferencePartitionIndex(
				"media", keys, payloads, this.tupleIds, this.tuples, this.sortValueSlots, this.chainSlots
			, slot -> SortIndex.createTypeLevelNormalizerFor(slot.comparatorBase(), slot.indexedDecimalPlaces()));
		}
	}

	/**
	 * Asserts the state is refused with an internal error whose message carries the fragment.
	 *
	 * @param state           the corrupted state
	 * @param messageFragment the part of the message naming the corruption
	 */
	private static void assertRefused(@Nonnull PersistedState state, @Nonnull String messageFragment) {
		final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class, state::load);
		assertTrue(
			error.getMessage().contains(messageFragment),
			"The message must name the corruption `" + messageFragment + "`: " + error.getMessage()
		);
	}

	@Test
	@DisplayName("the valid baseline loads with every slot, dictionary and sort index in place")
	void shouldLoadTheValidBaseline() {
		final VirtualReferencePartitionIndex index = new PersistedState().load();
		assertEquals(OptionalLong.of(NumberUtils.pack(10, 100)), index.getPartitionOwner(1));
		assertEquals(1, index.getRepresentativeTupleId(1));
		assertArrayEquals(GALLERY, index.getTuple(1));
		assertEquals(List.of(1, 2), snapshotOf(index, ORDER).records());
		assertEquals(OptionalInt.of(5), index.getChainPredecessor(CHAIN, 1));
	}

	@Nested
	@DisplayName("Tuple dictionary")
	class TupleDictionary {

		@Test
		@DisplayName("a tuple id listed twice is refused")
		void shouldRefuseATupleIdListedTwice() {
			assertRefused(
				new PersistedState().tuples(new int[]{1, 1}, GALLERY, new Serializable[]{"thumbnail"}),
				"lists tuple id 1 twice"
			);
		}

		@Test
		@DisplayName("a partition referring to a tuple id the dictionary lacks is refused")
		void shouldRefuseAPartitionReferringToAnUnknownTuple() {
			assertRefused(
				new PersistedState().entry(TUPLE_SLOT, 2, 2),
				"refers to tuple id 2, which its dictionary lacks"
			);
		}

		@Test
		@DisplayName("a tuple no partition refers to is refused")
		void shouldRefuseATupleWithoutUsers() {
			assertRefused(
				new PersistedState().tuples(new int[]{1, 2}, GALLERY, new Serializable[]{"thumbnail"}),
				"holds tuple id 2, which no partition refers to"
			);
		}

		@Test
		@DisplayName("two ids holding one tuple are refused")
		void shouldRefuseTwoIdsHoldingOneTuple() {
			// accepted, the later id would shadow the earlier one in the lookup by values, and releasing the earlier
			// one would drop the mapping of the later one while partitions still refer to it
			assertRefused(
				new PersistedState()
					.entry(TUPLE_SLOT, 2, 2)
					.tuples(new int[]{1, 2}, GALLERY, new Serializable[]{"gallery"}),
				"holds one tuple under tuple ids 1 and 2"
			);
		}
	}

	@Nested
	@DisplayName("Chain predecessor slots")
	class ChainSlots {

		@Test
		@DisplayName("a chain slot below the declared slot range is refused")
		void shouldRefuseAChainSlotBelowTheDeclaredRange() {
			assertRefused(
				new PersistedState().chainSlots(SORT_SLOT - 1),
				"chain predecessor slots of the virtual reference partition index for media are not in ascending " +
					"order of declared slot ids at slot " + (SORT_SLOT - 1)
			);
		}

		@Test
		@DisplayName("chain slots in descending order are refused")
		void shouldRefuseChainSlotsInDescendingOrder() {
			assertRefused(
				new PersistedState().chainSlots(CHAIN_SLOT + 1, CHAIN_SLOT),
				"chain predecessor slots of the virtual reference partition index for media are not in ascending " +
					"order of declared slot ids at slot " + CHAIN_SLOT
			);
		}
	}

	@Nested
	@DisplayName("Sort value slots")
	class SortValueSlots {

		@Test
		@DisplayName("a sort slot below the declared slot range is refused")
		void shouldRefuseASortSlotBelowTheDeclaredRange() {
			assertRefused(
				new PersistedState().sortSlots(PersistedState.sortSlot(SORT_SLOT - 1, new int[]{1, 2}, 5, 7)),
				"or share one with a chain key, at slot " + (SORT_SLOT - 1)
			);
		}

		@Test
		@DisplayName("sort slots in descending order are refused")
		void shouldRefuseSortSlotsInDescendingOrder() {
			assertRefused(
				new PersistedState().sortSlots(
					PersistedState.sortSlot(SORT_SLOT + 4, new int[0]),
					PersistedState.sortSlot(SORT_SLOT + 3, new int[0])
				),
				"or share one with a chain key, at slot " + (SORT_SLOT + 3)
			);
		}

		@Test
		@DisplayName("a sort slot sharing its id with a chain slot is refused")
		void shouldRefuseASortSlotSharingItsIdWithAChainSlot() {
			assertRefused(
				new PersistedState().sortSlots(PersistedState.sortSlot(CHAIN_SLOT, new int[]{1, 2}, 5, 7)),
				"or share one with a chain key, at slot " + CHAIN_SLOT
			);
		}

		@Test
		@DisplayName("a value id listed twice is refused")
		void shouldRefuseAValueIdListedTwice() {
			assertRefused(
				new PersistedState().sortSlots(PersistedState.sortSlot(SORT_SLOT, new int[]{1, 1}, 5, 7)),
				"lists value id 1 twice"
			);
		}

		@Test
		@DisplayName("two value ids the sort key orders as one value are refused")
		void shouldRefuseTwoValuesOrderedAsOne() {
			assertRefused(
				new PersistedState().sortSlots(PersistedState.sortSlot(SORT_SLOT, new int[]{1, 2}, 5, 5)),
				"which its sort key orders as one"
			);
		}

		@Test
		@DisplayName("a value no partition refers to is refused")
		void shouldRefuseAValueWithoutUsers() {
			assertRefused(
				new PersistedState().sortSlots(PersistedState.sortSlot(SORT_SLOT, new int[]{1, 2, 3}, 5, 7, 9)),
				"holds value id 3, which no partition refers to"
			);
		}
	}

	@Nested
	@DisplayName("Entries")
	class Entries {

		@Test
		@DisplayName("entries of a slot declared neither as a sort key nor as a chain key are refused")
		void shouldRefuseEntriesOfAnUndeclaredSlot() {
			assertRefused(
				new PersistedState().entry(CHAIN_SLOT + 1, 1, 1),
				"holds entries of slot " + (CHAIN_SLOT + 1) + ", which is not declared"
			);
		}

		@Test
		@DisplayName("a sort entry referring to a value id the dictionary lacks is refused")
		void shouldRefuseASortEntryReferringToAnUnknownValue() {
			assertRefused(
				new PersistedState().entry(SORT_SLOT, 2, 9),
				"refers to value id 9 of slot " + SORT_SLOT + ", which its dictionary lacks"
			);
		}
	}

}
