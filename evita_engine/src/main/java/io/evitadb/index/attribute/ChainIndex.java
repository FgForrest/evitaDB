/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.attribute;

import com.carrotsearch.hppc.IntArrayDeque;
import com.carrotsearch.hppc.IntArrayList;
import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntIntHashMap;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.ChainableType;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.array.UnorderedLookup;
import io.evitadb.index.array.UnorderedLookupTree.LeafPageHandle;
import io.evitadb.index.array.UnorderedLookupTree.LeafPageInput;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.page.PageStreamRegistry;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexStoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PrimitiveIterator.OfInt;
import java.util.stream.Collectors;

import static io.evitadb.dataType.ChainableType.HEAD_PK;
import static io.evitadb.utils.Assert.isPremiseValid;
import static io.evitadb.utils.Assert.isTrue;
import static java.util.Optional.ofNullable;

/**
 * This is a special index for data type of {@link ChainableType}.
 * Semi-consistent orders by:
 *
 * - the longest chain of elements starting with the head element
 * - others chains of elements starting with the head element (in order of their length)
 * - the chains that are not starting with the head element (in order of their length) - i.e. the chains that start
 * with element pointing to the predecessor that is part of the chain starting with the head element
 * - the chains with circular reference (in order of their length) - i.e. where the head has predecessor that is
 * part of its chain
 *
 * The structure of semi-consistent index is defined by the order of the operations applied on the index. The head of
 * circular dependency chain is setup at the moment when the element in existing chain is ordered to be successor of
 * an element which is present in the tail of its chain.
 *
 * # Internal representation (positional model)
 *
 * All elements of the attribute live in a **single** order-statistic {@link TransactionalUnorderedIntArray}
 * ({@link #elements}) in their materialized (semi-consistent) order. A logical *chain* is a **contiguous, head-first
 * run** of that array, described by a {@link ChainDescriptor} keyed by the run's head primary key in {@link #chains}
 * (the run spans `[indexOf(headPk), indexOf(headPk) + length)`). Chain membership of an element is therefore
 * **positional** - it is not stored per element - so promoting a new head, merging or splitting a chain never has to
 * re-stamp the elements of the chain (no `O(K)` reclassification). The only per-element datum kept is its predecessor
 * primary key ({@link #predecessors}), which mirrors the entity's {@link ChainableType} attribute and is needed for
 * the head's external predecessor and for integrity verification.
 *
 * A single-element relocation (`upsertPredecessor` on an existing element) therefore *detaches* the element from the
 * tree and *re-inserts* it right after its new predecessor, **without rewriting any neighbour's predecessor** - a
 * neighbour whose stored predecessor no longer equals its positional predecessor simply becomes the head of a new
 * (transient) run, created in `O(1)`. The {@link #collapse(IntArrayList)} pass then re-merges runs whose head predecessor is the
 * tail of another run (relocating the shorter run when the two are not already physically adjacent), which restores the
 * eventually-consistent single chain.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class ChainIndex implements
	IndexDataStructure,
	ConsistencySensitiveDataStructure,
	SortedRecordsSupplierFactory,
	TransactionalLayerProducer<ChainIndexChanges, ChainIndex>,
	Serializable
{
	@Serial private static final long serialVersionUID = 6633952268102524794L;
	/**
	 * Local stream key used with {@link #pageStreamRegistry}. A chain index owns exactly one page stream (its element
	 * tree), so a single fixed key suffices; the persisted, globally-unique stream id is a separate concept resolved
	 * store-side from the sub-index identity (see {@link ChainIndexLeafPagePart}), never this value.
	 */
	private static final int ELEMENTS_PAGE_STREAM = 0;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

	/**
	 * Single global order-statistic array holding **all** elements of the attribute in their materialized
	 * (semi-consistent) order. Each logical chain is a contiguous, head-first run of this array.
	 */
	final TransactionalUnorderedIntArray elements;
	/**
	 * Per-chain descriptors keyed by the chain's head primary key. The descriptor stores the run length and the head's
	 * {@link ElementState}; the run occupies `[elements.indexOf(headPk), +length)`.
	 */
	final TransactionalMap<Integer, ChainDescriptor> chains;
	/**
	 * Per-element predecessor primary key (mirror of the entity's {@link ChainableType} attribute). A head element maps
	 * to {@link ChainableType#HEAD_PK} when it is a true head, or to the external/absent predecessor it points at.
	 */
	final TransactionalMap<Integer, Integer> predecessors;
	/**
	 * Materialized inverse of {@link #predecessors}: maps each predecessor primary key to the bitmap of every element
	 * that declares it as its predecessor (excluding {@link ChainableType#HEAD_PK}). This lets the work-queue
	 * {@link #collapse(IntArrayList)} find, in `O(1)`, the successor heads waiting on an element the moment that
	 * element becomes present or becomes a run tail - replacing the former `O(C)` rescan of every chain descriptor on
	 * each mutation. The map is kept as sparse as the at-rest chain set: a bucket is dropped the moment it empties.
	 */
	final TransactionalMap<Integer, TransactionalBitmap> successorsByPredecessor;
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	@Nonnull private final TransactionalBoolean dirty;
	/**
	 * Reference key (discriminator) of the {@link AbstractReducedEntityIndex} this index belongs to. Or null if
	 * this index is part of the global {@link GlobalEntityIndex}.
	 */
	@Getter @Nullable private final RepresentativeReferenceKey referenceKey;
	/**
	 * Contains key identifying the attribute.
	 */
	@Getter private final AttributeIndexKey attributeIndexKey;
	/**
	 * Temporary data structure that should be NULL and should exist only when {@link Catalog} is in
	 * bulk insertion or read only state where transaction are not used.
	 */
	@Nullable private ChainIndexChanges chainIndexChanges;
	/**
	 * Owner-resident page bookkeeping for the granular chain-index storage layout: the advance-only page-sequence
	 * allocator, the explicit high-water and the live-page set of the {@link #elements} tree's persisted leaf pages. It
	 * lives OUTSIDE transactional memory and is carried BY REFERENCE through
	 * {@link #createCopyWithMergedTransactionalMemory} so the surviving committed owner keeps the allocator and live set
	 * across commits (the discarded transactional copy never has its own). It is consulted only on the single-writer
	 * flush/commit path and is therefore savepoint-exempt (the per-leaf page sequence / dirty flag ride the tree's own
	 * mementos instead).
	 */
	@Nonnull private final PageStreamRegistry pageStreamRegistry;

	public ChainIndex(@Nonnull AttributeIndexKey attributeIndexKey) {
		this(null, attributeIndexKey);
	}

	public ChainIndex(@Nullable RepresentativeReferenceKey referenceKey, @Nonnull AttributeIndexKey attributeIndexKey) {
		this.referenceKey = referenceKey;
		this.attributeIndexKey = attributeIndexKey;
		this.dirty = new TransactionalBoolean();
		// head-aware: the element array tracks chain-head marks so findRun locates the covering run in O(log N)
		this.elements = new TransactionalUnorderedIntArray(true);
		this.chains = new TransactionalMap<>(CollectionUtils.createHashMap(32));
		this.predecessors = new TransactionalMap<>(CollectionUtils.createHashMap(64));
		// the value type + wrapper make the transactional map recurse into the bitmap values on merge / removeLayer
		this.successorsByPredecessor = new TransactionalMap<>(
			CollectionUtils.createHashMap(64), TransactionalBitmap.class, TransactionalBitmap::new
		);
		this.pageStreamRegistry = new PageStreamRegistry();
	}

	public ChainIndex(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull int[][] chains,
		@Nonnull Map<Integer, ChainElementState> elementStates
	) {
		this(null, attributeIndexKey, chains, elementStates);
	}

	public ChainIndex(
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull int[][] chains,
		@Nonnull Map<Integer, ChainElementState> elementStates
	) {
		this.referenceKey = referenceKey;
		this.attributeIndexKey = attributeIndexKey;
		this.dirty = new TransactionalBoolean();

		// concatenate all chains into the single global array (run order = supplied array order)
		int total = 0;
		for (final int[] chain : chains) {
			total += chain.length;
		}
		final int[] flattened = new int[total];
		int offset = 0;
		// each chain's head (chain[0]) lands at its concatenation offset - collect these ascending positions so the
		// head-aware array marks them as chain heads during the O(N) bulk build (in the committed base)
		final int[] headPositions = new int[chains.length];
		int headCount = 0;
		final Map<Integer, ChainDescriptor> descriptors = CollectionUtils.createHashMap(chains.length << 1);
		for (final int[] chain : chains) {
			if (chain.length == 0) {
				continue;
			}
			System.arraycopy(chain, 0, flattened, offset, chain.length);
			headPositions[headCount++] = offset;
			offset += chain.length;
			final int headPk = chain[0];
			final ChainElementState headState = elementStates.get(headPk);
			// the head's state is preserved verbatim from the loaded data so the consistency report can validate it
			final ElementState state = headState == null ? ElementState.SUCCESSOR : headState.state();
			descriptors.put(headPk, new ChainDescriptor(chain.length, state));
		}
		this.elements = new TransactionalUnorderedIntArray(flattened, Arrays.copyOf(headPositions, headCount));
		this.chains = new TransactionalMap<>(descriptors);

		// mirror the per-element predecessor primary keys
		final Map<Integer, Integer> predecessorMap = CollectionUtils.createHashMap(elementStates.size() << 1);
		for (final Entry<Integer, ChainElementState> entry : elementStates.entrySet()) {
			predecessorMap.put(entry.getKey(), entry.getValue().predecessorPrimaryKey());
		}
		this.predecessors = new TransactionalMap<>(predecessorMap);

		// build the inverse of predecessors (predecessorPk -> declaring element pks) directly in the BASE state so the
		// work-queue collapse can locate the successor heads waiting on any element in O(1). HEAD_PK predecessors are
		// skipped (a true head never collapses via its predecessor). Every bitmap is constructed up-front from a plain
		// int[] (no incremental transactional op), so the data lands in BASE even when the index is loaded mid-
		// transaction - the same reason `predecessors` above is passed whole to the map constructor.
		final Map<Integer, IntArrayList> successorAccumulator = CollectionUtils.createHashMap(predecessorMap.size());
		for (final Entry<Integer, Integer> entry : predecessorMap.entrySet()) {
			final int predecessorPk = entry.getValue();
			if (predecessorPk != HEAD_PK) {
				successorAccumulator.computeIfAbsent(predecessorPk, k -> new IntArrayList()).add(entry.getKey());
			}
		}
		final Map<Integer, TransactionalBitmap> inverseMap = CollectionUtils.createHashMap(successorAccumulator.size());
		for (final Entry<Integer, IntArrayList> entry : successorAccumulator.entrySet()) {
			inverseMap.put(entry.getKey(), new TransactionalBitmap(entry.getValue().toArray()));
		}
		this.successorsByPredecessor = new TransactionalMap<>(
			inverseMap, TransactionalBitmap.class, TransactionalBitmap::new
		);
		this.pageStreamRegistry = new PageStreamRegistry();
	}

	private ChainIndex(
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull TransactionalUnorderedIntArray elements,
		@Nonnull Map<Integer, ChainDescriptor> chains,
		@Nonnull Map<Integer, Integer> predecessors,
		@Nonnull Map<Integer, TransactionalBitmap> successorsByPredecessor,
		@Nonnull PageStreamRegistry pageStreamRegistry
	) {
		this.referenceKey = referenceKey;
		this.attributeIndexKey = attributeIndexKey;
		this.dirty = new TransactionalBoolean();
		this.elements = elements;
		this.chains = new TransactionalMap<>(chains);
		this.predecessors = new TransactionalMap<>(predecessors);
		// the merged copy carries already-committed bitmap values - re-wrap each into a fresh transactional bitmap so
		// subsequent mutations on the new instance stay isolated (mirrors the EntityIndex entityIdsByLanguage copy)
		final Map<Integer, TransactionalBitmap> txSuccessorsByPredecessor =
			CollectionUtils.createHashMap(successorsByPredecessor.size());
		for (final Entry<Integer, TransactionalBitmap> entry : successorsByPredecessor.entrySet()) {
			txSuccessorsByPredecessor.put(entry.getKey(), new TransactionalBitmap(entry.getValue()));
		}
		this.successorsByPredecessor = new TransactionalMap<>(
			txSuccessorsByPredecessor, TransactionalBitmap.class, TransactionalBitmap::new
		);
		// the owner-resident page bookkeeping is carried BY REFERENCE (never re-wrapped): the surviving committed owner
		// keeps the allocator + live set the just-completed flush populated; it lives outside transactional memory
		this.pageStreamRegistry = pageStreamRegistry;
	}

	/**
	 * Rebuilds a {@link ChainIndex} from its persisted granular leaf pages (the PAGED reload path). The pages MUST be
	 * supplied in ascending logical (page-sequence-list) order — the order the PAGED root recorded them — because the
	 * concatenation of their record ids IS the index's materialized order.
	 *
	 * The reconstruction follows the load-bearing order (rebuilding {@link #chains} before {@link #predecessors} would
	 * trip {@link #computeHeadState}, which premise-fails on a missing predecessor entry):
	 *
	 * 1. assemble the {@link #elements} array 1:1 from the pages — one tree leaf per page, boundaries preserved,
	 *    each leaf stamped with its page sequence and left non-dirty so a first post-load flush emits nothing;
	 * 2. populate {@link #predecessors} over the fully-assembled global order — a HEAD record (its head bit set)
	 *    takes its persisted head predecessor; a NON-head record takes the previous record in the global order (a
	 *    page-boundary non-head takes the last record of the previous page — simply "the previous record globally");
	 * 3. build {@link #chains} — each run length is the distance to the next head mark across the concatenated pages
	 *    (the last run reaching to the end), and each head's {@link ElementState} is recomputed from its persisted
	 *    predecessor (state / length are NOT persisted — they can be flipped by a mutation in another leaf);
	 * 4. derive {@link #successorsByPredecessor} as the inverse of predecessors (the array-constructor logic).
	 *
	 * The head marks themselves ride in the assembled {@link #elements} leaves, so no explicit re-marking is needed.
	 *
	 * Finally the owner-resident {@link #pageStreamRegistry} is seeded from the reassembled leaves (page sequences
	 * restored, dirty flags cleared) so the FIRST post-load flush suppresses every untouched leaf and re-emits nothing.
	 *
	 * @param referenceKey          reference-key discriminator of the owning reduced index, or `null` for the global index
	 * @param attributeIndexKey     the attribute identity of this index
	 * @param pages                 the persisted leaf pages in ascending logical order (empty ⇒ an empty index)
	 * @param highWaterPageSequence the maximum page sequence ever allocated for the element-tree page stream (the value
	 *                              persisted on the PAGED root); {@link PageStreamRegistry#NO_PAGE} for an empty index
	 * @return the reconstructed chain index
	 */
	@Nonnull
	public static ChainIndex fromPersistedPages(
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull List<ChainIndexLeafPagePart> pages,
		int highWaterPageSequence
	) {
		// 0. a chain page is positional and carries no ordering invariant, so the stale leaf-page twin corruption
		// manifests as duplicate record ids across pages; assert none exists before assembly (fails fast otherwise — the
		// paged layout never shipped in a released version, so a duplicate is never a production catalog and is not
		// silently repaired)
		assertNoDuplicateChainRecords(pages, attributeIndexKey);
		// 1. assemble the element array 1:1 from the pages (boundary-stable, one leaf per page, dirty=false)
		final List<LeafPageInput> pageInputs = new ArrayList<>(pages.size());
		for (final ChainIndexLeafPagePart page : pages) {
			pageInputs.add(new LeafPageInput(page.getPageSequence(), page.getRecordIds(), page.getHeadWords()));
		}
		final TransactionalUnorderedIntArray elements = new TransactionalUnorderedIntArray(pageInputs);

		// 2. populate predecessors over the concatenated global order; collect head positions / pks for step 3, and a
		// record -> global-position lookup so the head state can be recomputed without a live index instance
		final int totalSize = elements.getLength();
		final Map<Integer, Integer> predecessorMap = CollectionUtils.createHashMap(Math.max(16, totalSize << 1));
		final Map<Integer, Integer> recordPositions = CollectionUtils.createHashMap(Math.max(16, totalSize << 1));
		final IntArrayList headPositions = new IntArrayList();
		final IntArrayList headPks = new IntArrayList();
		int globalPos = 0;
		// the previous record in the global order across pages - a non-head takes this as its predecessor (position 0
		// is always a chain head, so this sentinel is never consumed by a non-head)
		int previousRecord = Integer.MIN_VALUE;
		for (final ChainIndexLeafPagePart page : pages) {
			final int[] recordIds = page.getRecordIds();
			final long[] headWords = page.getHeadWords();
			final int[] headPredecessorPks = page.getHeadPredecessorPks();
			int headCursor = 0;
			for (int i = 0; i < recordIds.length; i++) {
				final int recordId = recordIds[i];
				final boolean isHead = isHeadBit(headWords, i);
				if (isHead) {
					// a head takes its persisted head predecessor verbatim (aligned with the set head bits)
					predecessorMap.put(recordId, headPredecessorPks[headCursor++]);
					headPositions.add(globalPos);
					headPks.add(recordId);
				} else {
					// a non-head takes the previous record in the global order (carries across the page boundary)
					predecessorMap.put(recordId, previousRecord);
				}
				recordPositions.put(recordId, globalPos);
				previousRecord = recordId;
				globalPos++;
			}
		}

		// 3. build chain descriptors: run length = distance to the next head mark (last run reaches the end); the head
		// state is recomputed from the persisted head predecessor (state is NOT persisted - landmine A)
		final int headCount = headPositions.size();
		final Map<Integer, ChainDescriptor> descriptors = CollectionUtils.createHashMap(headCount << 1);
		for (int h = 0; h < headCount; h++) {
			final int headPos = headPositions.get(h);
			final int nextHeadPos = h + 1 < headCount ? headPositions.get(h + 1) : totalSize;
			final int length = nextHeadPos - headPos;
			final int headPk = headPks.get(h);
			final ElementState state = computeHeadStateForReload(headPk, headPos, length, predecessorMap, recordPositions);
			descriptors.put(headPk, new ChainDescriptor(length, state));
		}

		// 4. derive the inverse of predecessors (predecessorPk -> declaring element pks), skipping HEAD_PK - the same
		// BASE-state inverse the array constructor builds (bitmaps built up-front from int[], so the data lands in BASE)
		final Map<Integer, IntArrayList> successorAccumulator = CollectionUtils.createHashMap(predecessorMap.size());
		for (final Entry<Integer, Integer> entry : predecessorMap.entrySet()) {
			final int predecessorPk = entry.getValue();
			if (predecessorPk != HEAD_PK) {
				successorAccumulator.computeIfAbsent(predecessorPk, k -> new IntArrayList()).add(entry.getKey());
			}
		}
		final Map<Integer, TransactionalBitmap> inverseMap = CollectionUtils.createHashMap(successorAccumulator.size());
		for (final Entry<Integer, IntArrayList> entry : successorAccumulator.entrySet()) {
			inverseMap.put(entry.getKey(), new TransactionalBitmap(entry.getValue().toArray()));
		}

		// 5. seed the owner-resident page bookkeeping from the reassembled leaves: restore the live-page set + high-water
		// and clear every leaf's dirty flag (the assembly's replaying inserts flag them dirty even though the leaves are
		// byte-identical to disk), so the first post-load flush re-emits nothing until a genuine mutation touches a leaf
		final PageStreamRegistry pageStreamRegistry = PageStreamRegistry.restoredFrom(
			ELEMENTS_PAGE_STREAM, highWaterPageSequence, elements.leafPageHandles()
		);

		return new ChainIndex(
			referenceKey, attributeIndexKey, elements, descriptors, predecessorMap, inverseMap, pageStreamRegistry
		);
	}

	/**
	 * Asserts that no record id appears in more than one persisted chain-index leaf page. Unlike the key-ordered paged
	 * indexes a chain page is positional and carries no ordering invariant to violate, so the stale leaf-page twin
	 * corruption — a writer race persisting a frozen stale snapshot of a leaf page alongside the page that superseded it
	 * — manifests instead as DUPLICATE record ids across pages. Because the paged persistence layout has never shipped
	 * in a released version, no production catalog can carry such a twin; a duplicate is index corruption that is not
	 * silently repaired but fails fast here with a {@link GenericEvitaInternalError} naming the record id, both
	 * offending page sequences, the attribute identity and an operator remediation hint.
	 *
	 * @param pages             the persisted leaf pages in ascending logical order
	 * @param attributeIndexKey the attribute identity of this index (diagnostics)
	 * @throws GenericEvitaInternalError when a record id appears in more than one leaf page
	 */
	private static void assertNoDuplicateChainRecords(
		@Nonnull List<ChainIndexLeafPagePart> pages,
		@Nonnull AttributeIndexKey attributeIndexKey
	) {
		// fast path: scan every record id against one set; when no record id repeats there is no corruption — the
		// overwhelmingly common case, O(N) over the ints
		final IntHashSet seen = new IntHashSet();
		boolean duplicateFound = false;
		for (final ChainIndexLeafPagePart page : pages) {
			for (final int recordId : page.getRecordIds()) {
				if (!seen.add(recordId)) {
					duplicateFound = true;
					break;
				}
			}
			if (duplicateFound) {
				break;
			}
		}
		if (!duplicateFound) {
			return;
		}
		// a record id repeats across pages — re-scan to attribute the first duplicate to its two page sequences for a
		// precise diagnostic, then fail fast
		final IntIntHashMap firstPageOfRecord = new IntIntHashMap();
		for (final ChainIndexLeafPagePart page : pages) {
			final int pageSequence = page.getPageSequence();
			for (final int recordId : page.getRecordIds()) {
				if (firstPageOfRecord.containsKey(recordId)) {
					throw new GenericEvitaInternalError(
						"Corrupted persisted chain index for attribute " + attributeIndexKey + ": record id " + recordId +
							" appears in more than one leaf page (sequences " + firstPageOfRecord.get(recordId) + " and " +
							pageSequence + "). This is a stale leaf-page twin or other index corruption. Restore the " +
							"catalog from a backup, or fully rebuild / reindex the affected catalog."
					);
				}
				firstPageOfRecord.put(recordId, pageSequence);
			}
		}
	}

	/**
	 * Tests whether the head bit for the record at position `bitIndex` is set in the packed head-mask words.
	 * `bitIndex >>> 6` selects the 64-bit word, `bitIndex & 63` the bit within that word.
	 *
	 * @param headWords packed head-mask words (one bit per record slot, least-significant bit first)
	 * @param bitIndex  record slot to test
	 * @return true when the record at `bitIndex` is marked as a chain head
	 */
	private static boolean isHeadBit(@Nonnull long[] headWords, int bitIndex) {
		return ((headWords[bitIndex >>> 6] >>> (bitIndex & 63)) & 1L) != 0L;
	}

	/**
	 * Recomputes the {@link ElementState} of a run head during a paged reload from the persisted head predecessor and a
	 * record → global-position lookup. Mirrors {@link #computeHeadState} exactly (a head with no predecessor is
	 * {@link ElementState#HEAD}, a head whose predecessor currently sits inside its own run is
	 * {@link ElementState#CIRCULAR}, otherwise {@link ElementState#SUCCESSOR}) but reads from the reload-time maps
	 * instead of the not-yet-constructed index instance.
	 *
	 * @param headPk          head primary key of the run
	 * @param headPos         global position of the head record
	 * @param length          length of the run
	 * @param predecessors    the reload-time predecessor map
	 * @param recordPositions the reload-time record → global-position lookup
	 * @return the computed head state
	 */
	@Nonnull
	private static ElementState computeHeadStateForReload(
		int headPk,
		int headPos,
		int length,
		@Nonnull Map<Integer, Integer> predecessors,
		@Nonnull Map<Integer, Integer> recordPositions
	) {
		final Integer headPredecessorRef = predecessors.get(headPk);
		isPremiseValid(
			headPredecessorRef != null,
			"Index damaged! The chain head `" + headPk + "` has no predecessor entry!"
		);
		final int headPredecessor = headPredecessorRef;
		if (headPredecessor == HEAD_PK) {
			return ElementState.HEAD;
		}
		final Integer predecessorPosRef = recordPositions.get(headPredecessor);
		if (predecessorPosRef == null) {
			return ElementState.SUCCESSOR;
		}
		final int predecessorPos = predecessorPosRef;
		return predecessorPos >= headPos && predecessorPos < headPos + length
			? ElementState.CIRCULAR
			: ElementState.SUCCESSOR;
	}

	/**
	 * Returns TRUE only if there is single consecutive chain of elements starting with the head element.
	 *
	 * @return TRUE if the index is consistent, FALSE otherwise
	 */
	public boolean isConsistent() {
		return this.chains.size() <= 1;
	}

	/**
	 * Intermediate result that combines the data from {@link #chains} into single
	 * array of primary keys. The array equals to a value of single {@link #chains} element in case the index is
	 * in consistent state, otherwise it contains record ordered as follows:
	 *
	 * - the longest chain of elements starting with the head element
	 * - others chains of elements starting with the head element (in order of their length)
	 * - the chains that are not starting with the head element (in order of their length) - i.e. the chains that start
	 * with element pointing to the predecessor that is part of the chain starting with the head element
	 * - the chains with circular reference (in order of their length) - i.e. where the head has predecessor that is
	 * part of its chain
	 */
	@Nonnull
	public UnorderedLookup getUnorderedLookup() {
		// sort the runs by element-state tier (HEAD, then SUCCESSOR, then CIRCULAR) and within the tier by descending
		// length - exactly the documented semi-consistent ordering
		final List<Entry<Integer, ChainDescriptor>> orderedChains = new ArrayList<>(this.chains.entrySet());
		orderedChains.sort((o1, o2) -> {
			final ChainDescriptor d1 = o1.getValue();
			final ChainDescriptor d2 = o2.getValue();
			if (d1.state() == d2.state()) {
				// the longest chains come first
				return Integer.compare(d2.length(), d1.length());
			} else {
				// this will sort heads first, then successors, then circulars
				return Integer.compare(d1.state().ordinal(), d2.state().ordinal());
			}
		});

		// flatten the whole element array ONCE - getSubArray would re-flatten it per chain (O(C*N)). Every run is then
		// sliced out of this single snapshot into the freshly-allocated result, so the shared memoized array (returned
		// by reference outside a transaction) is never aliased or mutated.
		final int[] all = this.elements.getArray();
		final int[] result = new int[all.length];
		int offset = 0;
		for (final Entry<Integer, ChainDescriptor> entry : orderedChains) {
			final int headPos = this.elements.indexOf(entry.getKey());
			final int length = entry.getValue().length();
			System.arraycopy(all, headPos, result, offset, length);
			offset += length;
		}

		return new UnorderedLookup(result);
	}

	/**
	 * Method adds or updates existing `predecessor` information for the `primaryKey` element in the index.
	 *
	 * @param predecessor pointer record to a predecessor element of the `primaryKey` element
	 * @param primaryKey  primary key of the element
	 */
	public void upsertPredecessor(@Nonnull ChainableType predecessor, int primaryKey) {
		Assert.isTrue(
			primaryKey != predecessor.predecessorPk() || predecessor instanceof ReferencedEntityPredecessor,
			"An entity that is its own predecessor doesn't have sense!"
		);
		final Integer existingPredecessor = this.predecessors.get(primaryKey);
		if (existingPredecessor == null) {
			// if existing state is not found - we need to insert new one
			insertElement(primaryKey, predecessor);
			// mirror the new predecessor link into the inverse map for the work-queue collapse
			linkSuccessor(predecessor.predecessorPk(), primaryKey);
		} else if (existingPredecessor == predecessor.predecessorPk()) {
			// the predecessor is the same - nothing to do
			return;
		} else {
			// otherwise we need to perform update (single-element relocation)
			updateElement(primaryKey, predecessor);
			// move the element between inverse buckets: drop it from the old predecessor, add it to the new one
			unlinkSuccessor(existingPredecessor, primaryKey);
			linkSuccessor(predecessor.predecessorPk(), primaryKey);
		}
		this.dirty.setToTrue();
		getOrCreateChainIndexChanges().reset();
	}

	/**
	 * Method removes existing `predecessor` information for the `primaryKey` element in the index.
	 *
	 * @param primaryKey primary key of the element
	 */
	public void removePredecessor(int primaryKey) {
		final Integer existingPredecessor = this.predecessors.remove(primaryKey);
		isTrue(
			existingPredecessor != null,
			"Value `" + primaryKey + "` is not present in the chain element index!"
		);
		// keep the inverse in lockstep with predecessors: the element no longer declares any predecessor
		unlinkSuccessor(existingPredecessor, primaryKey);
		final DetachOutcome detached = detachElement(primaryKey);
		// the removed element is gone, so only what the detach exposed can create a new collapsible pair
		final IntArrayList triggers = new IntArrayList(2);
		addTrigger(triggers, detached.affectedHead());
		addTrigger(triggers, detached.exposedTail());
		collapse(triggers);
		this.dirty.setToTrue();
		getOrCreateChainIndexChanges().reset();
	}

	/**
	 * Adds `primaryKey` to the inverse bucket of `predecessorPk` in {@link #successorsByPredecessor}, so the
	 * work-queue {@link #collapse(IntArrayList)} can later find it as a successor waiting on `predecessorPk`.
	 * {@link ChainableType#HEAD_PK} is skipped: a true head never collapses via its predecessor, so its inverse entry
	 * would never be read.
	 *
	 * @param predecessorPk the predecessor primary key the element declares
	 * @param primaryKey    the element declaring the predecessor
	 */
	private void linkSuccessor(int predecessorPk, int primaryKey) {
		if (predecessorPk == HEAD_PK) {
			return;
		}
		// the mapping function MUST be the lambda, not TransactionalBitmap::new - as a Function<Integer, ...> the
		// method reference binds the TransactionalBitmap(int...) constructor and would seed the bucket with its own key
		this.successorsByPredecessor
			.computeIfAbsent(predecessorPk, p -> new TransactionalBitmap())
			.add(primaryKey);
	}

	/**
	 * Removes `primaryKey` from the inverse bucket of `predecessorPk` in {@link #successorsByPredecessor}, dropping the
	 * bucket entirely (and releasing its transactional layer) once it empties so the map stays as sparse as the
	 * at-rest chain set. {@link ChainableType#HEAD_PK} is skipped (never linked in the first place).
	 *
	 * @param predecessorPk the predecessor primary key the element used to declare
	 * @param primaryKey    the element to unlink
	 */
	private void unlinkSuccessor(int predecessorPk, int primaryKey) {
		if (predecessorPk == HEAD_PK) {
			return;
		}
		final TransactionalBitmap successors = this.successorsByPredecessor.get(predecessorPk);
		if (successors != null) {
			successors.remove(primaryKey);
			if (successors.isEmpty()) {
				this.successorsByPredecessor.remove(predecessorPk);
				// the bitmap was removed entirely - drop its changes container (mirrors EntityIndex.removeLanguage)
				Transaction.removeTransactionalMemoryLayerIfExists(successors);
			}
		}
	}

	/**
	 * Returns true if {@link SortIndex} contains no data.
	 */
	public boolean isEmpty() {
		return this.predecessors.isEmpty();
	}

	@Nonnull
	@Override
	public SortedRecordsSupplier getAscendingOrderRecordsSupplier() {
		return getOrCreateChainIndexChanges().getAscendingOrderRecordsSupplier();
	}

	@Nonnull
	@Override
	public SortedRecordsSupplier getDescendingOrderRecordsSupplier() {
		return getOrCreateChainIndexChanges().getDescendingOrderRecordsSupplier();
	}

	/**
	 * This method verifies internal consistency of the data-structure. It checks whether the chains are correctly
	 * ordered and whether the element states are correctly set.
	 */
	@Nonnull
	@Override
	public ConsistencyReport getConsistencyReport() {
		final StringBuilder errors = new StringBuilder(512);

		// flatten the whole element array ONCE for the per-chain run slices below - getSubArray would re-flatten per
		// chain (O(C*N)). The method is read-only, so this single snapshot stays valid for every use; each run is a
		// copy sliced out of it, never an alias of the shared memoized array.
		final int[] all = this.elements.getArray();

		int overallCount = 0;
		for (final Entry<Integer, ChainDescriptor> entry : this.chains.entrySet()) {
			final int headPk = entry.getKey();
			final ChainDescriptor descriptor = entry.getValue();
			final int length = descriptor.length();
			overallCount += length;

			final int headPos = this.elements.indexOf(headPk);
			if (headPos == Integer.MIN_VALUE) {
				errors.append("\nThe head of the chain `")
					.append(headPk)
					.append("` is not present in the element array!");
				continue;
			}
			if (length <= 0 || headPos + length > this.elements.getLength()) {
				errors.append("\nThe chain with head `")
					.append(headPk)
					.append("` has an invalid length `")
					.append(length)
					.append("`!");
				continue;
			}

			final int[] run = Arrays.copyOfRange(all, headPos, headPos + length);
			int previousElementId = headPk;
			for (int i = 0; i < run.length; i++) {
				final int elementId = run[i];
				final Integer storedPredecessor = this.predecessors.get(elementId);
				if (storedPredecessor == null) {
					errors.append("\nThe element `")
						.append(elementId)
						.append("` is not present in the element states!");
				} else if (i > 0 && storedPredecessor != previousElementId) {
					errors.append("\nThe predecessor of the element `")
						.append(elementId)
						.append("` doesn't match the previous element!");
				}
				previousElementId = elementId;
			}

			// verify the head state matches the structural reality
			final ElementState expectedState = computeHeadState(headPk, length);
			if (descriptor.state() != expectedState) {
				errors.append("\nThe head `")
					.append(headPk)
					.append("` is flagged ")
					.append(descriptor.state())
					.append(" but the structure implies ")
					.append(expectedState)
					.append("!");
			}
		}

		// every known element must belong to exactly one chain (== be present in the element array)
		for (final Integer elementId : this.predecessors.keySet()) {
			if (!this.elements.contains(elementId)) {
				errors.append("\nThe element `")
					.append(elementId)
					.append("` has a recorded predecessor but is not present in any chain!");
			}
		}

		if (overallCount != this.predecessors.size() || overallCount != this.elements.getLength()) {
			errors.append("\nThe number of elements in chains doesn't match " +
				"the number of elements in element states!");
		}

		// cross-check the successorsByPredecessor inverse against predecessors: the two must be exact inverses
		// (excluding HEAD_PK). A desync here would let the work-queue collapse silently miss a mergeable pair.
		for (final Entry<Integer, Integer> entry : this.predecessors.entrySet()) {
			final int predecessorPk = entry.getValue();
			if (predecessorPk != HEAD_PK) {
				final TransactionalBitmap successors = this.successorsByPredecessor.get(predecessorPk);
				if (successors == null || !successors.contains(entry.getKey())) {
					errors.append("\nThe element `")
						.append(entry.getKey())
						.append("` declares predecessor `")
						.append(predecessorPk)
						.append("` but is missing from that predecessor's successor inverse!");
				}
			}
		}
		for (final Entry<Integer, TransactionalBitmap> entry : this.successorsByPredecessor.entrySet()) {
			final int predecessorPk = entry.getKey();
			final OfInt successorIt = entry.getValue().iterator();
			while (successorIt.hasNext()) {
				final int successorPk = successorIt.nextInt();
				final Integer declaredPredecessor = this.predecessors.get(successorPk);
				if (declaredPredecessor == null || declaredPredecessor != predecessorPk) {
					errors.append("\nThe successor inverse maps `")
						.append(successorPk)
						.append("` under predecessor `")
						.append(predecessorPk)
						.append("` but its declared predecessor is `")
						.append(declaredPredecessor)
						.append("`!");
				}
			}
		}

		final ConsistencyState state;
		if (!errors.isEmpty()) {
			state = ConsistencyState.BROKEN;
		} else if (isConsistent()) {
			state = ConsistencyState.CONSISTENT;
		} else {
			state = ConsistencyState.INCONSISTENT;
		}

		final String chainsListing = this.chains.entrySet()
			.stream()
			.map(entry -> {
				final StringBuilder sb = new StringBuilder("\t- ");
				final int headPos = this.elements.indexOf(entry.getKey());
				final int length = entry.getValue().length();
				int counter = 0;
				while (counter < length && sb.length() < 80) {
					if (counter > 0) {
						sb.append(", ");
					}
					sb.append(this.elements.get(headPos + counter));
					counter++;
				}
				if (counter < length) {
					sb.append("... (")
						.append(length - counter)
						.append(" more)");
				}
				return sb.toString();
			})
			.collect(Collectors.joining("\n"));
		return new ConsistencyReport(
			state,
			"## Chains\n\n" + chainsListing + "\n\n" +
				(errors.isEmpty() ? "## No errors detected." : "## Errors detected\n\n" + errors)
		);
	}

	/**
	 * Emits this chain index's modified storage parts into `sink` on the commit/flush path — the granular persistence
	 * entry point mirroring the UNIQUE/FILTER/SORT indexes (see {@link AttributeIndex#getModifiedStorageParts}). A clean
	 * index emits nothing. A dirty index emits either the inline SINGLE {@link ChainIndexStoragePart} (a single-leaf
	 * element tree, possibly just collapsed from PAGED) or the granular PAGED shape (one {@link ChainIndexLeafPagePart}
	 * per changed leaf + one {@link ChainIndexLeafPageRemoval} per freed leaf + a PAGED {@link ChainIndexStoragePart}
	 * root carrying only the page-stream metadata).
	 *
	 * @param entityIndexPrimaryKey the owning entity index primary key
	 * @param sink                  the trapped-changes accumulator for this commit
	 */
	public void appendStorageParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges sink) {
		if (this.dirty.isTrue()) {
			// all data are persisted to disk - we may get rid of temporary, modification only helper container
			this.chainIndexChanges = null;
			doAppendStorageParts(entityIndexPrimaryKey, sink);
		}
	}

	/**
	 * Returns the page sequences of every leaf page this chain index will have on disk once the in-flight commit is
	 * durable (its element page-stream's staged set mid-flush, else its published live set), or an empty array when it
	 * is inline (SINGLE) or has never paged. The owning {@link AttributeIndex} snapshots this so that when a PAGED chain
	 * is later emptied and dropped from the sub-index map — after which THIS index's own {@link #appendStorageParts}
	 * never runs again — the parent still knows the now-orphaned leaf pages and can emit a {@link ChainIndexLeafPageRemoval}
	 * for each instead of leaking them forever in the append-only OffsetIndex. Reading the staged set (not
	 * merely the published one) keeps the snapshot correct even when taken right after this commit's flush staged a new
	 * page set but before it was published.
	 *
	 * @return the current on-disk leaf-page sequences, or an empty array for a SINGLE / never-paged index
	 */
	@Nonnull
	public int[] currentLeafPageSequences() {
		return this.pageStreamRegistry.pendingLivePageSequences(ELEMENTS_PAGE_STREAM);
	}

	/**
	 * Promotes the page set staged by the PREVIOUS flush of the element-tree page stream to the live
	 * change-detection baseline, so this flush's freed-page diff and `pageListChanged` verdict are taken against
	 * what disk actually holds.
	 *
	 * The registry's live set answers "which leaf pages does this chain's element tree have on disk", and both the
	 * pages a leaf drop freed (so the corresponding {@link ChainIndexLeafPageRemoval} is emitted instead of leaving an
	 * unreferenced-but-never-removed record in the append-only OffsetIndex) and whether the ordered page list changed
	 * at all (so the PAGED {@link ChainIndexStoragePart} root carrying it is re-emitted rather than skipped as
	 * unchanged) are derived from it. It advances solely by publishing, which
	 * {@link #createCopyWithMergedTransactionalMemory} does at the commit-merge.
	 *
	 * A WARM_UP (bulk) flush never reaches a commit-merge — {@link #appendStorageParts} is called directly, flush
	 * after flush, and the merge that publishes only ever runs for a transaction. Left alone, the live set of a
	 * freshly re-indexed chain would therefore stay EMPTY for the whole warm-up while disk moved on, so a leaf that a
	 * mid-life shrink or rebalance drops without a fresh leaf being born would never be removed from storage and
	 * would stay listed on a root skipped as "unchanged". The next cold load would then reassemble a stale, no
	 * longer valid leaf-page sequence alongside its live neighbours.
	 *
	 * Publishing a staged set HERE — rather than only at the merge — is correct for every path, because of one
	 * invariant: **a failed flush is never followed by another flush of the same data**. Note that this publish runs
	 * at COLLECT time, before this flush has written anything (the baseline-capture pass re-enters this pipeline), so
	 * it cannot lean on the previous flush's bytes having landed by now. It does not need to: a flush that fails
	 * during trunk incorporation SUSPENDS the catalog's transaction processing ({@code TransactionManager.suspend}),
	 * and a flush that fails on the warm-up path POISONS the collection's buffer
	 * ({@code WarmUpDataStoreMemoryBuffer.poison}), so every later collect of it refuses deterministically. Those two
	 * are the same invariant in different dresses: after a failed flush no later flush of that data ever runs, so
	 * nothing can ever diff against the baselines it left behind. A flush that does NOT fail leaves `staged` holding
	 * exactly the page set it wrote — the baseline the next flush must diff against — regardless of which path staged
	 * it, and regardless of whether the commit-merge ever ran. (Should the process die instead,
	 * {@link #fromPersistedPages} rebuilds the registry from disk on restart — page allocation is advance-only, so a
	 * burnt id is harmless.) That is what makes this safe in its own right — not the fact that it happens to be a
	 * no-op on the transactional path (where the merge published first, leaving nothing staged). The commit
	 * handshake is untouched.
	 */
	private void publishPreviousFlush() {
		this.pageStreamRegistry.publishStaged();
	}

	/**
	 * Emits this (dirty) index's modified storage parts into `sink`. The SINGLE/PAGED decision mirrors
	 * {@link OwnerSortIndex#doAppendStorageParts}: a multi-leaf element tree
	 * ({@link TransactionalUnorderedIntArray#isRootInternal()}) persists granularly (one leaf page per changed leaf +
	 * removals for freed leaves + a PAGED root that is re-emitted only when the live leaf-page list changed (a
	 * content-only commit leaves it byte-identical, so the root is skipped), a single-leaf tree persists inline
	 * (the SINGLE root). A PAGED→SINGLE
	 * collapse first removes every prior live leaf page and forgets the page stream (the registry AND the per-leaf
	 * bookkeeping) so a later regrow into PAGED starts from a clean baseline and re-emits every leaf.
	 *
	 * Before deciding SINGLE vs PAGED, any set still staged by the PREVIOUS flush is promoted to live: see
	 * {@link #publishPreviousFlush()} for why that is both necessary and safe.
	 *
	 * @param entityIndexPrimaryKey the owning entity index primary key
	 * @param sink                  the trapped-changes accumulator for this commit
	 */
	private void doAppendStorageParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges sink) {
		publishPreviousFlush();
		// every leaf page (and removal) carries the CHAIN-typed sub-index identity, so its stream id is disjoint from the
		// FILTER / SORT streams of the same attribute and resolved store-side when the page's primary key is assigned
		final AttributeKeyWithIndexType streamKey =
			new AttributeKeyWithIndexType(this.attributeIndexKey, AttributeIndexType.CHAIN);
		if (this.elements.isRootInternal()) {
			// PAGED shape: emit one leaf page per CHANGED leaf, one removal per freed leaf, and a PAGED root carrying only
			// the high-water + the ordered live leaf-page list (the chain state / element order are reconstructed from the
			// reloaded leaf pages on load, so nothing else is written)
			final List<LeafPageHandle> handles = this.elements.leafPageHandles();
			final PageEmission<ChainLeafPage> emission = this.pageStreamRegistry.collectChangedPages(
				ELEMENTS_PAGE_STREAM, handles, this::buildChainLeafPage
			);
			for (final ChainLeafPage page : emission.changedPages()) {
				sink.addChangeToStore(
					new ChainIndexLeafPagePart(
						entityIndexPrimaryKey, streamKey, page.pageSequence(),
						page.recordIds(), page.headWords(), page.headPredecessorPks()
					)
				);
			}
			// remove the leaf pages a merge/shrink dropped this commit so they don't leak (the append-only OffsetIndex
			// never reclaims an unreferenced-but-never-removed record - page ids are advance-only and never re-keyed)
			for (final int freedPageSequence : emission.freedPageSequences()) {
				sink.addChangeToStore(new ChainIndexLeafPageRemoval(entityIndexPrimaryKey, streamKey, freedPageSequence));
			}
			// the PAGED root carries nothing but the high-water + ordered live leaf-page list, so it only needs rewriting
			// when that list actually changed (a leaf was allocated or freed). A commit that merely mutated leaf CONTENT
			// (no split/merge) leaves the persisted root byte-identical, so skip it — the steady-state root cost is O(1),
			// not O(live pages) (~40 KB at 10M elements). The changed leaf pages above are always emitted (dirty leaves).
			if (emission.pageListChanged()) {
				sink.addChangeToStore(
					ChainIndexStoragePart.paged(
						entityIndexPrimaryKey, this.attributeIndexKey,
						emission.highWaterPageSequence(), emission.orderedPageSequences()
					)
				);
			}
			return;
		}
		// SINGLE shape (possibly just collapsed from PAGED): remove every leaf page from its prior PAGED life (the SINGLE
		// root no longer references them) BEFORE dropping the page bookkeeping, then forget the stream - both the registry
		// live set / high-water AND the per-leaf page sequences + dirty flags - so a later regrow into PAGED starts from a
		// clean baseline and re-emits every leaf
		// Reclaim against the staged-or-published set, uniformly with every other paged index: this flush already
		// published the previous one above, so the two coincide here.
		for (final int freedPageSequence : this.pageStreamRegistry.pendingLivePageSequences(ELEMENTS_PAGE_STREAM)) {
			sink.addChangeToStore(new ChainIndexLeafPageRemoval(entityIndexPrimaryKey, streamKey, freedPageSequence));
		}
		this.pageStreamRegistry.forget(ELEMENTS_PAGE_STREAM);
		this.elements.forgetPageStream();
		sink.addChangeToStore(buildSingleStoragePart(entityIndexPrimaryKey));
	}

	/**
	 * Materializes one persisted leaf page from a live element-tree leaf handle: the leaf's ordered record ids, its head
	 * bitset sliced to the meaningful `ceil(recordIds.length / 64)`-word prefix, and — aligned with the set head bits, in
	 * ascending bit-position order — each head's predecessor primary key read from the live {@link #predecessors} map.
	 * NO chain state / run length is written (landmine A): a head's state / length can be flipped by a mutation in a
	 * DIFFERENT leaf whose own leaf stays byte-clean, so any state stored here could go stale; both are recomputed at
	 * load. The head predecessor IS dirty-safe (changing it always mutates the head's own leaf).
	 *
	 * @param pageSequence the stable page sequence assigned to this leaf
	 * @param handle       the live leaf handle (record ids + head-mask words)
	 * @return the materialized leaf page
	 */
	@Nonnull
	private ChainLeafPage buildChainLeafPage(int pageSequence, @Nonnull LeafPageHandle handle) {
		final int[] recordIds = handle.recordIds();
		final long[] fullMask = handle.headMask();
		isPremiseValid(
			fullMask != null,
			"Index damaged! A head-aware chain leaf must carry a head mask!"
		);
		// slice the wider in-memory mask (long[ceil((leafCapacity + 1) / 64)]) down to the meaningful prefix the
		// persisted page carries (ceil(recordIds.length / 64) words)
		final int wordCount = (recordIds.length + 63) >>> 6;
		final long[] headWords = Arrays.copyOf(fullMask, wordCount);
		// count the head bits so the predecessor column is allocated exactly (only bits at valid record slots are set)
		int headCount = 0;
		for (int i = 0; i < recordIds.length; i++) {
			if (isHeadBit(headWords, i)) {
				headCount++;
			}
		}
		// one predecessor pk per set head bit, in ascending bit-position order (aligned with the reload's head cursor)
		final int[] headPredecessorPks = new int[headCount];
		int headCursor = 0;
		for (int i = 0; i < recordIds.length; i++) {
			if (isHeadBit(headWords, i)) {
				final Integer predecessor = this.predecessors.get(recordIds[i]);
				isPremiseValid(
					predecessor != null,
					"Index damaged! The chain head `" + recordIds[i] + "` has no predecessor entry!"
				);
				headPredecessorPks[headCursor++] = predecessor;
			}
		}
		return new ChainLeafPage(pageSequence, recordIds, headWords, headPredecessorPks);
	}

	/**
	 * One leaf page produced by the granular chain write path: its stable page sequence, the leaf's ordered record ids,
	 * the head bitset (sliced to the meaningful prefix) and the per-head predecessor pks aligned with the set head bits.
	 *
	 * @param pageSequence        the leaf's stable page sequence
	 * @param recordIds           the leaf's record ids in tree (chain) order
	 * @param headWords           the head bitset (`ceil(recordIds.length / 64)` words)
	 * @param headPredecessorPks  the head predecessor pks aligned with the set head bits
	 */
	private record ChainLeafPage(
		int pageSequence, @Nonnull int[] recordIds, @Nonnull long[] headWords, @Nonnull int[] headPredecessorPks
	) {
	}

	/**
	 * Builds the inline SINGLE {@link ChainIndexStoragePart} carrying the whole chain state (the {@link #chains} runs
	 * plus the fat per-element {@link ChainElementState} map). The fat map is materialized only transiently on the heap;
	 * the slim persisted format (see `ChainIndexStoragePartSerializer`) derives, per chain, just the run primary keys plus
	 * the head's predecessor and state and reconstructs this exact map on read, so this method and the load path are
	 * unchanged by the slimming. Emitted by the SINGLE branch of {@link #doAppendStorageParts}.
	 *
	 * @param entityIndexPrimaryKey the owning entity index primary key
	 * @return the inline SINGLE storage part
	 */
	@Nonnull
	private ChainIndexStoragePart buildSingleStoragePart(int entityIndexPrimaryKey) {
		final int[][] chainArrays = new int[this.chains.size()][];
		final Map<Integer, ChainElementState> elementStates = CollectionUtils.createHashMap(this.predecessors.size() << 1);
		// flatten the whole element array ONCE and slice each run out of it - getSubArray would re-flatten per chain
		// (O(C*N) on the commit critical path). Every run MUST stay an independent copy: it is stored into the
		// returned storage part and serialized, and `all` may be the shared memoized array (returned by reference
		// outside a transaction) - handing it over or aliasing a slice would corrupt the live index.
		final int[] all = this.elements.getArray();
		int chainIndex = 0;
		for (final Entry<Integer, ChainDescriptor> entry : this.chains.entrySet()) {
			final int headPk = entry.getKey();
			final ChainDescriptor descriptor = entry.getValue();
			final int headPos = this.elements.indexOf(headPk);
			final int[] run = Arrays.copyOfRange(all, headPos, headPos + descriptor.length());
			chainArrays[chainIndex++] = run;
			for (int i = 0; i < run.length; i++) {
				final int elementId = run[i];
				final Integer predecessor = this.predecessors.get(elementId);
				isPremiseValid(
					predecessor != null,
					"Index damaged! The element `" + elementId + "` has no predecessor entry!"
				);
				// The slim persisted format (ChainIndexStoragePartSerializer) keeps only the head's predecessor and
				// reconstructs every non-head element's predecessor as its positional predecessor - the previous
				// element in the run. This is the same invariant getConsistencyReport() verifies; enforce it here at
				// the persistence chokepoint so a damaged index fails loud instead of being silently "healed" on the
				// next load. Heads (i == 0) are exempt: their (external / circular / HEAD_PK) predecessor is persisted
				// verbatim. The check is guarded by `i > 0` so the message never dereferences run[i - 1] for a head.
				if (i > 0) {
					isPremiseValid(
						predecessor == run[i - 1],
						"Index damaged! The non-head element `" + elementId + "` has stored predecessor `" +
							predecessor + "` but its positional predecessor in the run is `" + run[i - 1] +
							"` - the slim chain storage format cannot represent this state!"
					);
				}
				elementStates.put(
					elementId,
					new ChainElementState(
						headPk,
						predecessor,
						i == 0 ? descriptor.state() : ElementState.SUCCESSOR
					)
				);
			}
		}

		return new ChainIndexStoragePart(
			entityIndexPrimaryKey,
			this.attributeIndexKey,
			elementStates,
			chainArrays
		);
	}

	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	/**
	 * Returns the heap this index occupies, in bytes — its own object, its dirty flag, the element order-statistic
	 * array, all three chain maps and the derived-cache layer when one exists.
	 *
	 * # What is charged, and what is not
	 *
	 * Every boxed `Integer` key and value is charged **in full, at each holder**. A primary key appearing in
	 * {@link #chains}, {@link #predecessors} and {@link #successorsByPredecessor} is therefore counted three times.
	 * That is deliberate: whether the JVM hands back a cached box depends on `-XX:AutoBoxCacheMax`, so a figure that
	 * deduplicated them would answer differently on two JVMs holding identical data.
	 *
	 * - {@link #chains} values are {@link ChainDescriptor} records — a length and an {@link ElementState}, whose enum
	 *   constants are JVM-wide and contribute their slot alone.
	 * - {@link #successorsByPredecessor} values are charged in full; every bucket is constructed here and dropped the
	 *   moment it empties, so none is shared with another structure.
	 * - {@link #chainIndexChanges} prices only its own fields — the back-reference to this index is never followed.
	 * - {@link #referenceKey} and {@link #attributeIndexKey} contribute their slot: both belong to the enclosing
	 *   {@code AttributeIndex}, which hands them to every sub-index under it.
	 * - {@link #pageStreamRegistry} is excluded: single-writer flush bookkeeping carried by reference across commits,
	 *   not index content.
	 *
	 * Like every walk over the element tree and the maps this is `O(elements)` rather than `O(1)`, so it belongs to
	 * the index detail call and must never be called from a query path.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		final long boxedInteger = layout.sizeOfObject(Integer.BYTES);
		// a ChainDescriptor is a record holding a length and an ElementState slot; the enum constant behind it is the
		// JVM's, one per value for the whole process
		final long chainDescriptor = layout.sizeOfObject(Integer.BYTES + layout.referenceSize());
		// id, then the elements / chains / predecessors / successorsByPredecessor / dirty / referenceKey /
		// attributeIndexKey / chainIndexChanges / pageStreamRegistry slots
		long size = layout.sizeOfObject(Long.BYTES + 9L * layout.referenceSize())
			+ this.dirty.getHeapSizeInBytes()
			+ this.elements.getHeapSizeInBytes()
			+ this.chains.getHeapSizeInBytes(key -> boxedInteger, value -> chainDescriptor)
			+ this.predecessors.getHeapSizeInBytes(key -> boxedInteger, value -> boxedInteger)
			+ this.successorsByPredecessor.getHeapSizeInBytes(
				key -> boxedInteger, TransactionalBitmap::getHeapSizeInBytes
			);
		if (this.chainIndexChanges != null) {
			size += this.chainIndexChanges.getHeapSizeInBytes();
		}
		return size;
	}

	@Override
	public ChainIndexChanges createLayer() {
		return new ChainIndexChanges(this);
	}

	/**
	 * The chain data this index writes lives in contained transactional structures that journal their own writes, and
	 * the lazily created {@link #chainIndexChanges} helper the delegate branch installs journals its own memoized
	 * caches through the {@link io.evitadb.core.transaction.memory.Snapshotable} contract it already implements.
	 * Instantiating that helper inside a rolled-back mutation is harmless — it holds nothing but rebuildable caches,
	 * so the installed instance is indistinguishable from the `null` slot it replaced.
	 *
	 * @return always `true` — see above
	 */
	@Override
	public boolean supportsWarmUpRollback() {
		return true;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.dirty.removeLayer(transactionalLayer);
		this.elements.removeLayer(transactionalLayer);
		this.chains.removeLayer(transactionalLayer);
		this.predecessors.removeLayer(transactionalLayer);
		// one call recurses into the transactional bitmap values (the map was built with the value type + wrapper)
		this.successorsByPredecessor.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public ChainIndex createCopyWithMergedTransactionalMemory(
		@Nullable ChainIndexChanges layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		// the merge runs only AFTER this commit's flush has durably written the changed leaf pages + root, so the live set
		// staged by that flush now reflects what is on disk and becomes the change-detection baseline for the next
		// commit; publish it, then carry the registry BY REFERENCE into the committed copy so the surviving owner keeps it.
		// A SINGLE flush stages nothing (it forgets the stream), so this is a no-op in that case. This is the EARLIEST
		// publish point on the transactional path only; it is not the only one — a staged set that never reaches a merge
		// (the warm-up path has no merge at all) is published by the next flush instead, see `publishPreviousFlush`. (No
		// discard counterpart is needed: a pre-flush abort never stages, and a failed flush suspends this catalog's
		// transaction processing — on the warm-up path it poisons the collection's buffer instead, the same invariant
		// in another dress — so no later flush ever diffs against the baseline a failed one left behind; restart
		// rebuilds a clean registry from disk.)
		this.pageStreamRegistry.publishStaged();
		return new ChainIndex(
			this.referenceKey,
			this.attributeIndexKey,
			transactionalLayer.getStateCopyWithCommittedChanges(this.elements),
			transactionalLayer.getStateCopyWithCommittedChanges(this.chains),
			transactionalLayer.getStateCopyWithCommittedChanges(this.predecessors),
			transactionalLayer.getStateCopyWithCommittedChanges(this.successorsByPredecessor),
			this.pageStreamRegistry
		);
	}

	@Override
	public String toString() {
		final List<Entry<Integer, ChainDescriptor>> orderedChains = new ArrayList<>(this.chains.entrySet());
		orderedChains.sort(Comparator.comparingInt(o -> this.elements.indexOf(o.getKey())));
		// flatten the whole element array ONCE and slice each run's copy out of it (getSubArray would re-flatten per chain)
		final int[] all = this.elements.getArray();
		final StringBuilder chainsDump = new StringBuilder(256);
		for (final Entry<Integer, ChainDescriptor> entry : orderedChains) {
			final int headPos = this.elements.indexOf(entry.getKey());
			final int[] run = Arrays.copyOfRange(all, headPos, headPos + entry.getValue().length());
			chainsDump.append("\n      - ").append(Arrays.toString(run));
		}
		final List<Integer> orderedElements = new ArrayList<>(this.predecessors.keySet());
		orderedElements.sort(Comparator.naturalOrder());
		final StringBuilder statesDump = new StringBuilder(256);
		for (final Integer elementId : orderedElements) {
			statesDump.append("\n      - ").append(elementId).append(": ")
				.append(reconstructState(elementId));
		}
		return "ChainIndex" + (this.referenceKey == null ? "" : " (refKey: " + this.referenceKey + ")") + ":\n" +
			"   - chains:" + chainsDump + "\n" +
			"   - elementStates:" + statesDump;
	}

	/**
	 * Retrieves or creates temporary data structure. When transaction exists, it is created in the transactional memory
	 * space so that other threads are not affected by the changes in the {@link ChainIndex}.
	 */
	@Nonnull
	private ChainIndexChanges getOrCreateChainIndexChanges() {
		final ChainIndexChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return ofNullable(this.chainIndexChanges).orElseGet(() -> {
				this.chainIndexChanges = new ChainIndexChanges(this);
				return this.chainIndexChanges;
			});
		} else {
			return layer;
		}
	}

	/**
	 * Inserts a brand-new element (one not yet present in the index) together with its predecessor pointer.
	 *
	 * @param primaryKey  primary key of the new element
	 * @param predecessor pointer record to a predecessor element of the `primaryKey` element
	 */
	private void insertElement(int primaryKey, @Nonnull ChainableType predecessor) {
		this.predecessors.put(primaryKey, predecessor.predecessorPk());
		attachElement(primaryKey, predecessor);
		// the freshly attached element is the only new run tail (and possibly new orphan head) this op created;
		// seeding collapse with it covers both its own follower candidacy and the successors waiting on it as a tail
		final IntArrayList triggers = new IntArrayList(1);
		triggers.add(primaryKey);
		collapse(triggers);
	}

	/**
	 * Updates the predecessor of an element that is already present in the index. The element is detached from its
	 * current position and re-attached right after its new predecessor (single-element relocation, no suffix drag).
	 *
	 * @param primaryKey  primary key of the element
	 * @param predecessor new predecessor pointer
	 */
	private void updateElement(int primaryKey, @Nonnull ChainableType predecessor) {
		final DetachOutcome detached = detachElement(primaryKey);
		this.predecessors.put(primaryKey, predecessor.predecessorPk());
		attachElement(primaryKey, predecessor);
		// seed collapse with the re-attached element (its new predecessor may be a tail) plus whatever the detach
		// exposed: a successor promoted to head, and a run tail newly uncovered by the removal
		final IntArrayList triggers = new IntArrayList(3);
		triggers.add(primaryKey);
		addTrigger(triggers, detached.affectedHead());
		addTrigger(triggers, detached.exposedTail());
		collapse(triggers);
	}

	/**
	 * Places `primaryKey` (already absent from {@link #elements}) into the array according to its (already stored)
	 * predecessor: a head element starts a fresh run at the tail of the array, an element whose predecessor is present
	 * and is a run tail is appended right after it (extending that run), and any other element starts a fresh
	 * (possibly transient) successor run at the tail of the array.
	 *
	 * @param primaryKey  primary key of the element to place
	 * @param predecessor predecessor pointer of the element
	 */
	private void attachElement(int primaryKey, @Nonnull ChainableType predecessor) {
		if (predecessor.isHead()) {
			// brand-new head: start a fresh run at the very end of the array
			this.elements.addOnIndex(this.elements.getLength(), primaryKey);
			this.chains.put(primaryKey, new ChainDescriptor(1, ElementState.HEAD));
			this.elements.markAsHead(primaryKey);
			return;
		}
		final int predecessorPk = predecessor.predecessorPk();
		final int predecessorPos = this.elements.indexOf(predecessorPk);
		if (predecessorPos == Integer.MIN_VALUE) {
			// the predecessor is not present yet - start a fresh orphan successor run at the end
			this.elements.addOnIndex(this.elements.getLength(), primaryKey);
			this.chains.put(primaryKey, new ChainDescriptor(1, ElementState.SUCCESSOR));
			this.elements.markAsHead(primaryKey);
			return;
		}
		final RunRef predecessorRun = findRun(predecessorPos);
		final boolean predecessorIsTail = predecessorPos == predecessorRun.headPos() + predecessorRun.length() - 1;
		if (predecessorIsTail) {
			// append right after the predecessor, extending its run
			this.elements.addOnIndex(predecessorPos + 1, primaryKey);
			final ChainDescriptor descriptor = this.chains.get(predecessorRun.headPk());
			isPremiseValid(
				descriptor != null,
				"Index damaged! The run head `" + predecessorRun.headPk() + "` has no descriptor!"
			);
			final int newLength = descriptor.length() + 1;
			this.chains.put(
				predecessorRun.headPk(),
				new ChainDescriptor(newLength, computeHeadState(predecessorRun.headPk(), newLength))
			);
		} else {
			// predecessor already has a successor - this element forms a separate (split) successor run
			this.elements.addOnIndex(this.elements.getLength(), primaryKey);
			this.chains.put(primaryKey, new ChainDescriptor(1, ElementState.SUCCESSOR));
			this.elements.markAsHead(primaryKey);
		}
	}

	/**
	 * Removes `primaryKey` from {@link #elements} and repairs the descriptor of the run it belonged to: a singleton run
	 * is dropped, a removed head promotes its successor (re-keying the descriptor, no element re-stamping), a removed
	 * tail just shrinks the run, and a removed middle element splits the run into a head-side run and a (transient)
	 * orphan successor run. The element's {@link #predecessors} entry is left untouched (the caller decides whether to
	 * overwrite or drop it).
	 *
	 * @param primaryKey primary key of the element to detach from the array
	 * @return the collapse triggers exposed by the removal (a promoted head and/or a newly uncovered run tail),
	 *         {@link Integer#MIN_VALUE} in a slot when that kind of trigger did not occur
	 */
	@Nonnull
	private DetachOutcome detachElement(int primaryKey) {
		final int position = this.elements.indexOf(primaryKey);
		isPremiseValid(
			position != Integer.MIN_VALUE,
			"Index damaged! The primary key `" + primaryKey + "` must be present in the element array!"
		);
		final RunRef run = findRun(position);
		final int headPk = run.headPk();
		final int headPos = run.headPos();
		final int length = run.length();

		this.elements.remove(primaryKey);

		if (length == 1) {
			// the element was the only member of its run - the run disappears entirely (nothing exposed)
			this.chains.remove(headPk);
			return new DetachOutcome(Integer.MIN_VALUE, Integer.MIN_VALUE);
		} else if (position == headPos) {
			// the element was the head - its successor (now at headPos) becomes the new head
			final int newHead = this.elements.get(headPos);
			this.chains.remove(headPk);
			this.chains.put(newHead, new ChainDescriptor(length - 1, computeHeadState(newHead, length - 1)));
			// the removed head's mark was auto-cleared by remove(); the promoted successor becomes a head
			this.elements.markAsHead(newHead);
			return new DetachOutcome(newHead, Integer.MIN_VALUE);
		} else if (position == headPos + length - 1) {
			// the element was the tail - just shrink the run (head state may stop being circular); the element now at
			// `position - 1` becomes the run's new tail and a successor may be waiting on it
			this.chains.put(headPk, new ChainDescriptor(length - 1, computeHeadState(headPk, length - 1)));
			return new DetachOutcome(headPk, this.elements.get(position - 1));
		} else {
			// the element was in the middle - split the run into a head-side run and an orphan successor run; the
			// head-side run's new tail is the element at `position - 1` (a successor may be waiting on it)
			final int prefixLength = position - headPos;
			final int suffixLength = length - prefixLength - 1;
			final int suffixHead = this.elements.get(position);
			this.chains.put(headPk, new ChainDescriptor(prefixLength, computeHeadState(headPk, prefixLength)));
			this.chains.put(suffixHead, new ChainDescriptor(suffixLength, computeHeadState(suffixHead, suffixLength)));
			// the element that shifted into `position` heads the new orphan suffix run
			this.elements.markAsHead(suffixHead);
			return new DetachOutcome(headPk, this.elements.get(position - 1));
		}
	}

	/**
	 * Drains the collapse work-queue to a fixpoint: for every seeded trigger, merges any successor/orphan run whose
	 * head predecessor is the tail of another run into that run, cascading to the merged run's newly exposed tail after
	 * each merge. Seeded only by the elements whose presence or tail-status changed in the triggering mutation (via
	 * {@link #enqueueTrigger}), it reaches the same fixpoint as a full rescan of every chain but in `O(seeds + merges)`
	 * work per mutation instead of the former `O(C)` scan - the decisive difference for a large, permanently
	 * fragmented chain, where an at-rest mutation seeds `O(1)` instead of walking every descriptor. When the two runs
	 * to merge are not already physically adjacent, the shorter run is relocated so they become adjacent (`O(min)` and
	 * only transient - the steady state is a single chain).
	 *
	 * The merge decision always re-reads the authoritative {@link #predecessors} / {@link #elements} / {@link #chains}
	 * state, so a queued head that is no longer a mergeable successor (already merged away, predecessor absent, circular
	 * or a genuine head) is simply skipped - the queue is a candidate hint, never a source of truth.
	 *
	 * @param triggers the elements whose change may have created a collapsible pair (assembled at the call sites)
	 */
	private void collapse(@Nonnull IntArrayList triggers) {
		final IntArrayDeque queue = new IntArrayDeque();
		// dedup: never queue the same head twice while it is pending; it is dropped from the set the moment it is
		// polled, so a later cascade may legitimately re-queue it
		final IntHashSet queued = new IntHashSet();
		for (int i = 0; i < triggers.size(); i++) {
			enqueueTrigger(queue, queued, triggers.get(i));
		}
		while (!queue.isEmpty()) {
			final int headPk = queue.removeFirst();
			queued.remove(headPk);
			final ChainDescriptor descriptor = this.chains.get(headPk);
			if (descriptor == null || descriptor.state() != ElementState.SUCCESSOR) {
				// no longer a successor head - HEAD and CIRCULAR runs never follow another run
				continue;
			}
			final Integer headPredecessorRef = this.predecessors.get(headPk);
			isPremiseValid(
				headPredecessorRef != null,
				"Index damaged! The chain head `" + headPk + "` has no predecessor entry!"
			);
			final int headPredecessor = headPredecessorRef;
			if (headPredecessor == HEAD_PK) {
				continue;
			}
			final int predecessorPos = this.elements.indexOf(headPredecessor);
			if (predecessorPos == Integer.MIN_VALUE) {
				// predecessor not present yet - the run stays an orphan
				continue;
			}
			final RunRef predecessorRun = findRun(predecessorPos);
			if (predecessorRun.headPk() == headPk) {
				// defensive: predecessor inside the same run would mean circular - handled by state, skip
				continue;
			}
			final boolean predecessorIsTail =
				predecessorPos == predecessorRun.headPos() + predecessorRun.length() - 1;
			if (predecessorIsTail) {
				// the follower's tail becomes the merged run's new tail; capture it before mergeRunAfter relocates the
				// block, then cascade so any successor waiting on that newly exposed tail is re-examined
				final int followerTailPk =
					this.elements.get(this.elements.indexOf(headPk) + descriptor.length() - 1);
				mergeRunAfter(predecessorRun.headPk(), headPk);
				enqueueTrigger(queue, queued, followerTailPk);
			}
		}
	}

	/**
	 * Seeds the collapse work-queue with `trigger` and every element that declares `trigger` as its predecessor. The
	 * former covers `trigger` itself being a follower candidate (its own predecessor may have just become a tail); the
	 * latter covers `trigger` having become a present run tail that pending successor heads can now merge onto - found
	 * in `O(1)` via the {@link #successorsByPredecessor} inverse rather than by scanning every chain. A head is enqueued
	 * at most once while it is pending (tracked in `queued`).
	 *
	 * @param queue   the work-queue being drained
	 * @param queued  the set of currently-queued heads (dedup)
	 * @param trigger the element whose presence / tail-status changed
	 */
	private void enqueueTrigger(@Nonnull IntArrayDeque queue, @Nonnull IntHashSet queued, int trigger) {
		if (queued.add(trigger)) {
			queue.addLast(trigger);
		}
		final TransactionalBitmap waiters = this.successorsByPredecessor.get(trigger);
		if (waiters != null) {
			final OfInt waiterIt = waiters.iterator();
			while (waiterIt.hasNext()) {
				final int waiter = waiterIt.nextInt();
				if (queued.add(waiter)) {
					queue.addLast(waiter);
				}
			}
		}
	}

	/**
	 * Appends `candidate` to `triggers` unless it is the {@link Integer#MIN_VALUE} "none" sentinel used by the
	 * {@link DetachOutcome} slots.
	 *
	 * @param triggers  the trigger list being assembled for {@link #collapse(IntArrayList)}
	 * @param candidate a possibly-absent trigger element
	 */
	private static void addTrigger(@Nonnull IntArrayList triggers, int candidate) {
		if (candidate != Integer.MIN_VALUE) {
			triggers.add(candidate);
		}
	}

	/**
	 * Merges the run headed by `followerHeadPk` immediately after the run headed by `targetHeadPk` (the follower's head
	 * predecessor is the target's tail). If the two runs are not already physically adjacent the shorter of them is
	 * relocated to restore contiguity. After the merge a single run headed by `targetHeadPk` remains.
	 *
	 * @param targetHeadPk   head of the run the follower is appended to
	 * @param followerHeadPk head of the run being merged in
	 */
	private void mergeRunAfter(int targetHeadPk, int followerHeadPk) {
		final ChainDescriptor targetDescriptor = this.chains.get(targetHeadPk);
		final ChainDescriptor followerDescriptor = this.chains.get(followerHeadPk);
		isPremiseValid(
			targetDescriptor != null && followerDescriptor != null,
			"Index damaged! The run head `" +
				(targetDescriptor == null ? targetHeadPk : followerHeadPk) + "` has no descriptor!"
		);
		final int targetPos = this.elements.indexOf(targetHeadPk);
		final int targetLength = targetDescriptor.length();
		final int followerPos = this.elements.indexOf(followerHeadPk);
		final int followerLength = followerDescriptor.length();

		final boolean adjacent = targetPos + targetLength == followerPos;
		if (!adjacent) {
			if (followerLength <= targetLength) {
				// relocate the follower run to right after the target's tail
				final int targetTail = this.elements.get(targetPos + targetLength - 1);
				relocateBlockAfter(followerPos, followerLength, targetTail);
			} else {
				// relocate the target run to right before the follower's head
				relocateBlockBefore(targetPos, targetLength, followerHeadPk);
			}
		}

		this.chains.remove(followerHeadPk);
		final int mergedLength = targetLength + followerLength;
		this.chains.put(targetHeadPk, new ChainDescriptor(mergedLength, computeHeadState(targetHeadPk, mergedLength)));
		// the merged run has a single head (the target); the follower stops being a head. Idempotent marks cover every
		// branch uniformly - a relocate (remove + re-insert) resets a moved run's head to non-head, so re-assert the
		// intended final state here regardless of which run (if any) was relocated.
		this.elements.markAsHead(targetHeadPk);
		this.elements.unmarkAsHead(followerHeadPk);
	}

	/**
	 * Relocates the contiguous block `[blockStartPos, blockStartPos + blockLength)` so that it immediately follows the
	 * element `afterPk` (which must lie outside the block). The block elements are read positionally (`O(block·log N)`)
	 * rather than via {@code getSubArray}, which would flatten the whole array.
	 */
	private void relocateBlockAfter(int blockStartPos, int blockLength, int afterPk) {
		final int[] block = readBlock(blockStartPos, blockLength);
		for (final int recordId : block) {
			this.elements.remove(recordId);
		}
		final int insertAt = this.elements.indexOf(afterPk) + 1;
		for (int i = 0; i < block.length; i++) {
			this.elements.addOnIndex(insertAt + i, block[i]);
		}
	}

	/**
	 * Relocates the contiguous block `[blockStartPos, blockStartPos + blockLength)` so that it immediately precedes the
	 * element `beforePk` (which must lie outside the block). The block elements are read positionally
	 * (`O(block·log N)`) rather than via {@code getSubArray}, which would flatten the whole array.
	 */
	private void relocateBlockBefore(int blockStartPos, int blockLength, int beforePk) {
		final int[] block = readBlock(blockStartPos, blockLength);
		for (final int recordId : block) {
			this.elements.remove(recordId);
		}
		final int insertAt = this.elements.indexOf(beforePk);
		for (int i = 0; i < block.length; i++) {
			this.elements.addOnIndex(insertAt + i, block[i]);
		}
	}

	/**
	 * Reads the contiguous block `[startPos, startPos + length)` from {@link #elements} element-by-element. Used on the
	 * hot relocation path to avoid {@link TransactionalUnorderedIntArray#getSubArray} flattening the entire array.
	 */
	@Nonnull
	private int[] readBlock(int startPos, int length) {
		final int[] block = new int[length];
		for (int i = 0; i < length; i++) {
			block[i] = this.elements.get(startPos + i);
		}
		return block;
	}

	/**
	 * Finds the run (chain) that contains the element at the given array position. The run head is the chain head at the
	 * greatest head-position `<= position`, located in `O(log N)` by the head-aware element array (each chain head is
	 * marked in the array), replacing the former `O(C·log N)` scan over every chain descriptor.
	 *
	 * @param position position in {@link #elements}
	 * @return reference to the run containing the position
	 */
	@Nonnull
	private RunRef findRun(int position) {
		final TransactionalUnorderedIntArray.HeadLocation head = this.elements.findHeadCovering(position);
		final int headPk = head.recordId();
		final ChainDescriptor descriptor = this.chains.get(headPk);
		isPremiseValid(
			descriptor != null,
			"Index damaged! The run head `" + headPk + "` located for position `" + position + "` has no descriptor!"
		);
		return new RunRef(headPk, head.headPosition(), descriptor.length());
	}

	/**
	 * Computes the {@link ElementState} of a run head from the structural reality: a head with no predecessor is
	 * {@link ElementState#HEAD}, a head whose predecessor currently sits inside its own run is
	 * {@link ElementState#CIRCULAR}, otherwise {@link ElementState#SUCCESSOR}.
	 *
	 * @param headPk head primary key of the run
	 * @param length length of the run
	 * @return the computed head state
	 */
	@Nonnull
	private ElementState computeHeadState(int headPk, int length) {
		final Integer headPredecessorRef = this.predecessors.get(headPk);
		isPremiseValid(
			headPredecessorRef != null,
			"Index damaged! The chain head `" + headPk + "` has no predecessor entry!"
		);
		final int headPredecessor = headPredecessorRef;
		if (headPredecessor == HEAD_PK) {
			return ElementState.HEAD;
		}
		final int predecessorPos = this.elements.indexOf(headPredecessor);
		if (predecessorPos == Integer.MIN_VALUE) {
			return ElementState.SUCCESSOR;
		}
		final int headPos = this.elements.indexOf(headPk);
		return predecessorPos >= headPos && predecessorPos < headPos + length
			? ElementState.CIRCULAR
			: ElementState.SUCCESSOR;
	}

	/**
	 * Reconstructs the public {@link ChainElementState} of an element from the positional model. Package-private
	 * test/diagnostic accessor mirroring the former per-element {@code elementStates} map (chain membership and head
	 * state are derived positionally).
	 *
	 * @param primaryKey primary key of the element
	 * @return reconstructed element state, or {@code null} when the element is not present
	 */
	@Nullable
	ChainElementState getElementState(int primaryKey) {
		return this.predecessors.containsKey(primaryKey) ? reconstructState(primaryKey) : null;
	}

	/**
	 * Reconstructs the public {@link ChainElementState} of an element from the positional model (its chain head is
	 * looked up positionally). Used for {@code toString}.
	 *
	 * @param elementId primary key of the element
	 * @return reconstructed element state
	 */
	@Nonnull
	private ChainElementState reconstructState(int elementId) {
		final int position = this.elements.indexOf(elementId);
		final RunRef run = findRun(position);
		final ElementState state;
		if (elementId == run.headPk()) {
			final ChainDescriptor descriptor = this.chains.get(run.headPk());
			isPremiseValid(
				descriptor != null,
				"Index damaged! The run head `" + run.headPk() + "` has no descriptor!"
			);
			state = descriptor.state();
		} else {
			state = ElementState.SUCCESSOR;
		}
		final Integer predecessor = this.predecessors.get(elementId);
		isPremiseValid(
			predecessor != null,
			"Index damaged! The element `" + elementId + "` has no predecessor entry!"
		);
		return new ChainElementState(run.headPk(), predecessor, state);
	}

	/**
	 * Reference to a run (chain) in {@link #elements}: its head primary key, the position of the head and the run
	 * length.
	 */
	private record RunRef(int headPk, int headPos, int length) {
	}

	/**
	 * Collapse triggers produced by {@link #detachElement(int)}. Each slot holds {@link Integer#MIN_VALUE} when that
	 * kind of trigger did not occur:
	 *
	 * - `affectedHead` - a run head whose {@link ElementState} was recomputed by the detach (a promoted successor, or
	 *   the head of a shrunk / split run). This matters because splitting a run can move a head's declared predecessor
	 *   OUT of that head's run, flipping it from {@link ElementState#CIRCULAR} to {@link ElementState#SUCCESSOR} and
	 *   making it collapsible - a transition the exposed-tail seed alone would miss.
	 * - `exposedTail` - the element that became a run tail, onto which a pending successor may now merge.
	 *
	 * @param affectedHead a run head whose state was recomputed, or {@link Integer#MIN_VALUE}
	 * @param exposedTail  the element that became a run tail, or {@link Integer#MIN_VALUE}
	 */
	private record DetachOutcome(int affectedHead, int exposedTail) {
	}

	/**
	 * Descriptor of a single chain (run) in {@link #elements}: its length and the {@link ElementState} of its head.
	 *
	 * @param length number of elements in the run
	 * @param state  state of the run's head element
	 */
	public record ChainDescriptor(int length, @Nonnull ElementState state) {
	}

	/**
	 * Enum represents the state of the element in the index.
	 */
	public enum ElementState {
		/**
		 * Element is the head of one of the chains and have no predecessor.
		 */
		HEAD,
		/**
		 * Element have defined predecessor element. Element might be the head of one of the chains in case
		 * it is in inconsistent state (i.e. there are multiple elements referring to same predecessor).
		 */
		SUCCESSOR,
		/**
		 * Element is head of the chain that and it is in circular dependency with some of the elements in its tail.
		 */
		CIRCULAR
	}

	/**
	 * Record represents a state for each element in this index. It is the persisted / boundary representation; the live
	 * index keeps chain membership positionally and reconstructs this record on demand.
	 *
	 * @param inChainOfHeadWithPrimaryKey primary key of the head of the chain this element is part of
	 * @param predecessorPrimaryKey       primary key of the predecessor of this element
	 * @param state                       state of the element
	 */
	public record ChainElementState(
		int inChainOfHeadWithPrimaryKey,
		int predecessorPrimaryKey,
		@Nonnull ElementState state
	) {

		@Nonnull
		@Override
		public String toString() {
			switch (this.state) {
				case HEAD -> {
					return "HEAD 🔗 " + this.inChainOfHeadWithPrimaryKey;
				}
				case SUCCESSOR -> {
					return "SUCCESSOR of " + this.predecessorPrimaryKey + " 🔗 " + this.inChainOfHeadWithPrimaryKey;
				}
				case CIRCULAR -> {
					return "CIRCULAR of " + this.predecessorPrimaryKey + " 🔗 " + this.inChainOfHeadWithPrimaryKey;
				}
				default -> throw new IllegalStateException("Unexpected value: " + this.state);
			}
		}
	}

}
