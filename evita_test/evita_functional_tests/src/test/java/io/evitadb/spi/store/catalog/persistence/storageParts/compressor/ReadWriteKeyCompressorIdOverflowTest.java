/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.spi.store.catalog.persistence.storageParts.compressor;

import io.evitadb.api.exception.IdentifierOverflowException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link ReadWriteKeyCompressor} refuses to mint a key id past `Integer.MAX_VALUE` with an
 * {@link IdentifierOverflowException} telling the operator what helps, instead of wrapping into the negative range,
 * and that it refuses before either of its indexes changes. The compressor starts just below the limit through the
 * constructor every storage file's key dictionary is restored by.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(SERIALIZATION)
@DisplayName("ReadWriteKeyCompressor key id overflow")
class ReadWriteKeyCompressorIdOverflowTest {
	private static final AttributeKey PERSISTED = new AttributeKey("persisted");
	private static final AttributeKey LAST = new AttributeKey("last");
	private static final AttributeKey REFUSED = new AttributeKey("refused");

	@Test
	@DisplayName("hands out the last key id, then refuses a new key without changing anything")
	void shouldHandOutTheLastKeyIdAndRefuseTheNext() {
		final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Map.of(Integer.MAX_VALUE - 1, PERSISTED));

		assertEquals(Integer.MAX_VALUE, compressor.getId(LAST), "The last key id is still handed out.");
		compressor.resetDirtyFlag();

		final IdentifierOverflowException exception = assertThrows(
			IdentifierOverflowException.class, () -> compressor.getId(REFUSED)
		);
		assertTrue(
			exception.getPrivateMessage().contains(String.valueOf(REFUSED)) &&
				exception.getPrivateMessage().contains(String.valueOf(Integer.MAX_VALUE)),
			"The private message must name the key and the position: " + exception.getPrivateMessage()
		);
		final String publicMessage = exception.getPublicMessage();
		assertTrue(
			publicMessage.contains("key dictionary of a storage file is exhausted") &&
				publicMessage.contains("neither a restart nor a compaction helps") &&
				publicMessage.contains("`replaceCollection`") &&
				publicMessage.contains("`replaceCatalog`"),
			"The public message must name the id space and the remedy: " + publicMessage
		);

		// nothing changed: the refused key is unknown, the peak stays and nothing is left to persist
		assertFalse(compressor.getIdIfExists(REFUSED).isPresent());
		assertEquals(Integer.MAX_VALUE, compressor.getPeakId());
		assertEquals(2, compressor.getKeys().size());
		assertFalse(compressor.resetDirtyFlag(), "A refused key must not mark the compressor dirty.");
		// a known key still resolves, and an unknown one stays refused
		assertEquals(Integer.MAX_VALUE, compressor.getId(LAST));
		assertEquals(Integer.MAX_VALUE - 1, compressor.getId(PERSISTED));
		assertThrows(IdentifierOverflowException.class, () -> compressor.getId(REFUSED));
	}

	@Test
	@DisplayName("a dictionary restored with the last key id refuses its very first new key")
	void shouldRefuseTheFirstNewKeyOfADictionaryRestoredAtTheLimit() {
		final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Map.of(Integer.MAX_VALUE, PERSISTED));

		assertThrows(IdentifierOverflowException.class, () -> compressor.getId(REFUSED));
		assertEquals(Integer.MAX_VALUE, compressor.getPeakId());
		assertEquals(1, compressor.getKeys().size());
	}

}
