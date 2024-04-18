/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.comparator;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Interface allowing to compare two primitive integers. Interface follows all the rules of {@link java.util.Comparator},
 * but allows to compare primitive integers without boxing them into {@link Integer} objects.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface IntComparator {

	/**
	 * Compares two primitive integers.
	 * @param o1 first integer
	 * @param o2 second integer
	 * @return a negative integer, zero, or a positive integer as the first argument is less than, equal to, or greater
	 * than the second
	 */
	int compare(int o1, int o2);

	/**
	 * Comparator for sorting integers in ascending order.
	 */
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	class IntAscendingComparator implements IntComparator {
		public static final IntAscendingComparator INSTANCE = new IntAscendingComparator();

		@Override
		public int compare(int o1, int o2) {
			return Integer.compare(o1, o2);
		}
	}

	/**
	 * Comparator for sorting integers in descending order.
	 */
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	class IntDescendingComparator implements IntComparator {
		public static final IntDescendingComparator INSTANCE = new IntDescendingComparator();

		@Override
		public int compare(int o1, int o2) {
			return Integer.compare(o2, o1);
		}
	}

}
