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

package io.evitadb.index.component;

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.HistogramIndex;
import io.evitadb.index.HistogramIndex.PersistedHistogramLeafPages;
import io.evitadb.index.map.MapHeapSize;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramCardinalityStoragePartRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramLeafStreamKey.StreamKind;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

/**
 * {@link IndexComponent} adapter for the per-histogram-name {@link HistogramIndex} map carried by
 * {@link io.evitadb.index.ReducedGroupEntityIndex} and {@link io.evitadb.index.ReferencedTypeEntityIndex}.
 * Each entry stores bucketed histogram data (filter indexes + cardinality tracking) for all locale
 * variants of a single histogram definition.
 *
 * The component:
 *
 * 1. forwards `getModifiedStorageParts` to every wrapped {@link HistogramIndex},
 * 2. asks every wrapped {@link HistogramIndex} to populate the shared {@link EntityIndexManifest} with
 *    its own filter + cardinality storage keys via {@link HistogramIndex#collectStorageKeys},
 * 3. forwards reset/remove-layer calls into every entry via the {@link TransactionalMap} machinery.
 */
public final class HistogramIndexMapComponent implements IndexComponent {

	/**
	 * Backing per-histogram-name map owned by the parent index. Held by reference because the parent
	 * index never swaps the map instance during its lifetime — only the contents change.
	 */
	@Nonnull private final TransactionalMap<String, HistogramIndex> histogramIndexes;
	/**
	 * The parent {@link EntityIndexKey} forwarded into {@link HistogramIndex#collectStorageKeys} so
	 * the histogram can compose its own storage keys.
	 */
	@Nonnull private final EntityIndexKey entityIndexKey;

	/**
	 * Empty-drop-reclaim snapshot: the on-disk leaf-page sequences of every persisted `(histogram, locale)` sub-index at
	 * the last durable point, keyed by `(histogram name, locale)`. It is the histogram analogue of the per-key snapshot
	 * {@link io.evitadb.index.attribute.AttributeIndex} keeps for its paged families. Captured once at construction (from
	 * the committed / restored map, before any mutation) and refreshed at the end of every
	 * {@link #collectModifiedStorageParts}. When a whole histogram (or a single locale) is dropped from the map, the
	 * dropped sub-index's own flush never runs again — so {@link #collectModifiedStorageParts} diffs this baseline against
	 * the surviving key set and reclaims the orphaned leaf pages + cardinality sibling, or the append-only OffsetIndex
	 * would copy them forward forever. Held as a plain (non-transactional) {@link java.util.HashMap}: the component's
	 * lifecycle mirrors its owning entity index (rebuilt on merge-copy, reused on warm-up flushes).
	 */
	@Nonnull private Map<HistogramIndexKey, PersistedHistogramLeafPages> persistedLeafPages;

	/**
	 * @param histogramIndexes the wrapped per-histogram-name map
	 * @param entityIndexKey   the parent index key used to compose storage keys
	 */
	public HistogramIndexMapComponent(
		@Nonnull TransactionalMap<String, HistogramIndex> histogramIndexes,
		@Nonnull EntityIndexKey entityIndexKey
	) {
		this.histogramIndexes = histogramIndexes;
		this.entityIndexKey = entityIndexKey;
		// snapshot the committed on-disk leaf pages BEFORE any mutation, so a later empty-drop can still reclaim the
		// now-orphaned pages (mirrors AttributeIndex's construction-time per-family snapshot). A fresh (empty) map yields
		// an empty snapshot, rebuilt at the end of the first flush.
		this.persistedLeafPages = snapshotLeafPages(histogramIndexes);
	}

	/**
	 * Snapshots the on-disk leaf-page sequences of every persisted `(histogram, locale)` sub-index into a fresh plain map
	 * keyed by `(histogram name, locale)` — the baseline {@link #collectModifiedStorageParts} diffs to reclaim the leaf
	 * pages + cardinality sibling of an empty-dropped histogram / locale. The per-sub-index liveness predicate is the one
	 * {@link HistogramIndex#collectPersistedLeafPages} enforces (identical to `collectStorageKeys`), so the map holds
	 * exactly the keys the manifest advertises as live on disk.
	 *
	 * @param histogramIndexes the committed per-histogram-name map (already published or restored from disk)
	 * @return the per-`(name, locale)` on-disk leaf-page snapshot, or an empty map when no histogram is present
	 */
	@Nonnull
	private static Map<HistogramIndexKey, PersistedHistogramLeafPages> snapshotLeafPages(
		@Nonnull Map<String, HistogramIndex> histogramIndexes
	) {
		if (histogramIndexes.isEmpty()) {
			return Map.of();
		}
		final Map<HistogramIndexKey, PersistedHistogramLeafPages> snapshot =
			CollectionUtils.createHashMap(histogramIndexes.size());
		// `forEach` rather than `values()`: a HashMap keeps the view it hands out, and this runs against the LIVE map
		// on every flush - see `TransactionalMap#forEach`
		histogramIndexes.forEach(
			(histogramName, histogramIndex) ->
				histogramIndex.collectPersistedLeafPages(pages -> snapshot.put(pages.key(), pages))
		);
		return snapshot.isEmpty() ? Map.of() : snapshot;
	}

	/**
	 * Empty-drop reclaim: emits a leaf-page removal for every on-disk bucket / range page — plus a cardinality-sibling
	 * removal — of a `(histogram, locale)` sub-index that was dropped from the map this commit. The dropped sub-index's
	 * own flush never runs again, so its last leaf pages and its evicted cardinality sibling would otherwise be copied
	 * forward forever by the append-only OffsetIndex; this diffs the pre-commit `snapshot` against the keys that survived
	 * (`current`) and reclaims the orphaned parts of every vanished key. Surviving sub-indexes reclaim their own
	 * split/merge-freed pages through their own flush — this only covers the whole-drop the child can no longer see. The
	 * histogram root (a small identity part) is intentionally NOT reclaimed: it is a pre-existing monolithic-histogram
	 * leak outside this optimization's scope.
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param snapshot              the per-key on-disk leaf-page snapshot captured before this commit
	 * @param current               the per-key snapshot of the sub-indexes that survived this commit
	 * @param trappedChanges        the trapped-changes accumulator for this commit
	 */
	private static void emitDroppedReclaims(
		int entityIndexPrimaryKey,
		@Nonnull Map<HistogramIndexKey, PersistedHistogramLeafPages> snapshot,
		@Nonnull Map<HistogramIndexKey, PersistedHistogramLeafPages> current,
		@Nonnull TrappedChanges trappedChanges
	) {
		if (snapshot.isEmpty()) {
			return;
		}
		for (final Entry<HistogramIndexKey, PersistedHistogramLeafPages> entry : snapshot.entrySet()) {
			final HistogramIndexKey key = entry.getKey();
			if (current.containsKey(key)) {
				// survivor — its own flush already reclaimed any split/merge-freed pages this commit
				continue;
			}
			final String histogramName = key.histogramName();
			final Locale locale = key.locale();
			final PersistedHistogramLeafPages pages = entry.getValue();
			for (final int freedBucketPage : pages.bucketPageSequences()) {
				trappedChanges.addChangeToStore(
					new HistogramIndexLeafPageRemoval(
						entityIndexPrimaryKey, histogramName, locale, StreamKind.BUCKET, freedBucketPage
					)
				);
			}
			for (final int freedRangePage : pages.rangePageSequences()) {
				trappedChanges.addChangeToStore(
					new HistogramIndexLeafPageRemoval(
						entityIndexPrimaryKey, histogramName, locale, StreamKind.RANGE, freedRangePage
					)
				);
			}
			// the cardinality sibling is its own record type with a distinct primary key — reclaim it explicitly
			trappedChanges.addChangeToStore(
				new HistogramCardinalityStoragePartRemoval(entityIndexPrimaryKey, histogramName, locale)
			);
		}
	}

	/**
	 * Returns the heap this component occupies, in bytes - its own object plus the leaf-page baseline it alone holds.
	 *
	 * # Why this component prices itself when its four siblings do not
	 *
	 * Every other {@link IndexComponent} wrapper is a pure adapter: its fields point at sub-indexes charged at the
	 * owning {@link io.evitadb.index.EntityIndex}, so the index charges the wrapper's shell inline and is done. This
	 * one owns {@link #persistedLeafPages} outright - a plain map nothing else in the tree can reach - and a shell
	 * charge alone would report it as free. It is the histogram analogue of the five `persisted*LeafPages` snapshots
	 * {@link io.evitadb.index.attribute.AttributeIndex} keeps as its own fields and prices there; the accounting is
	 * deliberately the same, only the holder differs.
	 *
	 * # What is charged
	 *
	 * The keys **are** charged here, which is where this diverges from `AttributeIndex`: its snapshots are keyed by
	 * the very instances its sub-index maps hold, so it charges them a slot and lets those maps pay. A
	 * {@link HistogramIndexKey} is minted fresh by {@link HistogramIndex#persistedLeafPagesOf} for the snapshot and
	 * this map is its only holder - the histogram map above is keyed by bare names, and the manifest's
	 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey} is a different
	 * record entirely. What the key *points at* is borrowed: the histogram name belongs to the schema and the locale
	 * is interned by the JVM.
	 *
	 * A page sequence array is charged only when it holds pages. An axis that never paged parks the field on
	 * {@link io.evitadb.utils.ArrayUtils#EMPTY_INT_ARRAY}, one instance for the whole JVM that no index owns, and an
	 * empty snapshot likewise sits on `Map.of()` and contributes nothing at all.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// the histogramIndexes / entityIndexKey / persistedLeafPages slots - the first two are the owning index's
		final long shell = layout.sizeOfObject(3L * layout.referenceSize());
		if (this.persistedLeafPages.isEmpty()) {
			return shell;
		}
		// the histogram name is the schema's and the locale is JVM-interned, so the key record's own object is all
		// the snapshot owns of it
		final long histogramIndexKey = layout.sizeOfObject(2L * layout.referenceSize());
		return shell + MapHeapSize.sizeOf(
			this.persistedLeafPages,
			key -> histogramIndexKey,
			// the record's `key` reference points at the map key already charged above, so only the two page
			// sequences are added - each of them the shared empty singleton until that axis actually pages
			pages -> layout.sizeOfObject(3L * layout.referenceSize())
				+ pageSequencesHeapSizeInBytes(layout, pages.bucketPageSequences())
				+ pageSequencesHeapSizeInBytes(layout, pages.rangePageSequences())
		);
	}

	/**
	 * Prices one axis' on-disk page-sequence array, or nothing when that axis never paged.
	 *
	 * @param layout        the VM layout to size against
	 * @param pageSequences the axis' on-disk leaf-page sequences
	 * @return the owned heap footprint in bytes, `0` for the shared empty singleton
	 */
	private static long pageSequencesHeapSizeInBytes(@Nonnull VMLayout layout, @Nonnull int[] pageSequences) {
		return pageSequences.length == 0 ? 0L : layout.sizeOfArray(pageSequences.length, Integer.BYTES);
	}

	@Override
	public void collectModifiedStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull EntityIndexManifest manifest,
		@Nonnull TrappedChanges trappedChanges
	) {
		// emit dirty storage parts and announce live histogram keys — manifest population is
		// unconditional so a clean histogram still appears in the parent EntityIndexStoragePart
		this.histogramIndexes.forEach((histogramName, histogramIndex) -> {
			histogramIndex.getModifiedStorageParts(entityIndexPrimaryKey, trappedChanges);
			histogramIndex.collectStorageKeys(this.entityIndexKey, manifest.getHistogramKeys());
		});
		// empty-drop reclaim: a whole histogram (or a single locale) dropped from the map this commit still has its
		// bucket / range leaf pages + cardinality sibling on disk, but the dropped sub-index's own flush never runs
		// again — so diff the pre-commit on-disk snapshot against the surviving key set and reclaim the orphaned parts,
		// then refresh the snapshot to the surviving set so a reused instance (warm-up / repeated flush) diffs the next
		// drop against what was just written here rather than a stale construction-time baseline. Idempotent: the
		// baseline-capture re-run (notifyFlushed) reproduces the same map and finds no further drop.
		final Map<HistogramIndexKey, PersistedHistogramLeafPages> current = snapshotLeafPages(this.histogramIndexes);
		emitDroppedReclaims(entityIndexPrimaryKey, this.persistedLeafPages, current, trappedChanges);
		this.persistedLeafPages = current;
	}

	@Override
	public void resetDirty() {
		// HistogramIndex has no own dirty flag — its sub-structures track their own dirtiness;
		// the parent EntityIndex.dirty bit is reset by the base loop
		this.histogramIndexes.forEach((histogramName, histogramIndex) -> histogramIndex.resetDirty());
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// TransactionalMap#removeLayer drops its own diff layer AND propagates into every value
		// that is a TransactionalLayerProducer, so per-entry HistogramIndex layers are covered
		this.histogramIndexes.removeLayer(transactionalLayer);
	}

	@Override
	public void emitPersistedFootprintRemovals(
		int entityIndexPrimaryKey,
		@Nonnull TrappedChanges trappedChanges
	) {
		// whole-index drop: reclaim every persisted bucket / range leaf page + cardinality sibling of every persisted
		// `(histogram, locale)` sub-index by diffing the persisted baseline against an empty survivor set (nothing
		// survives). The histogram root is manifest-listed and reclaimed by EntityIndex.emitVanishedRootRemovals.
		// Reads only the persisted baseline and has no side effects — the baseline field is left untouched.
		emitDroppedReclaims(entityIndexPrimaryKey, this.persistedLeafPages, Map.of(), trappedChanges);
	}

}
