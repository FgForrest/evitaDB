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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Abstract transactional data structure encapsulating a single named histogram definition.
 * Each instance manages {@link FilterIndex} entries (the actual bucketed histogram data) paired
 * with {@link io.evitadb.index.cardinality.AttributeCardinalityIndex} entries that track how many
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
