/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.index.range;

import io.evitadb.dataType.Range;
import io.evitadb.index.bitmap.Bitmap;

import java.io.Serializable;

/**
 * RangePoint represents single point in {@link RangeIndex} that maintains set of records which {@link Range#getFrom()}
 * starts at this point and also set of records which {@link Range#getTo()} ends at this point.
 *
 * Each point is represented by {@link #getThreshold()} that allows to place points into monotonic row that allows fast
 * lookups by O(log n) binary search.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see RangeIndex for more information.
 */
public interface RangePoint<T extends RangePoint<T>> extends Comparable<T>, Serializable {

	/**
	 * Threshold that uniquely represents the point in number range.
	 */
	long getThreshold();

	/**
	 * Returns bitmap of all records which range ({@link Range#getFrom()}) starts at this point/threshold.
	 */
	Bitmap getStarts();

	/**
	 * Returns bitmap of all records which range ({@link Range#getTo()}) ends at this point/threshold.
	 */
	Bitmap getEnds();

	/**
	 * Allows to compare two range points and order them in ascending order.
	 */
	@Override
	default int compareTo(T o) {
		return Long.compare(getThreshold(), o.getThreshold());
	}

}
