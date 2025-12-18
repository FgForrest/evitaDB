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

package io.evitadb.stream;

import io.evitadb.exception.FileChecksumInvalidException;
import io.evitadb.utils.Crc32Calculator;

import javax.annotation.Nonnull;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Input stream wrapper that verifies CRC32 checksum on close. The verification is performed only if the
 * stream is read completely (number of read bytes equals to the expected file size).
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class Crc32VerifyingInputStream extends FilterInputStream {
	/**
	 * Expected CRC32 checksum of the file.
	 */
	private final long expectedChecksum;
	/**
	 * Expected size of the file in bytes.
	 */
	private final long expectedFileSize;
	/**
	 * Calculator used to compute CRC32 checksum of the read bytes.
	 */
	private final Crc32Calculator crcCalculator;
	/**
	 * Number of bytes read from the stream.
	 */
	private long bytesRead;

	/**
	 * Creates a new input stream that verifies CRC32 checksum on close.
	 *
	 * @param in               the underlying input stream
	 * @param expectedChecksum expected CRC32 checksum of the file
	 * @param expectedFileSize expected size of the file in bytes
	 */
	public Crc32VerifyingInputStream(@Nonnull InputStream in, long expectedChecksum, long expectedFileSize) {
		super(in);
		this.expectedChecksum = expectedChecksum;
		this.expectedFileSize = expectedFileSize;
		this.crcCalculator = new Crc32Calculator();
		this.bytesRead = 0;
	}

	@Override
	public int read() throws IOException {
		int b = super.read();
		if (b != -1) {
			this.crcCalculator.withByte((byte) b);
			this.bytesRead++;
		}
		return b;
	}

	@Override
	public int read(@Nonnull byte[] b, int off, int len) throws IOException {
		int read = super.read(b, off, len);
		if (read != -1) {
			this.crcCalculator.withByteArray(b, off, read);
			this.bytesRead += read;
		}
		return read;
	}

	@Override
	public void close() throws IOException {
		super.close();
		if (this.bytesRead >= this.expectedFileSize) {
			long actualChecksum = this.crcCalculator.getValue();
			if (actualChecksum != this.expectedChecksum && this.expectedChecksum != 0) {
				throw new FileChecksumInvalidException(
					"File checksum mismatch. Expected: " + this.expectedChecksum + ", Actual: " + actualChecksum,
					"File checksum mismatch."
				);
			}
		}
	}
}
