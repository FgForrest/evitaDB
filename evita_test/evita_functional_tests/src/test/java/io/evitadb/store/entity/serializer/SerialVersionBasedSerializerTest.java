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

package io.evitadb.store.entity.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.DefaultSerializers.StringSerializer;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.entity.exception.StoredVersionNotSupportedException;
import io.evitadb.store.schema.serializer.AttributeSchemaSerializer;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.io.ObjectStreamClass;
import java.util.Set;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SERIALIZATION;

/**
 * This test verifies whether it is possible to use special instance for deserializing old versions of the class
 * based on `serialVersionUID` field value.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Versioned serializer read and skip dispatch")
@Tag(STORAGE)
@Tag(MANAGEMENT)
@Tag(SERIALIZATION)
class SerialVersionBasedSerializerTest {
	private final Kryo kryo = new Kryo();
	private io.evitadb.store.entity.serializer.SerialVersionBasedSerializer<AttributeSchema> attributeSchemaSerializer;

	@BeforeEach
	void setUp() {
		this.attributeSchemaSerializer = new io.evitadb.store.entity.serializer.SerialVersionBasedSerializer<>(new AttributeSchemaSerializer(), AttributeSchema.class);
		this.kryo.register(AttributeSchema.class, this.attributeSchemaSerializer);
		this.kryo.register(OldAttributeSchema.class, new io.evitadb.store.entity.serializer.SerialVersionBasedSerializer<>(new OldAttributeSchemaSerializer(), OldAttributeSchema.class));
	}

	@Test
	void shouldFailToDeserializeOldVersion() {
		final ByteArrayOutputStream baos = writeOldFormat();
		try (final ByteBufferInput input = new ByteBufferInput(new ByteArrayInputStream(baos.toByteArray()))) {
			assertThrows(StoredVersionNotSupportedException.class, () -> this.kryo.readObject(input, AttributeSchema.class));
		}
	}

	@Test
	void shouldDeserializeOldVersion() {
		this.attributeSchemaSerializer.addBackwardCompatibleSerializer(
				-7465757161178326011L, new OldAttributeSchemaDeserializer()
		);

		final ByteArrayOutputStream baos = writeOldFormat();
		try (final ByteBufferInput input = new ByteBufferInput(new ByteArrayInputStream(baos.toByteArray()))) {
			final AttributeSchema attributeSchema = this.kryo.readObject(input, AttributeSchema.class);
			assertEquals("A", attributeSchema.getName());
			assertEquals(String.class, attributeSchema.getType());
		}
	}

	/**
	 * Selects distinct real payload readers for current and old UIDs and verifies the skip contract is used.
	 */
	@Test
	@DisplayName("dispatches reads and skips for current and historical UIDs")
	void shouldDispatchReadsAndSkipsWithoutMaterializingSkippedValues() {
		final CountingStringSerializer current = new CountingStringSerializer();
		final CountingStringSerializer old = new CountingStringSerializer();
		final SerialVersionBasedSerializer<String> serializer = new SerialVersionBasedSerializer<>(current, String.class);
		serializer.addBackwardCompatibleSerializer(123L, old);
		for (final long uid : new long[]{ObjectStreamClass.lookup(String.class).getSerialVersionUID(), 123L}) {
			final byte[] bytes = versionedString(uid);
			try (final Input input = new Input(bytes)) {
				assertEquals("payload", serializer.read(this.kryo, input, String.class));
				assertEquals(bytes.length - 4, input.total());
				assertEquals(987654321, input.readInt());
			}
			try (final Input input = new Input(bytes)) {
				serializer.skip(this.kryo, input, String.class);
				assertEquals(bytes.length - 4, input.total());
				assertEquals(987654321, input.readInt());
			}
		}
		assertEquals(1, current.reads);
		assertEquals(1, current.skips);
		assertEquals(1, old.reads);
		assertEquals(1, old.skips);
	}

	/**
	 * Verifies that ordinary serializers remain skippable by decoding their payload through the fallback.
	 */
	@Test
	@DisplayName("falls back to reading when a current or historical serializer has no skip implementation")
	void shouldFallBackToReadForSerializersWithoutSkip() {
		final Serializer<String> payload = new StringSerializer();
		final SerialVersionBasedSerializer<String> serializer = new SerialVersionBasedSerializer<>(payload, String.class);
		serializer.addBackwardCompatibleSerializer(123L, payload);
		for (final long uid : new long[]{ObjectStreamClass.lookup(String.class).getSerialVersionUID(), 123L}) {
			final byte[] bytes = versionedString(uid);
			try (final Input input = new Input(bytes)) {
				serializer.skip(this.kryo, input, String.class);
				assertEquals(bytes.length - 4, input.total());
				assertEquals(987654321, input.readInt());
			}
		}
	}

	/**
	 * Pins the exception type, offending UID and supported UID contents for both operations and both registry states.
	 */
	@Test
	@DisplayName("preserves unsupported UID errors for reads and skips")
	void shouldRejectUnknownUidForReadAndSkip() {
		final SerialVersionBasedSerializer<String> serializer =
			new SerialVersionBasedSerializer<>(new CountingStringSerializer(), String.class);
		final Set<Long> supportedVersions = CollectionUtils.createHashSet(40);
		assertUnsupportedUid(serializer, false, supportedVersions);
		assertUnsupportedUid(serializer, true, supportedVersions);

		for (int i = 0; i < 40; i++) {
			final long uid = i * 32L + 1;
			final Serializer<String> reader = new CountingStringSerializer();
			serializer.addBackwardCompatibleSerializer(uid, reader);
			supportedVersions.add(uid);
		}

		assertUnsupportedUid(serializer, false, supportedVersions);
		assertUnsupportedUid(serializer, true, supportedVersions);
	}

	/**
	 * Rejects duplicate historical UIDs and preserves the first reader for subsequent reads and skips.
	 */
	@Test
	@DisplayName("rejects a duplicate historical UID and preserves its first reader")
	void shouldRejectHistoricalSerializerRegisteredUnderSameUid() {
		final CountingStringSerializer first = new CountingStringSerializer();
		final CountingStringSerializer rejected = new CountingStringSerializer();
		final SerialVersionBasedSerializer<String> serializer =
			new SerialVersionBasedSerializer<>(new CountingStringSerializer(), String.class);
		serializer.addBackwardCompatibleSerializer(123L, first);
		final GenericEvitaInternalError exception = assertThrows(
			GenericEvitaInternalError.class,
			() -> serializer.addBackwardCompatibleSerializer(123L, rejected)
		);
		assertTrue(exception.getMessage().contains(String.class.getName()));
		assertTrue(exception.getMessage().contains("123"));
		final byte[] bytes = versionedString(123L);

		try (final Input input = new Input(bytes)) {
			assertEquals("payload", serializer.read(this.kryo, input, String.class));
		}
		try (final Input input = new Input(bytes)) {
			serializer.skip(this.kryo, input, String.class);
		}

		assertEquals(1, first.reads);
		assertEquals(1, first.skips);
		assertEquals(0, rejected.reads);
		assertEquals(0, rejected.skips);
		assertUnsupportedUid(serializer, false, Set.of(123L));
	}

	/**
	 * Rejects a duplicate of every registered historical UID, not only of the first one, and leaves each original
	 * reader serving its UID.
	 */
	@Test
	@DisplayName("rejects a duplicate of any registered historical UID and preserves every original reader")
	void shouldRejectDuplicateOfAnyRegisteredHistoricalUid() {
		final long[] uids = {121L, 122L, 123L};
		final CountingStringSerializer[] readers = new CountingStringSerializer[uids.length];
		final SerialVersionBasedSerializer<String> serializer =
			new SerialVersionBasedSerializer<>(new CountingStringSerializer(), String.class);
		for (int i = 0; i < uids.length; i++) {
			readers[i] = new CountingStringSerializer();
			serializer.addBackwardCompatibleSerializer(uids[i], readers[i]);
		}
		final CountingStringSerializer duplicate = new CountingStringSerializer();

		for (final long uid : uids) {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> serializer.addBackwardCompatibleSerializer(uid, duplicate),
				"uid=" + uid
			);
		}

		for (int i = 0; i < uids.length; i++) {
			try (final Input input = new Input(versionedString(uids[i]))) {
				assertEquals("payload", serializer.read(this.kryo, input, String.class), "uid=" + uids[i]);
			}
			assertEquals(1, readers[i].reads, "uid=" + uids[i]);
		}
	}

	/**
	 * Rejects historical registration under the current UID, which would never reach the historical reader.
	 */
	@Test
	@DisplayName("rejects a historical serializer registered under the current UID")
	void shouldRejectHistoricalSerializerRegisteredUnderCurrentUid() {
		final CountingStringSerializer current = new CountingStringSerializer();
		final CountingStringSerializer historical = new CountingStringSerializer();
		final SerialVersionBasedSerializer<String> serializer =
			new SerialVersionBasedSerializer<>(current, String.class);
		final long currentUid = ObjectStreamClass.lookup(String.class).getSerialVersionUID();
		final GenericEvitaInternalError exception = assertThrows(
			GenericEvitaInternalError.class,
			() -> serializer.addBackwardCompatibleSerializer(currentUid, historical)
		);
		assertTrue(exception.getMessage().contains(String.class.getName()));
		assertTrue(exception.getMessage().contains(Long.toString(currentUid)));

		try (final Input input = new Input(versionedString(currentUid))) {
			assertEquals("payload", serializer.read(this.kryo, input, String.class));
		}
		assertEquals(1, current.reads);
		assertEquals(0, historical.reads);
		assertUnsupportedUid(serializer, false, Set.of());
	}

	/**
	 * Every historical UID reaches the reader registered under it, not merely the first one registered.
	 */
	@Test
	@DisplayName("dispatches each historical UID to the serializer registered under it")
	void shouldDispatchEachHistoricalUidToItsOwnSerializer() {
		final long[] uids = {121L, 122L, 123L};
		final CountingStringSerializer[] readers = new CountingStringSerializer[uids.length];
		final SerialVersionBasedSerializer<String> serializer =
			new SerialVersionBasedSerializer<>(new CountingStringSerializer(), String.class);
		for (int i = 0; i < uids.length; i++) {
			readers[i] = new CountingStringSerializer();
			serializer.addBackwardCompatibleSerializer(uids[i], readers[i]);
		}

		for (int i = 1; i < uids.length; i++) {
			final byte[] bytes = versionedString(uids[i]);
			try (final Input input = new Input(bytes)) {
				assertEquals("payload", serializer.read(this.kryo, input, String.class), "uid=" + uids[i]);
			}
			try (final Input input = new Input(bytes)) {
				serializer.skip(this.kryo, input, String.class);
			}
		}

		assertEquals(0, readers[0].reads, "uid=" + uids[0]);
		assertEquals(0, readers[0].skips, "uid=" + uids[0]);
		for (int i = 1; i < uids.length; i++) {
			assertEquals(1, readers[i].reads, "uid=" + uids[i]);
			assertEquals(1, readers[i].skips, "uid=" + uids[i]);
		}
	}

	/**
	 * Reads or skips a payload of an unregistered UID and pins the exception type, the offending UID, the supported
	 * UIDs, and that nothing beyond the UID was consumed.
	 *
	 * @param serializer        the serializer under test
	 * @param skip              whether to skip the payload rather than read it
	 * @param expectedSupported the historical UIDs the exception must report
	 */
	private void assertUnsupportedUid(
		@Nonnull SerialVersionBasedSerializer<String> serializer,
		boolean skip,
		@Nonnull Set<Long> expectedSupported
	) {
		try (final Input input = new Input(versionedString(456L))) {
			final StoredVersionNotSupportedException error = assertThrows(
				StoredVersionNotSupportedException.class,
				() -> {
					if (skip) {
						serializer.skip(this.kryo, input, String.class);
					} else {
						serializer.read(this.kryo, input, String.class);
					}
				}
			);
			assertEquals(String.class, error.getSerializedClass());
			assertEquals(456L, error.getEncounteredVersion());
			assertEquals(expectedSupported, error.getCurrentlySupportedVersions());
			assertEquals(Long.BYTES, input.total());
		}
	}

	/**
	 * Writes an explicit UID followed by an ordinary Kryo string and a trailing alignment sentinel.
	 */
	@Nonnull
	private static byte[] versionedString(long uid) {
		try (final Output output = new Output(64, -1)) {
			output.writeLong(uid);
			output.writeString("payload");
			output.writeInt(987654321);
			return output.toBytes();
		}
	}

	/**
	 * Real payload serializer whose counters distinguish skip dispatch from read-and-discard fallback.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static class CountingStringSerializer extends Serializer<String> implements SkippableSerializer<String> {
		/** Number of payloads materialized by the read operation. */
		private int reads;
		/** Number of payloads consumed through skip dispatch. */
		private int skips;

		/** Writes the ordinary Kryo string payload. */
		@Override
		public void write(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull String value) {
			output.writeString(value);
		}

		/** Reads and records a materialized payload. */
		@Nonnull
		@Override
		public String read(@Nonnull Kryo kryo, @Nonnull Input input, @Nonnull Class<? extends String> type) {
			this.reads++;
			return input.readString();
		}

		/** Consumes a payload while recording that the skip dispatch selected this serializer. */
		@Override
		public void skip(@Nonnull Kryo kryo, @Nonnull Input input, @Nonnull Class<? extends String> type) {
			this.skips++;
			input.readString();
		}
	}

	@Nonnull
	private ByteArrayOutputStream writeOldFormat() {
		final OldAttributeSchema oldSchema = new OldAttributeSchema("A", String.class);
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
		try (final ByteBufferOutput output = new ByteBufferOutput(baos)) {
			this.kryo.writeObject(output, oldSchema);
		}
		return baos;
	}

	@Data
	@Immutable
	private static class OldAttributeSchema implements Serializable {
		@Serial private static final long serialVersionUID = -7465757161178326011L;

		private final String name;
		private final Class<? extends Serializable> type;
	}

	private static class OldAttributeSchemaSerializer extends Serializer<OldAttributeSchema> {

		@Override
		public void write(Kryo kryo, Output output, OldAttributeSchema object) {
			kryo.writeObject(output, object.getName());
			kryo.writeClass(output, object.getType());
		}

		@Override
		public OldAttributeSchema read(Kryo kryo, Input input, Class<? extends OldAttributeSchema> type) {
			throw new UnsupportedOperationException("Will not be used in test!");
		}

	}

	private static class OldAttributeSchemaDeserializer extends Serializer<AttributeSchema> {

		@Override
		public void write(Kryo kryo, Output output, AttributeSchema object) {
			throw new UnsupportedOperationException("Will not be used in test!");
		}

		@Override
		public AttributeSchema read(Kryo kryo, Input input, Class<? extends AttributeSchema> type) {
			final String name = kryo.readObject(input, String.class);
			@SuppressWarnings("unchecked") final Class<? extends Serializable> attrType = kryo.readClass(input).getType();
			return AttributeSchema._internalBuild(name, attrType, false, ConflictResolutionOverride.INHERITED);
		}

	}

}
