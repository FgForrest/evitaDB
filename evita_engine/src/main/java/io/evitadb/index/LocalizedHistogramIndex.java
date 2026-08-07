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
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.result.CardinalityChange;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
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
	 * Per-locale filter index storing bucketed histogram values mapped to owner entity primary keys.
	 */
	@Nonnull private final TransactionalMap<Locale, OwnerFilterIndex> filterIndexes;

	/**
	 * Per-locale cardinality index tracking how many references contribute a given histogram value
	 * for each owner entity.
	 */
	@Nonnull private final TransactionalMap<Locale, AttributeCardinalityIndex> cardinalities;

	/**
	 * Creates a new empty localized histogram index.
	 *
	 * @param histogramName        the name of the histogram definition
	 * @param referenceName        the reference name for storage key construction
	 * @param valueType            the plain numeric type of the attribute values
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 */
	public LocalizedHistogramIndex(
		@Nonnull String histogramName,
		@Nonnull String referenceName,
		@Nonnull Class<? extends Serializable> valueType,
		int indexedDecimalPlaces
	) {
		super(histogramName, referenceName, valueType, indexedDecimalPlaces);
		this.filterIndexes = new TransactionalMap<>(
			CollectionUtils.createHashMap(4), OwnerFilterIndex.class, Function.identity()
		);
		this.cardinalities = new TransactionalMap<>(
			CollectionUtils.createHashMap(4), AttributeCardinalityIndex.class, Function.identity()
		);
	}

	/**
	 * Creates a localized histogram index from persisted data.
	 *
	 * @param histogramName        the name of the histogram definition
	 * @param referenceName        the reference name for storage key construction
	 * @param valueType            the plain numeric type of the attribute values
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 * @param filterIndexes        persisted filter indexes by locale
	 * @param cardinalities        persisted cardinality indexes by locale
	 */
	public LocalizedHistogramIndex(
		@Nonnull String histogramName,
		@Nonnull String referenceName,
		@Nonnull Class<? extends Serializable> valueType,
		int indexedDecimalPlaces,
		@Nonnull Map<Locale, OwnerFilterIndex> filterIndexes,
		@Nonnull Map<Locale, AttributeCardinalityIndex> cardinalities
	) {
		super(histogramName, referenceName, valueType, indexedDecimalPlaces);
		this.filterIndexes = new TransactionalMap<>(
			filterIndexes, OwnerFilterIndex.class, Function.identity()
		);
		this.cardinalities = new TransactionalMap<>(
			cardinalities, AttributeCardinalityIndex.class, Function.identity()
		);
	}

	@Override
	public void insertValue(
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int ownerPK
	) {
		final Locale theLocale = Objects.requireNonNull(
			locale, "Locale must not be null for localized histogram!");
		final Class<? extends Serializable> theValueType = getValueType();
		// canonicalize so insert and remove agree on the same key regardless of whether the upstream value
		// arrived as a raw BigDecimal or an already-scaled Integer
		final Serializable normalizedValue = normalizeValue(value);
		final AttributeCardinalityIndex cardinalityIdx = this.cardinalities.computeIfAbsent(
			theLocale,
			k -> new AttributeCardinalityIndex(theValueType)
		);
		if (cardinalityIdx.addRecord(normalizedValue, ownerPK) == CardinalityChange.BOUNDARY_CROSSED) {
			final OwnerFilterIndex filterIdx = this.filterIndexes.computeIfAbsent(
				theLocale,
				k -> new OwnerFilterIndex(
					new AttributeIndexKey(getReferenceName(), getHistogramName(), theLocale),
					theValueType,
					getIndexedDecimalPlaces()
				)
			);
			filterIdx.addRecord(ownerPK, normalizedValue);
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
		final Serializable normalizedValue = normalizeValue(value);
		if (cardinalityIdx.removeRecord(normalizedValue, ownerPK) == CardinalityChange.BOUNDARY_CROSSED) {
			final OwnerFilterIndex filterIdx = this.filterIndexes.get(theLocale);
			if (filterIdx != null) {
				filterIdx.removeRecord(ownerPK, normalizedValue);
				if (filterIdx.isEmpty()) {
					removeFilterIndex(theLocale);
				}
			}
		}
		if (cardinalityIdx.isEmpty()) {
			removeCardinalityIndex(theLocale);
		}
	}

	/**
	 * Removes the {@link FilterIndex} entry for the given locale and cleans up its transactional layer.
	 */
	private void removeFilterIndex(@Nonnull Locale locale) {
		final OwnerFilterIndex removedFilter = this.filterIndexes.remove(locale);
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

	@Nonnull
	@Override
	public Locale[] getLocales() {
		// detached copy - removeValue drops a locale's map entry as soon as it empties, so handing out the
		// live key set would expose the caller to ConcurrentModificationException while it walks the result
		return this.filterIndexes.keySet().toArray(new Locale[0]);
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
		for (Entry<Locale, OwnerFilterIndex> filterEntry : this.filterIndexes.entrySet()) {
			final Locale locale = filterEntry.getKey();
			final OwnerFilterIndex filterIndex = filterEntry.getValue();
			final AttributeCardinalityIndex cardinalityIndex = this.cardinalities.get(locale);
			// filterIndexes and cardinalities always share their locale key set (a locale gains/loses both together),
			// so cardinalityIndex is non-null here; fall back defensively to a fresh empty one just in case
			appendHistogramStorageParts(
				entityIndexPrimaryKey, locale, filterIndex,
				cardinalityIndex != null ? cardinalityIndex : new AttributeCardinalityIndex(getValueType()),
				trappedChanges
			);
		}
	}

	@Override
	public void collectPersistedLeafPages(@Nonnull Consumer<PersistedHistogramLeafPages> sink) {
		// one entry per live locale — mirrors collectStorageKeys (which lists filterIndexes.keySet()), so a locale still
		// present here is never mistaken for a drop while a pruned locale correctly falls out of the snapshot
		for (final Entry<Locale, OwnerFilterIndex> entry : this.filterIndexes.entrySet()) {
			sink.accept(persistedLeafPagesOf(entry.getKey(), entry.getValue()));
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * Both maps are keyed by {@link Locale}, which the JVM interns per language tag and the schema hands to every
	 * index built from it, so only the entry slots are charged for the keys.
	 */
	@Override
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// one per locale variant: a filter index does not charge its `attributeIndexKey`, on the ruling that the
		// attribute index filing it in a map owns that instance, but a histogram MINTS one per locale in
		// `insertValue` and is the only holder - see `SimpleHistogramIndex` for the same charge in the singular
		final long mintedKeys = this.filterIndexes.size() * layout.sizeOfObject(3L * layout.referenceSize());
		// the filterIndexes / cardinalities slots
		return getBaseHeapSizeInBytes(2L * layout.referenceSize())
			+ this.filterIndexes.getHeapSizeInBytes(locale -> 0L, OwnerFilterIndex::getHeapSizeInBytes)
			+ this.cardinalities.getHeapSizeInBytes(locale -> 0L, AttributeCardinalityIndex::getHeapSizeInBytes)
			+ mintedKeys;
	}

	@Nonnull
	@Override
	public HistogramIndex createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		return new LocalizedHistogramIndex(
			getHistogramName(),
			getReferenceName(),
			getValueType(),
			getIndexedDecimalPlaces(),
			transactionalLayer.getStateCopyWithCommittedChanges(this.filterIndexes),
			transactionalLayer.getStateCopyWithCommittedChanges(this.cardinalities)
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.filterIndexes.removeLayer(transactionalLayer);
		this.cardinalities.removeLayer(transactionalLayer);
	}

	@Override
	public String toString() {
		return "LocalizedHistogramIndex('" + getHistogramName() +
			"', locales=" + this.filterIndexes.size() + ")";
	}
}
