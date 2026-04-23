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
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStoragePart;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static io.evitadb.core.transaction.Transaction.getTransactionalLayerMaintainer;
import static java.util.Optional.ofNullable;

/**
 * Histogram index for localized attributes. Holds per-locale {@link FilterIndex} and
 * {@link AttributeCardinalityIndex} entries in {@link TransactionalMap} instances keyed
 * by {@link Locale}.
 *
 * All locale parameters passed to this index must be non-null — calling with a null locale
 * is a programming error (use {@link SimpleHistogramIndex} for non-localized histograms).
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class LocalizedHistogramIndex extends HistogramIndex {

	@Serial private static final long serialVersionUID = 8375291046382957143L;

	/**
	 * Thread-local deferred locale removals used to avoid {@link java.util.ConcurrentModificationException}
	 * when {@link #removeValue} empties a locale's indexes during {@link #forEachLocale} iteration.
	 *
	 * - `null` — not inside {@link #forEachLocale}, removals happen immediately
	 * - `Collections.emptySet()` — inside iteration, no removals deferred yet (zero-cost sentinel)
	 * - real `HashSet` — inside iteration, lazily created on first deferred removal
	 */
	@SuppressWarnings("NonSerializableFieldInSerializableClass")
	private final ThreadLocal<Set<Locale>> deferredLocaleRemovals = new ThreadLocal<>();

	/**
	 * Per-locale filter index storing bucketed histogram values mapped to owner entity primary keys.
	 */
	@Nonnull private final TransactionalMap<Locale, FilterIndex> filterIndexes;

	/**
	 * Per-locale cardinality index tracking how many references contribute a given histogram value
	 * for each owner entity.
	 */
	@Nonnull private final TransactionalMap<Locale, AttributeCardinalityIndex> cardinalities;

	/**
	 * Creates a new empty localized histogram index.
	 *
	 * @param histogramName the name of the histogram definition
	 * @param referenceName the reference name for storage key construction
	 * @param valueType     the plain numeric type of the attribute values
	 */
	public LocalizedHistogramIndex(
		@Nonnull String histogramName,
		@Nonnull String referenceName,
		@Nonnull Class<? extends Serializable> valueType
	) {
		super(histogramName, referenceName, valueType);
		this.filterIndexes = new TransactionalMap<>(
			CollectionUtils.createHashMap(4), FilterIndex.class, Function.identity()
		);
		this.cardinalities = new TransactionalMap<>(
			CollectionUtils.createHashMap(4), AttributeCardinalityIndex.class, Function.identity()
		);
	}

	/**
	 * Creates a localized histogram index from persisted data.
	 *
	 * @param histogramName the name of the histogram definition
	 * @param referenceName the reference name for storage key construction
	 * @param valueType     the plain numeric type of the attribute values
	 * @param filterIndexes persisted filter indexes by locale
	 * @param cardinalities persisted cardinality indexes by locale
	 */
	public LocalizedHistogramIndex(
		@Nonnull String histogramName,
		@Nonnull String referenceName,
		@Nonnull Class<? extends Serializable> valueType,
		@Nonnull Map<Locale, FilterIndex> filterIndexes,
		@Nonnull Map<Locale, AttributeCardinalityIndex> cardinalities
	) {
		super(histogramName, referenceName, valueType);
		this.filterIndexes = new TransactionalMap<>(
			filterIndexes, FilterIndex.class, Function.identity()
		);
		this.cardinalities = new TransactionalMap<>(
			cardinalities, AttributeCardinalityIndex.class, Function.identity()
		);
	}

	@Override
	public void insertValue(
		@Nullable Locale locale,
		@Nonnull Number value,
		int ownerPK
	) {
		final Locale theLocale = Objects.requireNonNull(
			locale, "Locale must not be null for localized histogram!");
		final Class<? extends Serializable> theValueType = getValueType();
		final AttributeCardinalityIndex cardinalityIdx = this.cardinalities.computeIfAbsent(
			theLocale,
			k -> new AttributeCardinalityIndex(theValueType)
		);
		if (cardinalityIdx.addRecord(value, ownerPK)) {
			final FilterIndex filterIdx = this.filterIndexes.computeIfAbsent(
				theLocale,
				k -> new FilterIndex(
					new AttributeIndexKey(getReferenceName(), getHistogramName(), theLocale),
					theValueType
				)
			);
			filterIdx.addRecord(ownerPK, value);
		}
	}

	@Override
	public void removeValue(
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int ownerPK
	) {
		final Locale theLocale = Objects.requireNonNull(
			locale, "Locale must not be null for localized histogram!"
		);
		final AttributeCardinalityIndex cardinalityIdx = this.cardinalities.get(theLocale);
		if (cardinalityIdx == null) {
			throw new GenericEvitaInternalError(
				"Cannot remove value from localized histogram — no data exists for locale `" + theLocale + "`!"
			);
		}
		if (cardinalityIdx.removeRecord(value, ownerPK)) {
			final FilterIndex filterIdx = this.filterIndexes.get(theLocale);
			if (filterIdx != null) {
				filterIdx.removeRecord(ownerPK, value);
				if (filterIdx.isEmpty()) {
					if (localeRemovalDeferred(theLocale)) {
						removeFilterIndex(theLocale);
					}
				}
			}
		}
		if (cardinalityIdx.isEmpty()) {
			if (localeRemovalDeferred(theLocale)) {
				removeCardinalityIndex(theLocale);
			}
		}
	}

	/**
	 * Attempts to defer locale map-entry removal when called inside {@link #forEachLocale} iteration.
	 *
	 * @param locale the locale whose entries should be removed
	 * @return `true` if removal should proceed immediately, `false` if it was deferred
	 */
	private boolean localeRemovalDeferred(@Nonnull Locale locale) {
		final Set<Locale> deferred = this.deferredLocaleRemovals.get();
		if (deferred != null) {
			if (deferred.isEmpty()) {
				// sentinel — lazily allocate a real set on first deferred removal
				final Set<Locale> realSet = CollectionUtils.createHashSet(4);
				realSet.add(locale);
				this.deferredLocaleRemovals.set(realSet);
			} else {
				deferred.add(locale);
			}
			return false;
		}
		return true;
	}

	/**
	 * Removes the {@link FilterIndex} entry for the given locale and cleans up its transactional layer.
	 */
	private void removeFilterIndex(@Nonnull Locale locale) {
		final FilterIndex removedFilter = this.filterIndexes.remove(locale);
		if (removedFilter == null) {
			throw new GenericEvitaInternalError(
				"FilterIndex for locale " + locale + " doesn't exists!"
			);
		} else {
			ofNullable(getTransactionalLayerMaintainer())
				.ifPresent(removedFilter::removeLayer);
		}
	}

	/**
	 * Removes the {@link AttributeCardinalityIndex} entry for the given locale and cleans up
	 * its transactional layer.
	 */
	private void removeCardinalityIndex(@Nonnull Locale locale) {
		final AttributeCardinalityIndex removedCardinality = this.cardinalities.remove(locale);
		if (removedCardinality == null) {
			throw new GenericEvitaInternalError(
				"AttributeCardinalityIndex for locale " + locale + " doesn't exists!"
			);
		} else {
			ofNullable(getTransactionalLayerMaintainer())
				.ifPresent(removedCardinality::removeLayer);
		}
	}

	@Nullable
	@Override
	public FilterIndex getFilterIndex(@Nullable Locale locale) {
		return locale == null ? null : this.filterIndexes.get(locale);
	}

	@Override
	public boolean isEmpty() {
		return this.filterIndexes.isEmpty();
	}

	@Override
	public void forEachLocale(@Nonnull BiConsumer<String, Locale> consumer) {
		final String name = getHistogramName();
		// install sentinel so removeValue defers map-entry removals instead of modifying during iteration
		this.deferredLocaleRemovals.set(Collections.emptySet());
		try {
			for (Locale locale : this.filterIndexes.keySet()) {
				consumer.accept(name, locale);
			}
		} finally {
			final Set<Locale> deferred = this.deferredLocaleRemovals.get();
			this.deferredLocaleRemovals.remove();
			if (!deferred.isEmpty()) {
				for (Locale locale : deferred) {
					final FilterIndex filterIdx = this.filterIndexes.get(locale);
					if (filterIdx != null && filterIdx.isEmpty()) {
						removeFilterIndex(locale);
					}
					final AttributeCardinalityIndex cardinalityIdx = this.cardinalities.get(locale);
					if (cardinalityIdx != null && cardinalityIdx.isEmpty()) {
						removeCardinalityIndex(locale);
					}
				}
			}
		}
	}

	@Override
	public void collectStorageKeys(
		@Nonnull EntityIndexKey entityIndexKey,
		@Nonnull Set<HistogramIndexStorageKey> target
	) {
		final String histogramName = getHistogramName();
		for (Locale locale : this.filterIndexes.keySet()) {
			target.add(new HistogramIndexStorageKey(entityIndexKey, histogramName, locale));
		}
	}

	@Override
	public void getModifiedStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull TrappedChanges trappedChanges
	) {
		final String histogramName = getHistogramName();
		for (Entry<Locale, FilterIndex> filterEntry : this.filterIndexes.entrySet()) {
			final Locale locale = filterEntry.getKey();
			final FilterIndex filterIndex = filterEntry.getValue();
			final AttributeCardinalityIndex cardinalityIndex = this.cardinalities.get(locale);
			if (filterIndex.isDirty() || (cardinalityIndex != null && cardinalityIndex.isDirty())) {
				trappedChanges.addChangeToStore(
					new HistogramIndexStoragePart(
						entityIndexPrimaryKey, histogramName, locale, getValueType(),
						filterIndex.getInvertedIndex().getValueToRecordBitmap(),
						filterIndex.getRangeIndex(),
						cardinalityIndex != null ? cardinalityIndex : new AttributeCardinalityIndex(getValueType())
					)
				);
			}
		}
	}

	@Nonnull
	@Override
	public HistogramIndex createCopyWithMergedTransactionalMemory(
		@Nullable Void layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		return new LocalizedHistogramIndex(
			getHistogramName(),
			getReferenceName(),
			getValueType(),
			transactionalLayer.getStateCopyWithCommittedChanges(this.filterIndexes),
			transactionalLayer.getStateCopyWithCommittedChanges(this.cardinalities)
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.filterIndexes.removeLayer(transactionalLayer);
		this.cardinalities.removeLayer(transactionalLayer);
	}

	@Override
	public String toString() {
		return "LocalizedHistogramIndex('" + getHistogramName() +
			"', locales=" + this.filterIndexes.size() + ")";
	}
}
