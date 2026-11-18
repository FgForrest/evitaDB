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


package io.evitadb.spike.vector;

import javax.annotation.Nonnull;

/**
 * The ordinal map of spec §2.4 between the vector ordinals of the graph and the primary keys of the records they
 * belong to. A filter reaches the vector search as a bitmap of primary keys, the output of the rest of the query; a
 * walk tests a node through `ordToPk`, and the exact scan iterates the bitmap and reaches each member's vectors
 * through `pkStart`.
 *
 * The primary key of a record is its index in the corpus, in the order the extractor wrote the records. A record
 * may hold several vectors (chunks); its vectors have consecutive ordinals, so `pkToOrd` is a range per key.
 *
 * @param ordToPk per ordinal, the primary key of its record
 * @param pkStart per primary key, the first ordinal of its record; entry `pk + 1` ends the range, the last entry is
 *                the number of ordinals
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public record PrimaryKeyOrdinals(@Nonnull int[] ordToPk, @Nonnull int[] pkStart) {

	/**
	 * @param record      per ordinal, the index of its record, as {@link ClickRecallScenario.Corpus} reads it
	 * @param recordCount the number of records
	 * @return the map
	 * @throws IllegalArgumentException when the vectors of a record are not consecutive
	 */
	@Nonnull
	public static PrimaryKeyOrdinals of(@Nonnull int[] record, int recordCount) {
		final int[] pkStart = new int[recordCount + 1];
		int next = 0;
		for (int ord = 0; ord < record.length; ord++) {
			if (ord == 0 || record[ord] != record[ord - 1]) {
				if (record[ord] != next) {
					throw new IllegalArgumentException(
						"The vectors of record " + record[ord] + " are not consecutive, or records are out of order."
					);
				}
				pkStart[next++] = ord;
			}
		}
		pkStart[recordCount] = record.length;
		return new PrimaryKeyOrdinals(record, pkStart);
	}

	/**
	 * @param count the number of ordinals
	 * @return the map of a corpus where every vector is its own record
	 */
	@Nonnull
	public static PrimaryKeyOrdinals identity(int count) {
		final int[] ordToPk = new int[count];
		final int[] pkStart = new int[count + 1];
		for (int i = 0; i <= count; i++) {
			if (i < count) {
				ordToPk[i] = i;
			}
			pkStart[i] = i;
		}
		return new PrimaryKeyOrdinals(ordToPk, pkStart);
	}

	/**
	 * @return the number of ordinals
	 */
	public int ordinalCount() {
		return this.ordToPk.length;
	}
}
