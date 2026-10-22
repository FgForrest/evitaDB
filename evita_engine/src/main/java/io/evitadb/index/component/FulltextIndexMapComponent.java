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

package io.evitadb.index.component;

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.fulltext.FieldLengthTable.LengthBlock;
import io.evitadb.index.fulltext.FieldLengthTable.LengthBlockEmission;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.FulltextIndex.DictionaryPage;
import io.evitadb.index.map.MapHeapSize;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.page.PageStreamRegistry;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextDictionaryLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextDictionaryLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextFieldLengthBlockPart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextFieldLengthBlockRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexStoragePart.FieldEntry;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

/**
 * {@link IndexComponent} adapter for the per-locale {@link FulltextIndex} map carried by
 * {@link io.evitadb.index.GlobalEntityIndex}. Each entry is the fulltext index of one locale partition.
 *
 * ## Flush
 *
 * A dirty index writes its changed dictionary pages and their removals, then its changed length blocks and their
 * removals, then its {@link FulltextIndexStoragePart} root - pages before the root that lists them - and is reset
 * clean. The reset happens here, inside the collect, because nothing else resets it: the flush pipeline re-runs this
 * collect through {@link io.evitadb.index.EntityIndex#notifyFlushed()} into a sink it discards, and that second pass
 * must find the index clean, or it would stage pages that never reach the disk.
 *
 * Every index that has a root on disk, or writes one now, is announced into the {@link EntityIndexManifest}, which is
 * how the reload finds it and how {@link io.evitadb.index.EntityIndex} reclaims the root of a locale that vanished. An
 * index that was never written to is neither flushed nor announced: it holds nothing, and announcing it would promise
 * a root the reload could not find. Every write dirties the index, and the flush of a transaction runs before its
 * commit merge, so an index that holds data is always dirty or persisted when it is collected.
 *
 * ## Clean on construction
 *
 * Because the collect resets what it collects, it is not a harmless read, and the entity index runs it once more on
 * every component it builds: {@link io.evitadb.index.EntityIndex#captureOriginalsFromComponents()} captures the
 * on-disk baseline that way, into a sink it discards. A dirty index reaching that pass would lose its unflushed
 * changes to the discarded sink - silently outside a transaction, and with an error about a closed transaction
 * inside a commit. Every caller hands over clean indexes: a reload builds them from their pages, the commit merge
 * copy runs after the transaction's flush collected them, and go-live is preceded by a flush of the warm-up state.
 * The constructor checks it, so a new caller that breaks the rule fails at once, with a message naming the locale.
 *
 * ## Reclaim
 *
 * The root of a vanished index is reclaimed by the manifest diff in {@link io.evitadb.index.EntityIndex}, but its
 * dictionary pages and length blocks are this component's: the dropped index's own flush never runs again. So, as
 * {@link HistogramIndexMapComponent} does for its histograms, the component keeps a snapshot of what each index has
 * on disk, refreshed at every collect, and removes the footprint of every index that left the map. An index replaced
 * by a new one under the same locale - told apart by its {@link PageStreamRegistry}, which every merged copy of one
 * index shares - loses only the pages and blocks its replacement does not overwrite, because the replacement numbers
 * its pages from scratch under the same stream keys.
 */
public final class FulltextIndexMapComponent implements IndexComponent {

	/**
	 * Backing per-locale map owned by the parent index. Held by reference because the parent index never swaps the map
	 * instance during its lifetime - only the contents change.
	 */
	@Nonnull private final TransactionalMap<Locale, FulltextIndex> fulltextIndexes;

	/**
	 * What every index of the map has on disk, as of the last durable point: captured at construction from the
	 * committed or restored map, refreshed at the end of every {@link #collectModifiedStorageParts}. A plain map - its
	 * lifecycle mirrors the owning entity index, rebuilt on the merge copy and reused across warm-up flushes.
	 */
	@Nonnull private Map<Locale, PersistedFootprint> persistedFootprints;

	/**
	 * What one index has on disk besides its root.
	 *
	 * @param pageStreamRegistry the index's page bookkeeping, the identity of the logical index across merged copies
	 * @param dictionaryPages    sequences of its dictionary pages, ascending
	 * @param lengthBlocks       keys of the length blocks of each field on disk, ascending, the i-th for field id `i`
	 */
	private record PersistedFootprint(
		@Nonnull PageStreamRegistry pageStreamRegistry,
		@Nonnull int[] dictionaryPages,
		@Nonnull int[][] lengthBlocks
	) {
	}

	/**
	 * @param fulltextIndexes the wrapped per-locale map; every index in it must be clean - see "Clean on construction"
	 *                        in the class documentation
	 * @throws GenericEvitaInternalError when an index of the map has changes no flush has collected yet
	 */
	public FulltextIndexMapComponent(@Nonnull TransactionalMap<Locale, FulltextIndex> fulltextIndexes) {
		fulltextIndexes.forEach(
			(locale, index) -> Assert.isPremiseValid(
				!index.isDirty(),
				() -> "The fulltext index of locale `" + locale + "` has changes no flush has collected; building " +
					"an entity index over it would capture its baseline by collecting them into a discarded sink " +
					"and lose them. Flush the index before handing it over."
			)
		);
		this.fulltextIndexes = fulltextIndexes;
		this.persistedFootprints = snapshotFootprints(fulltextIndexes);
	}

	/**
	 * Takes the on-disk footprint of every index of the map that has one.
	 *
	 * @param fulltextIndexes the per-locale map
	 * @return the footprint of each persisted index by its locale, empty when none is persisted
	 */
	@Nonnull
	private static Map<Locale, PersistedFootprint> snapshotFootprints(@Nonnull Map<Locale, FulltextIndex> fulltextIndexes) {
		if (fulltextIndexes.isEmpty()) {
			return Map.of();
		}
		final Map<Locale, PersistedFootprint> snapshot = CollectionUtils.createHashMap(fulltextIndexes.size());
		// `forEach` rather than `entrySet()`: this runs against the LIVE map on every flush - see `TransactionalMap#forEach`
		fulltextIndexes.forEach(
			(locale, index) -> {
				final int[] dictionaryPages = index.getPersistedDictionaryPages();
				if (dictionaryPages.length > 0) {
					// the pending set comes out of a hash set, the reclaim looks pages up by binary search
					Arrays.sort(dictionaryPages);
					final int[][] lengthBlocks = new int[index.getFieldCount()][];
					for (int fieldId = 0; fieldId < lengthBlocks.length; fieldId++) {
						lengthBlocks[fieldId] = index.getPersistedLengthBlocks(fieldId);
					}
					snapshot.put(
						locale, new PersistedFootprint(index.getPageStreamRegistry(), dictionaryPages, lengthBlocks)
					);
				}
			}
		);
		return snapshot.isEmpty() ? Map.of() : snapshot;
	}

	/**
	 * Removes the on-disk footprint of every index that left the map since the snapshot was taken, and the part of the
	 * footprint of a replaced index its replacement does not overwrite. An index that stayed reclaims its own freed
	 * pages and blocks through its own flush.
	 *
	 * @param entityIndexPrimaryKey the owning entity index's primary key
	 * @param snapshot              the footprints at the previous durable point
	 * @param current               the footprints of the indexes in the map now
	 * @param trappedChanges        the accumulator collecting the removals
	 */
	private static void emitDroppedReclaims(
		int entityIndexPrimaryKey,
		@Nonnull Map<Locale, PersistedFootprint> snapshot,
		@Nonnull Map<Locale, PersistedFootprint> current,
		@Nonnull TrappedChanges trappedChanges
	) {
		for (final Entry<Locale, PersistedFootprint> entry : snapshot.entrySet()) {
			final Locale locale = entry.getKey();
			final PersistedFootprint previous = entry.getValue();
			final PersistedFootprint replacement = current.get(locale);
			if (replacement != null && replacement.pageStreamRegistry() == previous.pageStreamRegistry()) {
				// the same logical index - its own flush already removed what it freed
				continue;
			}
			for (final int pageSequence : previous.dictionaryPages()) {
				if (replacement == null || Arrays.binarySearch(replacement.dictionaryPages(), pageSequence) < 0) {
					trappedChanges.addChangeToStore(
						new FulltextDictionaryLeafPageRemoval(entityIndexPrimaryKey, locale, pageSequence)
					);
				}
			}
			for (int fieldId = 0; fieldId < previous.lengthBlocks().length; fieldId++) {
				final int[] replacementBlocks = replacement == null || fieldId >= replacement.lengthBlocks().length
					? null : replacement.lengthBlocks()[fieldId];
				for (final int blockKey : previous.lengthBlocks()[fieldId]) {
					if (replacementBlocks == null || Arrays.binarySearch(replacementBlocks, blockKey) < 0) {
						trappedChanges.addChangeToStore(
							new FulltextFieldLengthBlockRemoval(entityIndexPrimaryKey, locale, fieldId, blockKey)
						);
					}
				}
			}
		}
	}

	/**
	 * Writes what a dirty index changed: its dictionary pages and their removals, its length blocks and their removals,
	 * and its root, in that order.
	 *
	 * @param entityIndexPrimaryKey the owning entity index's primary key
	 * @param locale                the locale of the index
	 * @param index                 the dirty index
	 * @param trappedChanges        the accumulator collecting the parts
	 */
	private static void emitChanges(
		int entityIndexPrimaryKey,
		@Nonnull Locale locale,
		@Nonnull FulltextIndex index,
		@Nonnull TrappedChanges trappedChanges
	) {
		final PageEmission<DictionaryPage> pages = index.collectChangedPages();
		for (final DictionaryPage page : pages.changedPages()) {
			trappedChanges.addChangeToStore(
				new FulltextDictionaryLeafPagePart(
					entityIndexPrimaryKey, locale, page.pageSequence(), page.buckets(), page.impacts()
				)
			);
		}
		for (final int freedPageSequence : pages.freedPageSequences()) {
			trappedChanges.addChangeToStore(
				new FulltextDictionaryLeafPageRemoval(entityIndexPrimaryKey, locale, freedPageSequence)
			);
		}
		final LengthBlockEmission[] lengths = index.collectChangedLengthBlocks();
		final FieldEntry[] fields = new FieldEntry[lengths.length];
		for (int fieldId = 0; fieldId < lengths.length; fieldId++) {
			final LengthBlockEmission emission = lengths[fieldId];
			for (final LengthBlock block : emission.changedBlocks()) {
				trappedChanges.addChangeToStore(
					new FulltextFieldLengthBlockPart(entityIndexPrimaryKey, locale, fieldId, block)
				);
			}
			for (final int removedBlockKey : emission.removedBlockKeys()) {
				trappedChanges.addChangeToStore(
					new FulltextFieldLengthBlockRemoval(entityIndexPrimaryKey, locale, fieldId, removedBlockKey)
				);
			}
			fields[fieldId] = new FieldEntry(
				Objects.requireNonNull(index.getFieldKey(fieldId)),
				index.getLengthPivot(fieldId),
				index.isFieldRetired(fieldId),
				emission.blockKeys()
			);
		}
		trappedChanges.addChangeToStore(
			new FulltextIndexStoragePart(
				entityIndexPrimaryKey, locale, index.getAnalyzerName(), index.getDefaultLengthPivot(), fields,
				pages.highWaterPageSequence(), pages.orderedPageSequences(), null
			)
		);
	}

	@Override
	public void collectModifiedStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull EntityIndexManifest manifest,
		@Nonnull TrappedChanges trappedChanges
	) {
		this.fulltextIndexes.forEach(
			(locale, index) -> {
				if (index.isDirty()) {
					emitChanges(entityIndexPrimaryKey, locale, index, trappedChanges);
					// cleared here, not in resetDirty() - see the class javadoc
					index.resetDirty();
				} else if (index.getPersistedDictionaryPages().length == 0) {
					// never written to - it holds nothing and has no root to announce
					return;
				}
				manifest.addFulltextKey(new FulltextIndexKey(locale));
			}
		);
		// refresh the snapshot to what this flush leaves on disk, after reclaiming whatever left the map since the
		// last one - idempotent: the baseline-capture re-run finds the same map and nothing left
		final Map<Locale, PersistedFootprint> current = snapshotFootprints(this.fulltextIndexes);
		emitDroppedReclaims(entityIndexPrimaryKey, this.persistedFootprints, current, trappedChanges);
		this.persistedFootprints = current;
	}

	@Override
	public void resetDirty() {
		this.fulltextIndexes.forEach((locale, index) -> index.resetDirty());
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// TransactionalMap#removeLayer drops its own diff layer AND propagates into every value that is a
		// TransactionalLayerProducer, so the layers of each per-locale FulltextIndex are covered
		this.fulltextIndexes.removeLayer(transactionalLayer);
	}

	@Override
	public void emitPersistedFootprintRemovals(
		int entityIndexPrimaryKey,
		@Nonnull TrappedChanges trappedChanges
	) {
		// the whole entity index is dropped: nothing survives, so every persisted page and block goes; the roots are
		// manifest-listed and reclaimed by EntityIndex.emitVanishedRootRemovals. Reads the persisted baseline only.
		emitDroppedReclaims(entityIndexPrimaryKey, this.persistedFootprints, Map.of(), trappedChanges);
	}

	/**
	 * Returns the heap this component occupies, in bytes - its own object plus the footprint snapshot it alone holds,
	 * priced the way {@link HistogramIndexMapComponent#getHeapSizeInBytes()} prices its leaf-page baseline.
	 *
	 * The locales are interned by the JVM and the page registry belongs to its index, so neither is charged; nor are
	 * the per-field block arrays, which are the length tables' own on-disk sets, held by reference rather than copied.
	 * The dictionary page arrays are fresh copies and are charged.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// the fulltextIndexes / persistedFootprints slots - the map behind the first is charged by the owning index
		final long shell = layout.sizeOfObject(2L * layout.referenceSize());
		if (this.persistedFootprints.isEmpty()) {
			return shell;
		}
		return shell + MapHeapSize.sizeOf(
			this.persistedFootprints,
			locale -> 0L,
			footprint -> layout.sizeOfObject(3L * layout.referenceSize())
				+ layout.sizeOfArray(footprint.dictionaryPages().length, Integer.BYTES)
				+ layout.sizeOfArray(footprint.lengthBlocks().length, layout.referenceSize())
		);
	}

}
