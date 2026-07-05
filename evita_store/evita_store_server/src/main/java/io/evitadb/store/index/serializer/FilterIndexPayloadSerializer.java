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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.store.index.serializer.PagedStreamMetadataSerializer.PagedStreamMetadata;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shared read/write of the bucketed filter payload that both {@link FilterIndexStoragePartSerializer} and
 * {@link HistogramIndexStoragePartSerializer} persist after their (differing) identity header: the inline
 * {@link ValueToRecordBitmap} bucket points (empty for a bucket-`PAGED` axis), the optional inline {@link RangeIndex}
 * companion (`null` when the range axis is paged or absent), the frozen `indexedDecimalPlaces` scale, and the two
 * independent bucket / range `PAGED`/`SINGLE` page-stream metadata axes (each delegated to
 * {@link PagedStreamMetadataSerializer}).
 *
 * This is the persistent shape of a {@code FilterIndex}; the histogram root reuses it verbatim because it embeds an
 * {@code OwnerFilterIndex}. Extracting it here keeps the two serializers byte-identical over this region by construction
 * — each writes/reads its own identity fields, then defers the whole payload tail to this single helper.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class FilterIndexPayloadSerializer {

	private FilterIndexPayloadSerializer() {
		throw new UnsupportedOperationException("This class is not intended to be instantiated!");
	}

	/**
	 * Writes the payload tail: the inline bucket points, the optional inline range companion, the frozen decimal-places
	 * scale, and the bucket then range page-stream metadata axes. Byte-identical to the prior hand-written blocks in both
	 * serializers.
	 *
	 * @param kryo                        the kryo instance
	 * @param output                      the target output
	 * @param points                      the inline bucket points (empty when the bucket axis is paged)
	 * @param rangeIndex                  the inline range companion, or `null` when range-paged or absent
	 * @param indexedDecimalPlaces        the frozen `indexedDecimalPlaces` scale (0 for non-`BigDecimal` source types)
	 * @param bucketPaged                 whether the bucket axis is paged
	 * @param bucketHighWaterPageSequence the bucket stream high-water page sequence (ignored when not bucket-paged)
	 * @param bucketLeafPageSequences     the ordered live bucket leaf-page sequences (ignored when not bucket-paged)
	 * @param rangePaged                  whether the range axis is paged
	 * @param rangeHighWaterPageSequence  the range stream high-water page sequence (ignored when not range-paged)
	 * @param rangeLeafPageSequences      the ordered live range leaf-page sequences (ignored when not range-paged)
	 */
	public static void write(
		@Nonnull Kryo kryo,
		@Nonnull Output output,
		@Nonnull ValueToRecordBitmap[] points,
		@Nullable RangeIndex rangeIndex,
		int indexedDecimalPlaces,
		boolean bucketPaged,
		int bucketHighWaterPageSequence,
		@Nonnull int[] bucketLeafPageSequences,
		boolean rangePaged,
		int rangeHighWaterPageSequence,
		@Nonnull int[] rangeLeafPageSequences
	) {
		// SINGLE bucket axis carries every bucket inline here; a bucket-PAGED axis carries none (length 0) — its buckets
		// live in individual leaf pages.
		output.writeInt(points.length);
		for (final ValueToRecordBitmap point : points) {
			kryo.writeObject(output, point);
		}

		// the inline range companion: present only when the range is NOT paged (a range-PAGED part writes its threshold
		// tree as individual leaf pages instead, and carries a null inline range)
		final boolean hasRangeIndex = rangeIndex != null;
		output.writeBoolean(hasRangeIndex);
		if (hasRangeIndex) {
			kryo.writeObject(output, rangeIndex);
		}

		// the frozen `indexedDecimalPlaces` scale (0 for non-BigDecimal source types)
		output.writeVarInt(indexedDecimalPlaces, true);

		// the bucket SINGLE/PAGED discriminator + the PAGED page-stream metadata. The page-stream id is NOT persisted —
		// it is recomputed at load from the sub-index identity.
		PagedStreamMetadataSerializer.writeOptional(
			output, bucketPaged, bucketHighWaterPageSequence, bucketLeafPageSequences
		);

		// the RANGE SINGLE/PAGED discriminator + the range page-stream metadata, independent of the bucket axis. The
		// range stream id is recomputed at load with StreamKind.RANGE.
		PagedStreamMetadataSerializer.writeOptional(
			output, rangePaged, rangeHighWaterPageSequence, rangeLeafPageSequences
		);
	}

	/**
	 * Reads the payload tail written by {@link #write} into a {@link FilterIndexPayload}, positioned immediately after
	 * the caller has consumed its own identity header.
	 *
	 * @param kryo  the kryo instance
	 * @param input the source input
	 * @return the decoded bucket points, optional range companion, frozen scale and both page-stream metadata axes
	 */
	@Nonnull
	public static FilterIndexPayload read(@Nonnull Kryo kryo, @Nonnull Input input) {
		final int pointCount = input.readInt();
		final ValueToRecordBitmap[] points = new ValueToRecordBitmap[pointCount];
		for (int i = 0; i < pointCount; i++) {
			points[i] = kryo.readObject(input, ValueToRecordBitmap.class);
		}

		final boolean hasRangeIndex = input.readBoolean();
		final RangeIndex rangeIndex = hasRangeIndex ? kryo.readObject(input, RangeIndex.class) : null;

		// the frozen `indexedDecimalPlaces` scale (0 for non-BigDecimal source types)
		final int indexedDecimalPlaces = input.readVarInt(true);

		// the bucket SINGLE/PAGED discriminator + the PAGED page-stream metadata
		final PagedStreamMetadata bucketMetadata = PagedStreamMetadataSerializer.readOptional(input);

		// the RANGE SINGLE/PAGED discriminator + the range page-stream metadata, independent of the bucket axis
		final PagedStreamMetadata rangeMetadata = PagedStreamMetadataSerializer.readOptional(input);

		return new FilterIndexPayload(points, rangeIndex, indexedDecimalPlaces, bucketMetadata, rangeMetadata);
	}

	/**
	 * The decoded bucketed filter payload: the inline bucket points, the optional inline range companion, the frozen
	 * decimal-places scale, and the bucket and range page-stream metadata axes.
	 *
	 * @param points               the inline bucket points (empty when the bucket axis is paged)
	 * @param rangeIndex           the inline range companion, or `null` when range-paged or absent
	 * @param indexedDecimalPlaces the frozen `indexedDecimalPlaces` scale
	 * @param bucketMetadata       the bucket-axis page-stream metadata
	 * @param rangeMetadata        the range-axis page-stream metadata
	 */
	public record FilterIndexPayload(
		@Nonnull ValueToRecordBitmap[] points,
		@Nullable RangeIndex rangeIndex,
		int indexedDecimalPlaces,
		@Nonnull PagedStreamMetadata bucketMetadata,
		@Nonnull PagedStreamMetadata rangeMetadata
	) {
	}

}
