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
import lombok.Getter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.util.Locale;

/**
 * Sibling storage part carrying the {@link AttributeCardinalityIndex} of a single histogram index entry (one histogram
 * name + locale pair), evicted out of {@link HistogramIndexStoragePart} so it can be (re)written independently of the
 * histogram's paged bucket / range leaf pages.
 *
 * The cardinality index gates histogram bucket boundary crossings (a value enters/leaves the bucket tree only on a
 * `0 -> 1` / `1 -> 0` per-owner transition) and therefore changes on nearly every reference add/remove, far more often
 * than the buckets themselves. Keeping it inline in the histogram root would re-emit the whole (paged) root on every
 * cardinality delta; evicting it here — keyed by the SAME {@link HistogramIndexKey} `(histogramName, locale)` identity
 * as the root, only under its own record type — decouples it entirely: a cardinality-only commit rewrites just this
 * part, and a bucket-content commit skips it. It is evicted whole (not itself paged): its backing
 * `PersistentTransactionalMap` is a CHAMP trie that cannot leaf-page without out-of-scope node paging.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
@ToString(callSuper = true)
public class HistogramCardinalityStoragePart extends AbstractHistogramStoragePart {

	@Serial private static final long serialVersionUID = -8172940638194057263L;

	/**
	 * Cardinality index tracking how many references contribute each histogram value per owner entity.
	 */
	@Getter @Nonnull private final AttributeCardinalityIndex cardinalityIndex;

	/**
	 * Creates a fresh cardinality sibling part whose storage part PK is not yet assigned (computed before persistence).
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param histogramName         name of the histogram definition
	 * @param locale                locale for localized histograms, or `null`
	 * @param cardinalityIndex      cardinality tracking index
	 */
	public HistogramCardinalityStoragePart(
		int entityIndexPrimaryKey,
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull AttributeCardinalityIndex cardinalityIndex
	) {
		this(entityIndexPrimaryKey, histogramName, locale, cardinalityIndex, null);
	}

	/**
	 * Canonical constructor carrying every field including the already-assigned storage part PK.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param histogramName         name of the histogram definition
	 * @param locale                locale for localized histograms, or `null`
	 * @param cardinalityIndex      cardinality tracking index
	 * @param storagePartPK         the already-assigned storage part PK, or `null`
	 */
	public HistogramCardinalityStoragePart(
		int entityIndexPrimaryKey,
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull AttributeCardinalityIndex cardinalityIndex,
		@Nullable Long storagePartPK
	) {
		super(entityIndexPrimaryKey, histogramName, locale, storagePartPK);
		this.cardinalityIndex = cardinalityIndex;
	}

}
