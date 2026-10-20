/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link AttributeValueSerializer_2026_2} brings out-of-window temporal values of a body written by
 * release 2026.2 into the supported window, while a current-version body and every other value read back unchanged.
 *
 * A legacy body is written the way release 2026.2 wrote it: the `serialVersionUID` the record carried in that
 * release, followed by the payload of the current {@link AttributeValueSerializer}. The layout never changed, only
 * the version discriminator did.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(ATTRIBUTE)
@DisplayName("Attribute value reader of release 2026.2")
class AttributeValueSerializer_2026_2Test {
	/**
	 * `serialVersionUID` the {@link AttributeValue} record carried in release 2026.2, i.e. the discriminator
	 * {@link AttributeValueSerializer_2026_2} is registered under.
	 */
	private static final long LEGACY_ATTRIBUTE_VALUE_UID = -5387437940533059959L;
	private static final AttributeKey KEY = new AttributeKey("until");
	private Kryo kryo;
	private AttributeValueSerializer currentSerializer;

	@BeforeEach
	void setUp() {
		final ReadWriteKeyCompressor keyCompressor = new ReadWriteKeyCompressor(new ConcurrentHashMap<>());
		keyCompressor.getId(KEY);
		this.kryo = KryoFactory.createKryo(
			SharedClassesConfigurer.INSTANCE.andThen(new EntityStoragePartConfigurer(keyCompressor))
		);
		this.currentSerializer = new AttributeValueSerializer(keyCompressor);
	}

	@Test
	@DisplayName("should clamp OffsetDateTime beyond the window keeping its offset")
	void shouldClampOffsetDateTimeBeyondWindow() {
		final AttributeValue read = readLegacy(new AttributeValue(3, KEY, OffsetDateTime.MAX, false));
		final OffsetDateTime expected = EvitaDataTypes.clampToSupportedWindow(OffsetDateTime.MAX);
		assertEquals(expected, read.value());
		assertEquals(OffsetDateTime.MAX.getOffset(), ((OffsetDateTime) read.value()).getOffset());
		assertTrue(((OffsetDateTime) read.value()).toEpochSecond() < OffsetDateTime.MAX.toEpochSecond());
		assertEquals(3, read.version());
		assertEquals(KEY, read.key());
	}

	@Test
	@DisplayName("should clamp OffsetDateTime before the window")
	void shouldClampOffsetDateTimeBeforeWindow() {
		final AttributeValue read = readLegacy(new AttributeValue(1, KEY, OffsetDateTime.MIN, false));
		assertEquals(EvitaDataTypes.clampToSupportedWindow(OffsetDateTime.MIN), read.value());
		assertTrue(((OffsetDateTime) read.value()).toEpochSecond() > OffsetDateTime.MIN.toEpochSecond());
	}

	@Test
	@DisplayName("should clamp LocalDateTime beyond the window on both sides")
	void shouldClampLocalDateTime() {
		assertEquals(
			EvitaDataTypes.clampToSupportedWindow(LocalDateTime.MAX),
			readLegacy(new AttributeValue(1, KEY, LocalDateTime.MAX, false)).value()
		);
		assertEquals(
			EvitaDataTypes.clampToSupportedWindow(LocalDateTime.MIN),
			readLegacy(new AttributeValue(1, KEY, LocalDateTime.MIN, false)).value()
		);
	}

	@Test
	@DisplayName("should clamp only the out-of-window elements of an array and keep its length")
	void shouldClampArrayElements() {
		final OffsetDateTime inside = OffsetDateTime.of(2030, 1, 2, 3, 4, 5, 6_000_007, ZoneOffset.ofHours(2));
		final OffsetDateTime[] offsets = {inside, OffsetDateTime.MAX, OffsetDateTime.MIN};
		final OffsetDateTime[] readOffsets =
			(OffsetDateTime[]) readLegacy(new AttributeValue(1, KEY, offsets, false)).value();
		assertEquals(3, readOffsets.length);
		assertEquals(inside, readOffsets[0]);
		assertEquals(EvitaDataTypes.clampToSupportedWindow(OffsetDateTime.MAX), readOffsets[1]);
		assertEquals(EvitaDataTypes.clampToSupportedWindow(OffsetDateTime.MIN), readOffsets[2]);

		final LocalDateTime localInside = LocalDateTime.of(2031, 5, 6, 7, 8);
		final LocalDateTime[] locals = {LocalDateTime.MAX, localInside};
		final LocalDateTime[] readLocals =
			(LocalDateTime[]) readLegacy(new AttributeValue(1, KEY, locals, false)).value();
		assertArrayEquals(
			new LocalDateTime[]{EvitaDataTypes.clampToSupportedWindow(LocalDateTime.MAX), localInside}, readLocals
		);
	}

	@Test
	@DisplayName("should keep the null elements of a temporal array")
	void shouldKeepNullArrayElements() {
		final OffsetDateTime[] offsets = {OffsetDateTime.MAX, null};
		assertArrayEquals(
			new OffsetDateTime[]{EvitaDataTypes.clampToSupportedWindow(OffsetDateTime.MAX), null},
			(OffsetDateTime[]) readLegacy(new AttributeValue(1, KEY, offsets, false)).value()
		);

		final LocalDateTime[] locals = {null, LocalDateTime.MIN, LocalDateTime.of(2031, 5, 6, 7, 8)};
		assertArrayEquals(
			new LocalDateTime[]{
				null, EvitaDataTypes.clampToSupportedWindow(LocalDateTime.MIN), LocalDateTime.of(2031, 5, 6, 7, 8)
			},
			(LocalDateTime[]) readLegacy(new AttributeValue(1, KEY, locals, false)).value()
		);

		final OffsetDateTime[] insideOnly = {null, OffsetDateTime.of(2030, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC)};
		assertArrayEquals(
			insideOnly, (OffsetDateTime[]) readLegacy(new AttributeValue(1, KEY, insideOnly, false)).value()
		);
	}

	@Test
	@DisplayName("should keep sub-millisecond digits of an in-window value")
	void shouldNotTruncateInWindowValue() {
		final OffsetDateTime nanos = OffsetDateTime.of(2030, 1, 2, 3, 4, 5, 123_456_789, ZoneOffset.UTC);
		assertEquals(nanos, readLegacy(new AttributeValue(1, KEY, nanos, false)).value());
		final LocalDateTime localNanos = LocalDateTime.of(2030, 1, 2, 3, 4, 5, 123_456_789);
		assertEquals(localNanos, readLegacy(new AttributeValue(1, KEY, localNanos, false)).value());
	}

	@Test
	@DisplayName("should keep the dropped flag and a null value")
	void shouldKeepDroppedFlagAndNull() {
		final AttributeValue read = readLegacy(new AttributeValue(7, KEY, OffsetDateTime.MAX, true));
		assertTrue(read.dropped());
		assertNull(readLegacy(new AttributeValue(7, KEY, null, true)).value());
	}

	@Test
	@DisplayName("should read every non-temporal type unchanged")
	void shouldReadNonTemporalTypesUnchanged() {
		final Serializable[] values = {
			"text", 42, 42L, new BigDecimal("1.50"), true, LocalDate.MAX, new String[]{"a", "b"}, new Integer[]{1, 2}
		};
		for (final Serializable value : values) {
			final Serializable read = readLegacy(new AttributeValue(1, KEY, value, false)).value();
			if (value.getClass().isArray()) {
				assertArrayEquals((Object[]) value, (Object[]) read);
			} else {
				assertEquals(value, read);
			}
		}
	}

	@Test
	@DisplayName("should write the current version that a legacy body is distinguished from")
	void shouldWriteCurrentVersion() {
		final byte[] current = write(new AttributeValue(1, KEY, OffsetDateTime.MAX, false));
		final long writtenVersion = new Input(current).readLong();
		assertEquals(ObjectStreamClass.lookup(AttributeValue.class).getSerialVersionUID(), writtenVersion);
		assertTrue(writtenVersion != LEGACY_ATTRIBUTE_VALUE_UID);
		final AttributeValue read = read(current);
		assertEquals(OffsetDateTime.MAX, read.value());
	}

	/**
	 * Writes `value` the way release 2026.2 wrote it - that release's `serialVersionUID`, then the unchanged payload,
	 * which is what `SerialVersionBasedSerializer` emits in front of the payload - and reads it back through the
	 * registered serializer, so the version decides which reader parses it.
	 */
	@Nonnull
	private AttributeValue readLegacy(@Nonnull AttributeValue value) {
		final Output output = new Output(256, -1);
		output.writeLong(LEGACY_ATTRIBUTE_VALUE_UID);
		this.currentSerializer.write(this.kryo, output, value);
		return read(output.toBytes());
	}

	@Nonnull
	private byte[] write(@Nonnull AttributeValue value) {
		final Output output = new Output(256, -1);
		this.kryo.writeObject(output, value);
		return output.toBytes();
	}

	@Nonnull
	private AttributeValue read(@Nonnull byte[] bytes) {
		return this.kryo.readObject(new Input(bytes), AttributeValue.class);
	}

}
