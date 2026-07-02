/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.store.index.serializer.util.SortedIntArrayCodec;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * This {@link Serializer} implementation reads/writes {@link SortIndex} from/to binary format.
 *
 * The wire format is gated by two discriminators so the common unchanged case stays byte-stable.
 * A leading `valuesPresent` marker selects owner-SINGLE: the inline distinct values + cardinalities
 * followed by the per-value delta-encoded `sortedRecords` blocks, byte-identical to the pre-paging
 * format. When it is absent, a second `paged` discriminator distinguishes owner-PAGED (only the
 * value tree's high-water page sequence and ordered live leaf-page list — the positional records
 * and distinct values are reconstructed from the
 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPagePart}
 * records on load) from view-slim (the raw positional `sortedRecords`, its value side re-derived
 * from the shared
 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart}). The
 * `paged` flag is written only inside the no-inline-values branch, so owner-SINGLE bytes stay
 * byte-identical to the pre-paging format and need no format or `serialVersionUID` change.
 *
 * The `sortedRecords` array is a concatenation of per-value blocks (one block per distinct value in
 * {@link SortIndexStoragePart#getSortedRecordsValues()} order, block length =
 * {@code valueCardinalities.getOrDefault(value, 1)}); record ids WITHIN a block are sorted ascending. Owner-mode parts
 * (those that carry values + cardinalities) therefore encode each block as a delta-varint run via
 * {@link SortedIntArrayCodec}, collapsing each id to one or two bytes instead of a raw fixed 4-byte int. A rare
 * migration-collapsed part (two distinct raw `BigDecimal`s that scale to the same int, whose blocks were concatenated
 * without re-sorting) can be non-ascending within a block; such a part falls back to a raw `writeInts` encoding flagged
 * by a per-part `blockDeltaEncoded` boolean, so it round-trips losslessly. View-mode parts (no values/cardinalities,
 * re-derived from the shared {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart}
 * on load) keep the raw encoding because their block lengths are not available here. The released-minor formats are
 * read by {@link SortIndexStoragePartSerializer_2025_5} / {@link SortIndexStoragePartSerializer_2026_1}; the prior
 * 2026.2 development format was never released, so it has no backward-compatible reader.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class SortIndexStoragePartSerializer extends Serializer<SortIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, SortIndexStoragePart sortIndex) {
		output.writeInt(sortIndex.getEntityIndexPrimaryKey());
		final Long uniquePartId = sortIndex.getStoragePartPK();
		Assert.notNull(uniquePartId, "Unique part id should have been computed by now!");
		output.writeVarLong(uniquePartId, true);
		output.writeVarInt(this.keyCompressor.getId(sortIndex.getAttributeIndexKey()), true);

		final ComparatorSource[] comparatorBase = sortIndex.getComparatorBase();
		output.writeVarInt(comparatorBase.length, true);
		for (final ComparatorSource comparatorSource : comparatorBase) {
			kryo.writeClass(output, comparatorSource.type());
			kryo.writeObject(output, comparatorSource.orderDirection());
			kryo.writeObject(output, comparatorSource.orderBehaviour());
		}

		// a both-filterable-and-sortable (view-mode) sort index derives its distinct values and per-value cardinalities
		// from the shared FilterIndexStoragePart, so a slim part carries none of them. The marker records whether the
		// values + cardinalities sections follow; only owner-mode parts (sort-only / compound) write them. It is written
		// BEFORE sortedRecords because the reader needs the per-value block lengths (from the cardinalities) to
		// delta-decode the blocks.
		final Serializable[] sortedRecordValues = sortIndex.getSortedRecordsValues();
		// the sparse cardinality columns are read straight off the part (ascending, aligned with sortedRecordValues) - no
		// map is materialized on the commit/flush hot path
		final Serializable[] cardinalityValues = sortIndex.getCardinalityValues();
		final int[] cardinalities = sortIndex.getCardinalities();
		final boolean valuesPresent = sortedRecordValues.length > 0;
		output.writeBoolean(valuesPresent);
		if (valuesPresent) {
			output.writeVarInt(sortedRecordValues.length, true);
			for (final Serializable sortedRecordValue : sortedRecordValues) {
				writeComparableValue(kryo, output, sortedRecordValue, comparatorBase.length);
			}

			output.writeVarInt(cardinalityValues.length, true);
			for (int i = 0; i < cardinalityValues.length; i++) {
				writeComparableValue(kryo, output, cardinalityValues[i], comparatorBase.length);
				output.writeVarInt(cardinalities[i], true);
			}
		}

		final int[] sortedRecords = sortIndex.getSortedRecords();
		if (valuesPresent) {
			// owner-SINGLE: sortedRecords is a concatenation of per-value ascending blocks - delta-encode each block when
			// every block is non-decreasing, otherwise fall back to a raw encoding (a migration-collapsed part may hold a
			// non-ascending block - see the class javadoc). This branch is byte-identical to the pre-paging format.
			final int[] blockLengths = computeBlockLengths(sortedRecordValues, cardinalityValues, cardinalities, sortedRecords.length);
			final boolean blockDeltaEncoded = allBlocksAscending(sortedRecords, blockLengths);
			output.writeBoolean(blockDeltaEncoded);
			output.writeVarInt(sortedRecords.length, true);
			if (blockDeltaEncoded) {
				int offset = 0;
				for (final int blockLength : blockLengths) {
					SortedIntArrayCodec.writeAscendingRun(output, sortedRecords, offset, blockLength);
					offset += blockLength;
				}
			} else {
				output.writeInts(sortedRecords, 0, sortedRecords.length);
			}
		} else {
			// no inline values: the part is either a granular owner-PAGED root (its value side lives in SortIndexLeafPagePart
			// records) or a slim view-mode part (re-derived from the shared FILTER index on load). The `paged` discriminator
			// is written ONLY in this branch, so the owner-SINGLE bytes above stay byte-identical to the pre-paging format.
			final boolean paged = sortIndex.isPaged();
			output.writeBoolean(paged);
			if (paged) {
				// owner-PAGED root: only the page-stream metadata (high-water + the ordered live leaf-page list). The
				// positional sortedRecords and the distinct values are NOT written - they are reconstructed from the leaf
				// pages on load.
				output.writeVarInt(sortIndex.getHighWaterPageSequence(), true);
				final int[] leafPageSequences = sortIndex.getLeafPageSequences();
				Assert.notNull(leafPageSequences, "A paged sort index part must carry its leaf page sequences!");
				output.writeVarInt(leafPageSequences.length, true);
				output.writeInts(leafPageSequences, 0, leafPageSequences.length);
			} else {
				// view mode: block lengths are not available here (re-derived from the shared filter index on load), so the
				// array is written raw - no win, no regression.
				output.writeVarInt(sortedRecords.length, true);
				output.writeInts(sortedRecords, 0, sortedRecords.length);
			}
		}

		// the frozen `indexedDecimalPlaces` scale (0 for non-BigDecimal and compound sort attributes) - written LAST in
		// all three branches (owner-SINGLE, owner-PAGED, view-slim)
		output.writeVarInt(sortIndex.getIndexedDecimalPlaces(), true);
	}

	@Override
	public SortIndexStoragePart read(Kryo kryo, Input input, Class<? extends SortIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final AttributeIndexKey attributeIndexKey = this.keyCompressor.getKeyForId(input.readVarInt(true));

		final int comparatorBaseLength = input.readVarInt(true);
		final ComparatorSource[] comparatorBase = new ComparatorSource[comparatorBaseLength];
		for (int i = 0; i < comparatorBaseLength; i++) {
			comparatorBase[i] = new ComparatorSource(
				kryo.readClass(input).getType(),
				kryo.readObject(input, OrderDirection.class),
				kryo.readObject(input, OrderBehaviour.class)
			);
		}

		// the values + cardinalities sections precede sortedRecords so the per-value block lengths are known before the
		// blocks are delta-decoded; a slim (view-mode) part wrote a `false` marker and omitted them — they are re-derived
		// from the shared FilterIndexStoragePart on load.
		final boolean valuesPresent = input.readBoolean();
		final Serializable[] sortedRecordValues;
		final Map<Serializable, Integer> cardinalities;
		if (valuesPresent) {
			final int sortedValuesCount = input.readVarInt(true);
			sortedRecordValues = new Serializable[sortedValuesCount];
			for (int i = 0; i < sortedValuesCount; i++) {
				sortedRecordValues[i] = readComparableValue(kryo, input, comparatorBaseLength);
			}

			final int cardinalityCount = input.readVarInt(true);
			cardinalities = createHashMap(cardinalityCount);
			for (int i = 0; i < cardinalityCount; i++) {
				final Serializable value = readComparableValue(kryo, input, comparatorBaseLength);
				cardinalities.put(
					value, input.readVarInt(true)
				);
			}
		} else {
			sortedRecordValues = new Serializable[0];
			cardinalities = Map.of();
		}

		if (valuesPresent) {
			// owner-SINGLE: the sortedRecords blocks follow, then the trailing scale
			final boolean blockDeltaEncoded = input.readBoolean();
			final int sortedRecordCount = input.readVarInt(true);
			final int[] sortedRecords;
			if (blockDeltaEncoded) {
				// the block lengths (and the sum == count sanity check) are recovered from the cardinalities, exactly as
				// they were derived on write
				final int[] blockLengths = computeBlockLengths(sortedRecordValues, cardinalities, sortedRecordCount);
				sortedRecords = new int[sortedRecordCount];
				int offset = 0;
				for (final int blockLength : blockLengths) {
					SortedIntArrayCodec.readAscendingRun(input, sortedRecords, offset, blockLength);
					offset += blockLength;
				}
			} else {
				sortedRecords = input.readInts(sortedRecordCount);
			}
			final int indexedDecimalPlaces = input.readVarInt(true);
			return new SortIndexStoragePart(
				entityIndexPrimaryKey, attributeIndexKey, comparatorBase,
				sortedRecords, sortedRecordValues, cardinalities, indexedDecimalPlaces, uniquePartId
			);
		}

		// no inline values: read the `paged` discriminator written in this branch
		final boolean paged = input.readBoolean();
		if (paged) {
			// owner-PAGED root: the page-stream metadata, then the trailing scale. The value side (and the positional
			// sortedRecords) live in the leaf pages and are reconstructed from the reloaded tree, so the part carries empty
			// inline columns.
			final int highWaterPageSequence = input.readVarInt(true);
			final int leafPageCount = input.readVarInt(true);
			final int[] leafPageSequences = input.readInts(leafPageCount);
			final int indexedDecimalPlaces = input.readVarInt(true);
			return SortIndexStoragePart.paged(
				entityIndexPrimaryKey, attributeIndexKey, comparatorBase,
				indexedDecimalPlaces, highWaterPageSequence, leafPageSequences, uniquePartId
			);
		}

		// view-slim: the raw positional sortedRecords, then the trailing scale
		final int sortedRecordCount = input.readVarInt(true);
		final int[] sortedRecords = input.readInts(sortedRecordCount);
		final int indexedDecimalPlaces = input.readVarInt(true);
		return new SortIndexStoragePart(
			entityIndexPrimaryKey, attributeIndexKey, comparatorBase,
			sortedRecords, sortedRecordValues, cardinalities, indexedDecimalPlaces, uniquePartId
		);
	}

	/**
	 * Writes a single distinct sort value to the output. A scalar (single-component) sort index emits the value
	 * self-describingly via {@link Kryo#writeClassAndObject} — the stored value is the normalizer's output, whose class
	 * may differ from the declared comparator-base type (e.g. a BigDecimal attribute is scaled to an Integer key), so the
	 * concrete class must travel with the value rather than being assumed on read. A compound sort index unwraps the
	 * {@link ComparableArray} and emits each component in ascending index order, again self-describingly.
	 *
	 * @param kryo                the kryo instance to write with (must not be null)
	 * @param output             the output to write to (must not be null)
	 * @param value              the distinct value or {@link ComparableArray} to write (must not be null)
	 * @param comparatorBaseLength the number of comparator components (1 == scalar, &gt;1 == compound)
	 */
	static void writeComparableValue(
		@Nonnull Kryo kryo,
		@Nonnull Output output,
		@Nonnull Serializable value,
		int comparatorBaseLength
	) {
		if (comparatorBaseLength == 1) {
			kryo.writeClassAndObject(output, value);
		} else {
			final ComparableArray comparableArray = (ComparableArray) value;
			for (int i = 0; i < comparatorBaseLength; i++) {
				kryo.writeClassAndObject(output, comparableArray.array()[i]);
			}
		}
	}

	/**
	 * Reads a single distinct sort value written by
	 * {@link #writeComparableValue(Kryo, Output, Serializable, int)} — the exact inverse. A scalar (single-component)
	 * sort index reads one self-describing value; a compound sort index reads {@code comparatorBaseLength} components and
	 * wraps them in a {@link ComparableArray}.
	 *
	 * @param kryo                the kryo instance to read with (must not be null)
	 * @param input              the input to read from (must not be null)
	 * @param comparatorBaseLength the number of comparator components (1 == scalar, &gt;1 == compound)
	 * @return the reconstructed value or {@link ComparableArray} (never null)
	 */
	@Nonnull
	static Serializable readComparableValue(
		@Nonnull Kryo kryo,
		@Nonnull Input input,
		int comparatorBaseLength
	) {
		if (comparatorBaseLength == 1) {
			return (Serializable) kryo.readClassAndObject(input);
		} else {
			final Serializable[] comparableArray = new Serializable[comparatorBaseLength];
			for (int j = 0; j < comparatorBaseLength; j++) {
				comparableArray[j] = (Serializable) kryo.readClassAndObject(input);
			}
			return new ComparableArray(comparableArray);
		}
	}

	/**
	 * Derives the per-value block lengths of the `sortedRecords` array. Block `i` (the contiguous run of record ids that
	 * share the value `sortedRecordValues[i]`) has length {@code valueCardinalities.getOrDefault(value, 1)} — the map is
	 * SPARSE, storing only values shared by more than one record, so a missing value is an implied cardinality of one.
	 * The summed length must equal the total record count; a mismatch means the part is damaged and fails loud.
	 *
	 * @param sortedRecordValues the distinct values in block order (must not be null)
	 * @param cardinalities      the sparse value → cardinality map (must not be null)
	 * @param totalRecords       the length of the `sortedRecords` array the blocks must cover
	 * @return the block length per value, in `sortedRecordValues` order (never null)
	 */
	@Nonnull
	private static int[] computeBlockLengths(
		@Nonnull Serializable[] sortedRecordValues,
		@Nonnull Map<Serializable, Integer> cardinalities,
		int totalRecords
	) {
		final int[] blockLengths = new int[sortedRecordValues.length];
		int sum = 0;
		for (int i = 0; i < sortedRecordValues.length; i++) {
			final int length = cardinalities.getOrDefault(sortedRecordValues[i], 1);
			blockLengths[i] = length;
			sum += length;
		}
		Assert.isPremiseValid(
			sum == totalRecords,
			"Sort index damaged! The per-value block lengths sum to " + sum + " but the sorted-records array holds " +
				totalRecords + " ids!"
		);
		return blockLengths;
	}

	/**
	 * Allocation-free variant used on the persistence (write) hot path: the sparse cardinality columns are a subset of
	 * `sortedRecordValues` in the same ascending order, so a single two-pointer merge recovers the per-value block length
	 * (`cardinality > 1` from the sparse columns, an implied `1` otherwise) without building a lookup map. Equality is
	 * tested with {@link Object#equals} exactly as the map-based variant's `getOrDefault`, so the two agree.
	 */
	private static int[] computeBlockLengths(
		@Nonnull Serializable[] sortedRecordValues,
		@Nonnull Serializable[] cardinalityValues,
		@Nonnull int[] cardinalities,
		int totalRecords
	) {
		final int[] blockLengths = new int[sortedRecordValues.length];
		int sum = 0;
		int c = 0;
		for (int i = 0; i < sortedRecordValues.length; i++) {
			int length = 1;
			if (c < cardinalityValues.length && cardinalityValues[c].equals(sortedRecordValues[i])) {
				length = cardinalities[c++];
			}
			blockLengths[i] = length;
			sum += length;
		}
		Assert.isPremiseValid(
			sum == totalRecords,
			"Sort index damaged! The per-value block lengths sum to " + sum + " but the sorted-records array holds " +
				totalRecords + " ids!"
		);
		return blockLengths;
	}

	/**
	 * Tests whether every per-value block of `sortedRecords` is non-decreasing within itself, using the same
	 * {@code current >= previous} predicate {@link SortedIntArrayCodec#writeAscendingRun} asserts on — so the
	 * raw-fallback decision here and the codec's assert can never disagree. Cross-block ordering is irrelevant (each
	 * block is delta-encoded independently with its own first element). A `false` result selects the raw fallback for a
	 * rare migration-collapsed part.
	 *
	 * @param sortedRecords the concatenated block array (must not be null)
	 * @param blockLengths  the per-value block lengths covering the array (must not be null)
	 * @return {@code true} when every block is non-decreasing, {@code false} when at least one block is not
	 */
	private static boolean allBlocksAscending(@Nonnull int[] sortedRecords, @Nonnull int[] blockLengths) {
		int offset = 0;
		for (final int blockLength : blockLengths) {
			for (int k = 1; k < blockLength; k++) {
				if (sortedRecords[offset + k] < sortedRecords[offset + k - 1]) {
					return false;
				}
			}
			offset += blockLength;
		}
		return true;
	}

}
