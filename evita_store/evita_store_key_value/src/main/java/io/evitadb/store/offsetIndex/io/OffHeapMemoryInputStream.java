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

package io.evitadb.store.offsetIndex.io;

import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Provides an InputStream implementation that reads from off-heap memory.
 * This class is not thread-safe.
 */
@NotThreadSafe
public class OffHeapMemoryInputStream extends InputStream {
	@Getter private ByteBuffer buffer;
	@Getter private final int written;
	private final Runnable closeCallback;

	public OffHeapMemoryInputStream(@Nonnull ByteBuffer buffer, @Nonnull Runnable closeCallback) {
		this.buffer = buffer;
		this.written = buffer.position();
		this.buffer.position(0);
		this.closeCallback = closeCallback;

	}

	public OffHeapMemoryInputStream(@Nonnull ByteBuffer buffer) {
		this.buffer = buffer;
		this.written = buffer.position();
		this.buffer.position(0);
		this.closeCallback = null;
	}

	private int remaining() {
		return written - buffer.position();
	}

	@Override
	public int read() throws IOException {
		if (remaining() > 0) {
			return (int) buffer.get();
		} else {
			return -1;
		}
	}

	@Override
	public int read(@Nonnull byte[] b) throws IOException {
		return read(b, 0, Math.min(written, b.length));
	}

	@Override
	public int read(@Nonnull byte[] b, int off, int len) {
		final int toRead = Math.min(Math.min(written, len), remaining() - off);
		buffer.get(b, off, toRead);
		return toRead;
	}

	@Override
	public byte[] readAllBytes() {
		final byte[] written = new byte[this.written];
		buffer.get(written);
		return written;
	}

	@Override
	public byte[] readNBytes(int len) {
		final int toRead = Math.min(Math.min(written, len), remaining());
		final byte[] result = new byte[toRead];
		buffer.get(result);
		return result;
	}

	@Override
	public int readNBytes(byte[] b, int off, int len) {
		final int toRead = Math.min(Math.min(written, len), remaining() - off);
		buffer.get(b, off, toRead);
		return toRead;
	}

	@Override
	public long skip(long n) throws IOException {
		buffer.position(Math.min(written, buffer.position() + (int) n));
		return buffer.position();
	}

	@Override
	public void skipNBytes(long n) {
		buffer.position(Math.min(written, buffer.position() + (int) n));
	}

	@Override
	public int available() throws IOException {
		return remaining();
	}

	@Override
	public synchronized void mark(int readlimit) {
		buffer.mark();
	}

	@Override
	public synchronized void reset() throws IOException {
		buffer.reset();
	}

	@Override
	public boolean markSupported() {
		return true;
	}

	@Override
	public void close() {
		buffer = null;
		if (closeCallback != null) {
			closeCallback.run();
		}
	}
}
