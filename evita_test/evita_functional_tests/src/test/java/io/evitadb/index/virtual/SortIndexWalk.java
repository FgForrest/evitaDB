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

package io.evitadb.index.virtual;

import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedComparableForwardSeeker;
import io.evitadb.index.attribute.SortIndex;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The content of a {@link SortIndex} read position by position: the record at every position paired with the value
 * the index orders it by, and the blocks of records sharing one value.
 *
 * The values come from one forward seeker walked over ascending positions, so every value is the one the index
 * stores - its normalized form - and no value is ever looked up. A lookup by value would normalize the value once more,
 * which the index cannot do to its own stored values: it casts a `ComparableLocale` back to the `Locale` it expects
 * as input and unwraps a compound into a plain array. Records of one block are recognized by sharing the very value
 * instance the seeker hands out for the block, so two blocks whose values merely encode alike stay two blocks.
 *
 * @param records the record at every position
 * @param values  the value of the record at the same position
 * @param blocks  the blocks of records sharing one value, in index order
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
record SortIndexWalk(
	@Nonnull int[] records,
	@Nonnull Serializable[] values,
	@Nonnull List<Block> blocks
) {

	/**
	 * Walks a sort index.
	 *
	 * @param sortIndex the sort index
	 * @return its content, position by position
	 */
	@Nonnull
	static SortIndexWalk of(@Nonnull SortIndex sortIndex) {
		final int[] records = sortIndex.getSortedRecords().clone();
		assertEquals(
			sortIndex.size(), records.length,
			"The sort index `" + sortIndex.getAttributeIndexKey() + "` reports another size than its records"
		);
		final Serializable[] values = new Serializable[records.length];
		final List<Block> blocks = new ArrayList<>(Math.min(records.length, 16));
		final SortedComparableForwardSeeker seeker = sortIndex.createSortedComparableForwardSeeker();
		int blockStart = 0;
		for (int position = 0; position < records.length; position++) {
			values[position] = seeker.getValueToCompareOn(position);
			if (position > 0 && values[position] != values[position - 1]) {
				blocks.add(new Block(values[blockStart], blockStart, position - blockStart));
				blockStart = position;
			}
		}
		if (records.length > 0) {
			blocks.add(new Block(values[blockStart], blockStart, records.length - blockStart));
		}
		return new SortIndexWalk(records, values, blocks);
	}

	/**
	 * Renders the walk block by block - each value encoded by {@link CanonicalValue} followed by its records in index
	 * order - so two walks render alike exactly when they hold the same records in the same order, in the same blocks,
	 * under the same values.
	 *
	 * @return the rendering
	 */
	@Nonnull
	String describe() {
		final StringBuilder description = new StringBuilder(32 + 32 * this.blocks.size());
		for (final Block block : this.blocks) {
			description.append('{').append(CanonicalValue.encode(block.value())).append(": ");
			for (int i = 0; i < block.length(); i++) {
				if (i > 0) {
					description.append(", ");
				}
				description.append(this.records[block.start() + i]);
			}
			description.append("} ");
		}
		return description.toString();
	}

	/**
	 * One block of records sharing one value.
	 *
	 * @param value  the value of the block, as the index stores it
	 * @param start  the position of the first record of the block
	 * @param length how many records the block holds
	 */
	record Block(@Nonnull Serializable value, int start, int length) {
	}
}
