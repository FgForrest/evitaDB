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
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

import static io.evitadb.store.index.serializer.SortIndexStoragePartSerializer_2026_2.isRepresentationIdentical;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Backward-compatible {@link Serializer} that reads the 2026.1 binary format of {@link SortIndex} data.
 *
 * The 2026.1 format **always** wrote the sorted distinct values (`sortedRecordValues`) and the sparse
 * `valueCardinalities` map, immediately after the `sortedRecords` block. The current serializer made those two
 * sections optional — a sort index that is also filterable (view mode) derives its values and cardinalities from the
 * shared `FilterIndexStoragePart` and therefore omits them, writing a single boolean marker instead. This serializer
 * reads the legacy always-present layout so catalogs written by 2026.1 (and earlier 2025.6-era catalogs that share its
 * `serialVersionUID`) keep loading.
 *
 * The sparse map was written in arbitrary iteration order. Its entries are matched by released representation,
 * preserving distinct range spellings that are equal today, and returned as dense positional block lengths for
 * the migration's owner-sort correction. When old localized keys cannot be attributed exactly, their original
 * sparse columns survive instead; those catalogs retain the migration's existing conditional loss warning.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Deprecated(since = "2026.2", forRemoval = true)
@RequiredArgsConstructor
public class SortIndexStoragePartSerializer_2026_1 extends Serializer<SortIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	/**
	 * Attributes unordered sparse cardinalities to their exact released values without using today's equality.
	 * A missing sparse entry implies one record. The dense result shares the value column's positions, which the
	 * migration consumes before it merges and reorders blocks. Representation-key hashing keeps attribution linear.
	 * Old localized maps can carry a different spelling from their value-column representative. If exact attribution
	 * cannot cover every record, the caller retains the original sparse columns and the existing warning outcome.
	 *
	 * @param sortedRecordValues the distinct released values in block order
	 * @param cardinalityValues the sparse map's keys in arbitrary stored order
	 * @param cardinalities the corresponding block lengths
	 * @param sortedRecordCount the number of records the blocks must cover
	 * @return every block length in value-column order, or `null` when exact attribution is impossible
	 */
	@Nullable
	static int[] attributeBlockLengthsOrNull(
		@Nonnull Serializable[] sortedRecordValues,
		@Nonnull Serializable[] cardinalityValues,
		@Nonnull int[] cardinalities,
		int sortedRecordCount
	) {
		final Map<RepresentationKey, Integer> lengths = createHashMap(cardinalityValues.length);
		for (int i = 0; i < cardinalityValues.length; i++) {
			lengths.put(new RepresentationKey(cardinalityValues[i]), cardinalities[i]);
		}
		final int[] blockLengths = new int[sortedRecordValues.length];
		int sum = 0;
		for (int i = 0; i < sortedRecordValues.length; i++) {
			final Integer length = lengths.remove(new RepresentationKey(sortedRecordValues[i]));
			blockLengths[i] = length == null ? 1 : length;
			sum += blockLengths[i];
		}
		return lengths.isEmpty() && sum == sortedRecordCount ? blockLengths : null;
	}

	/**
	 * Hashes precisely the representation compared by the released sort reader, recursively for compound elements.
	 *
	 * @param value the stored value, nullable only for compound elements
	 * @return a hash consistent with released representation identity
	 */
	private static int representationHash(@Nullable Serializable value) {
		if (value instanceof DateTimeRange range) {
			return Objects.hash(range.getPreciseFrom(), range.getPreciseTo());
		} else if (value instanceof ComparableArray array) {
			int result = 1;
			for (final Serializable element : array.array()) {
				result = 31 * result + representationHash(element);
			}
			return result;
		} else {
			return Objects.hashCode(value);
		}
	}

	/**
	 * Map key that retains the released range bounds and offsets, including those inside compounds.
	 *
	 * @param value the original stored value
	 */
	private record RepresentationKey(@Nonnull Serializable value) {
		/**
		 * Compares the exact stored representation using the rule shared with the 2026.2 reader.
		 *
		 * @param other the candidate key
		 * @return whether the keys represent the same released value
		 */
		@Override
		public boolean equals(@Nullable Object other) {
			return other instanceof RepresentationKey key && isRepresentationIdentical(this.value, key.value);
		}

		/**
		 * Hashes the exact stored representation, consistently with {@link #equals(Object)}.
		 *
		 * @return the representation hash
		 */
		@Override
		public int hashCode() {
			return representationHash(this.value);
		}
	}

	@Override
	public void write(Kryo kryo, Output output, SortIndexStoragePart sortIndex) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
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

		final int sortedRecordCount = input.readVarInt(true);
		final int[] sortedRecords = input.readInts(sortedRecordCount);

		final int sortedValuesCount = input.readVarInt(true);
		final Serializable[] sortedRecordValues = new Serializable[sortedValuesCount];
		for (int i = 0; i < sortedValuesCount; i++) {
			if (comparatorBaseLength == 1) {
				//noinspection unchecked
				sortedRecordValues[i] = (Serializable) kryo.readObject(input, comparatorBase[0].type());
			} else {
				final Serializable[] comparableArray = new Serializable[comparatorBaseLength];
				for (int j = 0; j < comparatorBase.length; j++) {
					//noinspection unchecked
					comparableArray[j] = (Serializable) kryo.readObjectOrNull(input, comparatorBase[j].type());
				}
				sortedRecordValues[i] = new ComparableArray(comparableArray);
			}
		}

		final int cardinalityCount = input.readVarInt(true);
		final Serializable[] cardinalityValues = new Serializable[cardinalityCount];
		final int[] cardinalities = new int[cardinalityCount];
		for (int i = 0; i < cardinalityCount; i++) {
			final Serializable value;
			if (comparatorBaseLength == 1) {
				//noinspection unchecked
				value = (Serializable) kryo.readObject(input, comparatorBase[0].type());
			} else {
				final Serializable[] comparableArray = new Serializable[comparatorBaseLength];
				for (int j = 0; j < comparatorBase.length; j++) {
					//noinspection unchecked
					comparableArray[j] = (Serializable) kryo.readObjectOrNull(input, comparatorBase[j].type());
				}
				value = new ComparableArray(comparableArray);
			}
			cardinalityValues[i] = value;
			cardinalities[i] = input.readVarInt(true);
		}

		final int[] blockLengths = attributeBlockLengthsOrNull(
			sortedRecordValues, cardinalityValues, cardinalities, sortedRecordCount
		);
		return new SortIndexStoragePart(
			entityIndexPrimaryKey, attributeIndexKey, comparatorBase, sortedRecords,
			sortedRecordValues, blockLengths == null ? cardinalityValues : sortedRecordValues,
			blockLengths == null ? cardinalities : blockLengths, 0, uniquePartId
		);
	}

}
