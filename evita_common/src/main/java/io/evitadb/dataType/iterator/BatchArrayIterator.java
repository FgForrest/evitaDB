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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.dataType.iterator;


import io.evitadb.dataType.array.CompositeIntArray;

import java.util.Iterator;

/**
 * Interface that allows to wrap both RoaringBitmap batchIterator and {@link CompositeIntArray} to unified
 * iterator that optimally traverses the internal structures providing arrays with data loaded optimally in a batch
 * fashion.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface BatchArrayIterator {

	/**
	 * Returns true if there is another batch available - analogy to {@link Iterator#hasNext()}.
	 */
	boolean hasNext();

	/**
	 * Returns array with data - analogy to {@link Iterator#next()}.
	 */
	int[] nextBatch();

	/**
	 * If needed, advance as long as the next value is smaller than `target`
	 *
	 * The advanceIfNeeded method is used for performance reasons, to skip over unnecessary repeated calls to next.
	 *
	 * Suppose for example that you wish to compute the intersection between an ordered list of integers
	 * (e.g., int[] x = {1,4,5}) and a BatchIterator.
	 *
	 * You might do it as follows...
	 * <pre><code>
	 *     int[] buffer = new int[128];
	 *     BatchIterator j = // get an iterator
	 *     int val = // first value from my other data structure
	 *     j.advanceIfNeeded(val);
	 *     while ( j.hasNext() ) {
	 *       int limit = j.nextBatch(buffer);
	 *       for (int i = 0; i < limit; i++) {
	 *         if (buffer[i] == val) {
	 *           // got it!
	 *           // do something here
	 *           val = // get next value?
	 *         }
	 *       }
	 *       j.advanceIfNeeded(val);
	 *     }
	 *     </code></pre>
	 *
	 * The benefit of calling advanceIfNeeded is that each such call can be much faster than repeated calls to "next".
	 * The underlying implementation can "skip" over some data.
	 *
	 * The method copies the approach in `org.roaringbitmap.BatchIterator#advanceIfNeeded(int)`
	 */
	void advanceIfNeeded(int target);

	/**
	 * Returns size in the data really fetched into the array returned by {@link #nextBatch()}. The array is pre-allocated
	 * and shared for all calls and in case of last page there may not be enough records to fill it up fully. The peek
	 * marks the end of the sensible data in the array. Last readable data is <code>array[getPeek() - 1]</code>.
	 */
	int getPeek();

}
