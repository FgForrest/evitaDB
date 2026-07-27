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

package io.evitadb.spi.store.catalog.persistence.storageParts.compressor;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.spi.store.catalog.exception.CompressionKeyUnknownException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.EXPORT;

/**
 * Verifies that {@link ReadOnlyKeyCompressorView} correctly delegates read-only operations to the underlying
 * {@link ReadWriteKeyCompressor} while preventing mutation through {@link ReadOnlyKeyCompressorView#getId(Comparable)}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(ENGINE)
@Tag(EXPORT)
class ReadOnlyKeyCompressorViewTest {

	private static final AttributeKey KEY_NAME = new AttributeKey("name");
	private static final AttributeKey KEY_CODE = new AttributeKey("code");
	private static final AttributeKey KEY_DESC = new AttributeKey("description", Locale.ENGLISH);
	private static final AttributeKey KEY_UNKNOWN = new AttributeKey("unknown");

	private ReadWriteKeyCompressor delegate;
	private ReadOnlyKeyCompressorView view;

	/**
	 * Creates a fresh {@link ReadWriteKeyCompressor} pre-loaded with three known keys and wraps it
	 * in a {@link ReadOnlyKeyCompressorView} under test.
	 */
	@BeforeEach
	void setUp() {
		this.delegate = createSeededWriteCompressor();
		this.view = new ReadOnlyKeyCompressorView(this.delegate);
	}

	@Nested
	@DisplayName("Read delegation")
	class ReadDelegation {

		@Test
		@DisplayName("getId() returns the integer ID assigned to a known key")
		void shouldReturnCorrectIdForKnownKey() {
			final int expectedId = delegate.getId(KEY_NAME);
			assertEquals(expectedId, view.getId(KEY_NAME));
		}

		@Test
		@DisplayName("getKeyForId() returns the original key for a known integer ID")
		void shouldReturnCorrectKeyForKnownId() {
			final int id = delegate.getId(KEY_CODE);
			final AttributeKey result = view.getKeyForId(id);
			assertEquals(KEY_CODE, result);
		}

		@Test
		@DisplayName("getKeyForIdIfExists() returns null when no key is mapped to the given ID")
		void shouldReturnNullForUnknownIdViaGetKeyForIdIfExists() {
			assertNull(view.getKeyForIdIfExists(99999));
		}

		@Test
		@DisplayName("getIdIfExists() returns present optional with correct ID for a known key")
		void shouldReturnPresentOptionalForKnownKeyViaGetIdIfExists() {
			final int expectedId = delegate.getId(KEY_DESC);
			final OptionalInt result = view.getIdIfExists(KEY_DESC);
			assertTrue(result.isPresent());
			assertEquals(expectedId, result.getAsInt());
		}

		@Test
		@DisplayName("getIdIfExists() returns empty optional for an unregistered key")
		void shouldReturnEmptyOptionalForUnknownKeyViaGetIdIfExists() {
			final OptionalInt result = view.getIdIfExists(KEY_UNKNOWN);
			assertTrue(result.isEmpty());
		}

	}

	@Nested
	@DisplayName("Immutability enforcement")
	class ImmutabilityEnforcement {

		@Test
		@DisplayName("getId() throws CompressionKeyUnknownException when called with an unregistered key")
		void shouldThrowCompressionKeyUnknownExceptionForUnknownKey() {
			final CompressionKeyUnknownException ex = assertThrows(
				CompressionKeyUnknownException.class,
				() -> view.getId(KEY_UNKNOWN)
			);
			assertTrue(ex.getMessage().contains(KEY_UNKNOWN.toString()));
		}

		@Test
		@DisplayName("getId() does not allocate a new ID in the underlying compressor for an unknown key")
		void shouldNotAllocateNewIdWhenGetIdCalledWithUnknownKey() {
			final int keyCountBefore = delegate.getKeys().size();

			assertThrows(CompressionKeyUnknownException.class, () -> view.getId(KEY_UNKNOWN));

			assertEquals(keyCountBefore, delegate.getKeys().size());
			assertTrue(delegate.getIdIfExists(KEY_UNKNOWN).isEmpty());
		}

		@Test
		@DisplayName("getKeys() returns an unmodifiable map")
		void shouldReturnUnmodifiableMapFromGetKeys() {
			final Map<Integer, Object> keys = view.getKeys();
			assertThrows(UnsupportedOperationException.class, () -> keys.put(99999, KEY_UNKNOWN));
		}

	}

	@Nested
	@DisplayName("Live view behavior")
	class LiveViewBehavior {

		@Test
		@DisplayName("getKeys() reflects keys added to the underlying compressor after view creation")
		void shouldReflectKeysAddedToUnderlyingCompressorAfterViewCreation() {
			final AttributeKey newKey = new AttributeKey("rating");
			final int newId = delegate.getId(newKey);

			final Map<Integer, Object> viewKeys = view.getKeys();
			assertTrue(viewKeys.containsKey(newId));
			assertEquals(newKey, viewKeys.get(newId));
		}

		@Test
		@DisplayName("getId() and getKeyForId() resolve keys added to the underlying compressor after view creation")
		void shouldReflectIdLookupForKeysAddedAfterViewCreation() {
			final AttributeKey newKey = new AttributeKey("weight");
			final int newId = delegate.getId(newKey);

			assertEquals(newId, view.getId(newKey));
			assertEquals(newKey, view.getKeyForId(newId));
		}

	}

	/**
	 * Creates a {@link ReadWriteKeyCompressor} initialized with an empty map and pre-registers
	 * three known keys ({@link #KEY_NAME}, {@link #KEY_CODE}, {@link #KEY_DESC}).
	 *
	 * @return a seeded write compressor with three entries
	 */
	@Nonnull
	private static ReadWriteKeyCompressor createSeededWriteCompressor() {
		final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Map.of());
		compressor.getId(KEY_NAME);
		compressor.getId(KEY_CODE);
		compressor.getId(KEY_DESC);
		return compressor;
	}

}
