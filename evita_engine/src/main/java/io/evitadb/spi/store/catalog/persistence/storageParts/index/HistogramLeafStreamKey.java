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
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;

/**
 * Identifies the per-histogram page STREAM under the granular histogram layout. A granular
 * {@link io.evitadb.index.HistogramIndex} persists the bucket-tree (and optional range-tree) leaves of its embedded
 * {@code OwnerFilterIndex} as individual page storage parts keyed by `pack(streamId, pageSequence)`
 * ({@link HistogramIndexLeafPagePart} / {@link HistogramRangeIndexLeafPagePart}); `pageSequence` is unique only WITHIN a
 * stream, so the stream itself needs a unique, compact, restart-stable `int` id — and that id must encode the FULL
 * sub-index identity in a single `int` because the other half of the joined `long` PK is consumed by `pageSequence`.
 *
 * The id is obtained by registering this key with the catalog's {@link KeyCompressor}, exactly as the root part already
 * obtains its compressed id from `(histogramName, locale)`
 * ({@link HistogramIndexStoragePart#computeUniquePartId}). The difference is that the root part may spend both 32-bit
 * halves of its PK on identity (`pack(entityIndexPrimaryKey, compressor.getId(HistogramIndexKey))`), so its compressed
 * id need only cover the `(histogramName, locale)` pair; a leaf page may not, so its stream id must ALSO fold in the
 * `entityIndexPrimaryKey`. `HistogramLeafStreamKey` is therefore `(entityIndexPrimaryKey, histogramName, locale,
 * streamKind)` — the histogram analog of {@link LeafStreamKey}.
 *
 * A single histogram persists TWO independent page streams — its value bucket tree ({@link StreamKind#BUCKET}) and its
 * optional threshold range tree ({@link StreamKind#RANGE}) — that share the same `(entityIndexPrimaryKey, histogramName,
 * locale)` identity, so the {@link StreamKind} is what makes their {@link KeyCompressor} ids (and therefore their
 * leaf-page primary keys `pack(streamId, pageSequence)`) distinct.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@EqualsAndHashCode
public class HistogramLeafStreamKey implements Comparable<HistogramLeafStreamKey>, Serializable {
	@Serial private static final long serialVersionUID = -6184035729481027365L;

	/**
	 * Distinguishes the two independent page streams a single {@link io.evitadb.index.HistogramIndex} persists under the
	 * granular layout: its value bucket tree ({@link #BUCKET}) and its optional threshold range tree ({@link #RANGE}).
	 * Both streams share the same `(entityIndexPrimaryKey, histogramName, locale)` identity, so the stream kind is what
	 * makes their {@link KeyCompressor} ids — and therefore their leaf-page primary keys `pack(streamId, pageSequence)`
	 * — distinct; without it the two streams' page sequences would collide.
	 */
	public enum StreamKind {
		/**
		 * The embedded {@code InvertedIndex} value bucket tree's page stream.
		 */
		BUCKET,
		/**
		 * The embedded {@code RangeIndex} threshold tree's page stream.
		 */
		RANGE
	}

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex}. Folded into the stream identity because the same
	 * histogram name exists independently in many entity indexes.
	 */
	@Getter private final int entityIndexPrimaryKey;
	/**
	 * Name of the histogram definition this sub-index is related to, reusing the exact discriminator the root part
	 * already compresses.
	 */
	@Nonnull @Getter private final String histogramName;
	/**
	 * Locale for localized histograms, or `null` for non-localized — part of the histogram identity.
	 */
	@Nullable @Getter private final Locale locale;
	/**
	 * Which of the histogram's two page streams this key identifies — see {@link StreamKind}.
	 */
	@Nonnull @Getter private final StreamKind streamKind;

	/**
	 * Creates a histogram page-stream key from the owning entity-index primary key, the histogram name, the locale and
	 * the stream kind.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning {@link io.evitadb.index.EntityIndex}
	 * @param histogramName         name of the histogram definition the sub-index is related to
	 * @param locale                locale for localized histograms, or `null`
	 * @param streamKind            which of the histogram's two page streams this key identifies
	 */
	public HistogramLeafStreamKey(
		int entityIndexPrimaryKey,
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull StreamKind streamKind
	) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.histogramName = histogramName;
		this.locale = locale;
		this.streamKind = streamKind;
	}

	@Override
	public int compareTo(@Nonnull HistogramLeafStreamKey o) {
		final int pkResult = Integer.compare(this.entityIndexPrimaryKey, o.entityIndexPrimaryKey);
		if (pkResult != 0) {
			return pkResult;
		}
		final int nameResult = this.histogramName.compareTo(o.histogramName);
		if (nameResult != 0) {
			return nameResult;
		}
		// nulls first: a non-localized stream sorts before any localized one for the same histogram
		if (this.locale == null) {
			if (o.locale != null) {
				return -1;
			}
		} else if (o.locale == null) {
			return 1;
		} else {
			final int localeResult = this.locale.toString().compareTo(o.locale.toString());
			if (localeResult != 0) {
				return localeResult;
			}
		}
		return this.streamKind.compareTo(o.streamKind);
	}

}
