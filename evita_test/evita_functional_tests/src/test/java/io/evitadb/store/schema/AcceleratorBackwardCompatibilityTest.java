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
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaFilterableMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.store.schema.serializer.AttributeSchemaSerializer;
import io.evitadb.store.schema.serializer.EntityAttributeSchemaSerializer;
import io.evitadb.store.schema.serializer.GlobalAttributeSchemaSerializer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.store.wal.schema.attribute.CreateAttributeSchemaMutationSerializer;
import io.evitadb.store.wal.schema.attribute.CreateGlobalAttributeSchemaMutationSerializer;
import io.evitadb.store.wal.schema.attribute.SetAttributeSchemaFilterableMutationSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ObjectStreamClass;
import java.io.ByteArrayOutputStream;

import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies backward-compatible deserialization of the schema types and schema mutations that gained the per-scope
 * {@link AttributeFilterAccelerator} field. Adding it bumped the `serialVersionUID` of every affected class, so any
 * record
 * persisted by the immediately-preceding format must still be readable through the version-routing
 * {@link io.evitadb.store.entity.serializer.SerialVersionBasedSerializer} - reading back as an attribute that is
 * filterable exactly as it was, with no acceleration declared anywhere.
 *
 * Each test emulates a pre-capability on-disk record the same way its sibling
 * {@link ConflictResolutionBackwardCompatibilityTest} does: it writes the routing envelope's leading
 * `serialVersionUID` as the value the bump orphaned, followed by the payload the *current* serializer produces. That
 * is byte-valid here because every writer touched by this change appends the capability section **last**, so the
 * pre-capability payload is a byte-exact prefix of the current one and the backward-compatible reader simply stops
 * short of it. The record is then read back through the fully configured Kryo, exercising the real
 * registration/routing path rather than the serializer alone.
 *
 * Every subject is deliberately built **with** a capability, so the trailing bytes genuinely exist in the rendered
 * payload. A subject declaring none would leave nothing for the reader to stop short of, and the test would pass just
 * as happily against a reader that was never registered at all.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Filter accelerator backward compatibility")
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(WAL)
@Tag(SCHEMA)
class AcceleratorBackwardCompatibilityTest {

	/**
	 * Pre-capability `serialVersionUID` of {@link AttributeSchema} (storage), covered by its `_2026_2` reader.
	 */
	private static final long ATTRIBUTE_SCHEMA_PRE_CAPABILITY_UID = -4825670975814791473L;
	/**
	 * Pre-capability `serialVersionUID` of {@link GlobalAttributeSchema} (storage), covered by `_2026_2`.
	 */
	private static final long GLOBAL_ATTRIBUTE_SCHEMA_PRE_CAPABILITY_UID = -6027390261318420825L;
	/**
	 * Pre-capability `serialVersionUID` of {@link EntityAttributeSchema} (storage), covered by `_2026_2`.
	 */
	private static final long ENTITY_ATTRIBUTE_SCHEMA_PRE_CAPABILITY_UID = 8168305590483159083L;
	/**
	 * Pre-capability `serialVersionUID` of {@link CreateAttributeSchemaMutation} (WAL), covered by `_2026_2`.
	 */
	private static final long CREATE_ATTRIBUTE_MUTATION_PRE_CAPABILITY_UID = -469815390440407269L;
	/**
	 * Pre-capability `serialVersionUID` of {@link CreateGlobalAttributeSchemaMutation} (WAL), covered by `_2026_2`.
	 */
	private static final long CREATE_GLOBAL_ATTRIBUTE_MUTATION_PRE_CAPABILITY_UID = 496202593310308291L;
	/**
	 * Pre-capability `serialVersionUID` of {@link SetAttributeSchemaFilterableMutation} (WAL), covered by `_2026_2`.
	 */
	private static final long SET_FILTERABLE_MUTATION_PRE_CAPABILITY_UID = -382658973541254821L;

	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_URL = "url";
	private static final String ATTRIBUTE_NAME = "name";

	/**
	 * The carriers every subject in this test is built from - one scope, one capability, so the appended section is
	 * never empty.
	 */
	private static final ScopedAttributeFilterAccelerators[] SUBSTRING_IN_LIVE = {
		new ScopedAttributeFilterAccelerators(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)
	};

	@Test
	@DisplayName("pre-capability AttributeSchema (storage) reads back filterable with no capability")
	void shouldReadPreCapabilityAttributeSchemaWithoutCapabilities() {
		final Kryo kryo = createSchemaKryo();
		final AttributeSchema attributeSchema = AttributeSchema._internalBuild(
			ATTRIBUTE_CODE, null, null,
			(ScopedAttributeUniquenessType[]) null,
			Scope.DEFAULT_SCOPES,
			SUBSTRING_IN_LIVE,
			Scope.NO_SCOPE,
			false, false, false,
			String.class, null, 0,
			ConflictResolutionOverride.ENTITY
		);

		final AttributeSchema deserialized = readThroughBackwardCompatibleRoute(
			kryo,
			ATTRIBUTE_SCHEMA_PRE_CAPABILITY_UID,
			new AttributeSchemaSerializer(),
			AttributeSchema.class,
			attributeSchema
		);

		assertPreCapabilityAttribute(deserialized, ATTRIBUTE_CODE, ConflictResolutionOverride.ENTITY);
	}

	@Test
	@DisplayName("pre-capability GlobalAttributeSchema (storage) reads back filterable with no capability")
	void shouldReadPreCapabilityGlobalAttributeSchemaWithoutCapabilities() {
		final Kryo kryo = createSchemaKryo();
		final GlobalAttributeSchema attributeSchema = GlobalAttributeSchema._internalBuild(
			ATTRIBUTE_URL, null, null,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE)
			},
			new ScopedGlobalAttributeUniquenessType[]{
				new ScopedGlobalAttributeUniquenessType(Scope.LIVE, GlobalAttributeUniquenessType.NOT_UNIQUE)
			},
			Scope.DEFAULT_SCOPES,
			SUBSTRING_IN_LIVE,
			Scope.NO_SCOPE,
			false, false, false,
			String.class, null, 0,
			ConflictResolutionOverride.GRANULAR
		);

		final GlobalAttributeSchema deserialized = readThroughBackwardCompatibleRoute(
			kryo,
			GLOBAL_ATTRIBUTE_SCHEMA_PRE_CAPABILITY_UID,
			new GlobalAttributeSchemaSerializer(),
			GlobalAttributeSchema.class,
			attributeSchema
		);

		assertPreCapabilityAttribute(deserialized, ATTRIBUTE_URL, ConflictResolutionOverride.GRANULAR);
	}

	@Test
	@DisplayName("pre-capability EntityAttributeSchema (storage) reads back filterable with no capability")
	void shouldReadPreCapabilityEntityAttributeSchemaWithoutCapabilities() {
		final Kryo kryo = createSchemaKryo();
		final EntityAttributeSchema attributeSchema = EntityAttributeSchema._internalBuild(
			ATTRIBUTE_NAME, null, null,
			(ScopedAttributeUniquenessType[]) null,
			Scope.DEFAULT_SCOPES,
			SUBSTRING_IN_LIVE,
			Scope.NO_SCOPE,
			false, false, false,
			String.class, null, 0,
			ConflictResolutionOverride.ENTITY
		);

		final EntityAttributeSchema deserialized = readThroughBackwardCompatibleRoute(
			kryo,
			ENTITY_ATTRIBUTE_SCHEMA_PRE_CAPABILITY_UID,
			new EntityAttributeSchemaSerializer(),
			EntityAttributeSchema.class,
			attributeSchema
		);

		assertPreCapabilityAttribute(deserialized, ATTRIBUTE_NAME, ConflictResolutionOverride.ENTITY);
	}

	@Test
	@DisplayName("pre-capability CreateAttributeSchemaMutation (WAL) reads back declaring no capability")
	void shouldReadPreCapabilityCreateAttributeSchemaMutationWithoutCapabilities() {
		final Kryo walKryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		final CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
			ATTRIBUTE_CODE, null, null,
			null,
			Scope.DEFAULT_SCOPES,
			SUBSTRING_IN_LIVE,
			Scope.NO_SCOPE,
			false, false, false,
			String.class, null, 0,
			ConflictResolutionOverride.GRANULAR
		);

		final CreateAttributeSchemaMutation deserialized = readThroughBackwardCompatibleRoute(
			walKryo,
			CREATE_ATTRIBUTE_MUTATION_PRE_CAPABILITY_UID,
			new CreateAttributeSchemaMutationSerializer(),
			CreateAttributeSchemaMutation.class,
			mutation
		);

		assertEquals(ATTRIBUTE_CODE, deserialized.getName());
		// written immediately before the capability section - proves byte alignment rather than a lucky default
		assertEquals(ConflictResolutionOverride.GRANULAR, deserialized.getConflictResolutionOverride());
		assertArrayEquals(Scope.DEFAULT_SCOPES, deserialized.getFilterableInScopes());
		assertEquals(0, deserialized.getAcceleratorsInScopes().length);
	}

	@Test
	@DisplayName("pre-capability CreateGlobalAttributeSchemaMutation (WAL) reads back declaring no capability")
	void shouldReadPreCapabilityCreateGlobalAttributeSchemaMutationWithoutCapabilities() {
		final Kryo walKryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		final CreateGlobalAttributeSchemaMutation mutation = new CreateGlobalAttributeSchemaMutation(
			ATTRIBUTE_URL, null, null,
			null, null,
			Scope.DEFAULT_SCOPES,
			SUBSTRING_IN_LIVE,
			Scope.NO_SCOPE,
			false, false, false,
			String.class, null, 0,
			ConflictResolutionOverride.GRANULAR
		);

		final CreateGlobalAttributeSchemaMutation deserialized = readThroughBackwardCompatibleRoute(
			walKryo,
			CREATE_GLOBAL_ATTRIBUTE_MUTATION_PRE_CAPABILITY_UID,
			new CreateGlobalAttributeSchemaMutationSerializer(),
			CreateGlobalAttributeSchemaMutation.class,
			mutation
		);

		assertEquals(ATTRIBUTE_URL, deserialized.getName());
		assertEquals(ConflictResolutionOverride.GRANULAR, deserialized.getConflictResolutionOverride());
		assertArrayEquals(Scope.DEFAULT_SCOPES, deserialized.getFilterableInScopes());
		assertEquals(0, deserialized.getAcceleratorsInScopes().length);
	}

	@Test
	@DisplayName("SetAttributeSchemaFilterableMutation still writes the released format, byte for byte")
	void shouldWriteSetFilterableMutationInTheReleasedFormat() {
		// the accelerator axis was lifted out of this mutation before it ever shipped, which is what lets it keep the
		// released serialVersionUID and lets its released serializer read what today's writer produces without any
		// backward-compatible reader in between. This test is what keeps that claim honest: the payload must be
		// exactly the name and the scope array, with nothing appended after them.
		final Kryo walKryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		final SetAttributeSchemaFilterableMutation mutation = new SetAttributeSchemaFilterableMutation(
			ATTRIBUTE_NAME, Scope.DEFAULT_SCOPES
		);

		final ByteArrayOutputStream written = new ByteArrayOutputStream(64);
		try (final Output output = new Output(written)) {
			new SetAttributeSchemaFilterableMutationSerializer().write(walKryo, output, mutation);
		}

		final ByteArrayOutputStream expected = new ByteArrayOutputStream(64);
		try (final Output output = new Output(expected)) {
			output.writeString(ATTRIBUTE_NAME);
			output.writeVarInt(Scope.DEFAULT_SCOPES.length, true);
			for (final Scope scope : Scope.DEFAULT_SCOPES) {
				walKryo.writeObject(output, scope);
			}
		}

		assertArrayEquals(expected.toByteArray(), written.toByteArray());
		assertEquals(
			SET_FILTERABLE_MUTATION_PRE_CAPABILITY_UID,
			ObjectStreamClass.lookup(SetAttributeSchemaFilterableMutation.class).getSerialVersionUID(),
			"the released serialVersionUID must be intact - a changed one would orphan every stored WAL record"
		);
	}

	/**
	 * The assertions every storage-schema subject shares - the name it was built under, the override written
	 * immediately before the capability section, the surviving filterability, and the absent capability section.
	 *
	 * @param deserialized     the schema read through the backward-compatible route
	 * @param expectedName     name the subject was built under
	 * @param expectedOverride conflict-resolution override the subject was built with
	 */
	private static void assertPreCapabilityAttribute(
		@Nonnull AttributeSchema deserialized,
		@Nonnull String expectedName,
		@Nonnull ConflictResolutionOverride expectedOverride
	) {
		assertEquals(expectedName, deserialized.getName());
		// the conflict-resolution override is the field written immediately before the capability section, so its
		// survival is the sharpest witness that the reader stopped in exactly the right place …
		assertEquals(expectedOverride, deserialized.getConflictResolutionOverride());
		assertTrue(deserialized.isFilterableInScope(Scope.LIVE), "the filterability itself must survive intact");
		// … and the trailing capability section reads back as absent, which is what a plain `filterable()` means
		assertTrue(deserialized.getAcceleratorsInScopes().isEmpty());
	}

	/**
	 * Builds the catalog-storage Kryo the schema serializers are registered in.
	 *
	 * @return the fully configured Kryo
	 */
	@Nonnull
	private static Kryo createSchemaKryo() {
		return KryoFactory.createKryo(SchemaKryoConfigurer.INSTANCE.andThen(SharedClassesConfigurer.INSTANCE));
	}

	/**
	 * Emulates reading a record persisted by the pre-capability format and routes it through the fully configured
	 * Kryo. The routing envelope's leading `serialVersionUID` is written as the orphaned pre-capability value,
	 * followed by the payload produced by the current serializer. Because the capability section is strictly appended,
	 * the pre-capability payload is a byte-exact prefix of the current payload; the backward-compatible reader
	 * selected by the routing stops short of the trailing capability bytes, reproducing a genuine old on-disk record.
	 * The object is then read back through `kryo.readObject`, exercising the real registration and version-routing
	 * path.
	 *
	 * @param kryo              the fully configured Kryo whose registered version-routing serializer must select the
	 *                          pre-capability reader
	 * @param orphanedUid       the pre-capability `serialVersionUID` stored in the envelope
	 * @param currentSerializer the current serializer used to render the payload prefix deterministically
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
