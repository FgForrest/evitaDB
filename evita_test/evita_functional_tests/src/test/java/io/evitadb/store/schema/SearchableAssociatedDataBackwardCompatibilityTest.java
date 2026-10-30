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

package io.evitadb.store.schema;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.store.schema.serializer.AssociatedDataSchemaSerializer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.store.wal.schema.associatedData.CreateAssociatedDataSchemaMutationSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.EnumSet;

import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the associated data schema and its create mutation, which gained the searchable scopes and the text
 * expression, still read the record the released 2026.2 format persisted - as associated data searchable nowhere - and
 * that the current format round-trips both new fields.
 *
 * The pre-capability record is emulated the way {@link AcceleratorBackwardCompatibilityTest} does it: the routing
 * envelope carries the orphaned `serialVersionUID`, followed by the payload of the *current* serializer, which is valid
 * because the searchability section is appended **last** - the old payload is a byte-exact prefix and the
 * backward-compatible reader stops short of it. Every subject declares searchability, so the trailing bytes exist.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Searchable associated data backward compatibility")
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(WAL)
@Tag(SCHEMA)
class SearchableAssociatedDataBackwardCompatibilityTest {

	/**
	 * `serialVersionUID` of {@link AssociatedDataSchema} as released in 2026.2, covered by its `_2026_2` reader.
	 */
	private static final long ASSOCIATED_DATA_SCHEMA_2026_2_UID = -995599294301442063L;
	/**
	 * `serialVersionUID` of {@link CreateAssociatedDataSchemaMutation} as released in 2026.2, covered by `_2026_2`.
	 */
	private static final long CREATE_ASSOCIATED_DATA_MUTATION_2026_2_UID = -7368528015832499967L;
	private static final Scope[] BOTH_SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	private static final Expression TEXT = ExpressionFactory.parse("$value.blocks.*[$.text]");

	@Test
	@DisplayName("2026.2 AssociatedDataSchema (storage) reads back searchable nowhere")
	void shouldReadReleasedAssociatedDataSchemaAsNotSearchable() {
		final Kryo kryo = createSchemaKryo();
		final AssociatedDataSchema schema = searchableSchema();
		final AssociatedDataSchema deserialized = readThroughBackwardCompatibleRoute(
			kryo, ASSOCIATED_DATA_SCHEMA_2026_2_UID, new AssociatedDataSchemaSerializer(), AssociatedDataSchema.class,
			schema
		);
		assertEquals("article", deserialized.getName());
		assertTrue(deserialized.isLocalized());
		assertEquals(ConflictResolutionOverride.GRANULAR, deserialized.getConflictResolutionOverride());
		assertFalse(deserialized.isSearchableInAnyScope());
		assertNull(deserialized.getTextExpression());
	}

	@Test
	@DisplayName("current AssociatedDataSchema (storage) round-trips the scopes and the expression")
	void shouldRoundTripCurrentAssociatedDataSchema() {
		final Kryo kryo = createSchemaKryo();
		final AssociatedDataSchema schema = searchableSchema();
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(1_024);
		try (final Output output = new Output(baos)) {
			kryo.writeObject(output, schema);
		}
		try (final Input input = new Input(new ByteArrayInputStream(baos.toByteArray()))) {
			final AssociatedDataSchema deserialized = kryo.readObject(input, AssociatedDataSchema.class);
			assertEquals(EnumSet.of(Scope.LIVE, Scope.ARCHIVED), deserialized.getSearchableInScopes());
			assertEquals(TEXT, deserialized.getTextExpression());
			assertEquals(schema, deserialized);
		}
	}

	@Test
	@DisplayName("2026.2 CreateAssociatedDataSchemaMutation (WAL) reads back searchable nowhere")
	void shouldReadReleasedCreateMutationAsNotSearchable() {
		final Kryo kryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		final CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
			"article", "description", null, ComplexDataObject.class, true, false, BOTH_SCOPES, TEXT,
			ConflictResolutionOverride.ENTITY
		);
		final CreateAssociatedDataSchemaMutation deserialized = readThroughBackwardCompatibleRoute(
			kryo, CREATE_ASSOCIATED_DATA_MUTATION_2026_2_UID, new CreateAssociatedDataSchemaMutationSerializer(),
			CreateAssociatedDataSchemaMutation.class, mutation
		);
		assertEquals("description", deserialized.getDescription());
		assertEquals(ConflictResolutionOverride.ENTITY, deserialized.getConflictResolutionOverride());
		assertArrayEquals(Scope.NO_SCOPE, deserialized.getSearchableInScopes());
		assertNull(deserialized.getTextExpression());
	}

	/**
	 * Builds a searchable complex data object associated data schema.
	 *
	 * @return the schema
	 */
	@Nonnull
	private static AssociatedDataSchema searchableSchema() {
		return AssociatedDataSchema._internalBuild(
			"article", "description", null, ComplexDataObject.class, true, false, BOTH_SCOPES, TEXT,
			ConflictResolutionOverride.GRANULAR
		);
	}

	/**
	 * Creates a Kryo configured as the catalog storage configures it for schemas.
	 *
	 * @return the Kryo
	 */
	@Nonnull
	private static Kryo createSchemaKryo() {
		return KryoFactory.createKryo(SchemaKryoConfigurer.INSTANCE.andThen(SharedClassesConfigurer.INSTANCE));
	}

	/**
	 * Emulates reading a record persisted by the released format: the envelope carries the orphaned
	 * `serialVersionUID`, followed by the payload the current serializer produces, of which the released payload is a
	 * prefix. The record is read back through `kryo.readObject`, exercising the real version routing.
	 *
	 * @param kryo              the fully configured Kryo
	 * @param orphanedUid       the released `serialVersionUID` stored in the envelope
	 * @param currentSerializer the current serializer rendering the payload
	 * @param type              the deserialized type
	 * @param object            the object whose current-format payload is rendered
	 * @param <T>               the schema/mutation type
	 * @return the object read back through the version-routing path
	 */
	@Nonnull
	private static <T> T readThroughBackwardCompatibleRoute(
		@Nonnull Kryo kryo,
		long orphanedUid,
		@Nonnull Serializer<T> currentSerializer,
		@Nonnull Class<T> type,
		@Nonnull T object
	) {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(1_024);
		try (final Output output = new Output(baos)) {
			output.writeLong(orphanedUid);
			currentSerializer.write(kryo, output, object);
		}
		try (final Input input = new Input(new ByteArrayInputStream(baos.toByteArray()))) {
			return kryo.readObject(input, type);
		}
	}

}
