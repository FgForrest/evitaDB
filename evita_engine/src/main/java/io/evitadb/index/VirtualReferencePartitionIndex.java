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

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.array.CompositeLongArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.bPlusTree.LongPayloadBucketTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.LeafPageHandle;
import io.evitadb.index.bPlusTree.ValueColumnFactory;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.PersistentTransactionalMap;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.page.PageStreamRegistry;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionRootRemoval;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NumberUtils;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

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
 * # Persistence
 *
 * The tree is persisted granularly, exactly like {@link io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex}:
 * a tree of one leaf is written inline on the root part, a larger one as one
 * {@link VirtualReferencePartitionIndexLeafPagePart} per leaf, rewriting only the leaves a flush changed. The
 * dictionary rides inline on the root, without its counts - they are rebuilt from the
 * {@link VirtualReferencePartitionSlot#REPRESENTATIVE_TUPLE} entries on load. A PAGED root is therefore rewritten
 * only when its leaf list or its dictionary changes, and a change of one partition's entry costs one leaf page.
 * An empty index persists nothing at all and has no root on disk.
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
	 * Whether anything changed since the last flush.
	 */
	@Nonnull private final TransactionalBoolean dirty;
	/**
	 * Whether a tuple was added to or dropped from the dictionary since the last flush, which is what forces a PAGED
	 * root to be rewritten even when its leaf list did not change.
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
		@Nonnull Serializable[][] tuples
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
		return new VirtualReferencePartitionIndex(tree, registry, tupleIds, tuples, indexDescription);
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
	}

	/**
	 * Rebuilds an index whose entries were persisted inline on its root.
	 *
	 * @param indexDescription identification of the index for corruption diagnostics (e.g. the reference)
	 * @param keys             the persisted entry keys in ascending order
	 * @param payloads         the persisted entry payloads, aligned with `keys`
	 * @param tupleIds         the persisted dictionary tuple ids
	 * @param tuples           the persisted dictionary tuples, aligned with `tupleIds`
	 */
	public VirtualReferencePartitionIndex(
		@Nonnull String indexDescription,
		@Nonnull long[] keys,
		@Nonnull long[] payloads,
		@Nonnull int[] tupleIds,
		@Nonnull Serializable[][] tuples
	) {
		this(seedTree(keys, payloads), new PageStreamRegistry(), tupleIds, tuples, indexDescription);
	}

	/**
	 * Adopts a tree rebuilt from storage and rebuilds the tuple dictionary around it - the persisted tuples paired
	 * with the user counts read off the {@link VirtualReferencePartitionSlot#REPRESENTATIVE_TUPLE} entries.
	 *
	 * @param tree               the rebuilt entry tree
	 * @param pageStreamRegistry the page bookkeeping matching the tree
	 * @param tupleIds           the persisted dictionary tuple ids
	 * @param tuples             the persisted dictionary tuples, aligned with `tupleIds`
	 * @param indexDescription   identification of the index for corruption diagnostics
	 */
	private VirtualReferencePartitionIndex(
		@Nonnull TransactionalBucketBPlusTree tree,
		@Nonnull PageStreamRegistry pageStreamRegistry,
		@Nonnull int[] tupleIds,
		@Nonnull Serializable[][] tuples,
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
		for (int i = 0; i < tupleIds.length; i++) {
			final int tupleId = tupleIds[i];
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
			tupleIdIndex.put(tuple, boxedId);
			tupleIndex.put(boxedId, new DictionaryEntry(tuple, boxedId, userCount));
		}
		this.tupleIds = new PersistentTransactionalMap<>(tupleIdIndex);
		this.tuples = new PersistentTransactionalMap<>(tupleIndex);
	}

	/**
	 * Adopts already-committed state directly; used by {@link #createCopyWithMergedTransactionalMemory}.
	 *
	 * @param committedTree      the committed entry tree
	 * @param pageStreamRegistry the page bookkeeping, carried by reference
	 * @param tupleIds           the committed `tuple -> id` map
	 * @param tuples             the committed `id -> entry` map
	 */
	private VirtualReferencePartitionIndex(
		@Nonnull LongPayloadBucketTree committedTree,
		@Nonnull PageStreamRegistry pageStreamRegistry,
		@Nonnull Map<ComparableArray, Integer> tupleIds,
		@Nonnull Map<Integer, DictionaryEntry> tuples
	) {
		this.dirty = new TransactionalBoolean();
		this.dictionaryDirty = new TransactionalBoolean();
		this.entries = committedTree;
		this.pageStreamRegistry = pageStreamRegistry;
		this.tupleIds = new PersistentTransactionalMap<>(tupleIds);
		this.tuples = new PersistentTransactionalMap<>(tuples);
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
	 * Composes the tree key of a partition's entry in a slot.
	 *
	 * @param slot                the slot
	 * @param partitionPrimaryKey the partition primary key, positive
	 * @return the composed key
	 */
	private static long key(@Nonnull VirtualReferencePartitionSlot slot, int partitionPrimaryKey) {
		// a plain branch rather than an assertion with a message, so the valid path builds no string
		if (partitionPrimaryKey <= 0) {
			throw new GenericEvitaInternalError(
				"Partition primary key must be positive, but was " + partitionPrimaryKey + "!"
			);
		}
		return NumberUtils.pack(slot.getId(), partitionPrimaryKey);
	}

	/**
	 * Returns TRUE if the index holds no entry.
	 *
	 * @return TRUE if the index holds no entry
	 */
	public boolean isEmpty() {
		return this.entries.size() == 0;
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
	 * - an empty index removes its root and every leaf page it has on disk, so it leaves nothing behind,
	 * - a PAGED index writes one leaf page per changed leaf and a removal per freed leaf, and rewrites its root only
	 *   when the leaf list or the tuple dictionary changed,
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
		if (this.entries.size() == 0) {
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
			// the root carries the leaf list and the dictionary only - a flush that changed neither leaves the
			// persisted root byte-identical, so a one-entry change costs one leaf page
			if (emission.pageListChanged() || this.dictionaryDirty.isTrue()) {
				final DictionarySnapshot dictionary = dictionarySnapshot();
				sink.addChangeToStore(
					VirtualReferencePartitionIndexStoragePart.paged(
						entityIndexPrimaryKey, referenceName,
						emission.highWaterPageSequence(), emission.orderedPageSequences(),
						dictionary.tupleIds(), dictionary.tuples(), null
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
					dictionary.tupleIds(), dictionary.tuples(), null
				)
			);
		}
	}

	@Override
	public void resetDirty() {
		this.dirty.reset();
		this.dictionaryDirty.reset();
	}

	/**
	 * Returns the heap this index occupies, in bytes - its own object, both dirty flags, the entry tree and the tuple
	 * dictionary.
	 *
	 * The entry tree keeps its keys inline in a primitive column, but its internal nodes box one separator key per
	 * leaf boundary, which {@link IndexHeapSize#OWNED_KEY_SIZER} prices. Each dictionary tuple and each id box is
	 * shared by both dictionary maps and charged once, at the `tuple -> id` map; the `id -> entry` map is charged only
	 * for its entry shells. {@link #pageStreamRegistry} is flush bookkeeping rather than index content and is excluded.
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
		// the dirty / dictionaryDirty / entries / pageStreamRegistry / tupleIds / tuples slots
		return layout.sizeOfObject(6L * layout.referenceSize())
			+ this.dirty.getHeapSizeInBytes()
			+ this.dictionaryDirty.getHeapSizeInBytes()
			+ this.entries.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER)
			+ this.tupleIds.getHeapSizeInBytes(
				VirtualReferencePartitionIndex::tupleSizeInBytes, tupleId -> boxedInteger
			)
			+ this.tuples.getHeapSizeInBytes(tupleId -> 0L, entry -> dictionaryEntry);
	}

	/*
		TransactionalLayerProducer implementation
	 */

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.entries.removeLayer(transactionalLayer);
		this.tupleIds.removeLayer(transactionalLayer);
		this.tuples.removeLayer(transactionalLayer);
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
				transactionalLayer.getStateCopyWithCommittedChanges(this.tuples)
			);
		} else {
			return this;
		}
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Prices one dictionary tuple the index owns: the wrapper, its array and every value in it.
	 *
	 * @param tuple the dictionary tuple
	 * @return the heap footprint of the tuple in bytes
	 */
	private static long tupleSizeInBytes(@Nonnull ComparableArray tuple) {
		final VMLayout layout = VMLayout.current();
		final Serializable[] values = tuple.array();
		long size = layout.sizeOfObject(layout.referenceSize())
			+ layout.sizeOfArray(values.length, layout.referenceSize());
		for (final Serializable value : values) {
			if (value != null) {
				size += EvitaDataTypes.estimateSize(value);
			}
		}
		return size;
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
		final boolean removed = this.entries.removeLongRecord(key);
		if (removed) {
			this.dirty.setToTrue();
		}
		return removed;
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
	 * Adds a tuple the dictionary does not know yet, with one user, under an id one above the largest id in use.
	 *
	 * @param tuple the representative attribute values, copied into the dictionary
	 * @return the new tuple id
	 */
	private int addTuple(@Nonnull Serializable[] tuple) {
		final int[] largestTupleId = {0};
		this.tuples.forEach((tupleId, entry) -> largestTupleId[0] = Math.max(largestTupleId[0], tupleId));
		// one box shared by both maps, see DictionaryEntry
		final Integer tupleId = largestTupleId[0] + 1;
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
		return new DictionarySnapshot(tupleIdColumn, tupleColumn);
	}

	/**
	 * Promotes the page set staged by the previous flush to the live baseline, so this flush's freed-page diff is
	 * taken against what disk actually holds.
	 *
	 * A warm-up flush never reaches the commit-merge that publishes in
	 * {@link #createCopyWithMergedTransactionalMemory}, so without this the live set of a bulk-loaded index would stay empty while disk moved on, and a leaf merge
	 * between two warm-up flushes would leave the dropped page orphaned on disk - and, because this PAGED root lists
	 * only its leaves, also listed on a stale root that a later flush skips rewriting. Publishing here is safe
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
	 * The tuple dictionary as positionally-aligned columns in ascending id order.
	 *
	 * @param tupleIds the tuple ids
	 * @param tuples   the tuples, aligned with `tupleIds`
	 */
	private record DictionarySnapshot(@Nonnull int[] tupleIds, @Nonnull Serializable[][] tuples) {
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
