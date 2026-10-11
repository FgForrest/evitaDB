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

import io.evitadb.api.exception.IdentifierOverflowException;
import io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.SortSnapshot;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart.*;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Set;

import static io.evitadb.index.VirtualReferencePartitionIndex.NO_SORT_VALUE_ID;
import static io.evitadb.index.VirtualReferencePartitionIndex.NO_TUPLE_ID;
import static io.evitadb.index.VirtualReferencePartitionIndexChainPredecessorTest.CHAIN;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.INTEGER_ASC;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.ORDER;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.REFERENCE_NAME;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.snapshotOf;
import static io.evitadb.index.VirtualReferencePartitionIndexTest.flush;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link VirtualReferencePartitionIndex} refuses to hand out an identifier once its id space is
 * exhausted - a representative tuple id, a sort value id of a declared sort key, a declared slot id - with an
 * {@link IdentifierOverflowException} naming the exhausted space, instead of wrapping into the negative range, and that
 * it refuses before anything changes.
 *
 * Every index starts just below the limit through the load constructor, the very path a restart takes, so that each
 * test first proves the last valid id is still handed out.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition index identifier overflow")
@Tag(INDEXING)
@Tag(REFERENCE)
class VirtualReferencePartitionIndexIdOverflowTest {
	private static final int TUPLE_SLOT = VirtualReferencePartitionSlot.REPRESENTATIVE_TUPLE.getId();
	private static final int SORT_SLOT = VirtualReferencePartitionSlot.FIRST_DECLARED_SLOT_ID;
	/**
	 * The last id a tuple or sort value dictionary hands out - the next one is refused.
	 */
	private static final int LAST_ID = Integer.MAX_VALUE - 1;
	private static final Serializable[] GALLERY = {"gallery"};
	private static final Serializable[] THUMBNAIL = {"thumbnail"};
	private static final Serializable[] POSTER = {"poster"};
	private static final AttributeIndexKey SECOND_CHAIN = new AttributeIndexKey(REFERENCE_NAME, "secondOrder", null);
	private static final AttributeIndexKey THIRD_CHAIN = new AttributeIndexKey(REFERENCE_NAME, "thirdOrder", null);
	private static final AttributeIndexKey SECOND_ORDER = new AttributeIndexKey(REFERENCE_NAME, "rank", null);
	private static final String TUPLE_SPACE = "representative tuple ids";
	private static final String PUBLIC_TUPLE_SPACE = "representative tuple ids of a reference partition index";
	private static final String SORT_VALUE_SPACE = "sort value ids of sort key `" + ORDER + "`";
	private static final String PUBLIC_SORT_VALUE_SPACE = "sort value ids of `" + ORDER + "`";

	/**
	 * Loads an index whose partition 1 holds the tuple `gallery` under the given tuple id.
	 *
	 * @param galleryTupleId the persisted tuple id of `gallery`
	 * @return the loaded index
	 */
	@Nonnull
	private static VirtualReferencePartitionIndex loadWithTupleId(int galleryTupleId) {
		return new VirtualReferencePartitionIndex(
			"media", new long[]{NumberUtils.pack(TUPLE_SLOT, 1)}, new long[]{galleryTupleId},
			new int[]{galleryTupleId}, new Serializable[][]{GALLERY}, new SortValueSlot[0], new ChainPredecessorSlot[0]
		);
	}

	/**
	 * Loads an index with the integer sort key {@link VirtualReferencePartitionIndexSortValueTest#ORDER} declared,
	 * whose partition 1 holds the value `5` under the given value id.
	 *
	 * @param fiveValueId the persisted value id of `5`
	 * @return the loaded index
	 */
	@Nonnull
	private static VirtualReferencePartitionIndex loadWithSortValueId(int fiveValueId) {
		return new VirtualReferencePartitionIndex(
			"media", new long[]{NumberUtils.pack(SORT_SLOT, 1)}, new long[]{fiveValueId},
			new int[0], new Serializable[0][],
			new SortValueSlot[]{new SortValueSlot(
				SORT_SLOT, ORDER, INTEGER_ASC, 0, new int[]{fiveValueId}, new Serializable[]{5}
			)},
			new ChainPredecessorSlot[0]
		);
	}

	/**
	 * Loads an index with the chain key {@link VirtualReferencePartitionIndexChainPredecessorTest#CHAIN} declared in
	 * the slot of the given id, holding no entry.
	 *
	 * @param chainSlotId the persisted slot id of the chain key
	 * @return the loaded index
	 */
	@Nonnull
	private static VirtualReferencePartitionIndex loadWithChainSlotId(int chainSlotId) {
		return new VirtualReferencePartitionIndex(
			"media", new long[0], new long[0], new int[0], new Serializable[0][], new SortValueSlot[0],
			new ChainPredecessorSlot[]{new ChainPredecessorSlot(chainSlotId, CHAIN)}
		);
	}

	/**
	 * Asserts the call is refused with an identifier overflow whose private message names the exhausted id space for
	 * the server log and whose public message names it for the client together with what the operator can do: a
	 * restart, which does not help for a slot id and helps for a dictionary id only after its top ids were released,
	 * and the rebuild of the entity collection, which always helps.
	 *
	 * @param exhaustedSpace       the part of the private message naming the id space
	 * @param publicExhaustedSpace the part of the public message naming the id space
	 * @param call                 the refused call
	 */
	private static void assertOverflow(
		@Nonnull String exhaustedSpace,
		@Nonnull String publicExhaustedSpace,
		@Nonnull Executable call
	) {
		final IdentifierOverflowException exception = assertThrows(IdentifierOverflowException.class, call);
		assertTrue(
			exception.getPrivateMessage().contains(exhaustedSpace),
			"The message must name the exhausted id space `" + exhaustedSpace + "`: " + exception.getPrivateMessage()
		);
		assertTrue(
			exception.getPrivateMessage().contains("must be rebuilt"),
			"The message must say what helps: " + exception.getPrivateMessage()
		);
		final String publicMessage = exception.getPublicMessage();
		assertTrue(
			publicMessage.contains(publicExhaustedSpace),
			"The public message must name the exhausted id space `" + publicExhaustedSpace + "`: " + publicMessage
		);
		assertTrue(
			publicMessage.contains("restart") &&
				publicMessage.contains("rebuild the entity collection") &&
				publicMessage.contains("`replaceCollection`"),
			"The public message must say whether a restart helps and how to rebuild the collection: " + publicMessage
		);
	}

	@Nested
	@DisplayName("Representative tuple ids")
	class TupleIds {

		@Test
		@DisplayName("the last tuple id is handed out and the next new tuple is refused without any change")
		void shouldHandOutTheLastTupleIdAndRefuseTheNext() {
			final VirtualReferencePartitionIndex index = loadWithTupleId(LAST_ID - 1);
			index.putRepresentativeTuple(2, THUMBNAIL);
			assertEquals(LAST_ID, index.getTupleId(THUMBNAIL));
			assertEquals(LAST_ID, index.getRepresentativeTupleId(2));
			index.resetDirty();

			assertOverflow(TUPLE_SPACE, PUBLIC_TUPLE_SPACE, () -> index.putRepresentativeTuple(3, POSTER));
			// a partition holding a tuple keeps it when its replacement is refused
			assertOverflow(TUPLE_SPACE, PUBLIC_TUPLE_SPACE, () -> index.putRepresentativeTuple(2, POSTER));

			assertEquals(NO_TUPLE_ID, index.getTupleId(POSTER));
			assertEquals(NO_TUPLE_ID, index.getRepresentativeTupleId(3));
			assertEquals(LAST_ID, index.getRepresentativeTupleId(2));
			assertArrayEquals(THUMBNAIL, index.getTuple(LAST_ID));
			assertTrue(flush(index).isEmpty(), "A refused tuple must not mark the index dirty");

			// a tuple the dictionary knows needs no new id
			index.putRepresentativeTuple(3, GALLERY);
			assertEquals(LAST_ID - 1, index.getRepresentativeTupleId(3));
		}

		@Test
		@Tag(TRANSACTION)
		@DisplayName("a tuple refused inside a transaction leaves the transactional layer as it was")
		void shouldRefuseInsideATransactionWithoutTouchingTheLayer() {
			final VirtualReferencePartitionIndex index = loadWithTupleId(LAST_ID - 1);
			assertStateAfterCommit(
				index,
				original -> {
					original.putRepresentativeTuple(2, THUMBNAIL);
					assertOverflow(TUPLE_SPACE, PUBLIC_TUPLE_SPACE, () -> original.putRepresentativeTuple(3, POSTER));
				},
				(original, committed) -> {
					assertEquals(LAST_ID, committed.getTupleId(THUMBNAIL));
					assertEquals(NO_TUPLE_ID, committed.getTupleId(POSTER));
					assertEquals(NO_TUPLE_ID, committed.getRepresentativeTupleId(3));
					assertOverflow(TUPLE_SPACE, PUBLIC_TUPLE_SPACE, () -> committed.putRepresentativeTuple(3, POSTER));
				}
			);
		}

		@Test
		@DisplayName("a load whose largest tuple id is the maximum resumes exhausted instead of wrapping")
		void shouldResumeExhaustedAfterLoadingTheMaximumTupleId() {
			final VirtualReferencePartitionIndex index = loadWithTupleId(Integer.MAX_VALUE);
			assertArrayEquals(GALLERY, index.getTuple(Integer.MAX_VALUE));

			assertOverflow(TUPLE_SPACE, PUBLIC_TUPLE_SPACE, () -> index.putRepresentativeTuple(2, THUMBNAIL));
			assertEquals(NO_TUPLE_ID, index.getTupleId(THUMBNAIL));
			assertEquals(NO_TUPLE_ID, index.getRepresentativeTupleId(2));
		}
	}

	@Nested
	@DisplayName("Sort value ids")
	class SortValueIds {

		@Test
		@DisplayName("the last value id is handed out and the next new value is refused without any change")
		void shouldHandOutTheLastValueIdAndRefuseTheNext() {
			final VirtualReferencePartitionIndex index = loadWithSortValueId(LAST_ID - 1);
			index.putSortValue(ORDER, 2, 7);
			assertEquals(LAST_ID, index.getSortValueId(ORDER, 2));
			index.resetDirty();
			final SortSnapshot before = snapshotOf(index, ORDER);

			assertOverflow(SORT_VALUE_SPACE, PUBLIC_SORT_VALUE_SPACE, () -> index.putSortValue(ORDER, 3, 9));
			// a partition holding a value keeps it when its replacement is refused
			assertOverflow(SORT_VALUE_SPACE, PUBLIC_SORT_VALUE_SPACE, () -> index.putSortValue(ORDER, 1, 9));

			assertEquals(NO_SORT_VALUE_ID, index.getSortValueId(ORDER, 3));
			assertEquals(LAST_ID - 1, index.getSortValueId(ORDER, 1));
			assertEquals(before, snapshotOf(index, ORDER));
			assertTrue(flush(index).isEmpty(), "A refused value must not mark the index dirty");

			// a value the dictionary knows needs no new id
			index.putSortValue(ORDER, 3, 7);
			assertEquals(LAST_ID, index.getSortValueId(ORDER, 3));
		}

		@Test
		@DisplayName("a load whose largest value id is the maximum resumes exhausted instead of wrapping")
		void shouldResumeExhaustedAfterLoadingTheMaximumValueId() {
			final VirtualReferencePartitionIndex index = loadWithSortValueId(Integer.MAX_VALUE);
			assertEquals(Integer.MAX_VALUE, index.getSortValueId(ORDER, 1));

			assertOverflow(SORT_VALUE_SPACE, PUBLIC_SORT_VALUE_SPACE, () -> index.putSortValue(ORDER, 2, 7));
			assertEquals(NO_SORT_VALUE_ID, index.getSortValueId(ORDER, 2));
		}
	}

	@Nested
	@DisplayName("Declared slot ids")
	class DeclaredSlotIds {

		@Test
		@DisplayName("the last slot id is handed out and the next declaration is refused without any change")
		void shouldHandOutTheLastSlotIdAndRefuseTheNext() {
			final VirtualReferencePartitionIndex index = loadWithChainSlotId(Integer.MAX_VALUE - 1);
			index.declareChainKey(SECOND_CHAIN);
			// the key got the last slot id: its entries are stored and read back under it
			index.putChainPredecessor(SECOND_CHAIN, 1, 5);
			assertEquals(5, index.getChainPredecessor(SECOND_CHAIN, 1).orElseThrow());
			index.resetDirty();

			assertOverflow(
				"to declare `" + THIRD_CHAIN + "`", "to declare `" + THIRD_CHAIN + "`",
				() -> index.declareChainKey(THIRD_CHAIN)
			);
			assertOverflow(
				"to declare `" + SECOND_ORDER + "`", "to declare `" + SECOND_ORDER + "`",
				() -> index.declareSortKey(SECOND_ORDER, INTEGER_ASC, 0)
			);

			assertEquals(Set.of(CHAIN, SECOND_CHAIN), index.getDeclaredChainKeys());
			assertEquals(Set.of(), index.getDeclaredSortKeys());
			assertTrue(flush(index).isEmpty(), "A refused declaration must not mark the index dirty");

			// retiring the key holding the largest slot id frees it again
			index.retireChainKey(SECOND_CHAIN);
			index.declareChainKey(THIRD_CHAIN);
			assertEquals(Set.of(CHAIN, THIRD_CHAIN), index.getDeclaredChainKeys());
		}
	}

}
