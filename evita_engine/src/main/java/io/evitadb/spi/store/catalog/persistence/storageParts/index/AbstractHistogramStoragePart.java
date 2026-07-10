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

import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;

/**
 * Shared identity base for the two storage parts of a single histogram index entry: the {@link HistogramIndexStoragePart}
 * root (bucket / range data) and its sibling {@link HistogramCardinalityStoragePart} (cardinality tracking). Both are
 * addressed by the SAME logical identity — the owning entity index primary key plus the (histogramName, locale) pair —
 * and therefore compute the SAME storage part primary key, disambiguated on disk only by their distinct record type.
 *
 * Hoisting that identity (the fields, the {@link #computeUniquePartId} packing and the
 * {@link #computeUniquePartIdAndSet} assign-once template) here makes the "these two siblings map to one id" contract
 * structural rather than a pair of hand-kept copies that must never drift.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ToString(of = {"histogramName", "locale", "entityIndexPrimaryKey"})
public abstract class AbstractHistogramStoragePart implements StoragePart {

	@Serial private static final long serialVersionUID = 6621565629544845943L;

	/**
	 * Unique id that identifies the owning {@link io.evitadb.index.EntityIndex}.
	 */
	@Getter private final int entityIndexPrimaryKey;

	/**
	 * Name of the histogram definition — part of the identity shared by both histogram parts.
	 */
	@Getter @Nonnull private final String histogramName;

	/**
	 * Locale for localized histograms, or `null` for non-localized — part of the identity shared by both histogram parts.
	 */
	@Getter @Nullable private final Locale locale;

	/**
	 * Id used for lookups in persistent storage for this particular container.
	 */
	@Nullable @Getter @Setter private Long storagePartPK;

	/**
	 * Creates the shared identity for a histogram part.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param histogramName         name of the histogram definition
	 * @param locale                locale for localized histograms, or `null`
	 * @param storagePartPK         the already-assigned storage part PK, or `null` when not yet assigned
	 */
	protected AbstractHistogramStoragePart(
		int entityIndexPrimaryKey,
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nullable Long storagePartPK
	) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.histogramName = histogramName;
		this.locale = locale;
		this.storagePartPK = storagePartPK;
	}

	/**
	 * Computes the unique storage part primary key by bit-joining the entity index primary key (high 32 bits) with the
	 * compressed integer id for the (histogramName, locale) pair (low 32 bits). Both histogram parts share this identity,
	 * differing on disk only by their record type.
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
		@Nullable Locale locale,
		@Nonnull KeyCompressor keyCompressor
	) {
		return NumberUtils.pack(
			entityIndexPrimaryKey,
			keyCompressor.getId(new HistogramIndexKey(histogramName, locale))
		);
	}

	@Override
	public final long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		final long computedUniquePartId = StoragePart.verifyUniquePartId(
			computeUniquePartId(this.entityIndexPrimaryKey, this.histogramName, this.locale, keyCompressor),
			this.storagePartPK
		);
		this.storagePartPK = computedUniquePartId;
		return computedUniquePartId;
	}

}
