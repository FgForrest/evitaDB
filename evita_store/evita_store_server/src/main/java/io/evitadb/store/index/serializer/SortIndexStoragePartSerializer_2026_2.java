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
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.store.index.serializer.util.SortedIntArrayCodec;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Objects;

/**
 * Backward-compatible {@link Serializer} that reads the {@link SortIndex} format shipped by release 2026.2.
 *
 * The **byte layout** is the one {@link SortIndexStoragePartSerializer} writes today. What differs is what the values
 * in it may be: 2026.2 told `DateTimeRange` values apart at second granularity with the open bound of a range taken at
 * the other bound's offset, so `until(10:00+01:00)` and `until(11:00+02:00)` were two values with two blocks, and
 * today they are equal. The current reader looks a value's block length up by `equals` and so hands both twins the
 * same length — the part fails its block-length sum, or, when a twin of cardinality one precedes a twin of greater
 * cardinality, decodes the delta-coded record blocks to wrong ids without noticing.
 *
 * This reader therefore attributes the sparse cardinality section to the value column **by position**: 2026.2 wrote
 * both from one walk of one value tree, so the cardinality entries are an ascending subsequence of the value column,
 * each entry the very value object its column position held. Walking both columns forward and matching each entry
 * with the next value it is representation-identical to (see {@link #isRepresentationIdentical}) recovers every block
 * length exactly, with no collator and no frozen comparator. The part is returned with a **dense** cardinality column
 * (every block length at its value's position). The storage migration consumes these lengths positionally when it
 * rewrites owner sort indexes in the current order.
 *
 * The owner-`PAGED` root and the view-slim part carry no cardinality section; they are read exactly as the current
 * serializer reads them.
 *
 * @deprecated retained while any sort part carries the released UID. Upgrading to protocol 7 does not establish
 * that every part was rewritten: already-correct parts retain their released UID until a later flush.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Deprecated(since = "2026.3", forRemoval = true)
@RequiredArgsConstructor
public class SortIndexStoragePartSerializer_2026_2 extends Serializer<SortIndexStoragePart> {
	/** Resolves the attribute identity stored in the part header. */
	private final KeyCompressor keyCompressor;

	/**
	 * Tests whether two values of a 2026.2 sort part are the same value **as it was written**, which is finer than
	 * today's `equals` wherever an equality was coarsened since the release:
	 *
	 * - `DateTimeRange`: both precise bounds equal by `OffsetDateTime#equals`, which also compares the offset — an
	 *   open-ended range at two offsets is two values here, as it was two values in the release;
	 * - `ComparableArray`: element by element, by this same rule;
	 * - every other type (`String` included): `equals`, whose meaning has not changed for any of them.
	 *
	 * Two values of one 2026.2 value column are never representation-identical, because the release comparator told
	 * them apart and it never tells apart two values that are identical in this sense.
	 *
	 * @param first  the first value, may be `null` only as a compound element
	 * @param second the second value, may be `null` only as a compound element
	 * @return `true` when both are the same written value
	 */
	static boolean isRepresentationIdentical(@Nullable Serializable first, @Nullable Serializable second) {
		if (first instanceof DateTimeRange firstRange && second instanceof DateTimeRange secondRange) {
			return Objects.equals(firstRange.getPreciseFrom(), secondRange.getPreciseFrom()) &&
				Objects.equals(firstRange.getPreciseTo(), secondRange.getPreciseTo());
		} else if (first instanceof ComparableArray firstArray && second instanceof ComparableArray secondArray) {
			final Serializable[] firstElements = firstArray.array();
			final Serializable[] secondElements = secondArray.array();
			if (firstElements.length != secondElements.length) {
				return false;
			}
			for (int i = 0; i < firstElements.length; i++) {
				if (!isRepresentationIdentical(firstElements[i], secondElements[i])) {
					return false;
				}
			}
			return true;
		} else {
			return Objects.equals(first, second);
		}
	}

	/**
	 * Matches the sparse cardinality section of a 2026.2 owner-`SINGLE` part to its value column by position and
	 * returns the block length of every value. Each cardinality entry is assigned to the next value it is
	 * representation-identical to, walking both columns forward; a value no entry matched has the implied length `1`.
	 *
	 * Fails when an entry is left unmatched, or when the block lengths do not cover the record array — either means
	 * the part is not what release 2026.2 wrote.
	 *
	 * @param sortedRecordValues the distinct values in block order
	 * @param cardinalityValues  the values of the sparse cardinality entries, in stored order
	 * @param cardinalities      the cardinalities of those entries, positionally aligned
	 * @param sortedRecordCount  the length of the record array the blocks must cover
	 * @return the block length of every value, positionally aligned with `sortedRecordValues`
	 */
	@Nonnull
	static int[] attributeBlockLengths(
		@Nonnull Serializable[] sortedRecordValues,
		@Nonnull Serializable[] cardinalityValues,
		@Nonnull int[] cardinalities,
		int sortedRecordCount
	) {
		final int[] blockLengths = new int[sortedRecordValues.length];
		int sum = 0;
		int entry = 0;
		for (int i = 0; i < sortedRecordValues.length; i++) {
			int length = 1;
			if (entry < cardinalityValues.length &&
				isRepresentationIdentical(cardinalityValues[entry], sortedRecordValues[i])) {
				length = cardinalities[entry++];
			}
			blockLengths[i] = length;
			sum += length;
		}
		final int matchedEntries = entry;
		Assert.isPremiseValid(
			matchedEntries == cardinalityValues.length,
			() -> "Sort index damaged! Only " + matchedEntries + " of " + cardinalityValues.length +
				" cardinality entries match a value of the part written by release 2026.2!"
		);
		final int blockSum = sum;
		Assert.isPremiseValid(
			blockSum == sortedRecordCount,
			() -> "Sort index damaged! The per-value block lengths sum to " + blockSum +
				" but the sorted-records array holds " + sortedRecordCount + " ids!"
		);
		return blockLengths;
	}

	/**
	 * Reads one distinct sort value exactly as release 2026.2 wrote it: a scalar sort index stores one
	 * self-describing value, a compound one stores `comparatorBaseLength` self-describing elements wrapped into a
	 * {@link ComparableArray} on read.
	 *
	 * @param kryo                 the kryo instance to read with
	 * @param input                the input to read from
	 * @param comparatorBaseLength the number of comparator elements (1 == scalar, more == compound)
	 * @return the value or compound value
	 */
	@Nonnull
	private static Serializable readComparableValue(
		@Nonnull Kryo kryo,
		@Nonnull Input input,
		int comparatorBaseLength
	) {
		if (comparatorBaseLength == 1) {
			return (Serializable) kryo.readClassAndObject(input);
		} else {
			final Serializable[] elements = new Serializable[comparatorBaseLength];
			for (int i = 0; i < comparatorBaseLength; i++) {
				elements[i] = (Serializable) kryo.readClassAndObject(input);
			}
			return new ComparableArray(elements);
		}
	}

	/** Rejects writes of the retired format. */
	@Override
	public void write(Kryo kryo, Output output, SortIndexStoragePart sortIndex) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	/** Reads the released bytes without equality-based block lookups. */
	@Override
	public SortIndexStoragePart read(Kryo kryo, Input input, Class<? extends SortIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final AttributeIndexKey attributeIndexKey = this.keyCompressor.getKeyForId(input.readVarInt(true));

		final ComparatorSource[] comparatorBase = new ComparatorSource[input.readVarInt(true)];
		for (int i = 0; i < comparatorBase.length; i++) {
			comparatorBase[i] = new ComparatorSource(
				kryo.readClass(input).getType(),
				kryo.readObject(input, OrderDirection.class),
				kryo.readObject(input, OrderBehaviour.class)
			);
		}

		final SortIndexStoragePart part;
		if (input.readBoolean()) {
			// owner single: the distinct values, the sparse cardinality entries (read into two arrays, never a map, so
			// two values equal today stay two entries), the record blocks and the trailing scale
			final Serializable[] sortedRecordValues = new Serializable[input.readVarInt(true)];
			for (int i = 0; i < sortedRecordValues.length; i++) {
				sortedRecordValues[i] = readComparableValue(kryo, input, comparatorBase.length);
			}
			final int cardinalityCount = input.readVarInt(true);
			final Serializable[] cardinalityValues = new Serializable[cardinalityCount];
			final int[] cardinalities = new int[cardinalityCount];
			for (int i = 0; i < cardinalityCount; i++) {
				cardinalityValues[i] = readComparableValue(kryo, input, comparatorBase.length);
				cardinalities[i] = input.readVarInt(true);
			}

			final boolean blockDeltaEncoded = input.readBoolean();
			final int sortedRecordCount = input.readVarInt(true);
			// the block lengths are needed to decode the delta-coded blocks and are checked against the record count in
			// both encodings, so a part this reader cannot attribute never gets past this point
			final int[] blockLengths = attributeBlockLengths(
				sortedRecordValues, cardinalityValues, cardinalities, sortedRecordCount
			);
			final int[] sortedRecords;
			if (blockDeltaEncoded) {
				sortedRecords = new int[sortedRecordCount];
				int offset = 0;
				for (final int blockLength : blockLengths) {
					SortedIntArrayCodec.readAscendingRun(input, sortedRecords, offset, blockLength);
					offset += blockLength;
				}
			} else {
				// the raw fallback the release wrote for a part whose blocks were not ascending
				sortedRecords = input.readInts(sortedRecordCount);
			}
			final int indexedDecimalPlaces = input.readVarInt(true);
			part = new SortIndexStoragePart(
				entityIndexPrimaryKey, attributeIndexKey, comparatorBase,
				sortedRecords, sortedRecordValues, sortedRecordValues, blockLengths,
				indexedDecimalPlaces, uniquePartId
			);
		} else if (input.readBoolean()) {
			// owner paged root: the page-stream metadata and the trailing scale; the values live in the leaf pages
			final int highWaterPageSequence = input.readVarInt(true);
			final int[] leafPageSequences = input.readInts(input.readVarInt(true));
			final int indexedDecimalPlaces = input.readVarInt(true);
			part = SortIndexStoragePart.paged(
				entityIndexPrimaryKey, attributeIndexKey, comparatorBase,
				indexedDecimalPlaces, highWaterPageSequence, leafPageSequences, uniquePartId
			);
		} else {
			// view-slim: the raw positional record array and the trailing scale; the values come from the FILTER part
			final int[] sortedRecords = input.readInts(input.readVarInt(true));
			final int indexedDecimalPlaces = input.readVarInt(true);
			part = new SortIndexStoragePart(
				entityIndexPrimaryKey, attributeIndexKey, comparatorBase,
				sortedRecords, ArrayUtils.EMPTY_SERIALIZABLE_ARRAY,
				ArrayUtils.EMPTY_SERIALIZABLE_ARRAY, ArrayUtils.EMPTY_INT_ARRAY,
				indexedDecimalPlaces, uniquePartId
			);
		}
		return part;
	}

}
