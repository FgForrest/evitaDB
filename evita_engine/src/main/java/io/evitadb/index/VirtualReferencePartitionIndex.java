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

import com.carrotsearch.hppc.IntIntHashMap;
import io.evitadb.api.exception.IdentifierOverflowException;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.dataType.array.CompositeLongArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.bPlusTree.LongPayloadBucketTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.LeafPageHandle;
import io.evitadb.index.bPlusTree.ValueColumnFactory;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.PersistentTransactionalMap;
import io.evitadb.index.map.PersistentTransactionalProducerMap;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.page.PageStreamRegistry;
import io.evitadb.index.reference.TransactionalReference;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart.ChainPredecessorSlot;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart.SortValueSlot;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionRootRemoval;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NumberUtils;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Keeps, for one reference of one entity type and scope, what is known about each reference partition that has no
 * dedicated reduced entity index of its own: which owner entity it belongs to, which entity it references and which
 * representative attribute values tell it apart from the other partitions of the same owner and referenced entity.
 * It is carried by {@link ReferencedTypeEntityIndex}.
 *
 * # One tree, several slots
 *
 * Every entry lives in a single UNIQUE {@link LongPayloadBucketTree} keyed by `pack(slotId, partitionPrimaryKey)` and
 * holding one `long` payload per key. The slot is the high half of the key, so the entries of one
 * {@link VirtualReferencePartitionSlot} form one contiguous, partition-ordered key range:
 *
 * - {@link VirtualReferencePartitionSlot#PARTITION_OWNER} holds `pack(ownerPrimaryKey, targetPrimaryKey)` and is the
 *   single authority on which owner a partition belongs to,
 * - {@link VirtualReferencePartitionSlot#REPRESENTATIVE_TUPLE} holds the id of the partition's representative
 *   attribute values in the tuple dictionary below.
 *
 * Partition primary keys are internal sequence values and always positive, which keeps the low half of every key
 * ordered within its slot. Owner and target primary keys are external and may be any `int` - they appear only in
 * payloads, never in keys.
 *
 * # Tuple dictionary
 *
 * Representative attribute values repeat across many partitions (they mirror an application enumeration such as a
 * media role), so each distinct combination is stored once in a dictionary and the partitions refer to it by id. The
 * dictionary counts how many partitions use each tuple and drops a tuple together with its last user. Ids are
 * allocated one above the largest id in use, so an id is reused only after nothing refers to it any longer.
 *
 * # Sort value slots
 *
 * Partitions are also ordered by their sortable attributes and sortable attribute compounds. Each sort key - an
 * attribute or a compound, with its locale - is declared once and gets a slot of its own, with an id from
 * {@link VirtualReferencePartitionSlot#FIRST_DECLARED_SLOT_ID} up that stays fixed while the key is declared. The
 * entry of a partition in that slot holds the id of the partition's sort value in the slot's value dictionary, which
 * keeps every distinct value once, normalized exactly as {@link SortIndex} normalizes it, and counts its users the way
 * the tuple dictionary does. Values the sort key orders as equal - `1.0` and `1.00` in a compound - are one value, as
 * they are one bucket of the sort index.
 *
 * A sort value is recorded either as the attribute value or in the normalized form a sort index stores - the form
 * {@link SortIndex#getValueOfRecord(int)} reads back, with a scaled `Integer` in place of a `BigDecimal` and an
 * `Instant` in place of an `OffsetDateTime`. The normalized form is first turned back into a value of the declared
 * type that normalizes onto it again, so both take the one write path and leave the same entry behind.
 *
 * Next to the entries the index keeps one {@link OwnerSortIndex} per sort key, its record ids being partition primary
 * keys, and every put and removal updates the slot and the sort index together. The sort index itself is never
 * persisted. The slot and its dictionary are, and the sort index is rebuilt from them on load by a counting pass:
 * the persisted entries of a slot come in ascending partition order, so bucketing them by value id and laying the
 * buckets out in the order of the sorted distinct values yields exactly the blocks of ascending record ids the sort
 * index holds. Only the distinct values are ever compared, and the pass allocates nothing per entry.
 *
 * # Chain predecessor slots
 *
 * A partition can also take part in a chain - an attribute of the `Predecessor` or `ReferencedEntityPredecessor`
 * type. Each chained attribute, with its locale, is declared once and gets a slot of its own from the same id range as
 * the sort keys. The entry of a partition in that slot holds the primary key of its predecessor as the chain index
 * receives it, and the entry exists exactly when the partition is chained: a partition without an entry has no chain,
 * whatever else the index records about it.
 *
 * # Persistence
 *
 * The tree is persisted granularly, exactly like {@link io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex}:
 * a tree of one leaf is written inline on the root part, a larger one as one
 * {@link VirtualReferencePartitionIndexLeafPagePart} per leaf, rewriting only the leaves a flush changed. The tuple
 * dictionary, the declared sort and chain keys and the sort value dictionaries ride inline on the root, without their
 * counts -
 * those are rebuilt from the entries on load. A PAGED root is therefore rewritten only when its leaf list or one of
 * its dictionaries changes, and a change of one partition's entry costs one leaf page. An index with no entry and no
 * declared sort or chain key persists nothing at all and has no root on disk.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class VirtualReferencePartitionIndex
	implements VoidTransactionMemoryProducer<VirtualReferencePartitionIndex>, IndexDataStructure, Serializable {
	@Serial private static final long serialVersionUID = -5101159094921355474L;
	/**
	 * Returned by the tuple-id lookups when there is no tuple to report.
	 */
	public static final int NO_TUPLE_ID = -1;
	/**
	 * Returned by {@link #getSortValueId(AttributeIndexKey, int)} when the partition holds no sort value for the key.
	 * Value ids of a slot's dictionary are handed out from `1` up, so no id in use is ever negative.
	 */
	public static final int NO_SORT_VALUE_ID = -1;
	/**
	 * The counter value of an exhausted tuple id space or sort value id space. A counter holds the id it hands out
	 * next, so this value itself is never handed out: a counter past it would wrap into the negative range, where sort
	 * value ids no longer order as `pack(slotId, id)` assumes and both kinds of id eventually reach the
	 * {@link #NO_TUPLE_ID} and {@link #NO_SORT_VALUE_ID} markers.
	 */
	private static final int EXHAUSTED_ID_SPACE = Integer.MAX_VALUE;
	/**
	 * Block-size geometry of the entry tree - a 256-entry leaf with the matching minimum split thresholds (identical
	 * to the reference-type cardinality tree).
	 */
	private static final int VALUE_BLOCK_SIZE = 256;
	private static final int MIN_VALUE_BLOCK_SIZE = VALUE_BLOCK_SIZE / 2 - 1;
	private static final int MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(MIN_VALUE_BLOCK_SIZE / 2.0) - 1);
	/**
	 * The single page stream of the index - its entry tree.
	 */
	private static final int ENTRY_PAGE_STREAM = 0;
	/**
	 * Natural `long` order of the entry keys, which sorts by slot first and by partition primary key within a slot.
	 */
	private static final Comparator<Comparable<?>> NATURAL_ORDER = (Comparator) Comparator.naturalOrder();
	/**
	 * Turns the committed copy of a sort index back into the owner sort index it always is - a single shared instance,
	 * so the sort index map holds no function of its own.
	 */
	private static final Function<SortIndex, OwnerSortIndex> TO_OWNER_SORT_INDEX = OwnerSortIndex.class::cast;
	/**
	 * Marks, during the load-time walk over the persisted entries, a run of entries that belongs to a chain predecessor
	 * slot rather than to a sort value slot.
	 */
	private static final int CHAIN_SLOT_INDEX = -2;

	/**
	 * Whether anything changed since the last flush.
	 */
	@Nonnull private final TransactionalBoolean dirty;
	/**
	 * Whether a tuple or a sort value was added to or dropped from its dictionary, or a sort or chain key was declared
	 * or retired, since the last flush - which is what forces a PAGED root to be rewritten even when its leaf list did not
	 * change.
	 */
	@Nonnull private final TransactionalBoolean dictionaryDirty;
	/**
	 * The `pack(slotId, partitionPrimaryKey) -> payload` entries of every slot.
	 */
	@Nonnull private final LongPayloadBucketTree entries;
	/**
	 * Page bookkeeping of the granular storage layout of {@link #entries}. It lives outside transactional memory and
	 * is carried by reference through {@link #createCopyWithMergedTransactionalMemory}.
	 */
	@Nonnull private final PageStreamRegistry pageStreamRegistry;
	/**
	 * The tuple dictionary keyed by the tuple - finds the id of a known tuple. The values are the very boxes
	 * {@link #tuples} is keyed by.
	 */
	@Nonnull private final PersistentTransactionalMap<ComparableArray, Integer> tupleIds;
	/**
	 * The tuple dictionary keyed by the tuple id - resolves an id back to its tuple and counts its users.
	 */
	@Nonnull private final PersistentTransactionalMap<Integer, DictionaryEntry> tuples;
	/**
	 * The id the next tuple added to the dictionary gets - the box it is handed out in is the very one both dictionary
	 * maps then hold. Ids are handed out in ascending order and never twice in the lifetime of the loaded index; a
	 * load starts one above the largest persisted id, so the counter itself is never persisted. Once the counter
	 * reaches {@link #EXHAUSTED_ID_SPACE}, a new tuple is refused with an {@link IdentifierOverflowException}.
	 */
	@Nonnull private final TransactionalReference<Integer> nextTupleId;
	/**
	 * The declared sort keys - the slot of each and what it sorts by.
	 */
	@Nonnull private final PersistentTransactionalMap<AttributeIndexKey, DeclaredSortKey> sortKeys;
	/**
	 * The value dictionaries of every sort value slot keyed by the slot and the normalized value - finds the id of a
	 * known value. The values are the very boxes {@link #sortValues} holds.
	 */
	@Nonnull private final PersistentTransactionalMap<SortValueKey, Integer> sortValueIds;
	/**
	 * The value dictionaries of every sort value slot keyed by `pack(slotId, valueId)` - resolves an id back to its
	 * value and counts its users.
	 */
	@Nonnull private final PersistentTransactionalMap<Long, SortValueEntry> sortValues;
	/**
	 * The sort index of each declared sort key, holding the partition primary keys of the slot's entries.
	 */
	@Nonnull private final PersistentTransactionalProducerMap<AttributeIndexKey, OwnerSortIndex> sortIndexes;
	/**
	 * The declared chain keys - the chained attribute, with its locale, mapped to the id of its slot.
	 */
	@Nonnull private final PersistentTransactionalMap<AttributeIndexKey, Integer> chainKeys;

	/**
	 * Creates a fresh, empty entry tree ordered by natural `long` key order.
	 *
	 * @return the fresh empty long-payload bucket tree
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree createEmptyTree() {
		// the runtime key is always `Long`; the tree is built over the erased `Comparable` key type, while the `Long`
		// value-column factory selects the primitive long[] leaf column
		final Class keyType = Comparable.class;
		final ValueColumnFactory factory = ValueColumnFactory.forKey(Long.class, NATURAL_ORDER);
		return TransactionalBucketBPlusTree.withLongPayload(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			keyType, (Comparator) NATURAL_ORDER, factory
		);
	}

	/**
	 * Wraps the sort indexes in the transactional map that keeps them.
	 *
	 * @param sortIndexes the sort indexes keyed by their sort key
	 * @return the transactional sort index map
	 */
	@Nonnull
	private static PersistentTransactionalProducerMap<AttributeIndexKey, OwnerSortIndex> createSortIndexMap(
		@Nonnull Map<AttributeIndexKey, OwnerSortIndex> sortIndexes
	) {
		return new PersistentTransactionalProducerMap<>(sortIndexes, SortIndex.class, TO_OWNER_SORT_INDEX);
	}

	/**
	 * Rebuilds an index whose entries were persisted as leaf pages, preserving the leaf boundaries and page identities,
	 * so that the first flush after the load rewrites only the leaves that changed.
	 *
	 * @param indexDescription      identification of the index for corruption diagnostics (e.g. the reference)
	 * @param orderedPageSequences  the persisted leaf-page sequences in ascending key order
	 * @param perPageKeys           the keys of each leaf page, positionally aligned with `orderedPageSequences`
	 * @param perPagePayloads       the payloads of each leaf page, positionally aligned with `perPageKeys`
	 * @param highWaterPageSequence the largest page sequence ever allocated for the stream
	 * @param tupleIds              the persisted dictionary tuple ids
	 * @param tuples                the persisted dictionary tuples, aligned with `tupleIds`
	 * @param sortValueSlots        the persisted sort value slots in ascending slot id order
	 * @param chainPredecessorSlots the persisted chain predecessor slots in ascending slot id order
	 * @return the rebuilt index
	 */
	@Nonnull
	public static VirtualReferencePartitionIndex fromPersistedPages(
		@Nonnull String indexDescription,
		@Nonnull int[] orderedPageSequences,
		@Nonnull long[][] perPageKeys,
		@Nonnull long[][] perPagePayloads,
		int highWaterPageSequence,
		@Nonnull int[] tupleIds,
		@Nonnull Serializable[][] tuples,
		@Nonnull SortValueSlot[] sortValueSlots,
		@Nonnull ChainPredecessorSlot[] chainPredecessorSlots
	) {
		Assert.isPremiseValid(
			orderedPageSequences.length == perPageKeys.length && perPageKeys.length == perPagePayloads.length,
			"The number of page sequences must match the number of leaf-page arrays."
		);
		Assert.isPremiseValid(
			orderedPageSequences.length > 0,
			"A paged virtual reference partition index must have at least one leaf page."
		);
		final List<TransactionalBucketBPlusTree> pageTrees = new ArrayList<>(orderedPageSequences.length);
		for (int i = 0; i < orderedPageSequences.length; i++) {
			final long[] keys = perPageKeys[i];
			final long[] payloads = perPagePayloads[i];
			// a page never exceeds a leaf's capacity, so this single-leaf tree never splits
			final TransactionalBucketBPlusTree pageTree = createEmptyTree();
			for (int j = 0; j < keys.length; j++) {
				pageTree.addLongRecord(keys[j], payloads[j]);
			}
			pageTrees.add(pageTree);
		}
		final TransactionalBucketBPlusTree tree = createEmptyTree().assembleFromSingleLeafTrees(
			pageTrees, orderedPageSequences, "virtual reference partition index for " + indexDescription
		);
		final PageStreamRegistry registry = PageStreamRegistry.restoredFrom(
			ENTRY_PAGE_STREAM, highWaterPageSequence, tree.leafPageHandles()
		);
		return new VirtualReferencePartitionIndex(
			tree, registry, tupleIds, tuples, sortValueSlots, chainPredecessorSlots, perPageKeys, perPagePayloads,
			indexDescription
		);
	}

	/**
	 * Creates a fresh, empty index.
	 */
	public VirtualReferencePartitionIndex() {
		this.dirty = new TransactionalBoolean();
		this.dictionaryDirty = new TransactionalBoolean();
		this.entries = createEmptyTree();
		this.pageStreamRegistry = new PageStreamRegistry();
		this.tupleIds = new PersistentTransactionalMap<>(CollectionUtils.createHashMap(4));
		this.tuples = new PersistentTransactionalMap<>(CollectionUtils.createHashMap(4));
		this.nextTupleId = new TransactionalReference<>(1);
		this.sortKeys = new PersistentTransactionalMap<>(CollectionUtils.createHashMap(4));
		this.sortValueIds = new PersistentTransactionalMap<>(CollectionUtils.createHashMap(16));
		this.sortValues = new PersistentTransactionalMap<>(CollectionUtils.createHashMap(16));
		this.sortIndexes = createSortIndexMap(CollectionUtils.createHashMap(4));
		this.chainKeys = new PersistentTransactionalMap<>(CollectionUtils.createHashMap(4));
	}

	/**
	 * Rebuilds an index whose entries were persisted inline on its root.
	 *
	 * @param indexDescription identification of the index for corruption diagnostics (e.g. the reference)
	 * @param keys             the persisted entry keys in ascending order
	 * @param payloads         the persisted entry payloads, aligned with `keys`
	 * @param tupleIds         the persisted dictionary tuple ids
	 * @param tuples           the persisted dictionary tuples, aligned with `tupleIds`
	 * @param sortValueSlots        the persisted sort value slots in ascending slot id order
	 * @param chainPredecessorSlots the persisted chain predecessor slots in ascending slot id order
	 */
	public VirtualReferencePartitionIndex(
		@Nonnull String indexDescription,
		@Nonnull long[] keys,
		@Nonnull long[] payloads,
		@Nonnull int[] tupleIds,
		@Nonnull Serializable[][] tuples,
		@Nonnull SortValueSlot[] sortValueSlots,
		@Nonnull ChainPredecessorSlot[] chainPredecessorSlots
	) {
		this(
			seedTree(keys, payloads), new PageStreamRegistry(), tupleIds, tuples, sortValueSlots, chainPredecessorSlots,
			new long[][]{keys}, new long[][]{payloads}, indexDescription
		);
	}

	/**
	 * Adopts a tree rebuilt from storage and rebuilds the dictionaries and the sort indexes around it - the persisted
	 * tuples paired with the user counts read off the {@link VirtualReferencePartitionSlot#REPRESENTATIVE_TUPLE}
	 * entries, each sort value slot by {@link #rebuildSortKeys}, and the declared chain keys.
	 *
	 * @param tree               the rebuilt entry tree
	 * @param pageStreamRegistry the page bookkeeping matching the tree
	 * @param tupleIds           the persisted dictionary tuple ids
	 * @param tuples             the persisted dictionary tuples, aligned with `tupleIds`
	 * @param sortValueSlots     the persisted sort value slots in ascending slot id order
	 * @param chainSlots         the persisted chain predecessor slots in ascending slot id order
	 * @param keyColumns         the persisted entry keys the tree was built from, in ascending key order across the
	 *                           columns
	 * @param payloadColumns     the persisted entry payloads, aligned with `keyColumns`
	 * @param indexDescription   identification of the index for corruption diagnostics
	 */
	private VirtualReferencePartitionIndex(
		@Nonnull TransactionalBucketBPlusTree tree,
		@Nonnull PageStreamRegistry pageStreamRegistry,
		@Nonnull int[] tupleIds,
		@Nonnull Serializable[][] tuples,
		@Nonnull SortValueSlot[] sortValueSlots,
		@Nonnull ChainPredecessorSlot[] chainSlots,
		@Nonnull long[][] keyColumns,
		@Nonnull long[][] payloadColumns,
		@Nonnull String indexDescription
	) {
		Assert.isPremiseValid(
			tupleIds.length == tuples.length, "Tuple ids and tuples must be positionally aligned!"
		);
		this.dirty = new TransactionalBoolean();
		this.dictionaryDirty = new TransactionalBoolean();
		this.entries = tree;
		this.pageStreamRegistry = pageStreamRegistry;

		// count the users of every tuple id the entries refer to
		final Map<Integer, int[]> userCounts = CollectionUtils.createHashMap(tupleIds.length);
		for (final int tupleId : tupleIds) {
			Assert.isPremiseValid(
				userCounts.put(tupleId, new int[1]) == null,
				() -> new GenericEvitaInternalError(
					"The tuple dictionary of the virtual reference partition index for " + indexDescription +
						" lists tuple id " + tupleId + " twice!"
				)
			);
		}
		forEachEntry(
			VirtualReferencePartitionSlot.REPRESENTATIVE_TUPLE.getId(),
			(partitionPrimaryKey, payload) -> {
				final int[] userCount = userCounts.get((int) payload);
				Assert.isPremiseValid(
					userCount != null,
					() -> new GenericEvitaInternalError(
						"Partition " + partitionPrimaryKey + " of the virtual reference partition index for " +
							indexDescription + " refers to tuple id " + payload + ", which its dictionary lacks!"
					)
				);
				userCount[0]++;
			}
		);

		final Map<ComparableArray, Integer> tupleIdIndex = CollectionUtils.createHashMap(tupleIds.length);
		final Map<Integer, DictionaryEntry> tupleIndex = CollectionUtils.createHashMap(tupleIds.length);
		int largestTupleId = 0;
		for (int i = 0; i < tupleIds.length; i++) {
			final int tupleId = tupleIds[i];
			largestTupleId = Math.max(largestTupleId, tupleId);
			final int userCount = userCounts.get(tupleId)[0];
			Assert.isPremiseValid(
				userCount > 0,
				() -> new GenericEvitaInternalError(
					"The tuple dictionary of the virtual reference partition index for " + indexDescription +
						" holds tuple id " + tupleId + ", which no partition refers to!"
				)
			);
			// one box shared by both maps, see DictionaryEntry
			final Integer boxedId = tupleId;
			final ComparableArray tuple = new ComparableArray(tuples[i]);
			// a second id holding the same tuple would shadow the first in the lookup by values, and releasing the
			// first would then drop the mapping of the second while partitions still refer to it
			final Integer shadowedId = tupleIdIndex.put(tuple, boxedId);
			Assert.isPremiseValid(
				shadowedId == null,
				() -> new GenericEvitaInternalError(
					"The tuple dictionary of the virtual reference partition index for " + indexDescription +
						" holds one tuple under tuple ids " + shadowedId + " and " + tupleId + "!"
				)
			);
			tupleIndex.put(boxedId, new DictionaryEntry(tuple, boxedId, userCount));
		}
		this.tupleIds = new PersistentTransactionalMap<>(tupleIdIndex);
		this.tuples = new PersistentTransactionalMap<>(tupleIndex);
		this.nextTupleId = new TransactionalReference<>(nextIdAfterLoad(largestTupleId));

		final int[] chainSlotIds = new int[chainSlots.length];
		final Map<AttributeIndexKey, Integer> chainKeyIndex = CollectionUtils.createHashMap(chainSlots.length);
		for (int i = 0; i < chainSlots.length; i++) {
			final int slotId = chainSlots[i].slotId();
			if (slotId < VirtualReferencePartitionSlot.FIRST_DECLARED_SLOT_ID || (i > 0 && slotId <= chainSlotIds[i - 1])) {
				throw new GenericEvitaInternalError(
					"The chain predecessor slots of the virtual reference partition index for " + indexDescription +
						" are not in ascending order of declared slot ids at slot " + slotId + "!"
				);
			}
			chainSlotIds[i] = slotId;
			chainKeyIndex.put(chainSlots[i].attributeIndexKey(), slotId);
		}
		this.chainKeys = new PersistentTransactionalMap<>(chainKeyIndex);

		final Map<AttributeIndexKey, DeclaredSortKey> sortKeyIndex = CollectionUtils.createHashMap(sortValueSlots.length);
		final Map<SortValueKey, Integer> sortValueIdIndex = CollectionUtils.createHashMap(16);
		final Map<Long, SortValueEntry> sortValueIndex = CollectionUtils.createHashMap(16);
		final Map<AttributeIndexKey, OwnerSortIndex> sortIndexIndex =
			CollectionUtils.createHashMap(sortValueSlots.length);
		rebuildSortKeys(
			indexDescription, sortValueSlots, chainSlotIds, keyColumns, payloadColumns,
			sortKeyIndex, sortValueIdIndex, sortValueIndex, sortIndexIndex
		);
		this.sortKeys = new PersistentTransactionalMap<>(sortKeyIndex);
		this.sortValueIds = new PersistentTransactionalMap<>(sortValueIdIndex);
		this.sortValues = new PersistentTransactionalMap<>(sortValueIndex);
		this.sortIndexes = createSortIndexMap(sortIndexIndex);
	}

	/**
	 * Adopts already-committed state directly; used by {@link #createCopyWithMergedTransactionalMemory}.
	 *
	 * @param committedTree      the committed entry tree
	 * @param pageStreamRegistry the page bookkeeping, carried by reference
	 * @param tupleIds           the committed `tuple -> id` map
	 * @param tuples             the committed `id -> entry` map
	 * @param nextTupleId        the committed id the next tuple gets
	 * @param sortKeys           the committed declared sort keys
	 * @param sortValueIds       the committed `(slot, value) -> id` map
	 * @param sortValues         the committed `pack(slot, id) -> entry` map
	 * @param sortIndexes        the committed sort indexes
	 * @param chainKeys          the committed declared chain keys
	 */
	private VirtualReferencePartitionIndex(
		@Nonnull LongPayloadBucketTree committedTree,
		@Nonnull PageStreamRegistry pageStreamRegistry,
		@Nonnull Map<ComparableArray, Integer> tupleIds,
		@Nonnull Map<Integer, DictionaryEntry> tuples,
		@Nonnull Integer nextTupleId,
		@Nonnull Map<AttributeIndexKey, DeclaredSortKey> sortKeys,
		@Nonnull Map<SortValueKey, Integer> sortValueIds,
		@Nonnull Map<Long, SortValueEntry> sortValues,
		@Nonnull Map<AttributeIndexKey, OwnerSortIndex> sortIndexes,
		@Nonnull Map<AttributeIndexKey, Integer> chainKeys
	) {
		this.dirty = new TransactionalBoolean();
		this.dictionaryDirty = new TransactionalBoolean();
		this.entries = committedTree;
		this.pageStreamRegistry = pageStreamRegistry;
		this.tupleIds = new PersistentTransactionalMap<>(tupleIds);
		this.tuples = new PersistentTransactionalMap<>(tuples);
		this.nextTupleId = new TransactionalReference<>(nextTupleId);
		this.sortKeys = new PersistentTransactionalMap<>(sortKeys);
		this.sortValueIds = new PersistentTransactionalMap<>(sortValueIds);
		this.sortValues = new PersistentTransactionalMap<>(sortValues);
		this.sortIndexes = createSortIndexMap(sortIndexes);
		this.chainKeys = new PersistentTransactionalMap<>(chainKeys);
	}

	/**
	 * Builds a tree holding the given entries.
	 *
	 * @param keys     the entry keys
	 * @param payloads the entry payloads, aligned with `keys`
	 * @return the seeded tree
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree seedTree(@Nonnull long[] keys, @Nonnull long[] payloads) {
		Assert.isPremiseValid(keys.length == payloads.length, "Keys and payloads must be positionally aligned!");
		final TransactionalBucketBPlusTree tree = createEmptyTree();
		for (int i = 0; i < keys.length; i++) {
			tree.addLongRecord(keys[i], payloads[i]);
		}
		return tree;
	}

	/**
	 * Returns the id a tuple or sort value dictionary hands out next after a load whose largest persisted id is
	 * `largestId` - one above it. No allocation hands out {@link #EXHAUSTED_ID_SPACE}, but should one be persisted, the
	 * dictionary resumes exhausted rather than wrapping its counter into the negative range.
	 *
	 * @param largestId the largest persisted id of the dictionary, `0` when it is empty
	 * @return the id the dictionary hands out next
	 */
	private static int nextIdAfterLoad(int largestId) {
		return largestId == EXHAUSTED_ID_SPACE ? EXHAUSTED_ID_SPACE : largestId + 1;
	}

	/**
	 * Composes the tree key of a partition's entry in a slot.
	 *
	 * @param slot                the slot
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @return the composed key
	 */
	private static long key(@Nonnull VirtualReferencePartitionSlot slot, int partitionPrimaryKey) {
		return key(slot.getId(), partitionPrimaryKey);
	}

	/**
	 * Composes the tree key of a partition's entry in the slot with the given id.
	 *
	 * @param slotId              the slot id
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @return the composed key
	 */
	private static long key(int slotId, int partitionPrimaryKey) {
		// a plain branch rather than an assertion with a message, so the valid path builds no string
		if (partitionPrimaryKey <= 0) {
			throw new GenericEvitaInternalError(
				"Partition primary key must be positive, but was " + partitionPrimaryKey + "!"
			);
		}
		return NumberUtils.pack(slotId, partitionPrimaryKey);
	}

	/**
	 * Returns TRUE if the index holds no entry and no declared sort or chain key.
	 *
	 * @return TRUE if the index holds nothing
	 */
	public boolean isEmpty() {
		return this.entries.size() == 0 && this.sortKeys.isEmpty() && this.chainKeys.isEmpty();
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Returns how many entries the index holds across all its slots - owner rows, representative tuples, sort values
	 * and chain predecessors together. The dictionaries and the declared keys are not entries.
	 *
	 * @return the number of entries
	 */
	// TODO JNO - remove at the end of #1615
	public int getEntryCount() {
		return this.entries.size();
	}

	/**
	 * Returns the declared sort keys - the attributes and compounds, with their locales, the partitions are sorted by.
	 *
	 * @return an unmodifiable copy of the declared sort keys
	 */
	@Nonnull
	public Set<AttributeIndexKey> getDeclaredSortKeys() {
		return Set.copyOf(this.sortKeys.keySet());
	}

	/**
	 * Returns the declared chain keys - the chained attributes, with their locales, the partitions take part in.
	 *
	 * @return an unmodifiable copy of the declared chain keys
	 */
	@Nonnull
	public Set<AttributeIndexKey> getDeclaredChainKeys() {
		return Set.copyOf(this.chainKeys.keySet());
	}

	/**
	 * Records the owner of the partition and the entity the partition references, replacing the previous record.
	 *
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @param ownerPrimaryKey     the primary key of the owner entity
	 * @param targetPrimaryKey    the primary key of the referenced entity
	 */
	public void putPartitionOwner(int partitionPrimaryKey, int ownerPrimaryKey, int targetPrimaryKey) {
		putPayload(
			key(VirtualReferencePartitionSlot.PARTITION_OWNER, partitionPrimaryKey),
			NumberUtils.pack(ownerPrimaryKey, targetPrimaryKey)
		);
	}

	/**
	 * Forgets the owner of the partition.
	 *
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @return true if the partition had an owner recorded
	 */
	public boolean removePartitionOwner(int partitionPrimaryKey) {
		return removePayload(key(VirtualReferencePartitionSlot.PARTITION_OWNER, partitionPrimaryKey));
	}

	/**
	 * Forgets everything the index records about the partition: its owner, its representative attribute values, its
	 * sort value for every declared sort key and its predecessor in every declared chain. The declared keys themselves
	 * stay - see {@link #retireAllKeysIfNoPartition()}.
	 *
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @return true if the index recorded anything about the partition
	 */
	public boolean removePartition(int partitionPrimaryKey) {
		boolean removed = removePartitionOwner(partitionPrimaryKey);
		removed |= removeRepresentativeTuple(partitionPrimaryKey);
		for (final AttributeIndexKey sortKey : getDeclaredSortKeys()) {
			removed |= removeSortValue(sortKey, partitionPrimaryKey);
		}
		for (final AttributeIndexKey chainKey : getDeclaredChainKeys()) {
			removed |= removeChainPredecessor(chainKey, partitionPrimaryKey);
		}
		return removed;
	}

	/**
	 * Retires every declared sort and chain key once no partition has an owner recorded any longer. Every entry of a
	 * declared key belongs to a partition with an owner, so with no owner left the keys hold nothing and only keep the
	 * index from being empty - and the referenced-type entity index carrying it from being dropped.
	 *
	 * @return true if any key was retired
	 */
	public boolean retireAllKeysIfNoPartition() {
		if (hasEntry(VirtualReferencePartitionSlot.PARTITION_OWNER.getId())) {
			return false;
		}
		boolean retired = false;
		// the declared keys are read as copies, so retiring one does not change the set being iterated
		for (final AttributeIndexKey sortKey : getDeclaredSortKeys()) {
			retired |= retireSortKey(sortKey);
		}
		for (final AttributeIndexKey chainKey : getDeclaredChainKeys()) {
			retired |= retireChainKey(chainKey);
		}
		return retired;
	}

	/**
	 * Returns the owner of the partition and the entity it references, packed as `pack(ownerPrimaryKey,
	 * targetPrimaryKey)` - {@link NumberUtils#unpackHigh(long)} reads the owner, {@link NumberUtils#unpackLow(long)}
	 * the referenced entity.
	 *
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @return the packed owner and referenced entity, empty when no owner is recorded
	 */
	@Nonnull
	public OptionalLong getPartitionOwner(int partitionPrimaryKey) {
		return this.entries.getLongRecordEqualTo(
			key(VirtualReferencePartitionSlot.PARTITION_OWNER, partitionPrimaryKey)
		);
	}

	/**
	 * Records the representative attribute values of the partition, replacing the previous ones. The values are
	 * compared element-wise, as {@link io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey}
	 * compares them, and a combination the dictionary does not know yet is copied into it.
	 *
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @param tuple               the representative attribute values
	 */
	public void putRepresentativeTuple(int partitionPrimaryKey, @Nonnull Serializable[] tuple) {
		final long key = key(VirtualReferencePartitionSlot.REPRESENTATIVE_TUPLE, partitionPrimaryKey);
		final Integer knownTupleId = this.tupleIds.get(new ComparableArray(tuple));
		final OptionalLong previousTupleId = this.entries.getLongRecordEqualTo(key);
		if (knownTupleId != null && previousTupleId.isPresent() && previousTupleId.getAsLong() == knownTupleId) {
			return;
		}
		// the new tuple is acquired before the previous one is released, so the two never share an id
		final int tupleId = knownTupleId == null ? addTuple(tuple) : retainTuple(knownTupleId);
		if (previousTupleId.isPresent()) {
			this.entries.removeLongRecord(key);
			releaseTuple((int) previousTupleId.getAsLong());
		}
		this.entries.addLongRecord(key, (long) tupleId);
		this.dirty.setToTrue();
	}

	/**
	 * Forgets the representative attribute values of the partition, dropping their dictionary tuple when the
	 * partition was its last user.
	 *
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @return true if the partition had representative attribute values recorded
	 */
	public boolean removeRepresentativeTuple(int partitionPrimaryKey) {
		final long key = key(VirtualReferencePartitionSlot.REPRESENTATIVE_TUPLE, partitionPrimaryKey);
		final OptionalLong previousTupleId = this.entries.getLongRecordEqualTo(key);
		if (previousTupleId.isEmpty()) {
			return false;
		}
		this.entries.removeLongRecord(key);
		releaseTuple((int) previousTupleId.getAsLong());
		this.dirty.setToTrue();
		return true;
	}

	/**
	 * Returns the dictionary id of the partition's representative attribute values.
	 *
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @return the tuple id, or {@link #NO_TUPLE_ID} when the partition has none recorded
	 */
	public int getRepresentativeTupleId(int partitionPrimaryKey) {
		final OptionalLong tupleId = this.entries.getLongRecordEqualTo(
			key(VirtualReferencePartitionSlot.REPRESENTATIVE_TUPLE, partitionPrimaryKey)
		);
		return tupleId.isPresent() ? (int) tupleId.getAsLong() : NO_TUPLE_ID;
	}

	/**
	 * Returns the dictionary id of the given representative attribute values.
	 *
	 * @param tuple the representative attribute values, compared element-wise
	 * @return the tuple id, or {@link #NO_TUPLE_ID} when no partition uses these values
	 */
	public int getTupleId(@Nonnull Serializable[] tuple) {
		final Integer tupleId = this.tupleIds.get(new ComparableArray(tuple));
		return tupleId == null ? NO_TUPLE_ID : tupleId;
	}

	/**
	 * Returns the representative attribute values the dictionary keeps under the given id. The array is the
	 * dictionary's own and must not be modified.
	 *
	 * @param tupleId the tuple id
	 * @return the representative attribute values, or `null` when the id is not in use
	 */
	@Nullable
	public Serializable[] getTuple(int tupleId) {
		final DictionaryEntry entry = this.tuples.get(tupleId);
		return entry == null ? null : entry.tuple().array();
	}

	/**
	 * Visits every entry of the slot in ascending partition primary key order.
	 *
	 * @param slot     the slot to visit
	 * @param consumer invoked with the partition primary key and the payload of each entry
	 */
	public void forEachEntry(@Nonnull VirtualReferencePartitionSlot slot, @Nonnull EntryConsumer consumer) {
		forEachEntry(slot.getId(), consumer);
	}

	/**
	 * Declares a sort key: gives it a slot of its own and an empty sort index. Declaring a key that is already
	 * declared with the same comparator base and decimal places does nothing; declaring it with different ones is a
	 * programming error, because the values already in its slot were normalized and ordered by the old ones.
	 *
	 * @param attributeIndexKey    the attribute or compound, with its locale, to sort the partitions by
	 * @param comparatorBase       one descriptor per sorted element - a single one for a plain attribute, at least two
	 *                             for a sortable attribute compound
	 * @param indexedDecimalPlaces the decimal places a `BigDecimal` attribute is scaled by, `0` for any other type
	 */
	public void declareSortKey(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull ComparatorSource[] comparatorBase,
		int indexedDecimalPlaces
	) {
		if (this.chainKeys.containsKey(attributeIndexKey)) {
			throw new GenericEvitaInternalError(
				"Attribute `" + attributeIndexKey + "` is declared as a chain key, it cannot be a sort key too!"
			);
		}
		final DeclaredSortKey declared = this.sortKeys.get(attributeIndexKey);
		if (declared != null) {
			if (!Arrays.equals(declared.comparatorBase(), comparatorBase) ||
				declared.indexedDecimalPlaces() != indexedDecimalPlaces) {
				throw new GenericEvitaInternalError(
					"Sort key `" + attributeIndexKey + "` is already declared with comparator base " +
						Arrays.toString(declared.comparatorBase()) + " and " + declared.indexedDecimalPlaces() +
						" decimal places, it cannot be declared again with " + Arrays.toString(comparatorBase) +
						" and " + indexedDecimalPlaces + "!"
				);
			}
			return;
		}
		final DeclaredSortKey sortKey = DeclaredSortKey.of(
			nextDeclaredSlotId(attributeIndexKey), attributeIndexKey,
			Arrays.copyOf(comparatorBase, comparatorBase.length), indexedDecimalPlaces, 1
		);
		this.sortKeys.put(attributeIndexKey, sortKey);
		this.sortIndexes.put(attributeIndexKey, createSortIndex(sortKey, new int[0], new Serializable[0], Map.of()));
		this.dictionaryDirty.setToTrue();
		this.dirty.setToTrue();
	}

	/**
	 * Retires a sort key: removes the sort value of every partition from its slot, its value dictionary, its sort index
	 * and the slot itself.
	 *
	 * @param attributeIndexKey the attribute or compound, with its locale, the partitions are sorted by
	 * @return true if the key was declared
	 */
	public boolean retireSortKey(@Nonnull AttributeIndexKey attributeIndexKey) {
		final DeclaredSortKey sortKey = this.sortKeys.remove(attributeIndexKey);
		if (sortKey == null) {
			return false;
		}
		final int slotId = sortKey.slotId();
		removeSlotEntries(slotId);
		final List<SortValueEntry> slotValues = new ArrayList<>(16);
		this.sortValues.forEach(
			(packedId, entry) -> {
				if (entry.key().slotId() == slotId) {
					slotValues.add(entry);
				}
			}
		);
		for (final SortValueEntry entry : slotValues) {
			this.sortValues.remove(NumberUtils.pack(slotId, entry.id()));
			this.sortValueIds.remove(entry.key());
		}
		this.sortIndexes.remove(attributeIndexKey);
		this.dictionaryDirty.setToTrue();
		this.dirty.setToTrue();
		return true;
	}

	/**
	 * Records the sort value of the partition for a declared sort key of a plain attribute, replacing the previous one.
	 * The value is normalized as {@link SortIndex} normalizes it, a value the slot's dictionary does not know yet is
	 * added to it, and the partition is moved to the value's block of the key's sort index.
	 *
	 * @param attributeIndexKey   the declared sort key of a plain attribute
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @param value               the attribute value, of the type the key was declared with
	 */
	public void putSortValue(
		@Nonnull AttributeIndexKey attributeIndexKey,
		int partitionPrimaryKey,
		@Nonnull Serializable value
	) {
		putSortValue(attributeIndexKey, partitionPrimaryKey, value, false);
	}

	/**
	 * Records the sort value of the partition for a declared sort key of a sortable attribute compound, replacing the
	 * previous one. See {@link #putSortValue(AttributeIndexKey, int, Serializable)}; the array is not modified.
	 *
	 * @param attributeIndexKey   the declared sort key of a sortable attribute compound
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @param value               the values of the compound elements, in element order, `null` for a missing one
	 */
	public void putSortValue(
		@Nonnull AttributeIndexKey attributeIndexKey,
		int partitionPrimaryKey,
		@Nonnull Serializable[] value
	) {
		putSortValue(attributeIndexKey, partitionPrimaryKey, value, true);
	}

	/**
	 * Records the sort value of the partition for a declared sort key from its normalized form - the form a sort index
	 * stores and {@link SortIndex#getValueOfRecord(int)} reads back: a scaled `Integer` for a `BigDecimal` attribute,
	 * a millisecond `Instant` for an `OffsetDateTime` or a `LocalDateTime`, a decomposed `String`, a comparable wrapper
	 * of a `Locale` or a `Currency`, and a {@link ComparableArray} of such elements for a compound. The value is turned
	 * back into one of the declared type that normalizes onto it again and is then recorded exactly as
	 * {@link #putSortValue(AttributeIndexKey, int, Serializable)} records an attribute value, so the slot ends up with
	 * the same entry and the sort index with the same block as if the attribute value had been put.
	 *
	 * @param attributeIndexKey   the declared sort key
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @param normalizedValue     the normalized value, a {@link ComparableArray} for a sortable attribute compound
	 */
	public void putNormalizedSortValue(
		@Nonnull AttributeIndexKey attributeIndexKey,
		int partitionPrimaryKey,
		@Nonnull Serializable normalizedValue
	) {
		final DeclaredSortKey sortKey = getDeclaredSortKey(attributeIndexKey);
		if (sortKey.isCompound()) {
			if (!(normalizedValue instanceof ComparableArray compound)) {
				throw new GenericEvitaInternalError(
					"Sort key `" + attributeIndexKey + "` sorts by a compound, but `" +
						normalizedValue.getClass().getName() + "` was passed instead of its normalized elements!"
				);
			}
			putSortValue(attributeIndexKey, partitionPrimaryKey, denormalize(sortKey, compound), true);
		} else {
			putSortValue(
				attributeIndexKey, partitionPrimaryKey,
				Objects.requireNonNull(
					denormalize(sortKey.comparatorBase()[0].type(), sortKey.indexedDecimalPlaces(), normalizedValue)
				),
				false
			);
		}
	}

	/**
	 * Forgets the sort value of the partition for a declared sort key, dropping the value from the slot's dictionary
	 * when the partition was its last user.
	 *
	 * @param attributeIndexKey   the declared sort key
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @return true if the partition had a sort value recorded
	 */
	public boolean removeSortValue(@Nonnull AttributeIndexKey attributeIndexKey, int partitionPrimaryKey) {
		final DeclaredSortKey sortKey = getDeclaredSortKey(attributeIndexKey);
		final long key = key(sortKey.slotId(), partitionPrimaryKey);
		final OptionalLong previousValueId = this.entries.getLongRecordEqualTo(key);
		if (previousValueId.isEmpty()) {
			return false;
		}
		this.entries.removeLongRecord(key);
		final Serializable previousValue = releaseSortValue(sortKey.slotId(), (int) previousValueId.getAsLong());
		this.sortIndexes.markValueMutated(attributeIndexKey);
		removeFromSortIndex(sortKey, getSortIndexOfDeclaredKey(attributeIndexKey), previousValue, partitionPrimaryKey);
		this.dirty.setToTrue();
		return true;
	}

	/**
	 * Returns TRUE when the partition has a sort value recorded for the sort key.
	 *
	 * @param attributeIndexKey   the sort key
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @return TRUE when the key is declared and the partition has a sort value for it
	 */
	public boolean hasSortValue(@Nonnull AttributeIndexKey attributeIndexKey, int partitionPrimaryKey) {
		final DeclaredSortKey sortKey = this.sortKeys.get(attributeIndexKey);
		return sortKey != null &&
			this.entries.getLongRecordEqualTo(key(sortKey.slotId(), partitionPrimaryKey)).isPresent();
	}

	/**
	 * Returns TRUE when the partition has exactly the given sort value recorded for a declared sort key - the value is
	 * normalized and compared as the slot's dictionary compares its values, so `1.0` and `1.00` of a compound element
	 * are one value, as they are one block of the sort index.
	 *
	 * @param attributeIndexKey   the declared sort key
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @param value               the attribute value of the type the key was declared with, or the values of the
	 *                            compound elements in element order
	 * @return TRUE when the partition holds the value
	 */
	public boolean hasSortValue(
		@Nonnull AttributeIndexKey attributeIndexKey,
		int partitionPrimaryKey,
		@Nonnull Serializable value
	) {
		final DeclaredSortKey sortKey = getDeclaredSortKey(attributeIndexKey);
		final OptionalLong valueId = this.entries.getLongRecordEqualTo(key(sortKey.slotId(), partitionPrimaryKey));
		return valueId.isPresent() &&
			getSortValueEntry(sortKey.slotId(), (int) valueId.getAsLong()).key()
				.equals(new SortValueKey(sortKey.slotId(), normalize(sortKey, value)));
	}

	/**
	 * Returns the sort index of a sort key, its record ids being the primary keys of the partitions holding a sort value
	 * for the key.
	 *
	 * @param attributeIndexKey the sort key
	 * @return the sort index, or `null` when the key is not declared
	 */
	@Nullable
	public OwnerSortIndex getSortIndex(@Nonnull AttributeIndexKey attributeIndexKey) {
		return this.sortIndexes.get(attributeIndexKey);
	}

	/**
	 * Returns the id the partition's sort value has in the value dictionary of the sort key - one lookup of the
	 * partition's entry in the key's slot, `O(log n)` in the number of entries. The id names the value for as long as
	 * any partition holds it; {@link #getNormalizedSortValue(AttributeIndexKey, int)} resolves it back to the value.
	 *
	 * @param attributeIndexKey   the sort key
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @return the value id, or {@link #NO_SORT_VALUE_ID} when the key is not declared or the partition holds no value
	 *         for it
	 */
	public int getSortValueId(@Nonnull AttributeIndexKey attributeIndexKey, int partitionPrimaryKey) {
		final DeclaredSortKey sortKey = this.sortKeys.get(attributeIndexKey);
		if (sortKey == null) {
			return NO_SORT_VALUE_ID;
		}
		final OptionalLong valueId = this.entries.getLongRecordEqualTo(key(sortKey.slotId(), partitionPrimaryKey));
		return valueId.isPresent() ? (int) valueId.getAsLong() : NO_SORT_VALUE_ID;
	}

	/**
	 * Returns the sort value the value dictionary of a declared sort key keeps under the id, in the normalized form a
	 * sort index stores and {@link SortIndex#getValueOfRecord(int)} reads back - a {@link ComparableArray} for a
	 * sortable attribute compound. One hash lookup, whatever the number of partitions or values. The value is the
	 * dictionary's own and must not be modified.
	 *
	 * @param attributeIndexKey the declared sort key
	 * @param valueId           an id {@link #getSortValueId(AttributeIndexKey, int)} handed out
	 * @return the normalized sort value
	 * @throws GenericEvitaInternalError when the key is not declared or the id is not in use
	 */
	@Nonnull
	public Serializable getNormalizedSortValue(@Nonnull AttributeIndexKey attributeIndexKey, int valueId) {
		return getSortValueEntry(getDeclaredSortKey(attributeIndexKey).slotId(), valueId).key().value();
	}

	/**
	 * Opens a forward cursor over the owner rows of the partitions, in ascending partition primary key order, starting
	 * at the first partition whose primary key is greater than or equal to the given one. The cursor is positioned
	 * before that row - {@link PartitionOwnerCursor#next()} or {@link PartitionOwnerCursor#advanceTo(int)} moves onto
	 * it. It is the means of matching an ascending set of partition primary keys with the rows in one merge, rather
	 * than with one lookup per key.
	 *
	 * @param fromPartitionPrimaryKey the smallest partition primary key of interest
	 * @return the cursor
	 */
	@Nonnull
	public PartitionOwnerCursor seekPartitionOwner(int fromPartitionPrimaryKey) {
		return new PartitionOwnerCursor(this.entries, fromPartitionPrimaryKey);
	}

	/**
	 * Declares a chain key: gives the chained attribute a slot of its own. Declaring a key that is already declared
	 * does nothing.
	 *
	 * @param attributeIndexKey the attribute of the `Predecessor` or `ReferencedEntityPredecessor` type, with its locale
	 */
	public void declareChainKey(@Nonnull AttributeIndexKey attributeIndexKey) {
		if (this.sortKeys.containsKey(attributeIndexKey)) {
			throw new GenericEvitaInternalError(
				"Attribute `" + attributeIndexKey + "` is declared as a sort key, it cannot be a chain key too!"
			);
		}
		if (this.chainKeys.containsKey(attributeIndexKey)) {
			return;
		}
		this.chainKeys.put(attributeIndexKey, nextDeclaredSlotId(attributeIndexKey));
		this.dictionaryDirty.setToTrue();
		this.dirty.setToTrue();
	}

	/**
	 * Retires a chain key: removes the predecessor of every partition from its slot, and the slot itself.
	 *
	 * @param attributeIndexKey the chained attribute, with its locale
	 * @return true if the key was declared
	 */
	public boolean retireChainKey(@Nonnull AttributeIndexKey attributeIndexKey) {
		final Integer slotId = this.chainKeys.remove(attributeIndexKey);
		if (slotId == null) {
			return false;
		}
		removeSlotEntries(slotId);
		this.dictionaryDirty.setToTrue();
		this.dirty.setToTrue();
		return true;
	}

	/**
	 * Records the predecessor of the partition in the chain of a declared chain key, replacing the previous one. The
	 * partition is chained from then on.
	 *
	 * @param attributeIndexKey      the declared chain key
	 * @param partitionPrimaryKey    the partition primary key, positive
	 * @param predecessorPrimaryKey the primary key of the predecessor as the chain index receives it, any `int`
	 */
	public void putChainPredecessor(
		@Nonnull AttributeIndexKey attributeIndexKey,
		int partitionPrimaryKey,
		int predecessorPrimaryKey
	) {
		putPayload(key(getChainSlotId(attributeIndexKey), partitionPrimaryKey), predecessorPrimaryKey);
	}

	/**
	 * Forgets the predecessor of the partition in the chain of a declared chain key - the partition is no longer
	 * chained.
	 *
	 * @param attributeIndexKey   the declared chain key
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @return true if the partition was chained
	 */
	public boolean removeChainPredecessor(@Nonnull AttributeIndexKey attributeIndexKey, int partitionPrimaryKey) {
		return removePayload(key(getChainSlotId(attributeIndexKey), partitionPrimaryKey));
	}

	/**
	 * Returns the predecessor of the partition in the chain of a declared chain key.
	 *
	 * @param attributeIndexKey   the declared chain key
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @return the predecessor primary key, empty when the partition is not chained
	 */
	@Nonnull
	public OptionalInt getChainPredecessor(@Nonnull AttributeIndexKey attributeIndexKey, int partitionPrimaryKey) {
		final OptionalLong payload = this.entries.getLongRecordEqualTo(
			key(getChainSlotId(attributeIndexKey), partitionPrimaryKey)
		);
		return payload.isPresent() ? OptionalInt.of((int) payload.getAsLong()) : OptionalInt.empty();
	}

	/**
	 * Emits a removal for every leaf page this index has on disk, used when the owning entity index is dropped and
	 * its persisted footprint must be reclaimed. Reads only the persisted page baseline and has no side effects.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param referenceName         the reference this index belongs to
	 * @param sink                  the accumulator collecting the removal instructions
	 */
	public void emitPersistedLeafPageRemovals(
		int entityIndexPrimaryKey,
		@Nonnull String referenceName,
		@Nonnull TrappedChanges sink
	) {
		for (final int persistedPageSequence : this.pageStreamRegistry.pendingLivePageSequences(ENTRY_PAGE_STREAM)) {
			sink.addChangeToStore(
				new VirtualReferencePartitionIndexLeafPageRemoval(
					entityIndexPrimaryKey, referenceName, persistedPageSequence
				)
			);
		}
	}

	/**
	 * Appends the storage parts that changed since the last flush to the sink:
	 *
	 * - an index with no entry and no declared sort or chain key removes its root and every leaf page it has on
	 *   disk, so it leaves nothing behind,
	 * - a PAGED index writes one leaf page per changed leaf and a removal per freed leaf, and rewrites its root only
	 *   when the leaf list, a dictionary or the declared sort or chain keys changed,
	 * - a SINGLE index removes the leaf pages left behind by a collapse from PAGED and writes the inline root.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param referenceName         the reference this index belongs to
	 * @param sink                  the flush sink receiving the changed parts
	 */
	public void appendStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull String referenceName,
		@Nonnull TrappedChanges sink
	) {
		if (!this.dirty.isTrue()) {
			return;
		}
		if (isEmpty()) {
			// reclaim against what the previous flush left on disk (its staged set while unpublished), then forget the
			// stream so a later regrowth starts from a clean baseline; a root that never reached disk resolves to a
			// removal of nothing
			emitPersistedLeafPageRemovals(entityIndexPrimaryKey, referenceName, sink);
			this.pageStreamRegistry.forget(ENTRY_PAGE_STREAM);
			sink.addChangeToStore(new VirtualReferencePartitionRootRemoval(entityIndexPrimaryKey, referenceName));
		} else if (this.entries.isRootInternal()) {
			final PageEmission<LeafPage> emission = collectChangedPages();
			for (final LeafPage page : emission.changedPages()) {
				sink.addChangeToStore(
					new VirtualReferencePartitionIndexLeafPagePart(
						entityIndexPrimaryKey, referenceName, page.pageSequence(), page.keys(), page.payloads()
					)
				);
			}
			for (final int freedPageSequence : emission.freedPageSequences()) {
				sink.addChangeToStore(
					new VirtualReferencePartitionIndexLeafPageRemoval(
						entityIndexPrimaryKey, referenceName, freedPageSequence
					)
				);
			}
			// the root carries the leaf list and the dictionaries only - a flush that changed neither leaves the
			// persisted root byte-identical, so a one-entry change costs one leaf page
			if (emission.pageListChanged() || this.dictionaryDirty.isTrue()) {
				final DictionarySnapshot dictionary = dictionarySnapshot();
				sink.addChangeToStore(
					VirtualReferencePartitionIndexStoragePart.paged(
						entityIndexPrimaryKey, referenceName,
						emission.highWaterPageSequence(), emission.orderedPageSequences(),
						dictionary.tupleIds(), dictionary.tuples(), dictionary.sortValueSlots(),
						dictionary.chainPredecessorSlots(), null
					)
				);
			}
		} else {
			// a collapse from PAGED leaves the previous leaf pages unreferenced by the inline root - reclaim them
			// against what the previous flush left on disk, then forget the stream so a later regrowth re-emits every
			// leaf
			emitPersistedLeafPageRemovals(entityIndexPrimaryKey, referenceName, sink);
			this.pageStreamRegistry.forget(ENTRY_PAGE_STREAM);
			final CompositeLongArray keys = new CompositeLongArray();
			final CompositeLongArray payloads = new CompositeLongArray();
			final BucketCursor cursor = this.entries.cursor();
			while (cursor.next()) {
				keys.add((Long) cursor.value());
				payloads.add(cursor.longRecordId());
			}
			final DictionarySnapshot dictionary = dictionarySnapshot();
			sink.addChangeToStore(
				VirtualReferencePartitionIndexStoragePart.single(
					entityIndexPrimaryKey, referenceName, keys.toArray(), payloads.toArray(),
					dictionary.tupleIds(), dictionary.tuples(), dictionary.sortValueSlots(),
					dictionary.chainPredecessorSlots(), null
				)
			);
		}
	}

	/**
	 * Clears the dirty flags once the flush wrote the changes - those of the sort indexes too, which are never flushed
	 * themselves, but whose flags are what tells their own commit whether they changed.
	 */
	@Override
	public void resetDirty() {
		this.dirty.reset();
		this.dictionaryDirty.reset();
		this.sortIndexes.forEach((attributeIndexKey, sortIndex) -> sortIndex.resetDirty());
	}

	/**
	 * Returns the heap this index occupies, in bytes - its own object, both dirty flags, the entry tree, the tuple
	 * dictionary, the declared sort keys, their value dictionaries and their sort indexes.
	 *
	 * The entry tree keeps its keys inline in a primitive column, but its internal nodes box one separator key per
	 * leaf boundary, which {@link IndexHeapSize#OWNED_KEY_SIZER} prices. Each dictionary tuple and each id box is
	 * shared by both dictionary maps and charged once, at the `tuple -> id` map; the `id -> entry` map is charged only
	 * for its entry shells. The sort value dictionaries follow the same rule: the value, its key wrapper and its id box
	 * are charged at the `(slot, value) -> id` map, the `pack(slot, id) -> entry` map pays its `Long` keys and its entry
	 * shells. The sort index charges its own values in full as well, and a plain value its normalizer leaves untouched
	 * is the very instance both hold - so the figure exceeds the heap by at most one value per distinct sort value,
	 * never by anything per partition. An attribute index key belongs to the schema and contributes only its slot, and a
	 * chain key costs its map entry and its slot id box.
	 * {@link #pageStreamRegistry} is flush bookkeeping rather than index content and is excluded.
	 *
	 * Walking the tree is `O(entries / blockSize)`, so this belongs to the index detail call and must never be called
	 * from a query path.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		final long boxedInteger = layout.sizeOfObject(Integer.BYTES);
		// the tuple, the id box and the user count
		final long dictionaryEntry = layout.sizeOfObject(2L * layout.referenceSize() + Integer.BYTES);
		// the slot id, the key wrapper's value and the id box are charged at the `(slot, value) -> id` map
		final long sortValueKey = layout.sizeOfObject(Integer.BYTES + layout.referenceSize());
		// the key, the id box and the user count
		final long sortValueEntry = layout.sizeOfObject(2L * layout.referenceSize() + Integer.BYTES);
		final long boxedLong = layout.sizeOfObject(Long.BYTES);
		// the dirty / dictionaryDirty / entries / pageStreamRegistry / tupleIds / tuples / nextTupleId / sortKeys /
		// sortValueIds / sortValues / sortIndexes / chainKeys slots
		return layout.sizeOfObject(12L * layout.referenceSize())
			+ this.dirty.getHeapSizeInBytes()
			+ this.dictionaryDirty.getHeapSizeInBytes()
			+ this.entries.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER)
			+ this.tupleIds.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER, tupleId -> boxedInteger)
			+ this.tuples.getHeapSizeInBytes(tupleId -> 0L, entry -> dictionaryEntry)
			+ this.nextTupleId.getHeapSizeInBytes(tupleId -> boxedInteger)
			+ this.sortKeys.getHeapSizeInBytes(attributeIndexKey -> 0L, DeclaredSortKey::getHeapSizeInBytes)
			+ this.sortValueIds.getHeapSizeInBytes(
				key -> sortValueKey + IndexHeapSize.OWNED_KEY_SIZER.applyAsLong(key.value()), valueId -> boxedInteger
			)
			+ this.sortValues.getHeapSizeInBytes(packedId -> boxedLong, entry -> sortValueEntry)
			+ this.sortIndexes.getHeapSizeInBytes(attributeIndexKey -> 0L, OwnerSortIndex::getHeapSizeInBytes)
			+ this.chainKeys.getHeapSizeInBytes(attributeIndexKey -> 0L, slotId -> boxedInteger);
	}

	/*
		TransactionalLayerProducer implementation
	 */

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.entries.removeLayer(transactionalLayer);
		this.tupleIds.removeLayer(transactionalLayer);
		this.tuples.removeLayer(transactionalLayer);
		this.nextTupleId.removeLayer(transactionalLayer);
		this.sortKeys.removeLayer(transactionalLayer);
		this.sortValueIds.removeLayer(transactionalLayer);
		this.sortValues.removeLayer(transactionalLayer);
		this.sortIndexes.removeLayer(transactionalLayer);
		this.chainKeys.removeLayer(transactionalLayer);
		this.dirty.removeLayer(transactionalLayer);
		this.dictionaryDirty.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public VirtualReferencePartitionIndex createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		final boolean isDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		// the flag only steers the flush, which has already run - its layer is consumed and the value dropped
		transactionalLayer.getStateCopyWithCommittedChanges(this.dictionaryDirty);
		if (isDirty) {
			final LongPayloadBucketTree committedTree =
				(LongPayloadBucketTree) transactionalLayer.getStateCopyWithCommittedChanges(this.entries);
			// the merge runs only after the flush durably wrote the changed pages, so the staged page set now reflects
			// what is on disk; the registry is carried by reference into the committed copy. A warm-up flush never
			// reaches this merge and is published by the next flush instead, see `collectChangedPages`
			this.pageStreamRegistry.publishStaged();
			return new VirtualReferencePartitionIndex(
				committedTree,
				this.pageStreamRegistry,
				transactionalLayer.getStateCopyWithCommittedChanges(this.tupleIds),
				transactionalLayer.getStateCopyWithCommittedChanges(this.tuples),
				transactionalLayer.getStateCopyWithCommittedChanges(this.nextTupleId).orElseThrow(),
				transactionalLayer.getStateCopyWithCommittedChanges(this.sortKeys),
				transactionalLayer.getStateCopyWithCommittedChanges(this.sortValueIds),
				transactionalLayer.getStateCopyWithCommittedChanges(this.sortValues),
				transactionalLayer.getStateCopyWithCommittedChanges(this.sortIndexes),
				transactionalLayer.getStateCopyWithCommittedChanges(this.chainKeys)
			);
		} else {
			return this;
		}
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Creates the comparator a sort index of the given sort key orders its normalized values by - the very comparator
	 * {@link SortIndex} derives for itself.
	 *
	 * @param locale         the locale of the sort key, `null` when it is not localized
	 * @param comparatorBase one descriptor per sorted element
	 * @return the comparator of normalized values
	 */
	@Nonnull
	private static Comparator<Serializable> createSortValueComparator(
		@Nullable Locale locale,
		@Nonnull ComparatorSource[] comparatorBase
	) {
		return comparatorBase.length == 1 ?
			SortIndex.createComparatorFor(locale, comparatorBase[0]) :
			(Comparator) SortIndex.createCombinedComparatorFor(locale, comparatorBase);
	}

	/**
	 * Creates the sort index of a sort key from its blocks: `sortedRecords` is laid out value block by value block in
	 * the order of `sortedValues`, with ascending partition primary keys inside each block. The sort index is always
	 * built as an owner of its values - never as a view over a value tree it would share with another index - so its
	 * blocks hold exactly the partitions of this slot.
	 *
	 * @param sortKey       the declared sort key
	 * @param sortedRecords the partition primary keys, blocked per value
	 * @param sortedValues  the distinct normalized values in the sort key's order
	 * @param cardinalities the size of every block larger than one
	 * @return the sort index
	 */
	@Nonnull
	private static OwnerSortIndex createSortIndex(
		@Nonnull DeclaredSortKey sortKey,
		@Nonnull int[] sortedRecords,
		@Nonnull Serializable[] sortedValues,
		@Nonnull Map<Serializable, Integer> cardinalities
	) {
		return new OwnerSortIndex(
			sortKey.comparatorBase(), null, sortKey.attributeIndexKey(), sortKey.indexedDecimalPlaces(),
			sortedRecords, sortedValues, cardinalities
		);
	}

	/**
	 * Rebuilds the declared sort keys, their value dictionaries and their sort indexes from the persisted slots and
	 * the persisted entries, filling the passed maps.
	 *
	 * Per slot only the distinct values are sorted, by the comparator of the sort key. The entries are then walked
	 * twice in the order they were persisted, which is ascending by slot and by partition within a slot: the first
	 * walk counts the partitions of every value, which are both the user counts of the dictionary and the block sizes
	 * of the sort index, and the second drops each partition into the next free position of its value's block. The
	 * blocks therefore come out with ascending partition primary keys, exactly as the sort index keeps them, without a
	 * single comparison between entries and without an allocation per entry.
	 *
	 * @param indexDescription identification of the index for corruption diagnostics
	 * @param sortValueSlots   the persisted sort value slots in ascending slot id order
	 * @param chainSlotIds     the ids of the persisted chain predecessor slots in ascending order, whose entries the
	 *                         walks skip
	 * @param keyColumns       the persisted entry keys in ascending key order across the columns
	 * @param payloadColumns   the persisted entry payloads, aligned with `keyColumns`
	 * @param sortKeys         receives the declared sort keys
	 * @param sortValueIds     receives the `(slot, value) -> id` dictionary map
	 * @param sortValues       receives the `pack(slot, id) -> entry` dictionary map
	 * @param sortIndexes      receives the rebuilt sort indexes
	 */
	private static void rebuildSortKeys(
		@Nonnull String indexDescription,
		@Nonnull SortValueSlot[] sortValueSlots,
		@Nonnull int[] chainSlotIds,
		@Nonnull long[][] keyColumns,
		@Nonnull long[][] payloadColumns,
		@Nonnull Map<AttributeIndexKey, DeclaredSortKey> sortKeys,
		@Nonnull Map<SortValueKey, Integer> sortValueIds,
		@Nonnull Map<Long, SortValueEntry> sortValues,
		@Nonnull Map<AttributeIndexKey, OwnerSortIndex> sortIndexes
	) {
		final int slotCount = sortValueSlots.length;
		final int[] slotIds = new int[slotCount];
		// per slot: value id -> position of the value in the sort key's order
		final IntIntHashMap[] positionsByValueId = new IntIntHashMap[slotCount];
		final Serializable[][] sortedValues = new Serializable[slotCount][];
		final int[][] sortedValueIds = new int[slotCount][];
		// per slot: the first record position of every value's block, with the total record count appended
		final int[][] blockStarts = new int[slotCount][];
		for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
			final SortValueSlot slot = sortValueSlots[slotIndex];
			final int slotId = slot.slotId();
			if (slotId < VirtualReferencePartitionSlot.FIRST_DECLARED_SLOT_ID ||
				(slotIndex > 0 && slotId <= slotIds[slotIndex - 1]) ||
				Arrays.binarySearch(chainSlotIds, slotId) >= 0) {
				throw new GenericEvitaInternalError(
					"The sort value slots of the virtual reference partition index for " + indexDescription +
						" are not in ascending order of declared slot ids, or share one with a chain key, at slot " +
						slotId + "!"
				);
			}
			slotIds[slotIndex] = slotId;

			// only the distinct values are ever compared
			final Serializable[] values = slot.values();
			final int[] valueIds = slot.valueIds();
			final Comparator<Serializable> comparator = createSortValueComparator(
				slot.attributeIndexKey().locale(), slot.comparatorBase()
			);
			final Integer[] order = new Integer[values.length];
			for (int i = 0; i < order.length; i++) {
				order[i] = i;
			}
			Arrays.sort(order, (first, second) -> comparator.compare(values[first], values[second]));

			final IntIntHashMap positions = new IntIntHashMap(values.length);
			final Serializable[] slotSortedValues = new Serializable[values.length];
			final int[] slotSortedValueIds = new int[values.length];
			for (int position = 0; position < order.length; position++) {
				final int valueIndex = order[position];
				slotSortedValues[position] = values[valueIndex];
				slotSortedValueIds[position] = valueIds[valueIndex];
				if (!positions.putIfAbsent(valueIds[valueIndex], position)) {
					throw new GenericEvitaInternalError(
						"The value dictionary of slot " + slotId + " of the virtual reference partition index for " +
							indexDescription + " lists value id " + valueIds[valueIndex] + " twice!"
					);
				}
				if (position > 0 && comparator.compare(slotSortedValues[position - 1], slotSortedValues[position]) == 0) {
					throw new GenericEvitaInternalError(
						"The value dictionary of slot " + slotId + " of the virtual reference partition index for " +
							indexDescription + " holds the values `" + slotSortedValues[position - 1] + "` and `" +
							slotSortedValues[position] + "`, which its sort key orders as one!"
					);
				}
			}
			positionsByValueId[slotIndex] = positions;
			sortedValues[slotIndex] = slotSortedValues;
			sortedValueIds[slotIndex] = slotSortedValueIds;
			blockStarts[slotIndex] = new int[values.length + 1];
		}

		// first walk: the size of every value's block, counted one position to the right
		walkSortValueEntries(
			indexDescription, keyColumns, payloadColumns, slotIds, chainSlotIds, positionsByValueId,
			(slotIndex, position, partitionPrimaryKey) -> blockStarts[slotIndex][position + 1]++
		);
		final int[][] sortedRecords = new int[slotCount][];
		final int[][] nextPositions = new int[slotCount][];
		for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
			final int[] starts = blockStarts[slotIndex];
			for (int position = 0; position < starts.length - 1; position++) {
				if (starts[position + 1] == 0) {
					throw new GenericEvitaInternalError(
						"The value dictionary of slot " + slotIds[slotIndex] + " of the virtual reference partition " +
							"index for " + indexDescription + " holds value id " + sortedValueIds[slotIndex][position] +
							", which no partition refers to!"
					);
				}
				starts[position + 1] += starts[position];
			}
			sortedRecords[slotIndex] = new int[starts[starts.length - 1]];
			nextPositions[slotIndex] = Arrays.copyOf(starts, starts.length - 1);
		}
		// second walk: every partition into the next free position of its value's block, in ascending partition order
		walkSortValueEntries(
			indexDescription, keyColumns, payloadColumns, slotIds, chainSlotIds, positionsByValueId,
			(slotIndex, position, partitionPrimaryKey) ->
				sortedRecords[slotIndex][nextPositions[slotIndex][position]++] = partitionPrimaryKey
		);

		for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
			final SortValueSlot slot = sortValueSlots[slotIndex];
			final int slotId = slotIds[slotIndex];
			final Serializable[] slotSortedValues = sortedValues[slotIndex];
			final int[] slotSortedValueIds = sortedValueIds[slotIndex];
			final int[] starts = blockStarts[slotIndex];
			final Map<Serializable, Integer> cardinalities = CollectionUtils.createHashMap(slotSortedValues.length);
			int largestValueId = 0;
			for (int position = 0; position < slotSortedValues.length; position++) {
				final Serializable value = slotSortedValues[position];
				final int userCount = starts[position + 1] - starts[position];
				if (userCount > 1) {
					cardinalities.put(value, userCount);
				}
				// one box shared by both maps, see SortValueEntry
				final Integer valueId = slotSortedValueIds[position];
				final SortValueKey valueKey = new SortValueKey(slotId, value);
				sortValueIds.put(valueKey, valueId);
				sortValues.put(NumberUtils.pack(slotId, valueId), new SortValueEntry(valueKey, valueId, userCount));
				largestValueId = Math.max(largestValueId, valueId);
			}
			final DeclaredSortKey sortKey = DeclaredSortKey.of(
				slotId, slot.attributeIndexKey(), slot.comparatorBase(), slot.indexedDecimalPlaces(),
				nextIdAfterLoad(largestValueId)
			);
			sortKeys.put(slot.attributeIndexKey(), sortKey);
			sortIndexes.put(
				slot.attributeIndexKey(),
				createSortIndex(sortKey, sortedRecords[slotIndex], slotSortedValues, cardinalities)
			);
		}
	}

	/**
	 * Walks the persisted entries of every sort value slot in persisted order and hands each one to the visitor with
	 * the position of its value in the sort key's order. Entries of the fixed slots and of the chain predecessor slots
	 * are skipped; an entry of a slot that is declared neither way is refused.
	 *
	 * @param indexDescription   identification of the index for corruption diagnostics
	 * @param keyColumns         the persisted entry keys in ascending key order across the columns
	 * @param payloadColumns     the persisted entry payloads, aligned with `keyColumns`
	 * @param slotIds            the ids of the persisted sort value slots in ascending order
	 * @param chainSlotIds       the ids of the persisted chain predecessor slots in ascending order
	 * @param positionsByValueId per slot, the position of each value id in the sort key's order
	 * @param visitor            receives every sort value entry
	 */
	private static void walkSortValueEntries(
		@Nonnull String indexDescription,
		@Nonnull long[][] keyColumns,
		@Nonnull long[][] payloadColumns,
		@Nonnull int[] slotIds,
		@Nonnull int[] chainSlotIds,
		@Nonnull IntIntHashMap[] positionsByValueId,
		@Nonnull SortValueEntryVisitor visitor
	) {
		int currentSlotId = -1;
		int currentSlotIndex = -1;
		for (int column = 0; column < keyColumns.length; column++) {
			final long[] keys = keyColumns[column];
			final long[] payloads = payloadColumns[column];
			for (int i = 0; i < keys.length; i++) {
				final int slotId = NumberUtils.unpackHigh(keys[i]);
				if (slotId < VirtualReferencePartitionSlot.FIRST_DECLARED_SLOT_ID) {
					continue;
				}
				// the entries are ordered by slot, so the slot is looked up once per run of its entries
				if (slotId != currentSlotId) {
					currentSlotIndex = Arrays.binarySearch(slotIds, slotId);
					if (currentSlotIndex < 0) {
						if (Arrays.binarySearch(chainSlotIds, slotId) < 0) {
							throw new GenericEvitaInternalError(
								"The virtual reference partition index for " + indexDescription +
									" holds entries of slot " + slotId + ", which is not declared!"
							);
						}
						currentSlotIndex = CHAIN_SLOT_INDEX;
					}
					currentSlotId = slotId;
				}
				if (currentSlotIndex == CHAIN_SLOT_INDEX) {
					continue;
				}
				final int position = positionsByValueId[currentSlotIndex].getOrDefault((int) payloads[i], -1);
				if (position < 0) {
					throw new GenericEvitaInternalError(
						"Partition " + NumberUtils.unpackLow(keys[i]) + " of the virtual reference partition index for " +
							indexDescription + " refers to value id " + payloads[i] + " of slot " + slotId +
							", which its dictionary lacks!"
					);
				}
				visitor.visit(currentSlotIndex, position, NumberUtils.unpackLow(keys[i]));
			}
		}
	}

	/**
	 * Normalizes a sort value as the sort index of the key normalizes it, and checks it against the declared types
	 * before anything is written. A compound is normalized on a copy, which the dictionary then owns.
	 *
	 * @param sortKey the declared sort key
	 * @param value   the attribute value, or the element values of a compound
	 * @return the normalized value, a {@link ComparableArray} for a compound
	 */
	@Nonnull
	private static Serializable normalize(@Nonnull DeclaredSortKey sortKey, @Nonnull Serializable value) {
		final ComparatorSource[] comparatorBase = sortKey.comparatorBase();
		if (sortKey.isCompound()) {
			final Serializable[] elements = (Serializable[]) value;
			if (elements.length != comparatorBase.length) {
				throw new GenericEvitaInternalError(
					"Sort key `" + sortKey.attributeIndexKey() + "` sorts by " + comparatorBase.length +
						" elements, but " + elements.length + " values were passed!"
				);
			}
			// the compound normalizer rewrites the array it is given in place
			final Serializable[] normalized = (Serializable[]) sortKey.normalizer()
				.apply(Arrays.copyOf(elements, elements.length));
			// the validating constructor checks every element against its declared type
			return new ComparableArray(comparatorBase, normalized);
		} else {
			if (!comparatorBase[0].type().isInstance(value)) {
				throw new GenericEvitaInternalError(
					"Sort key `" + sortKey.attributeIndexKey() + "` sorts by `" + comparatorBase[0].type().getName() +
						"`, but `" + value.getClass().getName() + "` was passed!"
				);
			}
			return sortKey.normalizer().apply(value);
		}
	}

	/**
	 * Turns a normalized value of one attribute or one compound element back into a value of its declared type - one
	 * the sort index accepts and normalizes, by itself, onto exactly `normalizedValue` again. It undoes what
	 * {@link SortIndex#createNormalizerFor(ComparatorSource, int)} does:
	 *
	 * - the comparable wrapper of a `Locale` or a `Currency` is unwrapped,
	 * - an `Instant` becomes the declared `OffsetDateTime` or `LocalDateTime` at UTC - the offset the normalizer anchors
	 *   a `LocalDateTime` at, so the instant comes back to the millisecond,
	 * - the scaled `Integer` of a `BigDecimal` attribute becomes `BigDecimal.valueOf(scaled, indexedDecimalPlaces)`,
	 *   which scales back onto the very same `Integer`.
	 *
	 * Every other normalized form - a decomposed `String`, a millisecond `LocalTime`, the exact `BigDecimal` of a
	 * compound element, a range rescaled before it reached the index - is one the normalizer maps onto itself, and so
	 * is a value that already has its declared type.
	 *
	 * @param declaredType         the type the attribute or the compound element is declared with
	 * @param indexedDecimalPlaces the decimal places a `BigDecimal` attribute is scaled by
	 * @param normalizedValue      the normalized value, `null` for a missing compound element
	 * @return the value the sort index normalizes into `normalizedValue`
	 */
	@Nullable
	private static Serializable denormalize(
		@Nonnull Class<?> declaredType,
		int indexedDecimalPlaces,
		@Nullable Serializable normalizedValue
	) {
		if (normalizedValue instanceof ComparableLocale comparableLocale) {
			return comparableLocale.getLocale();
		} else if (normalizedValue instanceof ComparableCurrency comparableCurrency) {
			return comparableCurrency.getCurrency();
		} else if (normalizedValue instanceof Instant instant && OffsetDateTime.class.isAssignableFrom(declaredType)) {
			return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
		} else if (normalizedValue instanceof Instant instant && LocalDateTime.class.isAssignableFrom(declaredType)) {
			return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
		} else if (normalizedValue instanceof Integer scaled && BigDecimal.class.isAssignableFrom(declaredType)) {
			return BigDecimal.valueOf(scaled, indexedDecimalPlaces);
		} else {
			return normalizedValue;
		}
	}

	/**
	 * Turns a normalized compound value back into the values of its elements, each of its declared type - see
	 * {@link #denormalize(Class, int, Serializable)}. Missing elements stay `null`.
	 *
	 * @param sortKey         the declared sort key of a sortable attribute compound
	 * @param normalizedValue the normalized compound value
	 * @return a new array of the element values the sort index normalizes into `normalizedValue`
	 */
	@Nonnull
	private static Serializable[] denormalize(@Nonnull DeclaredSortKey sortKey, @Nonnull ComparableArray normalizedValue) {
		final ComparatorSource[] comparatorBase = sortKey.comparatorBase();
		final Serializable[] elements = normalizedValue.array();
		if (elements.length != comparatorBase.length) {
			throw new GenericEvitaInternalError(
				"Sort key `" + sortKey.attributeIndexKey() + "` sorts by " + comparatorBase.length +
					" elements, but " + elements.length + " values were passed!"
			);
		}
		final Serializable[] values = new Serializable[elements.length];
		for (int i = 0; i < elements.length; i++) {
			values[i] = denormalize(comparatorBase[i].type(), sortKey.indexedDecimalPlaces(), elements[i]);
		}
		return values;
	}

	/**
	 * Removes the partition from the block of its sort value in the sort index.
	 *
	 * @param sortKey             the declared sort key
	 * @param sortIndex           the sort index of the sort key
	 * @param normalizedValue     the normalized sort value the partition held, as the dictionary keeps it
	 * @param partitionPrimaryKey the partition primary key
	 */
	private static void removeFromSortIndex(
		@Nonnull DeclaredSortKey sortKey,
		@Nonnull OwnerSortIndex sortIndex,
		@Nonnull Serializable normalizedValue,
		int partitionPrimaryKey
	) {
		if (normalizedValue instanceof ComparableArray compound) {
			sortIndex.removeRecord(denormalize(sortKey, compound), partitionPrimaryKey);
		} else {
			sortIndex.removeRecord(
				Objects.requireNonNull(
					denormalize(sortKey.comparatorBase()[0].type(), sortKey.indexedDecimalPlaces(), normalizedValue)
				),
				partitionPrimaryKey
			);
		}
	}

	/**
	 * Compares two normalized sort values the way their sort index does when it looks for a value's block: element by
	 * element, a `BigDecimal` by its numeric value and anything else by `equals` - which every other sortable type
	 * keeps consistent with its order.
	 *
	 * @param first  the first normalized value
	 * @param second the second normalized value
	 * @return true if the sort index keeps both in one block
	 */
	private static boolean isSameSortValue(@Nonnull Serializable first, @Nonnull Serializable second) {
		if (first instanceof ComparableArray firstCompound && second instanceof ComparableArray secondCompound) {
			final Serializable[] firstElements = firstCompound.array();
			final Serializable[] secondElements = secondCompound.array();
			if (firstElements.length != secondElements.length) {
				return false;
			}
			for (int i = 0; i < firstElements.length; i++) {
				if (!isSameSortElement(firstElements[i], secondElements[i])) {
					return false;
				}
			}
			return true;
		}
		return isSameSortElement(first, second);
	}

	/**
	 * Compares one attribute value or compound element as {@link #isSameSortValue} describes.
	 *
	 * @param first  the first value
	 * @param second the second value
	 * @return true if the sort index orders both as one
	 */
	private static boolean isSameSortElement(@Nullable Serializable first, @Nullable Serializable second) {
		if (first instanceof BigDecimal firstNumber && second instanceof BigDecimal secondNumber) {
			return firstNumber.compareTo(secondNumber) == 0;
		}
		return Objects.equals(first, second);
	}

	/**
	 * Hashes a normalized sort value consistently with {@link #isSameSortValue}: a `BigDecimal` by its value with the
	 * trailing zeros stripped, so `1.0` and `1.00` meet in one bucket.
	 *
	 * @param value the normalized value
	 * @return the hash
	 */
	private static int sortValueHash(@Nonnull Serializable value) {
		if (value instanceof ComparableArray compound) {
			int hash = 1;
			for (final Serializable element : compound.array()) {
				hash = 31 * hash + sortElementHash(element);
			}
			return hash;
		}
		return sortElementHash(value);
	}

	/**
	 * Hashes one attribute value or compound element as {@link #sortValueHash} describes.
	 *
	 * @param value the value, `null` for a missing compound element
	 * @return the hash
	 */
	private static int sortElementHash(@Nullable Serializable value) {
		if (value instanceof BigDecimal number) {
			return number.stripTrailingZeros().hashCode();
		}
		return Objects.hashCode(value);
	}

	/**
	 * Records the sort value of the partition, see {@link #putSortValue(AttributeIndexKey, int, Serializable)}. The
	 * value is normalized and checked before anything changes, and the new value is acquired before the previous one
	 * is released, so the two never share an id.
	 *
	 * @param attributeIndexKey   the declared sort key
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @param value               the attribute value, or the element values of a compound
	 * @param compound            whether `value` holds the element values of a compound
	 */
	private void putSortValue(
		@Nonnull AttributeIndexKey attributeIndexKey,
		int partitionPrimaryKey,
		@Nonnull Serializable value,
		boolean compound
	) {
		final DeclaredSortKey sortKey = getDeclaredSortKey(attributeIndexKey);
		if (sortKey.isCompound() != compound) {
			throw new GenericEvitaInternalError(
				"Sort key `" + attributeIndexKey + "` sorts by " + (sortKey.isCompound() ? "a compound" : "an attribute") +
					", but " + (compound ? "compound element values were" : "an attribute value was") + " passed!"
			);
		}
		final int slotId = sortKey.slotId();
		final long key = key(slotId, partitionPrimaryKey);
		final SortValueKey valueKey = new SortValueKey(slotId, normalize(sortKey, value));
		final Integer knownValueId = this.sortValueIds.get(valueKey);
		final OptionalLong previousValueId = this.entries.getLongRecordEqualTo(key);
		if (knownValueId != null && previousValueId.isPresent() && previousValueId.getAsLong() == knownValueId) {
			return;
		}
		final OwnerSortIndex sortIndex = getSortIndexOfDeclaredKey(attributeIndexKey);
		// the value id is acquired first, so an exhausted id space refuses the value before anything changes
		final int valueId = knownValueId == null ? addSortValue(sortKey, valueKey) : retainSortValue(slotId, knownValueId);
		this.sortIndexes.markValueMutated(attributeIndexKey);
		if (previousValueId.isPresent()) {
			this.entries.removeLongRecord(key);
			final Serializable previousValue = releaseSortValue(slotId, (int) previousValueId.getAsLong());
			removeFromSortIndex(sortKey, sortIndex, previousValue, partitionPrimaryKey);
		}
		this.entries.addLongRecord(key, (long) valueId);
		if (compound) {
			// the sort index normalizes the array it is given in place, and the caller's array stays untouched
			final Serializable[] elements = (Serializable[]) value;
			sortIndex.addRecord(Arrays.copyOf(elements, elements.length), partitionPrimaryKey);
		} else {
			sortIndex.addRecord(value, partitionPrimaryKey);
		}
		this.dirty.setToTrue();
	}

	/**
	 * Returns the id the next declared slot gets: one above the largest declared slot id in use, at least
	 * {@link VirtualReferencePartitionSlot#FIRST_DECLARED_SLOT_ID}. A slot id is reused only after its key was retired
	 * together with all its entries.
	 *
	 * @param attributeIndexKey the sort or chain key the slot is declared for, named when no slot id is left
	 * @return the next declared slot id
	 * @throws IdentifierOverflowException when the largest declared slot id in use is `Integer.MAX_VALUE`
	 */
	private int nextDeclaredSlotId(@Nonnull AttributeIndexKey attributeIndexKey) {
		final int[] largestSlotId = {VirtualReferencePartitionSlot.FIRST_DECLARED_SLOT_ID - 1};
		this.sortKeys.forEach((key, sortKey) -> largestSlotId[0] = Math.max(largestSlotId[0], sortKey.slotId()));
		this.chainKeys.forEach((key, slotId) -> largestSlotId[0] = Math.max(largestSlotId[0], slotId));
		if (largestSlotId[0] == Integer.MAX_VALUE) {
			throw new IdentifierOverflowException(
				"No declared slot id is left in a virtual reference partition index to declare `" + attributeIndexKey +
					"`: slot id " + Integer.MAX_VALUE + " is in use and the next one would wrap into the negative " +
					"range. Slot ids are computed from the slots in use, so a restart does not help - retiring the " +
					"sort or chain key holding the largest slot id does, otherwise the entity collection must be " +
					"reindexed."
			);
		}
		return largestSlotId[0] + 1;
	}

	/**
	 * Removes every entry of the slot with the given id from the tree.
	 *
	 * @param slotId the slot id
	 */
	private void removeSlotEntries(int slotId) {
		// collect first, the tree must not change under its own cursor
		final CompositeLongArray slotKeys = new CompositeLongArray();
		forEachEntry(
			slotId, (partitionPrimaryKey, payload) -> slotKeys.add(NumberUtils.pack(slotId, partitionPrimaryKey))
		);
		for (final long slotKey : slotKeys.toArray()) {
			this.entries.removeLongRecord(slotKey);
		}
	}

	/**
	 * Returns the slot id of a declared chain key, failing when it is not declared.
	 *
	 * @param attributeIndexKey the chain key
	 * @return the slot id
	 */
	private int getChainSlotId(@Nonnull AttributeIndexKey attributeIndexKey) {
		final Integer slotId = this.chainKeys.get(attributeIndexKey);
		if (slotId == null) {
			throw new GenericEvitaInternalError("Chain key `" + attributeIndexKey + "` is not declared!");
		}
		return slotId;
	}

	/**
	 * Returns a declared sort key, failing when it is not declared.
	 *
	 * @param attributeIndexKey the sort key
	 * @return the declared sort key
	 */
	@Nonnull
	private DeclaredSortKey getDeclaredSortKey(@Nonnull AttributeIndexKey attributeIndexKey) {
		final DeclaredSortKey sortKey = this.sortKeys.get(attributeIndexKey);
		if (sortKey == null) {
			throw new GenericEvitaInternalError("Sort key `" + attributeIndexKey + "` is not declared!");
		}
		return sortKey;
	}

	/**
	 * Returns the sort index of a declared sort key, which a declared key always has.
	 *
	 * @param attributeIndexKey the declared sort key
	 * @return the sort index
	 */
	@Nonnull
	private OwnerSortIndex getSortIndexOfDeclaredKey(@Nonnull AttributeIndexKey attributeIndexKey) {
		final OwnerSortIndex sortIndex = this.sortIndexes.get(attributeIndexKey);
		if (sortIndex == null) {
			throw new GenericEvitaInternalError("Sort key `" + attributeIndexKey + "` is declared without a sort index!");
		}
		return sortIndex;
	}

	/**
	 * Adds a value the slot's dictionary does not know yet, with one user, under the next id of the slot.
	 *
	 * @param sortKey  the declared sort key
	 * @param valueKey the slot and the normalized value, which the dictionary then owns
	 * @return the new value id
	 * @throws IdentifierOverflowException when the slot's value id space is exhausted, before anything changes
	 */
	private int addSortValue(@Nonnull DeclaredSortKey sortKey, @Nonnull SortValueKey valueKey) {
		// one box shared by both maps, see SortValueEntry
		final Integer valueId = sortKey.nextValueId();
		if (valueId == EXHAUSTED_ID_SPACE) {
			throw new IdentifierOverflowException(
				"The sort value ids of sort key `" + sortKey.attributeIndexKey() + "` (slot " + sortKey.slotId() +
					") of a virtual reference partition index are exhausted: no id below " + EXHAUSTED_ID_SPACE +
					" is left to hand out and ids are never reused while the index stays loaded. A restart helps " +
					"only if the values holding the largest ids have been released since, because a load resumes " +
					"one above the largest id still in use - otherwise the entity collection must be reindexed."
			);
		}
		this.sortKeys.put(sortKey.attributeIndexKey(), sortKey.withNextValueId(valueId + 1));
		this.sortValueIds.put(valueKey, valueId);
		this.sortValues.put(NumberUtils.pack(sortKey.slotId(), valueId), new SortValueEntry(valueKey, valueId, 1));
		this.dictionaryDirty.setToTrue();
		return valueId;
	}

	/**
	 * Adds one user to a known sort value.
	 *
	 * @param slotId  the slot id
	 * @param valueId the value id
	 * @return the value id
	 */
	private int retainSortValue(int slotId, int valueId) {
		final SortValueEntry entry = getSortValueEntry(slotId, valueId);
		this.sortValues.put(
			NumberUtils.pack(slotId, valueId), new SortValueEntry(entry.key(), entry.id(), entry.userCount() + 1)
		);
		return valueId;
	}

	/**
	 * Removes one user from a known sort value and drops the value from the dictionary when it was the last one.
	 *
	 * @param slotId  the slot id
	 * @param valueId the value id
	 * @return the normalized value
	 */
	@Nonnull
	private Serializable releaseSortValue(int slotId, int valueId) {
		final SortValueEntry entry = getSortValueEntry(slotId, valueId);
		final long packedId = NumberUtils.pack(slotId, valueId);
		if (entry.userCount() == 1) {
			this.sortValues.remove(packedId);
			this.sortValueIds.remove(entry.key());
			this.dictionaryDirty.setToTrue();
		} else {
			this.sortValues.put(packedId, new SortValueEntry(entry.key(), entry.id(), entry.userCount() - 1));
		}
		return entry.key().value();
	}

	/**
	 * Returns the dictionary entry of a sort value an entry refers to.
	 *
	 * @param slotId  the slot id
	 * @param valueId the value id
	 * @return the dictionary entry
	 */
	@Nonnull
	private SortValueEntry getSortValueEntry(int slotId, int valueId) {
		final SortValueEntry entry = this.sortValues.get(NumberUtils.pack(slotId, valueId));
		if (entry == null) {
			throw new GenericEvitaInternalError(
				"Value id " + valueId + " of slot " + slotId + " is referred to, but missing from the value dictionary!"
			);
		}
		return entry;
	}

	/**
	 * Stores the payload under the key unless the key already holds exactly that payload.
	 *
	 * @param key     the composed key
	 * @param payload the payload
	 */
	private void putPayload(long key, long payload) {
		final OptionalLong previousPayload = this.entries.getLongRecordEqualTo(key);
		if (previousPayload.isPresent()) {
			if (previousPayload.getAsLong() == payload) {
				return;
			}
			// the UNIQUE tree holds exactly one payload per key, so a replacement is a removal and an insertion
			this.entries.removeLongRecord(key);
		}
		this.entries.addLongRecord(key, payload);
		this.dirty.setToTrue();
	}

	/**
	 * Removes the entry under the key.
	 *
	 * @param key the composed key
	 * @return true if an entry was removed
	 */
	private boolean removePayload(long key) {
		// an absent key is looked up first and never handed to the removal: the tree opens a transactional layer of
		// the leaf it searches before it finds the key missing, and with nothing marking this index dirty the commit
		// would not merge the tree, leaving that layer unswept
		if (this.entries.getLongRecordEqualTo(key).isEmpty()) {
			return false;
		}
		final boolean removed = this.entries.removeLongRecord(key);
		if (removed) {
			this.dirty.setToTrue();
		}
		return removed;
	}

	/**
	 * Returns TRUE when the slot with the given id holds at least one entry.
	 *
	 * @param slotId the slot id
	 * @return TRUE when the slot is not empty
	 */
	private boolean hasEntry(int slotId) {
		// partition primary keys are positive, so the slot's first possible key is pack(slotId, 1)
		final BucketCursor cursor = this.entries.cursor(NumberUtils.pack(slotId, 0));
		return cursor.next() && NumberUtils.unpackHigh((Long) cursor.value()) == slotId;
	}

	/**
	 * Visits every entry of the slot with the given id in ascending partition primary key order.
	 *
	 * @param slotId   the slot id
	 * @param consumer invoked with the partition primary key and the payload of each entry
	 */
	private void forEachEntry(int slotId, @Nonnull EntryConsumer consumer) {
		// partition primary keys are positive, so the slot's first possible key is pack(slotId, 1) and every key of
		// the slot shares its high half
		final BucketCursor cursor = this.entries.cursor(NumberUtils.pack(slotId, 0));
		while (cursor.next()) {
			final long key = (Long) cursor.value();
			if (NumberUtils.unpackHigh(key) != slotId) {
				break;
			}
			consumer.accept(NumberUtils.unpackLow(key), cursor.longRecordId());
		}
	}

	/**
	 * Adds a tuple the dictionary does not know yet, with one user, under the next tuple id.
	 *
	 * @param tuple the representative attribute values, copied into the dictionary
	 * @return the new tuple id
	 * @throws IdentifierOverflowException when the tuple id space is exhausted, before anything changes
	 */
	private int addTuple(@Nonnull Serializable[] tuple) {
		// one box shared by both maps, see DictionaryEntry
		final Integer tupleId = this.nextTupleId.get();
		if (tupleId == EXHAUSTED_ID_SPACE) {
			throw new IdentifierOverflowException(
				"The representative tuple ids of a virtual reference partition index are exhausted: no id below " +
					EXHAUSTED_ID_SPACE + " is left to hand out and ids are never reused while the index stays " +
					"loaded. A restart helps only if the tuples holding the largest ids have been released since, " +
					"because a load resumes one above the largest id still in use - otherwise the entity collection " +
					"must be reindexed."
			);
		}
		this.nextTupleId.set(tupleId + 1);
		final ComparableArray ownTuple = new ComparableArray(Arrays.copyOf(tuple, tuple.length));
		this.tupleIds.put(ownTuple, tupleId);
		this.tuples.put(tupleId, new DictionaryEntry(ownTuple, tupleId, 1));
		this.dictionaryDirty.setToTrue();
		return tupleId;
	}

	/**
	 * Adds one user to a known tuple.
	 *
	 * @param tupleId the tuple id
	 * @return the tuple id
	 */
	private int retainTuple(int tupleId) {
		final DictionaryEntry entry = getDictionaryEntry(tupleId);
		this.tuples.put(entry.id(), new DictionaryEntry(entry.tuple(), entry.id(), entry.userCount() + 1));
		return tupleId;
	}

	/**
	 * Removes one user from a known tuple and drops the tuple from the dictionary when it was the last one.
	 *
	 * @param tupleId the tuple id
	 */
	private void releaseTuple(int tupleId) {
		final DictionaryEntry entry = getDictionaryEntry(tupleId);
		if (entry.userCount() == 1) {
			this.tuples.remove(entry.id());
			this.tupleIds.remove(entry.tuple());
			this.dictionaryDirty.setToTrue();
		} else {
			this.tuples.put(entry.id(), new DictionaryEntry(entry.tuple(), entry.id(), entry.userCount() - 1));
		}
	}

	/**
	 * Returns the dictionary entry of a tuple id an entry refers to.
	 *
	 * @param tupleId the tuple id
	 * @return the dictionary entry
	 */
	@Nonnull
	private DictionaryEntry getDictionaryEntry(int tupleId) {
		final DictionaryEntry entry = this.tuples.get(tupleId);
		if (entry == null) {
			throw new GenericEvitaInternalError(
				"Tuple id " + tupleId + " is referred to, but missing from the tuple dictionary!"
			);
		}
		return entry;
	}

	/**
	 * Captures the tuple dictionary as positionally-aligned columns in ascending id order - the shape the root part
	 * persists.
	 *
	 * @return the dictionary snapshot
	 */
	@Nonnull
	private DictionarySnapshot dictionarySnapshot() {
		final DictionaryEntry[] dictionaryEntries = new DictionaryEntry[this.tuples.size()];
		final int[] index = {0};
		this.tuples.forEach((tupleId, entry) -> dictionaryEntries[index[0]++] = entry);
		Arrays.sort(dictionaryEntries, Comparator.comparingInt(DictionaryEntry::id));
		final int[] tupleIdColumn = new int[dictionaryEntries.length];
		final Serializable[][] tupleColumn = new Serializable[dictionaryEntries.length][];
		for (int i = 0; i < dictionaryEntries.length; i++) {
			tupleIdColumn[i] = dictionaryEntries[i].id();
			tupleColumn[i] = dictionaryEntries[i].tuple().array();
		}
		return new DictionarySnapshot(
			tupleIdColumn, tupleColumn, sortValueSlotSnapshot(), chainPredecessorSlotSnapshot()
		);
	}

	/**
	 * Captures the declared chain keys in ascending slot id order - the shape the root part persists.
	 *
	 * @return the chain predecessor slots
	 */
	@Nonnull
	private ChainPredecessorSlot[] chainPredecessorSlotSnapshot() {
		final ChainPredecessorSlot[] slots = new ChainPredecessorSlot[this.chainKeys.size()];
		final int[] index = {0};
		this.chainKeys.forEach(
			(attributeIndexKey, slotId) -> slots[index[0]++] = new ChainPredecessorSlot(slotId, attributeIndexKey)
		);
		Arrays.sort(slots, Comparator.comparingInt(ChainPredecessorSlot::slotId));
		return slots;
	}

	/**
	 * Captures the declared sort keys with their value dictionaries in ascending slot id order, each dictionary in
	 * ascending id order - the shape the root part persists.
	 *
	 * @return the sort value slots
	 */
	@Nonnull
	private SortValueSlot[] sortValueSlotSnapshot() {
		final DeclaredSortKey[] declaredSortKeys = new DeclaredSortKey[this.sortKeys.size()];
		final int[] keyIndex = {0};
		this.sortKeys.forEach((attributeIndexKey, sortKey) -> declaredSortKeys[keyIndex[0]++] = sortKey);
		Arrays.sort(declaredSortKeys, Comparator.comparingInt(DeclaredSortKey::slotId));
		// `pack(slotId, valueId)` of non-negative halves orders by slot first and by id within a slot
		final long[] packedIds = new long[this.sortValues.size()];
		final int[] valueIndex = {0};
		this.sortValues.forEach((packedId, entry) -> packedIds[valueIndex[0]++] = packedId);
		Arrays.sort(packedIds);

		final SortValueSlot[] slots = new SortValueSlot[declaredSortKeys.length];
		int nextPackedId = 0;
		for (int slotIndex = 0; slotIndex < declaredSortKeys.length; slotIndex++) {
			final DeclaredSortKey sortKey = declaredSortKeys[slotIndex];
			final int firstPackedId = nextPackedId;
			while (nextPackedId < packedIds.length && NumberUtils.unpackHigh(packedIds[nextPackedId]) == sortKey.slotId()) {
				nextPackedId++;
			}
			final int[] valueIds = new int[nextPackedId - firstPackedId];
			final Serializable[] values = new Serializable[valueIds.length];
			for (int i = 0; i < valueIds.length; i++) {
				final SortValueEntry entry = this.sortValues.get(packedIds[firstPackedId + i]);
				valueIds[i] = entry.id();
				values[i] = entry.key().value();
			}
			slots[slotIndex] = new SortValueSlot(
				sortKey.slotId(), sortKey.attributeIndexKey(), sortKey.comparatorBase(),
				sortKey.indexedDecimalPlaces(), valueIds, values
			);
		}
		return slots;
	}

	/**
	 * Promotes the page set staged by the previous flush to the live baseline, so this flush's freed-page diff is
	 * taken against what disk actually holds.
	 *
	 * A warm-up flush never reaches the commit-merge that publishes in
	 * {@link #createCopyWithMergedTransactionalMemory}, so without this the live set of a bulk-loaded index would
	 * stay empty while disk moved on, and a leaf merge between two warm-up flushes would leave the dropped page
	 * orphaned on disk - and, because this PAGED root lists only its leaves, also listed on a stale root that a
	 * later flush skips rewriting. Publishing here is safe
	 * because a failed flush is never followed by another flush of the same data: the catalog's transaction
	 * processing is suspended, or the warm-up catalog is marked unpublishable. See
	 * {@code ReferenceTypeCardinalityIndex#publishPreviousFlush} for the full argument.
	 */
	private void publishPreviousFlush() {
		this.pageStreamRegistry.publishStaged();
	}

	/**
	 * Walks the entry tree leaf by leaf and returns the granular emission for this flush: the leaves that changed,
	 * the ordered live leaf-page list, the high-water, the freed page sequences and whether the leaf list changed.
	 * The set staged by the previous flush is published first, see {@link #publishPreviousFlush()}.
	 *
	 * @return the granular emission
	 */
	@Nonnull
	private PageEmission<LeafPage> collectChangedPages() {
		publishPreviousFlush();
		final List<LeafPageHandle> handles = this.entries.leafPageHandles();
		return this.pageStreamRegistry.collectChangedPages(
			ENTRY_PAGE_STREAM, handles,
			(pageSequence, handle) -> {
				final BucketCursor cursor = handle.cursor();
				final CompositeLongArray pageKeys = new CompositeLongArray();
				final CompositeLongArray pagePayloads = new CompositeLongArray();
				while (cursor.next()) {
					pageKeys.add((Long) cursor.value());
					pagePayloads.add(cursor.longRecordId());
				}
				return new LeafPage(pageSequence, pageKeys.toArray(), pagePayloads.toArray());
			}
		);
	}

	/**
	 * A forward cursor over the {@link VirtualReferencePartitionSlot#PARTITION_OWNER} rows, in ascending partition
	 * primary key order - see {@link #seekPartitionOwner(int)}. It reads the tree it was opened over and must not
	 * outlive a change of it.
	 *
	 * Moving to a partition primary key that lies ahead ({@link #advanceTo(int)}) first steps along the rows a few
	 * times and only then descends the tree from its root again, so a merge with a dense set of keys walks the rows
	 * once, while a merge with a sparse set costs one descent per far jump - never one per key.
	 */
	public static final class PartitionOwnerCursor {
		/**
		 * How many rows {@link #advanceTo(int)} steps along before it descends the tree again.
		 */
		private static final int GALLOP_STEPS = 8;
		/**
		 * Id of the slot the cursor walks.
		 */
		private static final int SLOT_ID = VirtualReferencePartitionSlot.PARTITION_OWNER.getId();
		/**
		 * The entry tree of the index the cursor was opened over.
		 */
		@Nonnull private final LongPayloadBucketTree entries;
		/**
		 * The cursor of the tree positioned at the current row, replaced by every descent.
		 */
		@Nonnull private BucketCursor cursor;
		/**
		 * Primary key of the partition of the current row, `0` before the first move - no partition has it.
		 */
		private int partitionPrimaryKey;
		/**
		 * Payload of the current row, `pack(ownerPrimaryKey, targetPrimaryKey)`.
		 */
		private long ownerAndTarget;
		/**
		 * Whether the cursor moved past the last row of the slot.
		 */
		private boolean exhausted;

		/**
		 * Opens the cursor before the first row whose partition primary key is greater than or equal to the given one.
		 *
		 * @param entries                 the entry tree
		 * @param fromPartitionPrimaryKey the smallest partition primary key of interest
		 */
		private PartitionOwnerCursor(@Nonnull LongPayloadBucketTree entries, int fromPartitionPrimaryKey) {
			this.entries = entries;
			// partition primary keys are positive, so a non-positive bound starts at the first row of the slot
			this.cursor = entries.cursor(NumberUtils.pack(SLOT_ID, Math.max(fromPartitionPrimaryKey, 0)));
		}

		/**
		 * Moves to the next row of the slot.
		 *
		 * @return true when the cursor is on a row, false when the slot holds no further one
		 */
		public boolean next() {
			if (this.exhausted) {
				return false;
			}
			if (this.cursor.next()) {
				final long key = (Long) this.cursor.value();
				// the rows of the slot are one contiguous key range - the first key of another slot ends it
				if (NumberUtils.unpackHigh(key) == SLOT_ID) {
					this.partitionPrimaryKey = NumberUtils.unpackLow(key);
					this.ownerAndTarget = this.cursor.longRecordId();
					return true;
				}
			}
			this.exhausted = true;
			return false;
		}

		/**
		 * Moves to the first row whose partition primary key is greater than or equal to the given one. A cursor
		 * already on such a row stays where it is; the cursor never moves back.
		 *
		 * @param targetPartitionPrimaryKey the partition primary key to reach
		 * @return true when the cursor is on a row, false when the slot holds no row at or after the key
		 */
		public boolean advanceTo(int targetPartitionPrimaryKey) {
			if (this.exhausted) {
				return false;
			}
			if (this.partitionPrimaryKey >= targetPartitionPrimaryKey) {
				return true;
			}
			// a near row is cheaper to reach by stepping than by a descent from the root
			for (int i = 0; i < GALLOP_STEPS; i++) {
				if (!next()) {
					return false;
				}
				if (this.partitionPrimaryKey >= targetPartitionPrimaryKey) {
					return true;
				}
			}
			// a far row - descend once to the first key at or after it
			this.cursor = this.entries.cursor(NumberUtils.pack(SLOT_ID, targetPartitionPrimaryKey));
			return next();
		}

		/**
		 * Returns the primary key of the partition of the current row. Valid only after a move that returned true.
		 *
		 * @return the partition primary key
		 */
		public int partitionPrimaryKey() {
			return this.partitionPrimaryKey;
		}

		/**
		 * Returns the payload of the current row, `pack(ownerPrimaryKey, targetPrimaryKey)` - see
		 * {@link #getPartitionOwner(int)}. Valid only after a move that returned true.
		 *
		 * @return the packed owner and referenced entity
		 */
		public long ownerAndTarget() {
			return this.ownerAndTarget;
		}

	}

	/**
	 * Receives one entry of a slot.
	 */
	@FunctionalInterface
	public interface EntryConsumer {

		/**
		 * Receives one entry of a slot.
		 *
		 * @param partitionPrimaryKey the partition primary key
		 * @param payload             the payload of the entry
		 */
		void accept(int partitionPrimaryKey, long payload);

	}

	/**
	 * One tuple of the dictionary with the number of partitions using it. The tuple and the id box are the very
	 * instances the `tuple -> id` map holds, so the two maps share them rather than holding copies.
	 *
	 * @param tuple     the representative attribute values
	 * @param id        the tuple id
	 * @param userCount how many partitions refer to the tuple, always positive
	 */
	private record DictionaryEntry(
		@Nonnull ComparableArray tuple,
		@Nonnull Integer id,
		int userCount
	) implements Serializable {
	}

	/**
	 * Receives one entry of a sort value slot during the rebuild on load.
	 */
	@FunctionalInterface
	private interface SortValueEntryVisitor {

		/**
		 * Receives one entry of a sort value slot.
		 *
		 * @param slotIndex           the index of the slot among the persisted slots
		 * @param position            the position of the entry's value in the sort key's order
		 * @param partitionPrimaryKey the partition primary key
		 */
		void visit(int slotIndex, int position, int partitionPrimaryKey);

	}

	/**
	 * A declared sort key: the slot its sort values live in, what it sorts by, and the next id its value dictionary
	 * hands out. Ids are handed out in ascending order and never twice while the key stays declared; once the next id
	 * reaches {@link VirtualReferencePartitionIndex#EXHAUSTED_ID_SPACE}, a new value is refused with an
	 * {@link IdentifierOverflowException}.
	 *
	 * @param slotId               the id of the slot, the high half of every key of its entries
	 * @param attributeIndexKey    the attribute or compound, with its locale, the partitions are sorted by
	 * @param comparatorBase       one descriptor per sorted element - a single one for a plain attribute
	 * @param indexedDecimalPlaces the decimal places a `BigDecimal` attribute is scaled by, `0` for any other type
	 * @param normalizer           the normalizer the sort index of the key applies to each value
	 * @param nextValueId          the id the next value added to the slot's dictionary gets
	 */
	private record DeclaredSortKey(
		int slotId,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull ComparatorSource[] comparatorBase,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<Serializable> normalizer,
		int nextValueId
	) implements Serializable {

		/**
		 * Declares a sort key with the normalizer its sort index uses - the single-attribute normalizer for one
		 * element, the compound one for more.
		 *
		 * @param slotId               the id of the slot
		 * @param attributeIndexKey    the attribute or compound, with its locale
		 * @param comparatorBase       one descriptor per sorted element
		 * @param indexedDecimalPlaces the decimal places a `BigDecimal` attribute is scaled by
		 * @param nextValueId          the id the next value added to the slot's dictionary gets
		 * @return the declared sort key
		 */
		@Nonnull
		static DeclaredSortKey of(
			int slotId,
			@Nonnull AttributeIndexKey attributeIndexKey,
			@Nonnull ComparatorSource[] comparatorBase,
			int indexedDecimalPlaces,
			int nextValueId
		) {
			final UnaryOperator<Serializable> normalizer = comparatorBase.length == 1 ?
				SortIndex.createNormalizerFor(comparatorBase[0], indexedDecimalPlaces)
					.orElseGet(UnaryOperator::identity) :
				SortIndex.createNormalizerFor(comparatorBase, indexedDecimalPlaces);
			return new DeclaredSortKey(
				slotId, attributeIndexKey, comparatorBase, indexedDecimalPlaces, normalizer, nextValueId
			);
		}

		/**
		 * Returns TRUE if the key sorts by a sortable attribute compound rather than a plain attribute.
		 *
		 * @return TRUE for a compound
		 */
		boolean isCompound() {
			return this.comparatorBase.length > 1;
		}

		/**
		 * Returns this key with the next value id advanced.
		 *
		 * @param nextValueId the id the next value added to the slot's dictionary gets
		 * @return the updated key
		 */
		@Nonnull
		DeclaredSortKey withNextValueId(int nextValueId) {
			return new DeclaredSortKey(
				this.slotId, this.attributeIndexKey, this.comparatorBase, this.indexedDecimalPlaces,
				this.normalizer, nextValueId
			);
		}

		/**
		 * Prices the record and its comparator base. The attribute index key belongs to the schema and every
		 * {@link ComparatorSource} component addresses a `Class` or an enum constant, both JVM-owned; the normalizer
		 * is fixed scaffolding chosen by the comparator base and contributes its slot alone, as in {@link SortIndex}.
		 *
		 * @return the heap footprint in bytes
		 */
		long getHeapSizeInBytes() {
			final VMLayout layout = VMLayout.current();
			return layout.sizeOfObject(3L * Integer.BYTES + 3L * layout.referenceSize())
				+ layout.sizeOfArray(this.comparatorBase.length, layout.referenceSize())
				+ this.comparatorBase.length * layout.sizeOfObject(3L * layout.referenceSize());
		}

	}

	/**
	 * A normalized sort value of one slot as the value dictionary is keyed by it. Equality and hash follow the sort
	 * index, not plain `equals`: values the sort key orders as one, `1.0` and `1.00` among them, are one key.
	 *
	 * @param slotId the id of the slot
	 * @param value  the normalized value, a {@link ComparableArray} for a compound
	 */
	private record SortValueKey(int slotId, @Nonnull Serializable value) implements Serializable {

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof SortValueKey that)) {
				return false;
			}
			return this.slotId == that.slotId && isSameSortValue(this.value, that.value);
		}

		@Override
		public int hashCode() {
			return 31 * this.slotId + sortValueHash(this.value);
		}

	}

	/**
	 * One value of a slot's dictionary with the number of partitions holding it. The key and the id box are the very
	 * instances the `(slot, value) -> id` map holds, so the two maps share them rather than holding copies.
	 *
	 * @param key       the slot and the normalized value
	 * @param id        the value id
	 * @param userCount how many partitions hold the value, always positive
	 */
	private record SortValueEntry(
		@Nonnull SortValueKey key,
		@Nonnull Integer id,
		int userCount
	) implements Serializable {
	}

	/**
	 * The dictionaries as the root part persists them: the tuples as positionally-aligned columns in ascending id
	 * order, the declared sort keys with their value dictionaries, and the declared chain keys.
	 *
	 * @param tupleIds              the tuple ids
	 * @param tuples                the tuples, aligned with `tupleIds`
	 * @param sortValueSlots        the sort value slots in ascending slot id order
	 * @param chainPredecessorSlots the chain predecessor slots in ascending slot id order
	 */
	private record DictionarySnapshot(
		@Nonnull int[] tupleIds,
		@Nonnull Serializable[][] tuples,
		@Nonnull SortValueSlot[] sortValueSlots,
		@Nonnull ChainPredecessorSlot[] chainPredecessorSlots
	) {
	}

	/**
	 * One changed leaf page of the granular emission: its page sequence and its columns in ascending key order.
	 *
	 * @param pageSequence the leaf's page sequence within the stream
	 * @param keys         the leaf's keys in ascending order
	 * @param payloads     the payload of each key, aligned with `keys`
	 */
	private record LeafPage(int pageSequence, @Nonnull long[] keys, @Nonnull long[] payloads) {
	}

}
