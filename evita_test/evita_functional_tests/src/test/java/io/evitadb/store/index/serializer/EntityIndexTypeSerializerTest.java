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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.util.Collections;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage for {@link EntityIndexTypeSerializer}, and specifically for the one thing nothing else in the suite can
 * reach: an index part written before 2024.12 names an entity index type the enum no longer declares.
 *
 * **Nothing produces `REFERENCED_HIERARCHY_NODE` any more**, so no fixture can reach the path that reads it. The
 * constant was dropped from {@link io.evitadb.api.index.EntityIndexType} once nothing wrote it, and from that moment
 * the legacy read path became invisible to every other assertion in the suite - a regression there would surface as
 * an unreadable catalog rather than as a failing test. This test is what keeps it covered.
 *
 * The name is spelled out as a literal here on purpose, and `EntityIndexTypeSerializer` holds its own copy for the
 * same reason: it is a wire constant that pre-2024.12 catalogs carry on disk, so it must not follow a rename of any
 * symbol. The duplication is the point - a shared constant would let one edit move both sides at once.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("EntityIndexTypeSerializer (retired type name folded on read)")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class EntityIndexTypeSerializerTest {
	/** The entity index type retired in 2024.12, exactly as pre-2024.12 catalogs spell it on disk. */
	private static final String RETIRED_REFERENCED_HIERARCHY_NODE = "REFERENCED_HIERARCHY_NODE";

	private Kryo kryo;

	@BeforeEach
	void setUp() {
		final ReadWriteKeyCompressor keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(keyCompressor));
	}

	@ParameterizedTest
	@EnumSource(EntityIndexType.class)
	@DisplayName("every declared type survives a round trip")
	void shouldRoundTripEveryDeclaredType(@Nonnull EntityIndexType type) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(64);
		try (final Output output = new Output(os, 64)) {
			this.kryo.writeObject(output, type);
		}

		try (final Input input = new Input(os.toByteArray())) {
			assertEquals(type, this.kryo.readObject(input, EntityIndexType.class));
		}
	}

	@Test
	@DisplayName("a pre-2024.12 REFERENCED_HIERARCHY_NODE reads back as REFERENCED_ENTITY")
	void shouldFoldTheRetiredHierarchyNodeType() {
		final byte[] legacyBytes = encodeTypeName(RETIRED_REFERENCED_HIERARCHY_NODE);

		try (final Input input = new Input(legacyBytes)) {
			assertEquals(
				EntityIndexType.REFERENCED_ENTITY, this.kryo.readObject(input, EntityIndexType.class),
				"An index part written before the 2024.12 merge must still load, under the type it was merged into"
			);
		}
	}

	@Test
	@DisplayName("a name that is neither declared nor retired still fails loudly")
	void shouldRejectAnUnknownTypeName() {
		// the fold must stay a fold - if it degraded into a catch-all it would turn a corrupted or
		// future-format part into a silently mis-typed index rather than a failed load
		final byte[] bogusBytes = encodeTypeName("REFERENCED_SOMETHING_ELSE");

		try (final Input input = new Input(bogusBytes)) {
			assertThrows(
				IllegalArgumentException.class, () -> this.kryo.readObject(input, EntityIndexType.class)
			);
		}
	}

	/**
	 * Hand-encodes an entity index type the way the enum-name wire format carries it - the class registration followed
	 * by the constant's name - so a name the enum no longer declares can be fed to the reader.
	 *
	 * @param typeName the constant name to write, declared or not
	 * @return the encoded bytes
	 */
	@Nonnull
	private byte[] encodeTypeName(@Nonnull String typeName) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(64);
		try (final Output output = new Output(os, 64)) {
			this.kryo.writeClass(output, EntityIndexType.class);
			output.writeString(typeName);
		}
		return os.toByteArray();
	}

}
