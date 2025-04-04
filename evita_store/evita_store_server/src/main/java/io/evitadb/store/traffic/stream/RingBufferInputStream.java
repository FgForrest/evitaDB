/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.store.traffic.stream;


import io.evitadb.stream.RandomAccessFileInputStream;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;

/**
 * Implementation of an {@link InputStream} that wraps around a {@link RandomAccessFileInputStream}, utilizing a ring buffer mechanism.
 * The stream processes a finite-size input, and upon reaching the end of the buffer, it resets to the beginning,
 * enabling continuous looping over the buffered input stream.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class RingBufferInputStream extends InputStream {
	private final RandomAccessFileInputStream delegatingInputStream;
	private final long inputBufferSize;
	private long position;

	public RingBufferInputStream(
		@Nonnull RandomAccessFileInputStream delegatingInputStream,
		long inputBufferSize,
		long startPosition
	) {
		this.delegatingInputStream = delegatingInputStream;
		this.inputBufferSize = inputBufferSize;
		this.position = startPosition;
		this.delegatingInputStream.seek(startPosition);
	}

	@Override
	public int read() throws IOException {
		this.position++;
		if (this.position > this.inputBufferSize) {
			this.position = 0L;
			this.delegatingInputStream.seek(this.position);
		}
		return this.delegatingInputStream.read();
	}

	public int read(@Nonnull byte[] buffer, int off, int len) throws IOException {
		if (this.position + len > this.inputBufferSize) {
			final int bytesUpToEOF = (int) (this.inputBufferSize - this.position);
			final int tailRead = this.delegatingInputStream.read(buffer, off, bytesUpToEOF);
			this.position = 0L;
			this.delegatingInputStream.seek(this.position);
			final int headRead = this.delegatingInputStream.read(buffer, off + bytesUpToEOF, len - bytesUpToEOF);
			return tailRead + headRead;
		} else {
			this.position += len;
			return this.delegatingInputStream.read(buffer, off, len);
		}
	}

	@Override
	public long skip(long n) throws IOException {
		this.position += n;
		if (this.position > this.inputBufferSize) {
			this.position = this.position % this.inputBufferSize;
			this.delegatingInputStream.seek(this.position);
		}
		return n;
	}

	@Override
	public void skipNBytes(long n) throws IOException {
		this.position += n;
		if (this.position > this.inputBufferSize) {
			this.position = this.position % this.inputBufferSize;
			this.delegatingInputStream.seek(this.position);
		}
	}

	@Override
	public int available() throws IOException {
		return Math.toIntExact(this.inputBufferSize);
	}

	@Override
	public synchronized void mark(int readlimit) {
		this.delegatingInputStream.mark(readlimit);
	}

	@Override
	public synchronized void reset() throws IOException {
		this.delegatingInputStream.reset();
	}

	@Override
	public boolean markSupported() {
		return this.delegatingInputStream.markSupported();
	}

	@Override
	public long transferTo(OutputStream out) throws IOException {
		throw new UnsupportedEncodingException("Transfer to is not supported for ring buffer input stream");
	}
}
