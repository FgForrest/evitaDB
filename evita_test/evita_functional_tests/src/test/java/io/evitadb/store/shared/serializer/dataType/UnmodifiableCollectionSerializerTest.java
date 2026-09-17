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

package io.evitadb.store.shared.serializer.dataType;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kryo round-trip coverage for the unmodifiable collection views registered by {@link KryoFactory} -
 * {@link UnmodifiableListSerializer}, {@link UnmodifiableMapSerializer} and {@link UnmodifiableSetSerializer}.
 *
 * The serializers these three extend follow a create-then-populate contract: `read` asks its factory for
 * an instance and then fills it item by item. A factory that hands out the unmodifiable view itself therefore
 * reads back only while the collection is empty and throws {@link UnsupportedOperationException} the moment
 * there is a first item to store. The non-empty cases below pin that down; the empty ones keep the path that
 * always worked from regressing.
 *
 * The golden byte arrays pin the write side. Only the object handed back by `read` changed, so a collection
 * of the same contents must still serialize to the very same bytes that are already sitting in persisted
 * catalogs - and those bytes must still read back.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Unmodifiable collection Kryo round-trip (populate, then wrap)")
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(DATA_TYPE)
class UnmodifiableCollectionSerializerTest {

	/**
	 * Serializes the passed collection through the default storage Kryo configuration.
	 */
	@Nonnull
	private static byte[] serialize(@Nonnull Object collection) {
		final Kryo kryo = KryoFactory.createKryo();
		final ByteArrayOutputStream os = new ByteArrayOutputStream(64);
		try (final Output output = new Output(os, 64)) {
			kryo.writeObject(output, collection);
		}
		return os.toByteArray();
	}

	/**
	 * Reads the passed bytes back as an instance of `type` through the default storage Kryo configuration.
	 */
	@Nonnull
	private static Object deserialize(@Nonnull byte[] bytes, @Nonnull Class<?> type) {
		final Kryo kryo = KryoFactory.createKryo();
		try (final Input input = new Input(bytes)) {
			return kryo.readObject(input, type);
		}
	}

	/**
	 * Writes the passed collection and reads it back, returning the deserialized instance.
	 */
	@Nonnull
	private static Object roundTrip(@Nonnull Object collection) {
		return deserialize(serialize(collection), collection.getClass());
	}

	@Nested
	@DisplayName("Collections.unmodifiableList")
	class UnmodifiableLists {
		/** Bytes of `unmodifiableList(["alpha", "beta", "gamma"])` as written before the read path was fixed. */
		private static final byte[] GOLDEN_NON_EMPTY = new byte[]{
			3, 105, 97, 108, 112, 104, -31, 98, 101, 116, -31, 103, 97, 109, 109, -31
		};

		@Nonnull
		private static List<String> nonEmpty() {
			return Collections.unmodifiableList(new ArrayList<>(List.of("alpha", "beta", "gamma")));
		}

		@Test
		@DisplayName("round-trips a non-empty list and keeps it unmodifiable")
		void shouldRoundTripNonEmptyListWhenItemsArePresent() {
			final List<String> original = nonEmpty();

			@SuppressWarnings("unchecked") final List<String> deserialized = (List<String>) roundTrip(original);

			assertEquals(original, deserialized);
			assertEquals(original.getClass(), deserialized.getClass());
			assertThrows(UnsupportedOperationException.class, () -> deserialized.add("delta"));
		}

		@Test
		@DisplayName("round-trips an empty list and keeps it unmodifiable")
		void shouldRoundTripEmptyListWhenNoItemsArePresent() {
			final List<String> original = Collections.unmodifiableList(new ArrayList<>());

			@SuppressWarnings("unchecked") final List<String> deserialized = (List<String>) roundTrip(original);

			assertTrue(deserialized.isEmpty());
			assertEquals(original.getClass(), deserialized.getClass());
			assertThrows(UnsupportedOperationException.class, () -> deserialized.add("delta"));
		}

		@Test
		@DisplayName("writes the unchanged byte layout and reads persisted bytes back")
		void shouldKeepByteLayoutWhenListIsWritten() {
			assertArrayEquals(GOLDEN_NON_EMPTY, serialize(nonEmpty()));

			@SuppressWarnings("unchecked") final List<String> deserialized =
				(List<String>) deserialize(GOLDEN_NON_EMPTY, nonEmpty().getClass());

			assertEquals(List.of("alpha", "beta", "gamma"), deserialized);
		}
	}

	@Nested
	@DisplayName("Collections.unmodifiableMap")
	class UnmodifiableMaps {
		/** Bytes of `unmodifiableMap({one=1, two=2, three=3})` as written before the read path was fixed. */
		private static final byte[] GOLDEN_NON_EMPTY = new byte[]{
			3, 105, 113, 111, 110, -27, 2, 116, 119, -17, 4, 116, 104, 114, 101, -27, 6
		};

		@Nonnull
		private static Map<String, Integer> nonEmpty() {
			final LinkedHashMap<String, Integer> contents = new LinkedHashMap<>();
			contents.put("one", 1);
			contents.put("two", 2);
			contents.put("three", 3);
			return Collections.unmodifiableMap(contents);
		}

		@Test
		@DisplayName("round-trips a non-empty map and keeps it unmodifiable")
		void shouldRoundTripNonEmptyMapWhenEntriesArePresent() {
			final Map<String, Integer> original = nonEmpty();

			@SuppressWarnings("unchecked") final Map<String, Integer> deserialized =
				(Map<String, Integer>) roundTrip(original);

			assertEquals(original, deserialized);
			assertEquals(original.getClass(), deserialized.getClass());
			assertThrows(UnsupportedOperationException.class, () -> deserialized.put("four", 4));
		}

		@Test
		@DisplayName("round-trips an empty map and keeps it unmodifiable")
		void shouldRoundTripEmptyMapWhenNoEntriesArePresent() {
			final Map<String, Integer> original = Collections.unmodifiableMap(new LinkedHashMap<>());

			@SuppressWarnings("unchecked") final Map<String, Integer> deserialized =
				(Map<String, Integer>) roundTrip(original);

			assertTrue(deserialized.isEmpty());
			assertEquals(original.getClass(), deserialized.getClass());
			assertThrows(UnsupportedOperationException.class, () -> deserialized.put("four", 4));
		}

		@Test
		@DisplayName("writes the unchanged byte layout and reads persisted bytes back")
		void shouldKeepByteLayoutWhenMapIsWritten() {
			assertArrayEquals(GOLDEN_NON_EMPTY, serialize(nonEmpty()));

			@SuppressWarnings("unchecked") final Map<String, Integer> deserialized =
				(Map<String, Integer>) deserialize(GOLDEN_NON_EMPTY, nonEmpty().getClass());

			assertEquals(Map.of("one", 1, "two", 2, "three", 3), deserialized);
		}
	}

	@Nested
	@DisplayName("Collections.unmodifiableSet")
	class UnmodifiableSets {
		/** Bytes of `unmodifiableSet(["red", "green", "blue"])` as written before the read path was fixed. */
		private static final byte[] GOLDEN_NON_EMPTY = new byte[]{
			3, 105, 114, 101, -28, 103, 114, 101, 101, -18, 98, 108, 117, -27
		};

		@Nonnull
		private static Set<String> nonEmpty() {
			return Collections.unmodifiableSet(new LinkedHashSet<>(List.of("red", "green", "blue")));
		}

		@Test
		@DisplayName("round-trips a non-empty set and keeps it unmodifiable")
		void shouldRoundTripNonEmptySetWhenItemsArePresent() {
			final Set<String> original = nonEmpty();

			@SuppressWarnings("unchecked") final Set<String> deserialized = (Set<String>) roundTrip(original);

			assertEquals(original, deserialized);
			assertEquals(original.getClass(), deserialized.getClass());
			assertThrows(UnsupportedOperationException.class, () -> deserialized.add("yellow"));
		}

		@Test
		@DisplayName("round-trips an empty set and keeps it unmodifiable")
		void shouldRoundTripEmptySetWhenNoItemsArePresent() {
			final Set<String> original = Collections.unmodifiableSet(new LinkedHashSet<>());

			@SuppressWarnings("unchecked") final Set<String> deserialized = (Set<String>) roundTrip(original);

			assertTrue(deserialized.isEmpty());
			assertEquals(original.getClass(), deserialized.getClass());
			assertThrows(UnsupportedOperationException.class, () -> deserialized.add("yellow"));
		}

		@Test
		@DisplayName("writes the unchanged byte layout and reads persisted bytes back")
		void shouldKeepByteLayoutWhenSetIsWritten() {
			assertArrayEquals(GOLDEN_NON_EMPTY, serialize(nonEmpty()));

			@SuppressWarnings("unchecked") final Set<String> deserialized =
				(Set<String>) deserialize(GOLDEN_NON_EMPTY, nonEmpty().getClass());

			assertEquals(Set.of("red", "green", "blue"), deserialized);
		}
	}

}
