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
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.result.CardinalityChange;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Histogram index for non-localized attributes. Holds a single {@link FilterIndex} and a single
 * {@link AttributeCardinalityIndex} eagerly initialized at construction time. Both inner objects
 * are always non-null, eliminating null-check overhead on every insert/remove operation.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class SimpleHistogramIndex extends HistogramIndex {

	@Serial private static final long serialVersionUID = 2948571629384756123L;

	/**
	 * The single filter index holding bucketed histogram data for non-localized attributes.
	 */
	@Nonnull private final OwnerFilterIndex filterIndex;

	/**
	 * The single cardinality index tracking how many references contribute a given histogram value.
	 */
	@Nonnull private final AttributeCardinalityIndex cardinality;

	/**
	 * Creates a new empty non-localized histogram index with eagerly initialized inner structures.
	 *
	 * @param histogramName        the name of the histogram definition
	 * @param referenceName        the reference name for storage key construction
	 * @param valueType            the plain numeric type of the attribute values
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 */
	public SimpleHistogramIndex(
		@Nonnull String histogramName,
		@Nonnull String referenceName,
		@Nonnull Class<? extends Serializable> valueType,
		int indexedDecimalPlaces
	) {
		super(histogramName, referenceName, valueType, indexedDecimalPlaces);
		this.filterIndex = new OwnerFilterIndex(
			new AttributeIndexKey(referenceName, histogramName, null),
			valueType,
			indexedDecimalPlaces
		);
		this.cardinality = new AttributeCardinalityIndex(valueType);
	}

	/**
	 * Creates a non-localized histogram index from persisted data.
	 *
	 * @param histogramName        the name of the histogram definition
	 * @param referenceName        the reference name for storage key construction
	 * @param valueType            the plain numeric type of the attribute values
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 * @param filterIndex          the persisted filter index
	 * @param cardinality          the persisted cardinality index
	 */
	public SimpleHistogramIndex(
		@Nonnull String histogramName,
		@Nonnull String referenceName,
		@Nonnull Class<? extends Serializable> valueType,
		int indexedDecimalPlaces,
		@Nonnull OwnerFilterIndex filterIndex,
		@Nonnull AttributeCardinalityIndex cardinality
	) {
		super(histogramName, referenceName, valueType, indexedDecimalPlaces);
		this.filterIndex = filterIndex;
		this.cardinality = cardinality;
	}

	@Override
	public void insertValue(
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int ownerPK
	) {
		// canonicalize so insert and remove agree on the same key even when the upstream value arrives as a
		// raw BigDecimal on one path and an already-scaled Integer on another
		final Serializable normalizedValue = normalizeValue(value);
		if (this.cardinality.addRecord(normalizedValue, ownerPK) == CardinalityChange.BOUNDARY_CROSSED) {
			this.filterIndex.addRecord(ownerPK, normalizedValue);
		}
	}

	@Override
	public void removeValue(
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int ownerPK
	) {
		final Serializable normalizedValue = normalizeValue(value);
		if (this.cardinality.removeRecord(normalizedValue, ownerPK) == CardinalityChange.BOUNDARY_CROSSED) {
			this.filterIndex.removeRecord(ownerPK, normalizedValue);
		}
	}

	@Nullable
	@Override
	public FilterIndex getFilterIndex(@Nullable Locale locale) {
		return this.filterIndex.isEmpty() ? null : this.filterIndex;
	}

	@Override
	public boolean isEmpty() {
		return this.filterIndex.isEmpty();
	}

	@Nonnull
	@Override
	public Locale[] getLocales() {
		// the sole index of a non-localized histogram is filed under the `null` locale key
		return this.filterIndex.isEmpty() ? new Locale[0] : new Locale[]{null};
	}

	@Override
	public void collectStorageKeys(
		@Nonnull EntityIndexKey entityIndexKey,
		@Nonnull Set<HistogramIndexStorageKey> target
	) {
		if (!this.filterIndex.isEmpty() || !this.cardinality.isEmpty()) {
			target.add(new HistogramIndexStorageKey(entityIndexKey, getHistogramName(), null));
		}
	}

	@Override
	public void getModifiedStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull TrappedChanges trappedChanges
	) {
		appendHistogramStorageParts(entityIndexPrimaryKey, null, this.filterIndex, this.cardinality, trappedChanges);
	}

	@Override
	public void collectPersistedLeafPages(@Nonnull Consumer<PersistedHistogramLeafPages> sink) {
		// mirror collectStorageKeys's liveness predicate exactly: a persisted sub-index MUST be reported so the drop diff
		// never mistakes a live histogram for a dropped one and reclaims its pages
		if (!this.filterIndex.isEmpty() || !this.cardinality.isEmpty()) {
			sink.accept(persistedLeafPagesOf(null, this.filterIndex));
		}
	}

	@Nonnull
	@Override
	public HistogramIndex createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		return new SimpleHistogramIndex(
			getHistogramName(),
			getReferenceName(),
			getValueType(),
			getIndexedDecimalPlaces(),
			transactionalLayer.getStateCopyWithCommittedChanges(this.filterIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.cardinality)
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.filterIndex.removeLayer(transactionalLayer);
		this.cardinality.removeLayer(transactionalLayer);
	}

	@Override
	public String toString() {
		return "SimpleHistogramIndex('" + getHistogramName() + "', " +
			(this.filterIndex.isEmpty() ? "empty" : "has data") + ")";
	}
}
