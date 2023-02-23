/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.index.iterator;

import org.roaringbitmap.BatchIterator;

/**
 * Implementation of {@link BatchArrayIterator} for {@link org.roaringbitmap.RoaringBitmap}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class RoaringBitmapBatchArrayIterator implements BatchArrayIterator {
	private final int[] buffer;
	private final BatchIterator delegate;
	private int peek;

	public RoaringBitmapBatchArrayIterator(BatchIterator delegate) {
		this.delegate = delegate;
		this.buffer = new int[512];
	}

	@Override
	public boolean hasNext() {
		return delegate.hasNext();
	}

	@Override
	public int[] nextBatch() {
		this.peek = this.delegate.nextBatch(buffer);
		return buffer;
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
