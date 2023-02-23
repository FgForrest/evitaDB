/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.core.query;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies behaviour of {@link QueryContext}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class QueryContextTest {

	@Test
	void shouldProduceSameRandomRow() {
		final byte[] rndAsBytes;
		final ByteArrayOutputStream bos = new ByteArrayOutputStream(500);
		try (var os = new ObjectOutputStream(bos)) {
			os.writeObject(new Random());
			rndAsBytes = bos.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		final Random a = getRandom(rndAsBytes);
		final Random b = getRandom(rndAsBytes);

		for (int i = 0; i < 50; i++) {
			assertEquals(a.nextInt(), b.nextInt());
		}
	}

	public Random getRandom(byte[] rndAsBytes) {
		try (var is = new ObjectInputStream(new ByteArrayInputStream(rndAsBytes))) {
			return (Random)is.readObject();
		} catch (IOException | ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}
}