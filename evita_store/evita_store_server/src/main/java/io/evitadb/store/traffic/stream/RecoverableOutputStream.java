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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.traffic.stream;


import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class RecoverableOutputStream extends OutputStream {
	private ByteBuffer buffer;
	private final Supplier<ByteBuffer> ofBufferFull;

	public int getBufferPosition() {
		return this.buffer == null ? -1 : this.buffer.position();
	}

	@Override
	public synchronized void write (int b) {
		if (this.buffer == null || !this.buffer.hasRemaining()) {
			// acquire new buffer
			this.buffer = this.ofBufferFull.get();
		}
		this.buffer.put((byte)b);
	}

	@Override
	public synchronized void write (@Nonnull byte[] bytes, int offset, int length) {
		if (this.buffer == null) {
			// acquire new buffer
			this.buffer = this.ofBufferFull.get();
			this.buffer.put(bytes, offset, length);
		} else if (this.buffer.remaining() < length) {
			final int bytesInExistingBlock = this.buffer.remaining();
			this.buffer.put(bytes, offset, bytesInExistingBlock);
			// acquire new buffer, and write the remaining bytes
			this.buffer = this.ofBufferFull.get();
			this.buffer.put(bytes, offset + bytesInExistingBlock, length - bytesInExistingBlock);
		} else {
			this.buffer.put(bytes, offset, length);
		}
	}

}
