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
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramCardinalityStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramLeafStreamKey.StreamKind;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramRangeIndexLeafPagePart;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Abstract transactional data structure encapsulating a single named histogram definition.
 * Each instance manages {@link FilterIndex} entries (the actual bucketed histogram data) paired
 * with {@link AttributeCardinalityIndex} entries that track how many
 * references contribute a given histogram value for each owner entity.
 *
 * Two concrete implementations exist:
 * - {@link SimpleHistogramIndex} for non-localized histograms (single FilterIndex + cardinality)
 * - {@link LocalizedHistogramIndex} for localized histograms (per-locale maps)
 *
 * The cardinality tracking is essential: a value is added to the FilterIndex only when its
 * cardinality transitions from 0 to 1, and removed only when cardinality drops back to 0.
 *
 * Follows the {@link io.evitadb.index.hierarchy.HierarchyIndex} pattern as a
 * {@link VoidTransactionMemoryProducer}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public abstract class HistogramIndex
	implements VoidTransactionMemoryProducer<HistogramIndex>,
	IndexDataStructure, Serializable {

	@Serial private static final long serialVersionUID = -7291547628319482153L;

	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

	/**
	 * Immutable name of the histogram definition this index represents.
	 */
	@Nonnull @Getter private final String histogramName;

	/**
	 * Immutable reference name, needed for constructing {@link FilterIndex} instances.
	 */
	@Nonnull @Getter private final String referenceName;

	/**
	 * The plain numeric type of the attribute values stored in this histogram. Known at construction
	 * time from the attribute schema's {@link io.evitadb.api.requestResponse.schema.AttributeSchemaContract#getPlainType()}.
	 */
	@Nonnull @Getter private final Class<? extends Serializable> valueType;

	/**
	 * Decimal-places scale used by a `BigDecimal` source attribute's filter index to encode values into the
	 * order-preserving scaled `int` it stores; `0` for every other value type. Carried so the histogram index
	 * canonicalizes raw `BigDecimal` values into the very same key form the source buckets hold.
	 */
	@Getter private final int indexedDecimalPlaces;

	/**
	 * Canonicalizes a histogram value into the exact key form the inner {@link FilterIndex} stores. For a
	 * `BigDecimal` value type this scales the value into the order-preserving `Integer` the source filter index
	 * uses at its `indexedDecimalPlaces` (matching `FilterIndex.getNormalizer`); every other type passes through
	 * unchanged. This lets insert and remove agree on the same key regardless of whether the upstream value
	 * arrived already scaled (`Integer`) or as a raw `BigDecimal`. The user-facing `BigDecimal` boundaries are
	 * reconstructed from the source attribute's real `indexedDecimalPlaces` at query time.
	 */
	@SuppressWarnings("TransientFieldNotInitialized")
	@Nonnull private final transient Function<Object, Serializable> valueNormalizer;

	protected HistogramIndex(
		@Nonnull String histogramName,
		@Nonnull String referenceName,
		@Nonnull Class<? extends Serializable> valueType,
		int indexedDecimalPlaces
	) {
		this.histogramName = histogramName;
		this.referenceName = referenceName;
		this.valueType = valueType;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.valueNormalizer = FilterIndex.getNormalizer(valueType, indexedDecimalPlaces);
	}

	/**
	 * Canonicalizes a raw histogram value into the key form the inner filter / cardinality indexes store, so
	 * insert and remove always agree on the same key (see {@link #valueNormalizer}).
	 *
	 * @param value the raw histogram value
	 * @return the canonicalized value
	 */
	@Nonnull
	public final Serializable normalizeValue(@Nonnull Serializable value) {
		return this.valueNormalizer.apply(value);
	}

	/**
	 * Inserts a histogram value for the given owner entity. The cardinality index is consulted
	 * to decide when to actually add to the filter index (only on 0 -> 1 transition).
	 *
	 * @param locale  the locale for localized histograms, or `null` for non-localized
	 * @param value   the histogram value in its original type (a `Number` for plain numeric attributes
	 *                or a `Range` instance for Range-typed attributes)
	 * @param ownerPK the primary key of the owner entity
	 */
	public abstract void insertValue(
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int ownerPK
	);

	/**
	 * Removes a histogram value for the given owner entity. The cardinality index is consulted
	 * to decide when to actually remove from the filter index (only when cardinality drops to zero).
	 *
	 * @param locale  the locale for localized histograms, or `null` for non-localized
	 * @param value   the histogram value in its original numeric type
	 * @param ownerPK the primary key of the owner entity
	 */
	public abstract void removeValue(
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int ownerPK
	);

	/**
	 * Returns the filter index for the given locale, or `null` if no data exists.
	 *
	 * @param locale the locale for localized histograms, or `null` for non-localized
	 * @return the filter index, or `null`
	 */
	@Nullable
	public abstract FilterIndex getFilterIndex(@Nullable Locale locale);

	/**
	 * Returns `true` if this histogram index contains no data.
	 */
	public abstract boolean isEmpty();

	/**
	 * Invokes the given consumer for each locale that has data in this index. For non-localized
	 * histograms, the consumer is called once with `null` locale. For localized histograms, it is
	 * called once per locale.
	 *
	 * @param consumer accepts (histogramName, locale) pairs
	 */
	public abstract void forEachLocale(@Nonnull BiConsumer<String, Locale> consumer);

	/**
	 * Populates the given set with storage keys for all filter and cardinality indexes.
	 *
	 * @param entityIndexKey the parent entity index key
	 * @param target         the set to populate
	 */
	public abstract void collectStorageKeys(
		@Nonnull EntityIndexKey entityIndexKey,
		@Nonnull Set<HistogramIndexStorageKey> target
	);

	/**
	 * Writes modified filter and cardinality storage parts into the given trapped changes.
	 *
	 * @param entityIndexPrimaryKey the primary key of the parent entity index
	 * @param trappedChanges        the target for storage part changes
	 */
	public abstract void getModifiedStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull TrappedChanges trappedChanges
	);

	/**
	 * Reports, for every `(histogram, locale)` sub-index that currently owns persisted data, the leaf-page sequences it
	 * WILL have on disk once the in-flight commit is durable (empty arrays for a SINGLE / inline sub-index that owns no
	 * leaf pages). The owning entity index's histogram-map component snapshots these at construction and at the end of
	 * every flush, so when a whole histogram (or a single locale) is dropped from the map — after which this index's own
	 * flush never runs again — the now-orphaned leaf pages and the evicted cardinality sibling can still be reclaimed
	 * instead of leaking forever on the append-only OffsetIndex.
	 *
	 * The liveness predicate MUST match {@link #collectStorageKeys} exactly: a sub-index announced to the manifest has to
	 * be reported here too, or a surviving sub-index would be mistaken for a drop and its live pages spuriously removed.
	 *
	 * @param sink accepts one {@link PersistedHistogramLeafPages} per live `(histogram, locale)` sub-index
	 */
	public abstract void collectPersistedLeafPages(@Nonnull Consumer<PersistedHistogramLeafPages> sink);

	/**
	 * Builds the {@link PersistedHistogramLeafPages} snapshot entry for one `(histogram, locale)` sub-index from its
	 * embedded {@link OwnerFilterIndex}: the bucket axis' current on-disk leaf-page sequences plus, for a range-typed
	 * histogram, its range companion's — both empty when the axis is SINGLE / never paged.
	 *
	 * @param locale      the locale of this sub-index, or `null` for a non-localized histogram
	 * @param filterIndex the embedded filter index holding the bucket / range trees
	 * @return the leaf-page snapshot for this sub-index
	 */
	@Nonnull
	protected final PersistedHistogramLeafPages persistedLeafPagesOf(
		@Nullable Locale locale, @Nonnull OwnerFilterIndex filterIndex
	) {
		final int[] bucketPageSequences = filterIndex.getInvertedIndex().currentLeafPageSequences();
		final RangeIndex rangeIndex = filterIndex.getRangeIndex();
		final int[] rangePageSequences =
			rangeIndex != null ? rangeIndex.currentLeafPageSequences() : ArrayUtils.EMPTY_INT_ARRAY;
		return new PersistedHistogramLeafPages(
			new HistogramIndexKey(this.histogramName, locale), bucketPageSequences, rangePageSequences
		);
	}

	/**
	 * The on-disk leaf-page sequences of one persisted `(histogram, locale)` sub-index — the histogram analogue of the
	 * per-key snapshot {@link io.evitadb.index.attribute.AttributeIndex} keeps for its paged families. Diffed by the
	 * histogram-map component against the surviving key set to reclaim the pages of a whole-histogram / per-locale drop.
	 *
	 * @param key                 the `(histogram name, locale)` identity of the sub-index
	 * @param bucketPageSequences the bucket axis' on-disk leaf-page sequences (empty for a SINGLE / never-paged bucket axis)
	 * @param rangePageSequences  the range axis' on-disk leaf-page sequences (empty when there is no range axis or it is SINGLE)
	 */
	public record PersistedHistogramLeafPages(
		@Nonnull HistogramIndexKey key,
		@Nonnull int[] bucketPageSequences,
		@Nonnull int[] rangePageSequences
	) {
	}

	/**
	 * Emits the paged storage parts for ONE `(histogram, locale)` sub-index into `sink`: the cardinality sibling
	 * (independently, whenever its cardinality changed) and — only when the embedded filter index actually changed —
	 * the bucket / range leaf pages plus the fused root (skipped when both axes are page-stable). Mirrors
	 * {@link FilterIndex#appendStorageParts} retargeted to histogram parts, and is shared by
	 * both concrete histogram implementations.
	 *
	 * The bucket/range fold runs ONLY when {@link OwnerFilterIndex#isDirty()} — a cardinality-only commit (a
	 * non-boundary-crossing reference add/remove) leaves the filter index clean, and
	 * {@link InvertedIndex#collectChangedPages()} must not be called on a clean index. The cardinality sibling is
	 * decoupled from the bucket/range axes and gated on its own {@link AttributeCardinalityIndex#isDirty()}.
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param locale                the locale of this sub-index, or `null` for a non-localized histogram
	 * @param filterIndex           the embedded filter index holding the bucket / range trees
	 * @param cardinality           the cardinality index gating the histogram buckets
	 * @param sink                  the trapped-changes accumulator for this commit
	 */
	protected final void appendHistogramStorageParts(
		int entityIndexPrimaryKey,
		@Nullable Locale locale,
		@Nonnull OwnerFilterIndex filterIndex,
		@Nonnull AttributeCardinalityIndex cardinality,
		@Nonnull TrappedChanges sink
	) {
		final String name = this.histogramName;
		// the cardinality sibling is decoupled from the bucket/range axes: emit it on its own dirty gate
		if (cardinality.isDirty()) {
			sink.addChangeToStore(
				new HistogramCardinalityStoragePart(entityIndexPrimaryKey, name, locale, cardinality)
			);
		}
		// run the bucket/range fold ONLY when the filter index actually changed (see method contract)
		if (!filterIndex.isDirty()) {
			return;
		}

		// BUCKET axis
		final InvertedIndex invertedIndex = filterIndex.getInvertedIndex();
		final boolean bucketPaged;
		final int bucketHighWater;
		final int[] bucketPages;
		final boolean bucketListChanged;
		final ValueToRecordBitmap[] inlineBuckets;
		if (invertedIndex.isPaged()) {
			final PageEmission<InvertedIndex.LeafPage> emission = invertedIndex.collectChangedPages();
			for (final InvertedIndex.LeafPage page : emission.changedPages()) {
				sink.addChangeToStore(
					new HistogramIndexLeafPagePart(
						entityIndexPrimaryKey, name, locale, page.pageSequence(), page.buckets()
					)
				);
			}
			// remove the leaf pages a merge dropped this commit so they don't leak
			for (final int freedPageSequence : emission.freedPageSequences()) {
				sink.addChangeToStore(
					new HistogramIndexLeafPageRemoval(entityIndexPrimaryKey, name, locale, StreamKind.BUCKET, freedPageSequence)
				);
			}
			bucketPaged = true;
			bucketHighWater = emission.highWaterPageSequence();
			bucketPages = emission.orderedPageSequences();
			bucketListChanged = emission.pageListChanged();
			inlineBuckets = HistogramIndexStoragePart.emptyHistogram();
		} else {
			// SINGLE shape (possibly just collapsed from PAGED): remove every prior leaf page BEFORE forgetting the
			// stream, then carry the whole bucket array inline on the root. Reclaim against the pages the previous flush
			// left ON DISK: its STAGED set while it is still unpublished (a warm-up flush never reaches the commit-merge
			// that publishes), else the published set. Reading only the published set reclaims nothing for a whole warm-up
			// and leaks every page the collapsed stream ever wrote — the same set the drop path above already reclaims from.
			for (final int freedPageSequence : invertedIndex.currentLeafPageSequences()) {
				sink.addChangeToStore(
					new HistogramIndexLeafPageRemoval(entityIndexPrimaryKey, name, locale, StreamKind.BUCKET, freedPageSequence)
				);
			}
			invertedIndex.forgetPageStream();
			bucketPaged = false;
			bucketHighWater = -1;
			bucketPages = HistogramIndexStoragePart.noLeafPages();
			bucketListChanged = true; // the inline histogram rides the root, forcing a root re-emit
			inlineBuckets = invertedIndex.getValueToRecordBitmap();
		}

		// RANGE axis (present only for range-typed histograms)
		final RangeIndex rangeIndex = filterIndex.getRangeIndex();
		final boolean rangePaged;
		final int rangeHighWater;
		final int[] rangePages;
		final boolean rangeListChanged;
		final RangeIndex inlineRange;
		if (rangeIndex != null && rangeIndex.isPaged()) {
			final PageEmission<RangeIndex.RangePage> emission = rangeIndex.collectChangedPages();
			for (final RangeIndex.RangePage page : emission.changedPages()) {
				sink.addChangeToStore(
					new HistogramRangeIndexLeafPagePart(
						entityIndexPrimaryKey, name, locale, page.pageSequence(), page.points()
					)
				);
			}
			for (final int freedPageSequence : emission.freedPageSequences()) {
				sink.addChangeToStore(
					new HistogramIndexLeafPageRemoval(entityIndexPrimaryKey, name, locale, StreamKind.RANGE, freedPageSequence)
				);
			}
			rangePaged = true;
			rangeHighWater = emission.highWaterPageSequence();
			rangePages = emission.orderedPageSequences();
			rangeListChanged = emission.pageListChanged();
			inlineRange = null;
		} else {
			if (rangeIndex != null) {
				// SINGLE range that may have just collapsed from PAGED: remove prior leaf pages before forgetting, against
				// the staged-or-published set for the same reason as the bucket axis above
				for (final int freedPageSequence : rangeIndex.currentLeafPageSequences()) {
					sink.addChangeToStore(
						new HistogramIndexLeafPageRemoval(entityIndexPrimaryKey, name, locale, StreamKind.RANGE, freedPageSequence)
					);
				}
				rangeIndex.forgetPageStream();
			}
			rangePaged = false;
			rangeHighWater = -1;
			rangePages = HistogramIndexStoragePart.noLeafPages();
			rangeListChanged = true;
			inlineRange = rangeIndex;
		}

		// root-skip: when both axes are externalized to leaf pages and neither list changed this commit, the root is
		// byte-identical to disk and can be skipped (collapsing the steady-state root cost to O(1))
		final boolean bucketRootStable = bucketPaged && !bucketListChanged;
		final boolean rangeRootStable = rangePaged ? !rangeListChanged : inlineRange == null;
		if (bucketRootStable && rangeRootStable) {
			return;
		}

		sink.addChangeToStore(
			new HistogramIndexStoragePart(
				entityIndexPrimaryKey, name, locale, this.valueType,
				inlineBuckets, inlineRange, this.indexedDecimalPlaces,
				bucketPaged, bucketHighWater, bucketPages,
				rangePaged, rangeHighWater, rangePages, null
			)
		);
	}

	@Override
	public void resetDirty() {
		// no own dirty flag — sub-structures track their own dirtiness
	}

	@Nonnull
	@Override
	public abstract HistogramIndex createCopyWithMergedTransactionalMemory(
		@Nullable Void layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	);

	@Override
	public abstract void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer);
}
