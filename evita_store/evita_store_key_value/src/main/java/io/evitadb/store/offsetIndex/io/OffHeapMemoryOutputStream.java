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

package io.evitadb.store.offsetIndex.io;

import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.function.BiConsumer;

/**
 * OffHeapMemoryOutputStream is a class that allows writing data to off-heap memory.
 * It provides an OutputStream implementation that writes to a ByteBuffer.
 * Only one instance of OffHeapMemoryOutputStream can be created for a specific buffer.
 *
 * Usage:
 * 1. Create a ByteBuffer to be used as the buffer for the output stream.
 * 2. Create a BiConsumer that will be executed when the output stream is closed.
 * 3. Initialize the OffHeapMemoryOutputStream by calling the init() method, providing the buffer and finalizer.
 * 4. Use the write() methods to write data to the output stream.
 * 5. Call the getInputStream() method to get an associated OffHeapMemoryInputStream for reading the data.
 * 6. Call the close() method to close either the output or the input stream and execute the finalizer.
 *
 * Note: The OffHeapMemoryOutputStream is not thread-safe, but the associated OffHeapMemoryInputStream is also not
 * thread-safe, so the usage should be synchronized externally if necessary.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@NotThreadSafe
@RequiredArgsConstructor
public class OffHeapMemoryOutputStream extends OutputStream {
	/**
	 * The region index of the OffHeapMemoryOutputStream in {@link CatalogOffHeapMemoryManager} memory block.
	 */
	@Getter private int regionIndex;
	/**
	 * Private ByteBuffer variable used for storing data in off-heap memory.
	 */
	private ByteBuffer buffer;
	/**
	 * Represents a finalizer function that takes an Integer and an OffHeapMemoryOutputStream
	 * as parameters and performs some action when the output stream is closed.
	 */
	private BiConsumer<Integer, OffHeapMemoryOutputStream> finalizer;
	/**
	 * Represents an OffHeapMemoryInputStream associated with this OffHeapMemoryOutputStream.
	 * It is created when the getInputStream() method is called for the first time.
	 */
	private OffHeapMemoryInputStream inputStream;
	/**
	 * Represents the position in the buffer where the next byte will be read.
	 */
	@Getter private int readPosition;
	/**
	 * Represents the position in the buffer where the next byte will be written.
	 */
	@Getter private int writePosition;
	/**
	 * Represents the current mode on the shared byte buffer.
	 */
	@Getter private Mode mode;

	/**
	 * Initializes the OffHeapMemoryOutputStream with the given buffer and finalizer.
	 * It must be called exactly once prior to using the output stream.
	 *
	 * @param buf        The ByteBuffer to be used as the buffer for the output stream. Must not be null.
	 * @param finalizer  The Runnable to be executed when the output stream is closed. Must not be null.
	 */
	public void init(int index, @Nonnull ByteBuffer buf, @Nonnull BiConsumer<Integer, OffHeapMemoryOutputStream> finalizer) {
		this.regionIndex = index;
		this.buffer = buf;
    	this.finalizer = finalizer;
		this.mode = Mode.WRITE;
	}

	/**
	 * Switches the mode of the OffHeapMemoryOutputStream shared byte buffer.
	 * If the current mode is READ, it can only be switched to WRITE mode.
	 * If the current mode is WRITE, it can only be switched to READ mode.
	 *
	 * @param newMode The new mode to switch to. Must not be null.
	 * @throws IllegalArgumentException If the new mode is invalid.
	 */
	private void switchMode(@Nonnull Mode newMode) {
		switch (this.mode) {
			case READ:
				Assert.isPremiseValid(
					newMode == Mode.WRITE,
					"Cannot switch from READ to WRITE mode!"
				);
				this.readPosition = this.buffer.position();
				this.buffer.position(this.writePosition);
				break;
			case WRITE:
				Assert.isPremiseValid(
					newMode == Mode.READ,
					"Cannot switch from WRITE to READ mode!"
				);
				this.writePosition = this.buffer.position();
				this.buffer.position(this.readPosition);
				break;
		}
		this.mode = newMode;
	}

	/**
	 * Retrieves the OffHeapMemoryInputStream associated with this OffHeapMemoryOutputStream.
	 * If the input stream has not been created yet, it is created and the underlying buffer is set to null.
	 *
	 * @return The OffHeapMemoryInputStream associated with this OffHeapMemoryOutputStream.
	 */
	@Nonnull
	public OffHeapMemoryInputStream getInputStream() {
		if (this.inputStream == null) {
			this.inputStream = new OffHeapMemoryInputStream(
				this.buffer,
				this::getMode,
				this::getWritePosition,
				this::switchMode,
				() -> this.inputStream = null
			);
			switchMode(Mode.READ);
		}
		return this.inputStream;
	}

	/**
	 * Retrieves the ByteBuffer associated with this OffHeapMemoryOutputStream.
	 * If the input stream has not been created yet, it is created and the underlying buffer is set to null.
	 *
	 * @return The ByteBuffer associated with this OffHeapMemoryOutputStream.
	 */
	@Nonnull
	public ByteBuffer getByteBuffer() {
		if (this.inputStream == null) {
			this.inputStream = new OffHeapMemoryInputStream(
				this.buffer,
				this::getMode,
				this::getWritePosition,
				this::switchMode,
				() -> this.inputStream = null
			);
			switchMode(Mode.READ);
		}
		return this.inputStream.getBuffer();
	}

	@Override
	public void write(int b) throws IOException {
		if (this.mode == Mode.READ) {
			switchMode(Mode.WRITE);
		}
		this.buffer.put((byte) b);
	}

	@Override
	public void write(@Nonnull byte[] bytes, int off, int len) throws IOException {
		if (this.mode == Mode.READ) {
			switchMode(Mode.WRITE);
		}
		this.buffer.put(bytes, off, len);
	}

	/**
	 * Retrieves the length of the peak data that has been written to the output stream.
	 *
	 * @return The length of the peak data written to the output stream.
	 */
	public long getPeakDataWrittenLength() {
		return this.mode == Mode.WRITE ? this.buffer.position() : this.writePosition;
	}

	/**
	 * Dumps the data from the buffer to the specified FileChannel.
	 *
	 * @param fileChannel The FileChannel to dump the data to. Must not be null.
	 * @throws IOException If an I/O error occurs while writing to the FileChannel.
	 */
	public void dumpToChannel(
		@Nonnull FileChannel fileChannel
	) throws IOException {
		if (this.mode == Mode.READ) {
			switchMode(Mode.WRITE);
		}
		// reset position to first byte before dumping data
		this.buffer.flip();
		// write all data in a cycle
		while(this.buffer.hasRemaining()) {
			final int written = fileChannel.write(this.buffer);
			Assert.isPremiseValid(
				written == this.buffer.limit(),
				"Failed to dump data to the file!"
			);
		}
	}

	@Override
	public void close() {
		if (this.inputStream != null) {
			final OffHeapMemoryInputStream theInputStream = this.inputStream;
			this.inputStream = null;
			theInputStream.close();
		}
		if (this.finalizer != null) {
			this.finalizer.accept(this.regionIndex, this);
			this.buffer = null;
			this.finalizer = null;
			this.inputStream = null;
		}
	}

	/**
	 * Represents the current mode on the shared byte buffer. Since we don't support parallel access, but we do support
	 * interleaving reading and writing from the same buffer, we need to keep track of the current mode.
	 */
	enum Mode {
		READ, WRITE
	}

}
