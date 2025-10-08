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

package io.evitadb.index.iterator;

import io.evitadb.dataType.iterator.BatchArrayIterator;
import org.roaringbitmap.BatchIterator;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link BatchArrayIterator} for {@link org.roaringbitmap.RoaringBitmap}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class RoaringBitmapBatchArrayIterator implements BatchArrayIterator {
	private final int[] buffer;
	private final BatchIterator delegate;
	private int peek;

	/**
	 * Beware the buffer array must not be used by any other thread.
	 * @param delegate batch iterator
	 * @param buffer buffer array
	 */
	public RoaringBitmapBatchArrayIterator(@Nonnull BatchIterator delegate, @Nonnull int[] buffer) {
		this.delegate = delegate;
		this.buffer = buffer;
	}

	@Override
	public boolean hasNext() {
		return this.delegate.hasNext();
	}

	@Override
	public int[] nextBatch() {
		this.peek = this.delegate.nextBatch(this.buffer);
		return this.buffer;
	}

	@Override
	public void advanceIfNeeded(int target) {
		this.delegate.advanceIfNeeded(target);
	}

	@Override
	public int getPeek() {
		return this.peek;
	}
}
