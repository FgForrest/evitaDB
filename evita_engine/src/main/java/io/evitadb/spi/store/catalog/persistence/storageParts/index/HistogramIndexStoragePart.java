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

package io.evitadb.spi.store.catalog.persistence.storageParts.index;

import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;

/**
 * Combined storage part for a single histogram index entry (one histogram name + locale pair). Bundles both the
 * bucketed filter data ({@link ValueToRecordBitmap} histogram points with optional {@link RangeIndex}) and the
 * cardinality tracking ({@link AttributeCardinalityIndex}) into a single persisted unit.
 *
 * Follows the same self-contained pattern as {@link FacetIndexStoragePart} — implements {@link StoragePart}
 * directly and computes its own storage part ID via {@link HistogramIndexKey} in the key compressor.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
@RequiredArgsConstructor
@AllArgsConstructor
@ToString(of = {"histogramName", "locale", "entityIndexPrimaryKey"})
public class HistogramIndexStoragePart implements StoragePart {

	@Serial private static final long serialVersionUID = 7294816253748291063L;

	/**
	 * Unique id that identifies the owning {@link io.evitadb.index.EntityIndex}.
	 */
	@Getter private final int entityIndexPrimaryKey;

	/**
	 * Name of the histogram definition.
	 */
	@Getter @Nonnull private final String histogramName;

	/**
	 * Locale for localized histograms, or `null` for non-localized.
	 */
	@Getter @Nullable private final java.util.Locale locale;

	/**
	 * The plain numeric type of the attribute values stored in this histogram.
	 */
	@Getter @Nonnull private final Class<?> valueType;

	/**
	 * Bucketed histogram data mapping attribute values to owner entity primary keys.
	 */
	@Getter @Nonnull private final ValueToRecordBitmap[] histogramPoints;

	/**
	 * Optional range index for range-type attributes.
	 */
	@Getter @Nullable private final RangeIndex rangeIndex;

	/**
	 * Cardinality index tracking how many references contribute each histogram value per owner entity.
	 */
	@Getter @Nonnull private final AttributeCardinalityIndex cardinalityIndex;

	/**
	 * Id used for lookups in persistent storage for this particular container.
	 */
	@Getter @Setter private Long storagePartPK;

	/**
	 * Computes the unique storage part primary key by bit-joining the entity index primary key (high 32 bits) with
	 * the compressed integer id for the (histogramName, locale) pair (low 32 bits).
	 *
	 * @param entityIndexPrimaryKey integer primary key of the owning entity index
	 * @param histogramName         the name of the histogram definition
	 * @param locale                the locale for localized histograms, or `null`
	 * @param keyCompressor         the key compressor for translating the composite key into a compact integer id
	 * @return a 64-bit storage part primary key
	 */
	public static long computeUniquePartId(
		int entityIndexPrimaryKey,
		@Nonnull String histogramName,
		@Nullable java.util.Locale locale,
		@Nonnull KeyCompressor keyCompressor
	) {
		return NumberUtils.join(
			entityIndexPrimaryKey,
			keyCompressor.getId(new HistogramIndexKey(histogramName, locale))
		);
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		final long computedUniquePartId = computeUniquePartId(
			this.entityIndexPrimaryKey, this.histogramName, this.locale, keyCompressor
		);
		final Long theUniquePartId = getStoragePartPK();
		if (theUniquePartId == null) {
			setStoragePartPK(computedUniquePartId);
		} else {
			Assert.isTrue(theUniquePartId == computedUniquePartId, "Unique part ids must never differ!");
		}
		return computedUniquePartId;
	}

}
