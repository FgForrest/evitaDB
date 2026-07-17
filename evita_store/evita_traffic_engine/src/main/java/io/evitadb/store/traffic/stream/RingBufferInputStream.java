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
		// `position` is the physical offset of the next byte to read; wrap BEFORE reading so it
		// stays in lock-step with the delegate's file pointer (otherwise the next wrap fires late
		// and reads one byte past the physical end of the ring buffer)
		if (this.position >= this.inputBufferSize) {
			this.position = 0L;
			this.delegatingInputStream.seek(this.position);
		}
		final int result = this.delegatingInputStream.read();
		if (result >= 0) {
			this.position++;
		}
		return result;
	}

	public int read(@Nonnull byte[] buffer, int off, int len) throws IOException {
		// sitting exactly on the physical end of the region - restart from the beginning first
		if (this.position >= this.inputBufferSize) {
			this.position = 0L;
			this.delegatingInputStream.seek(this.position);
		}
		if (this.position + len > this.inputBufferSize) {
			// the requested range straddles the physical end of the ring buffer - split it into a
			// tail read (up to the end of the region) and a head read (from the beginning)
			final int bytesUpToEOF = (int) (this.inputBufferSize - this.position);
			final int tailRead = this.delegatingInputStream.read(buffer, off, bytesUpToEOF);
			if (tailRead < bytesUpToEOF) {
				// short tail read - advance by what was actually returned and stop; the caller will
				// re-invoke read() to obtain the remaining bytes
				this.position += Math.max(tailRead, 0);
				return tailRead;
			}
			this.position = 0L;
			this.delegatingInputStream.seek(this.position);
			final int headRead = this.delegatingInputStream.read(buffer, off + bytesUpToEOF, len - bytesUpToEOF);
			// advance `position` by the bytes actually read from the head segment (never leave it at 0)
			this.position = Math.max(headRead, 0);
			return tailRead + Math.max(headRead, 0);
		} else {
			// advance by the bytes actually read, not the requested length, so a short read cannot
			// desync `position` from the delegate's file pointer
			final int bytesRead = this.delegatingInputStream.read(buffer, off, len);
			if (bytesRead > 0) {
				this.position += bytesRead;
			}
			return bytesRead;
		}
	}

	@Override
	public long skip(long n) throws IOException {
		// honor the InputStream#skip contract: a non-positive request skips nothing and returns 0
		// (a negative value would otherwise walk `position` backwards past the modulo guard and seek negative)
		if (n <= 0L) {
			return 0L;
		}
		this.position += n;
		if (this.position >= this.inputBufferSize) {
			this.position = this.position % this.inputBufferSize;
		}
		// always move the delegate's file pointer - even when the skip stays within the region -
		// otherwise the subsequent read would come from the un-skipped offset
		this.delegatingInputStream.seek(this.position);
		return n;
	}

	@Override
	public void skipNBytes(long n) throws IOException {
		// honor the InputStream#skipNBytes contract: a non-positive request skips nothing (see #skip)
		if (n <= 0L) {
			return;
		}
		this.position += n;
		if (this.position >= this.inputBufferSize) {
			this.position = this.position % this.inputBufferSize;
		}
		// always move the delegate's file pointer (see #skip)
		this.delegatingInputStream.seek(this.position);
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
