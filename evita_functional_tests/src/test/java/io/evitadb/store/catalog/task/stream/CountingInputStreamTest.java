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
		byteArrayInputStream = new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5});
		countingInputStream = new CountingInputStream(byteArrayInputStream);
	}

	@Test
	public void readIncreasesCount() throws IOException {
		countingInputStream.read();
		assertEquals(1, countingInputStream.getCount());
	}

	@Test
	public void readArrayIncreasesCountByArrayLength() throws IOException {
		byte[] b = new byte[3];
		countingInputStream.read(b);
		assertEquals(3, countingInputStream.getCount());
	}

	@Test
	public void readArrayWithOffsetAndLengthIncreasesCountByLength() throws IOException {
		byte[] b = new byte[5];
		countingInputStream.read(b, 1, 3);
		assertEquals(3, countingInputStream.getCount());
	}

	@Test
	public void skipIncreasesCountBySkippedBytes() throws IOException {
		countingInputStream.skip(2);
		assertEquals(2, countingInputStream.getCount());
	}

	@Test
	public void readAllBytesIncreasesCountByAllBytes() throws IOException {
		countingInputStream.readAllBytes();
		assertEquals(5, countingInputStream.getCount());
	}

	@Test
	public void readNBytesIncreasesCountByNBytes() throws IOException {
		countingInputStream.readNBytes(3);
		assertEquals(3, countingInputStream.getCount());
	}

	@Test
	public void readNBytesWithOffsetAndLengthIncreasesCountByLength() throws IOException {
		byte[] b = new byte[5];
		countingInputStream.readNBytes(b, 1, 3);
		assertEquals(3, countingInputStream.getCount());
	}

	@Test
	public void skipNBytesIncreasesCountByNBytes() throws IOException {
		countingInputStream.skipNBytes(2);
		assertEquals(2, countingInputStream.getCount());
	}

	@Test
	public void transferToIncreasesCountByTransferredBytes() throws IOException {
		countingInputStream.transferTo(new ByteArrayOutputStream());
		assertEquals(5, countingInputStream.getCount());
	}
}
