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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This class verifies behavior of {@link CountingInputStream}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class CountingInputStreamTest {
	private CountingInputStream countingInputStream;
	private ByteArrayInputStream byteArrayInputStream;

	@BeforeEach
	public void setup() {
		this.byteArrayInputStream = new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5});
		this.countingInputStream = new CountingInputStream(this.byteArrayInputStream);
	}

	@Test
	public void readIncreasesCount() throws IOException {
		this.countingInputStream.read();
		assertEquals(1, this.countingInputStream.getCount());
	}

	@Test
	public void readArrayIncreasesCountByArrayLength() throws IOException {
		byte[] b = new byte[3];
		this.countingInputStream.read(b);
		assertEquals(3, this.countingInputStream.getCount());
	}

	@Test
	public void readArrayWithOffsetAndLengthIncreasesCountByLength() throws IOException {
		byte[] b = new byte[5];
		this.countingInputStream.read(b, 1, 3);
		assertEquals(3, this.countingInputStream.getCount());
	}

	@Test
	public void skipIncreasesCountBySkippedBytes() throws IOException {
		this.countingInputStream.skip(2);
		assertEquals(2, this.countingInputStream.getCount());
	}

	@Test
	public void readAllBytesIncreasesCountByAllBytes() throws IOException {
		this.countingInputStream.readAllBytes();
		assertEquals(5, this.countingInputStream.getCount());
	}

	@Test
	public void readNBytesIncreasesCountByNBytes() throws IOException {
		this.countingInputStream.readNBytes(3);
		assertEquals(3, this.countingInputStream.getCount());
	}

	@Test
	public void readNBytesWithOffsetAndLengthIncreasesCountByLength() throws IOException {
		byte[] b = new byte[5];
		this.countingInputStream.readNBytes(b, 1, 3);
		assertEquals(3, this.countingInputStream.getCount());
	}

	@Test
	public void skipNBytesIncreasesCountByNBytes() throws IOException {
		this.countingInputStream.skipNBytes(2);
		assertEquals(2, this.countingInputStream.getCount());
	}

	@Test
	public void transferToIncreasesCountByTransferredBytes() throws IOException {
		this.countingInputStream.transferTo(new ByteArrayOutputStream());
		assertEquals(5, this.countingInputStream.getCount());
	}
}
