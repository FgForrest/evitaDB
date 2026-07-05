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

import io.evitadb.index.EntityIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Dedicated storage key for histogram index entries stored inside {@link EntityIndexStoragePart}. Each key
 * identifies a single {@link HistogramIndexStoragePart} that bundles both filter and cardinality data for
 * one (histogramName, locale) pair.
 *
 * @param entityIndexKey the parent entity index key
 * @param histogramName  the name of the histogram definition
 * @param locale         the locale for localized histograms, or `null` for non-localized
 */
public record HistogramIndexStorageKey(
	@Nonnull EntityIndexKey entityIndexKey,
	@Nonnull String histogramName,
	@Nullable Locale locale
) implements Comparable<HistogramIndexStorageKey>, EntityIndexKeyAccessor {

	/**
	 * Computes the unique storage part primary key for this histogram index entry. Delegates to
	 * {@link AbstractHistogramStoragePart#computeUniquePartId(int, String, Locale, KeyCompressor)}.
	 *
	 * @param entityIndexPrimaryKey integer primary key of the owning entity index
	 * @param keyCompressor         the key compressor for translating the composite key into a compact integer id
	 * @return a 64-bit storage part primary key
	 */
	public long computeUniquePartId(int entityIndexPrimaryKey, @Nonnull KeyCompressor keyCompressor) {
		return AbstractHistogramStoragePart.computeUniquePartId(
			entityIndexPrimaryKey, this.histogramName, this.locale, keyCompressor
		);
	}

	@Override
	public int compareTo(@Nonnull HistogramIndexStorageKey o) {
		int cmp = this.entityIndexKey.compareTo(o.entityIndexKey);
		if (cmp != 0) {
			return cmp;
		}
		cmp = this.histogramName.compareTo(o.histogramName);
		if (cmp != 0) {
			return cmp;
		}
		if (this.locale == null) {
			if (o.locale != null) {
				return -1;
			}
		} else {
			if (o.locale == null) {
				return 1;
			} else {
				cmp = this.locale.toLanguageTag().compareTo(o.locale.toLanguageTag());
				return cmp;
			}
		}
		return 0;
	}

}
