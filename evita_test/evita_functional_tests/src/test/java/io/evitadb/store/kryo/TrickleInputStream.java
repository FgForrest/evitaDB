/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.store.kryo;

import javax.annotation.Nonnull;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A test utility that wraps an {@link InputStream} and limits the number of bytes
 * returned per {@link #read(byte[], int, int)} call. This simulates slow I/O
 * or network streams where partial reads are common, forcing the consumer
 * to handle partially filled buffers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
class TrickleInputStream extends FilterInputStream {
	private final int maxBytesPerRead;

	/**
	 * Creates a TrickleInputStream that returns at most {@code maxBytesPerRead}
	 * bytes per {@link #read(byte[], int, int)} call.
	 *
	 * @param in              the underlying input stream
	 * @param maxBytesPerRead the maximum number of bytes to return per read call
	 */
	TrickleInputStream(@Nonnull InputStream in, int maxBytesPerRead) {
		super(in);
		this.maxBytesPerRead = maxBytesPerRead;
	}

	@Override
	public int read(@Nonnull byte[] b, int off, int len) throws IOException {
		return super.read(b, off, Math.min(len, this.maxBytesPerRead));
	}
}
