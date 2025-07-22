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

package io.evitadb.store.offsetIndex.io;

import io.evitadb.store.offsetIndex.io.OffHeapMemoryOutputStream.Mode;
import io.evitadb.stream.AbstractRandomAccessInputStream;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Provides an InputStream implementation that reads from off-heap memory.
 * This class is not thread-safe.
 */
@NotThreadSafe
public class OffHeapMemoryInputStream extends AbstractRandomAccessInputStream {
	@Nonnull private final Supplier<Mode> bufferModeSupplier;
	@Nonnull private final IntSupplier writePositionSupplier;
	@Nonnull private final Consumer<Mode> switchModeCallback;
	@Getter private ByteBuffer buffer;
	private final Runnable closeCallback;

	public OffHeapMemoryInputStream(
		@Nonnull ByteBuffer buffer,
		@Nonnull Supplier<Mode> bufferModeSupplier,
		@Nonnull IntSupplier writePositionSupplier,
		@Nonnull Consumer<Mode> switchModeCallback,
		@Nonnull Runnable closeCallback
	) {
		this.buffer = buffer;
		this.bufferModeSupplier = bufferModeSupplier;
		this.writePositionSupplier = writePositionSupplier;
		this.switchModeCallback = switchModeCallback;
		this.closeCallback = closeCallback;
	}

	@Override
	public void seek(long position) {
		switchToReadIfNecessary();
		Assert.isPremiseValid(position < getWrittenBytes(), "Cannot seek past the end of the stream.");
		this.buffer.position((int) position);
	}

	@Override
	public long getLength() {
		return getWrittenBytes();
	}

	@Override
	public int read() throws IOException {
		if (available() > 0) {
			return (int) this.buffer.get();
		} else {
			return -1;
		}
	}

	@Override
	public int read(@Nonnull byte[] b) throws IOException {
		switchToReadIfNecessary();
		final int toRead = Math.min(Math.min(getWrittenBytes(), b.length), available());
		this.buffer.get(b, 0, toRead);
		return toRead;
	}

	@Override
	public int read(@Nonnull byte[] b, int off, int len) {
		switchToReadIfNecessary();
		final int toRead = Math.min(Math.min(getWrittenBytes(), len), available() - off);
		this.buffer.get(b, off, toRead);
		return toRead;
	}

	@Override
	public byte[] readAllBytes() {
		switchToReadIfNecessary();
		final byte[] writtenData = new byte[this.getWrittenBytes()];
		buffer.get(writtenData);
		return writtenData;
	}

	@Override
	public byte[] readNBytes(int len) {
		switchToReadIfNecessary();
		final int toRead = Math.min(Math.min(getWrittenBytes(), len), available());
		final byte[] result = new byte[toRead];
		this.buffer.get(result);
		return result;
	}

	@Override
	public int readNBytes(byte[] b, int off, int len) {
		switchToReadIfNecessary();
		final int toRead = Math.min(Math.min(getWrittenBytes(), len), available() - off);
		this.buffer.get(b, off, toRead);
		return toRead;
	}

	@Override
	public long skip(long n) throws IOException {
		switchToReadIfNecessary();
		this.buffer.position(Math.min(getWrittenBytes(), buffer.position() + (int) n));
		return this.buffer.position();
	}

	@Override
	public void skipNBytes(long n) {
		switchToReadIfNecessary();
		this.buffer.position(Math.min(getWrittenBytes(), buffer.position() + (int) n));
	}

	@Override
	public int available() {
		switchToReadIfNecessary();
		return getWrittenBytes() - this.buffer.position();
	}

	@Override
	public synchronized void mark(int readlimit) {
		switchToReadIfNecessary();
		this.buffer.mark();
	}

	@Override
	public synchronized void reset() throws IOException {
		switchToReadIfNecessary();
		this.buffer.reset();
	}

	@Override
	public boolean markSupported() {
		return true;
	}

	@Override
	public void close() {
		switchToReadIfNecessary();
		this.buffer.position(0);
		this.buffer = null;
		this.closeCallback.run();
	}

	/**
	 * Checks the current buffer mode and switches to READ mode if necessary.
	 * If the buffer mode is WRITE, the switchModeCallback function is invoked with the READ mode.
	 */
	private void switchToReadIfNecessary() {
		if (bufferModeSupplier.get() == Mode.WRITE) {
			switchModeCallback.accept(Mode.READ);
		}
	}

	/**
	 * Returns the number of bytes that have been getWrittenBytes() to the buffer.
	 *
	 * @return The number of getWrittenBytes() bytes.
	 */
	private int getWrittenBytes() {
		return this.writePositionSupplier.getAsInt();
	}
}
