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
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
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
		serializer.addBackwardCompatibleSerializer(123L, new CountingStringSerializer());
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
		final Serializer<String> payload = new com.esotericsoftware.kryo.serializers.DefaultSerializers.StringSerializer();
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
		for (final boolean registered : new boolean[]{false, true}) {
			if (registered) {
				for (int i = 0; i < 40; i++) {
					final long uid = i * 32L + 1;
					final Serializer<String> reader = new CountingStringSerializer();
					serializer.addBackwardCompatibleSerializer(uid, reader);
					supportedVersions.add(uid);
				}
			}
			for (final boolean skip : new boolean[]{false, true}) {
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
					assertEquals(supportedVersions, error.getCurrentlySupportedVersions());
					assertEquals(8, input.total());
				}
			}
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
