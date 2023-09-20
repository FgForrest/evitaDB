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

package io.evitadb.core.query;

import com.esotericsoftware.kryo.util.Pool;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Shared pool of large buffer arrays that could be reused during query processing. Pool allows to minimize GC activity
 * a little bit.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ThreadSafe
public class SharedBufferPool extends Pool<int[]> {
	public static final SharedBufferPool INSTANCE = new SharedBufferPool(1000);

	private SharedBufferPool(int maximumCapacity) {
		super(true, false, maximumCapacity);
	}

	@Override
	protected int[] create() {
		return new int[512];
	}

}