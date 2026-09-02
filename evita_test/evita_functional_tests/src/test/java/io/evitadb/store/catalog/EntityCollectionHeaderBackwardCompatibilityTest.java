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

package io.evitadb.store.catalog;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.catalog.serializer.EntityCollectionHeaderSerializer;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.header.PersistentStorageHeader;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.store.shared.model.FileLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies that a collection storage header persisted before 2026.3 is still readable after the release appended
 * `lastModifiedMillis` to the format and bumped {@link EntityCollectionFileHeader}'s `serialVersionUID`.
 *
 * Without the reader registered in {@link CatalogHeaderKryoConfigurer}, opening *any* catalog written by an earlier
 * release fails with `StoredVersionNotSupportedException` - the header is read on catalog open, so the failure is
 * total rather than partial. That is what makes this the release gate for the change.
 *
 * The fixture emulates a pre-2026.3 on-disk record the way the sibling
 * {@link io.evitadb.store.schema.ConflictResolutionBackwardCompatibilityTest} does: it writes the version-routing
 * envelope's leading `serialVersionUID` as the orphaned pre-bump value, then the payload produced by the *current*
 * serializer. That is byte-valid here because the new field is strictly appended - everything preceding it is
 * unchanged - so the backward-compatible reader consumes the old layout exactly and stops before the trailing
 * timestamp. The read goes through `kryo.readObject` on the fully composed catalog kryo, so it exercises the real
 * registration and version-routing path rather than the serializer in isolation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(SERIALIZATION)
class EntityCollectionHeaderBackwardCompatibilityTest {

	/**
	 * `serialVersionUID` of {@link EntityCollectionFileHeader} as shipped up to and including 2026.2, orphaned by the
	 * bump that made room for `lastModifiedMillis`. Identical at `release_2026-1` and `release_2026-2` - the class had
	 * not been bumped since 2024.12 - so this single value covers every catalog written in between.
	 */
	private static final long PRE_TIMESTAMP_UID = -2149051526452828365L;

	@Test
	@DisplayName("A collection header written before 2026.3 still loads, with its timestamp reported as absent")
	void shouldReadPre2026_3CollectionHeaderAsUnstamped() {
		final Kryo kryo = catalogKryo();
		final EntityCollectionFileHeader header = buildHeader(1_750_000_000_000L);

		final EntityCollectionFileHeader deserialized = readThroughBackwardCompatibleRoute(kryo, header);

		// every field preceding the appended one survives intact, which is what proves the reader is byte-aligned
		// with the old layout rather than accidentally parsing garbage that happens not to throw
		assertEquals("product", deserialized.entityType());
		assertEquals(7, deserialized.entityTypePrimaryKey());
		assertEquals(3, deserialized.entityTypeFileIndex());
		assertEquals(42L, deserialized.version());
		assertEquals(11, deserialized.lastPrimaryKey());
		assertEquals(12, deserialized.lastEntityIndexPrimaryKey());
		assertEquals(13, deserialized.lastInternalPriceId());
		assertEquals(99, deserialized.recordCount());
		assertEquals(List.of(4, 5, 6), deserialized.usedEntityIndexPrimaryKeys());

		// and the field the old layout simply does not carry comes back as "unknown" rather than as an epoch instant
		assertEquals(EntityCollectionFileHeader.NOT_STAMPED, deserialized.lastModifiedMillis());
		assertNotEquals(header.lastModifiedMillis(), deserialized.lastModifiedMillis());
	}

	@Test
	@DisplayName("A header written by the current release round-trips with its timestamp")
	void shouldRoundTripTheTimestampThroughTheCurrentFormat() {
		final Kryo kryo = catalogKryo();
		final EntityCollectionFileHeader header = buildHeader(1_750_000_000_000L);

		final ByteArrayOutputStream baos = new ByteArrayOutputStream(1_024);
		try (final Output output = new Output(baos)) {
			kryo.writeObject(output, header);
		}
		final EntityCollectionFileHeader deserialized;
		try (final Input input = new Input(new ByteArrayInputStream(baos.toByteArray()))) {
			deserialized = kryo.readObject(input, EntityCollectionFileHeader.class);
		}

		// the counterpart to the test above: the appended field is genuinely written and read by the current
		// serializer, so "absent" there is the old layout speaking and not a field nobody ever persists
		assertEquals(1_750_000_000_000L, deserialized.lastModifiedMillis());
		assertEquals(header.version(), deserialized.version());
	}

	/**
	 * Composes the same kryo the catalog storage path builds, in the same order - see
	 * `DefaultCatalogPersistenceService.VERSIONED_KRYO_FACTORY`. Composition order matters for this class of bug:
	 * Kryo's registration map is last-write-wins by class, so a reader present in only one of two configurers that
	 * both register a type is silently dead.
	 *
	 * @return the composed catalog kryo
	 */
	@Nonnull
	private static Kryo catalogKryo() {
		return KryoFactory.createKryo(
			SchemaKryoConfigurer.INSTANCE
				.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
				.andThen(SharedClassesConfigurer.INSTANCE)
		);
	}

	/**
	 * Builds a header whose every field is distinct, so a mis-aligned reader shifts values between fields rather than
	 * reproducing plausible-looking defaults.
	 *
	 * @param lastModifiedMillis the timestamp to stamp it with
	 * @return the header
	 */
	@Nonnull
	private static EntityCollectionFileHeader buildHeader(long lastModifiedMillis) {
		return new EntityCollectionFileHeader(
			"product",
			7,
			3,
			99,
			11,
			12,
			13,
			0.75d,
			new PersistentStorageHeader(42L, new FileLocation(1_024L, 256), Map.of(), 1),
			8,
			List.of(4, 5, 6),
			lastModifiedMillis
		);
	}

	/**
	 * Renders the given header as a pre-2026.3 on-disk record - the orphaned `serialVersionUID` followed by the
	 * current serializer's payload, of which the old layout is a byte-exact prefix - and reads it back through the
	 * composed kryo's version routing.
	 *
	 * @param kryo   the composed catalog kryo
	 * @param header the header to render
	 * @return what the backward-compatible reader produced
	 */
	@Nonnull
	private static EntityCollectionFileHeader readThroughBackwardCompatibleRoute(
		@Nonnull Kryo kryo,
		@Nonnull EntityCollectionFileHeader header
	) {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(1_024);
		try (final Output output = new Output(baos)) {
			output.writeLong(PRE_TIMESTAMP_UID);
			new EntityCollectionHeaderSerializer().write(kryo, output, header);
		}
		try (final Input input = new Input(new ByteArrayInputStream(baos.toByteArray()))) {
			return kryo.readObject(input, EntityCollectionFileHeader.class);
		}
	}

}
