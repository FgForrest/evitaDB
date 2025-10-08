/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.store.catalog.task.stream;

import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * This input stream is aware of all bytes read from {@link #delegate}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class CountingInputStream extends InputStream {
	private final InputStream delegate;
	@Getter private long count = 0;

	public CountingInputStream(InputStream delegate) {
		this.delegate = delegate;
	}

	@Override
	public int read() throws IOException {
		int result = this.delegate.read();
		if (result != -1) {
			this.count++;
		}
		return result;
	}

	@Override
	public int read(@Nonnull byte[] b) throws IOException {
		int result = this.delegate.read(b);
		if (result != -1) {
			this.count += result;
		}
		return result;
	}

	@Override
	public int read(@Nonnull byte[] b, int off, int len) throws IOException {
		int result = this.delegate.read(b, off, len);
		if (result != -1) {
			this.count += result;
		}
		return result;
	}

	@Override
	public long skip(long n) throws IOException {
		long result = this.delegate.skip(n);
		this.count += result;
		return result;
	}

	@Override
	public int available() throws IOException {
		return this.delegate.available();
	}

	@Override
	public void close() throws IOException {
		this.delegate.close();
	}

	@Override
	public synchronized void mark(int readlimit) {
		this.delegate.mark(readlimit);
	}

	@Override
	public synchronized void reset() throws IOException {
		this.delegate.reset();
	}

	@Override
	public boolean markSupported() {
		return this.delegate.markSupported();
	}

	@Nonnull
	@Override
	public byte[] readAllBytes() throws IOException {
		byte[] result = this.delegate.readAllBytes();
		this.count += result.length;
		return result;
	}

	@Nonnull
	@Override
	public byte[] readNBytes(int len) throws IOException {
		byte[] result = this.delegate.readNBytes(len);
		this.count += result.length;
		return result;
	}

	@Override
	public int readNBytes(byte[] b, int off, int len) throws IOException {
		int result = this.delegate.readNBytes(b, off, len);
		if (result != -1) {
			this.count += result;
		}
		return result;
	}

	@Override
	public void skipNBytes(long n) throws IOException {
		this.delegate.skipNBytes(n);
		this.count += n;
	}

	@Override
	public long transferTo(OutputStream out) throws IOException {
		final long transferredBytes = this.delegate.transferTo(out);
		this.count += transferredBytes;
		return transferredBytes;
	}
}
