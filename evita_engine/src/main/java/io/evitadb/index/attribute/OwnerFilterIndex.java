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

package io.evitadb.index.attribute;

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.Range;
import io.evitadb.index.IndexHeapSize;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Comparator;
import java.util.function.Function;

/**
 * Owner variant of {@link FilterIndex}. It OWNS its {@link InvertedIndex} (and optional {@link RangeIndex}) and its own
 * transactional {@link #dirty} flag, fully participating in the commit cycle as a
 * {@link VoidTransactionMemoryProducer}. Used by the histogram subsystem ({@link io.evitadb.index.SimpleHistogramIndex},
 * {@link io.evitadb.index.LocalizedHistogramIndex}) and any standalone owner that is the sole writer of its data.
 *
 * Contrast with {@link FilterIndexView}, which is a stateless flyweight over an {@link AttributeIndex}-owned shared
 * tree and owns no transactional lifecycle.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@SuppressWarnings("rawtypes")
public final class OwnerFilterIndex extends FilterIndex implements VoidTransactionMemoryProducer<OwnerFilterIndex> {
	@Serial private static final long serialVersionUID = -6813305126746774103L;

	/**
	 * Unique transactional id minted once per owner instance — feeds the query-planner formula cache.
	 */
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Internal flag that tracks whether the index contents became dirty and need to be persisted.
	 */
	@Nonnull private final TransactionalBoolean dirty;

	/**
	 * Creates a new empty owner filter index for the given attribute, allocating its own value→ValueToRecord tree
	 * (and a {@link RangeIndex} for range-typed attributes).
	 *
	 * @param attributeIndexKey key identifying the attribute
	 * @param attributeType     the declared attribute type (array-aware)
	 */
	public OwnerFilterIndex(@Nonnull AttributeIndexKey attributeIndexKey, @Nonnull Class<?> attributeType) {
		this(attributeIndexKey, attributeType, 0);
	}

	/**
	 * Creates a new empty owner filter index for the given attribute, carrying the `BigDecimal` scaling decimal places.
	 *
	 * @param attributeIndexKey    key identifying the attribute
	 * @param attributeType        the declared attribute type (array-aware)
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 */
	public OwnerFilterIndex(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<?> attributeType,
		int indexedDecimalPlaces
	) {
		this(
			attributeIndexKey,
			attributeType,
			indexedDecimalPlaces,
			getNormalizer(plainTypeOf(attributeType), indexedDecimalPlaces),
			getComparator(attributeIndexKey, plainTypeOf(attributeType))
		);
	}

	/**
	 * Telescoping helper that builds the owned {@link InvertedIndex} and {@link RangeIndex} from the already-derived
	 * comparator / normalizer (so each is computed exactly once).
	 */
	private OwnerFilterIndex(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<?> attributeType,
		int indexedDecimalPlaces,
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Comparator<? extends Comparable> comparator
	) {
		this(
			attributeIndexKey,
			attributeType,
			indexedDecimalPlaces,
			new InvertedIndex(plainTypeOf(attributeType), normalizer, comparator, indexedDecimalPlaces),
			Range.class.isAssignableFrom(plainTypeOf(attributeType)) ? new RangeIndex() : null,
			comparator,
			normalizer
		);
	}

	/**
	 * Creates an owner filter index restored from persisted histogram points.
	 *
	 * @param attributeIndexKey key identifying the attribute
	 * @param valueToRecords    persisted value→ValueToRecord buckets
	 * @param rangeIndex        persisted range structure, or `null` for non-range attributes
	 * @param attributeType     the declared attribute type (array-aware)
	 */
	public OwnerFilterIndex(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull ValueToRecordBitmap[] valueToRecords,
		@Nullable RangeIndex rangeIndex,
		@Nonnull Class<?> attributeType
	) {
		this(attributeIndexKey, valueToRecords, rangeIndex, attributeType, 0);
	}

	/**
	 * Creates an owner filter index restored from persisted histogram points, carrying the `BigDecimal` scaling places.
	 *
	 * @param attributeIndexKey    key identifying the attribute
	 * @param valueToRecords       persisted value→ValueToRecord buckets
	 * @param rangeIndex           persisted range structure, or `null` for non-range attributes
	 * @param attributeType        the declared attribute type (array-aware)
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 */
	public OwnerFilterIndex(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull ValueToRecordBitmap[] valueToRecords,
		@Nullable RangeIndex rangeIndex,
		@Nonnull Class<?> attributeType,
		int indexedDecimalPlaces
	) {
		this(
			attributeIndexKey,
			valueToRecords,
			rangeIndex,
			attributeType,
			indexedDecimalPlaces,
			getNormalizer(plainTypeOf(attributeType), indexedDecimalPlaces),
			getComparator(attributeIndexKey, plainTypeOf(attributeType))
		);
	}

	/**
	 * Telescoping helper that builds the owned {@link InvertedIndex} from persisted buckets and the already-derived
	 * comparator / normalizer.
	 */
	private OwnerFilterIndex(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull ValueToRecordBitmap[] valueToRecords,
		@Nullable RangeIndex rangeIndex,
		@Nonnull Class<?> attributeType,
		int indexedDecimalPlaces,
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Comparator<? extends Comparable> comparator
	) {
		this(
			attributeIndexKey,
			attributeType,
			indexedDecimalPlaces,
			new InvertedIndex(plainTypeOf(attributeType), valueToRecords, normalizer, comparator, indexedDecimalPlaces),
			rangeIndex,
			comparator,
			normalizer
		);
	}

	/**
	 * Canonical constructor wiring the owned tree / range / comparator / normalizer and a fresh transactional dirty
	 * flag. Also reused by {@link #createCopyWithMergedTransactionalMemory} to assemble the committed copy.
	 */
	OwnerFilterIndex(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<?> attributeType,
		int indexedDecimalPlaces,
		@Nonnull InvertedIndex invertedIndex,
		@Nullable RangeIndex rangeIndex,
		@Nonnull Comparator<? extends Comparable> comparator,
		@Nonnull Function<Object, Serializable> normalizer
	) {
		super(
			attributeIndexKey, attributeType, indexedDecimalPlaces, invertedIndex, rangeIndex, comparator, normalizer
		);
		this.dirty = new TransactionalBoolean();
	}

	/**
	 * Rebuilds a boundary-stable owner filter index from an already-reconstructed {@link InvertedIndex} and optional
	 * {@link RangeIndex} — typically assembled from persisted leaf pages by a loader. Exposed as a public factory
	 * because the histogram loader lives in a different package and cannot reach the package-private canonical
	 * constructor; the comparator and normalizer are re-derived deterministically from the attribute identity, type and
	 * scale, so the resulting index matches one built by the normal constructors.
	 *
	 * @param attributeIndexKey    key identifying the attribute
	 * @param invertedIndex        the reconstructed value bucket tree (already boundary-stable)
	 * @param rangeIndex           the reconstructed range companion, or `null`
	 * @param attributeType        the declared attribute type (array-aware)
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 * @return the reconstructed owner filter index
	 */
	@Nonnull
	public static OwnerFilterIndex fromPersistedPages(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull InvertedIndex invertedIndex,
		@Nullable RangeIndex rangeIndex,
		@Nonnull Class<?> attributeType,
		int indexedDecimalPlaces
	) {
		final Class<?> plainType = plainTypeOf(attributeType);
		return new OwnerFilterIndex(
			attributeIndexKey,
			attributeType,
			indexedDecimalPlaces,
			invertedIndex,
			rangeIndex,
			getComparator(attributeIndexKey, plainType),
			getNormalizer(plainType, indexedDecimalPlaces)
		);
	}

	@Override
	public boolean isDirty() {
		return this.dirty.isTrue();
	}

	/**
	 * {@inheritDoc}
	 *
	 * An owner allocated its own value tree and its own range companion and charges both in full, on top of the
	 * fields and query memos every filter index shares. The tree's keys are attribute values this index owns, priced
	 * by {@link IndexHeapSize#OWNED_KEY_SIZER}, exactly as the owner unique index prices its own.
	 */
	@Override
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// the inherited id and the dirty slot, on top of the base's own fields
		long size = getSharedHeapSizeInBytes(Long.BYTES + layout.referenceSize())
			+ this.dirty.getHeapSizeInBytes()
			+ getInvertedIndex().getHeapSizeInBytes();
		final RangeIndex theRangeIndex = getRangeIndex();
		if (theRangeIndex != null) {
			size += theRangeIndex.getHeapSizeInBytes();
		}
		return size;
	}

	@Override
	protected void markDirty() {
		this.dirty.setToTrue();
	}

	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	@Nonnull
	@Override
	public OwnerFilterIndex createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		final RangeIndex theRangeIndex = getRangeIndex();
		return new OwnerFilterIndex(
			getAttributeIndexKey(),
			getAttributeType(),
			getIndexedDecimalPlaces(),
			transactionalLayer.getStateCopyWithCommittedChanges(getInvertedIndex()),
			theRangeIndex == null ? null : transactionalLayer.getStateCopyWithCommittedChanges(theRangeIndex),
			getComparator(),
			getNormalizer()
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		getInvertedIndex().removeLayer(transactionalLayer);
		final RangeIndex theRangeIndex = getRangeIndex();
		if (theRangeIndex != null) {
			theRangeIndex.removeLayer(transactionalLayer);
		}
		this.dirty.removeLayer(transactionalLayer);
	}

}
