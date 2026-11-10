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

package io.evitadb.store.schema.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSearchMode;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReflectedReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceSearchMode;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaSearchableThroughMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.catalog.CatalogHeaderKryoConfigurer;
import io.evitadb.store.entity.exception.StoredVersionNotSupportedException;
import io.evitadb.store.entity.serializer.SerialVersionBasedSerializer;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.store.wal.schema.MutationSerializationFunctions;
import io.evitadb.store.wal.schema.reference.CreateReferenceSchemaMutationSerializer;
import io.evitadb.store.wal.schema.reference.CreateReferenceSchemaMutationSerializer_2026_2_2;
import io.evitadb.store.wal.schema.reference.CreateReflectedReferenceSchemaMutationSerializer;
import io.evitadb.store.wal.schema.reference.CreateReflectedReferenceSchemaMutationSerializer_2026_2_2;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.*;
import static io.evitadb.store.wal.schema.reference.SetReferenceSchemaFacetedMutationSerializer.*;
import static io.evitadb.store.wal.schema.reference.SetReferenceSchemaBucketedMutationSerializer.*;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.*;

/** Ensures reference composition metadata survives current formats and the released formats stay readable. */
@DisplayName("Reference text composition schema and WAL serialization")
@Tag(STORAGE)
@Tag(SCHEMA)
@Tag(SERIALIZATION)
class ReferenceSearchableThroughSerializationTest {
	private static final MutationSerializationFunctions MUTATION_FUNCTIONS = new MutationSerializationFunctions() {};
	private static final long RELEASED_REFERENCE_UID = 5443565766311111160L;
	private static final long RELEASED_REFLECTED_UID = -3727686823037358036L;
	private static final long RELEASED_CREATE_REFERENCE_UID = -4158068801437475007L;
	private static final long RELEASED_CREATE_REFLECTED_UID = 6927605828004667958L;
	private static final int TRAILING_MARKER = 0x5A6B7C8D;
	private static final Map<Scope, ReferenceSearchMode> MODES = Map.of(
		Scope.LIVE, ReferenceSearchMode.COMPOSITION, Scope.ARCHIVED, ReferenceSearchMode.COMPOSITION
	);
	private static final ScopedReferenceSearchMode[] SCOPED_MODES = new ScopedReferenceSearchMode[]{
		new ScopedReferenceSearchMode(Scope.LIVE, ReferenceSearchMode.COMPOSITION),
		new ScopedReferenceSearchMode(Scope.ARCHIVED, ReferenceSearchMode.COMPOSITION)
	};
	private static final Function<String, EntitySchemaContract> IMPOSSIBLE_EXCEPTION_PRODUCER = entityType -> {
		throw new GenericEvitaInternalError("Managed entity name variants must not be queried.");
	};

	/** Creates a normal reference with a managed target and explicit index configuration. */
	@Nonnull
	private static ReferenceSchema reference() {
		return ReferenceSchema._internalBuild(
			"blocks", "ContentBlock", true, Cardinality.ZERO_OR_MORE, null, false,
			ScopedReferenceIndexType.DEFAULT, Scope.NO_SCOPE,
			null, ReferenceSchema.DEFAULT_SEARCHABLE_THROUGH_WEIGHT
		);
	}

	/** Creates a reflected reference whose index and description are inherited. */
	@Nonnull
	private static ReflectedReferenceSchema reflected() {
		return ReflectedReferenceSchema._internalBuild(
			"products", "Product", "blocks", null, ReflectedReferenceSchema.DEFAULT_SEARCHABLE_THROUGH_WEIGHT
		);
	}

	/** Composes the catalog or entity-collection registration order used by real storage. */
	@Nonnull
	private static Kryo schemaKryo(boolean catalog) {
		return KryoFactory.createKryo(catalog
			? SchemaKryoConfigurer.INSTANCE.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
				.andThen(SharedClassesConfigurer.INSTANCE)
			: SchemaKryoConfigurer.INSTANCE.andThen(SharedClassesConfigurer.INSTANCE)
				.andThen(SchemaKryoConfigurer.INSTANCE));
	}

	/** Writes and reads through the configured version-routing serializers. */
	@Nonnull
	private static <T> T roundTrip(@Nonnull Kryo kryo, @Nonnull T object, @Nonnull Class<T> type) {
		try (final Output output = new Output(1024, -1)) {
			kryo.writeObject(output, object);
			output.writeInt(TRAILING_MARKER);
			try (final Input input = new Input(output.toBytes())) {
				final T result = kryo.readObject(input, type);
				assertEquals(TRAILING_MARKER, input.readInt());
				assertEquals(input.limit(), input.position());
				return result;
			}
		}
	}

	/** Reads exact released bytes, including the old UID, through the real registration. */
	@Nonnull
	private static <T> T readReleased(
		@Nonnull Kryo kryo, long uid, @Nonnull Class<T> type, @Nonnull Consumer<Output> legacyWriter
	) {
		try (final Output output = new Output(1024, -1)) {
			output.writeLong(uid);
			legacyWriter.accept(output);
			output.writeInt(TRAILING_MARKER);
			try (final Input input = new Input(output.toBytes())) {
				final T result = kryo.readObject(input, type);
				assertEquals(TRAILING_MARKER, input.readInt());
				assertEquals(input.limit(), input.position());
				return result;
			}
		}
	}

	/** Demonstrates that the very same legacy fixture fails if its reader registration is missing. */
	private static <T> void assertMissingReaderFails(
		Kryo kryo, long uid, Class<T> type, Serializer<T> currentSerializer, Consumer<Output> legacyWriter
	) {
		kryo.register(type, new SerialVersionBasedSerializer<>(currentSerializer, type),
			kryo.getRegistration(type).getId());
		assertThrows(StoredVersionNotSupportedException.class,
			() -> readReleased(kryo, uid, type, legacyWriter));
	}

	/** Both storage Kryo compositions carry the trailing modes and weight of a plain reference. */
	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	@DisplayName("normal reference preserves both scopes and its shared weight in composed storage Kryo")
	void shouldRoundTripNormalReference(boolean catalog) {
		final ReferenceSchema source = reference().withSearchableThrough(MODES, 0.25f);
		final ReferenceSchema restored = roundTrip(schemaKryo(catalog), source, ReferenceSchema.class);
		assertEquals(source, restored);
		assertEquals(MODES, restored.getSearchableThroughInScopes());
		assertEquals(0.25f, restored.getSearchableThroughWeight());
	}

	/** A stored reflected declaration survives binding to an original that declares a different one. */
	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	@DisplayName("reflected reference preserves its independent setting even after the original is bound")
	void shouldRoundTripReflectedReference(boolean catalog) {
		final ReflectedReferenceSchema source = reflected().withSearchableThrough(MODES, 0.75f);
		final ReflectedReferenceSchema restored = roundTrip(
			schemaKryo(catalog), source, ReflectedReferenceSchema.class
		);
		assertEquals(source, restored);
		final ReflectedReferenceSchema bound = restored.withReferencedSchema(
			reference().withSearchableThrough(Collections.emptyMap(), 0.125f)
		);
		assertEquals(MODES, bound.getSearchableThroughInScopes());
		assertEquals(0.75f, bound.getSearchableThroughWeight());
	}

	/** Released bytes under the old serial version need the registered reader, which defaults composition. */
	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	@DisplayName("released normal reference fixture defaults composition while consuming exactly the old layout")
	void shouldReadReleasedNormalReference(boolean catalog) {
		final Kryo kryo = schemaKryo(catalog);
		final ReferenceSchema source = reference();
		final Kryo withoutReader = schemaKryo(catalog);
		assertMissingReaderFails(withoutReader, RELEASED_REFERENCE_UID, ReferenceSchema.class,
			new ReferenceSchemaSerializer(), output -> writeReleased(withoutReader, output, source));
		final ReferenceSchema restored = readReleased(kryo, RELEASED_REFERENCE_UID, ReferenceSchema.class,
			output -> writeReleased(kryo, output, source));
		assertEquals(source, restored);
		assertEquals("ContentBlock", restored.getReferencedEntityType());
		assertNull(restored.getSearchableThrough());
		assertTrue(restored.getSearchableThroughInScopes().isEmpty());
		assertEquals(0.5f, restored.getSearchableThroughWeight());
	}

	/** Released reflected bytes need the registered reader, which defaults composition and keeps inheritance. */
	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	@DisplayName("released reflected reference fixture defaults its own composition and preserves inheritance")
	void shouldReadReleasedReflectedReference(boolean catalog) {
		final Kryo kryo = schemaKryo(catalog);
		final ReflectedReferenceSchema source = reflected();
		final Kryo withoutReader = schemaKryo(catalog);
		assertMissingReaderFails(withoutReader, RELEASED_REFLECTED_UID, ReflectedReferenceSchema.class,
			new ReflectedReferenceSchemaSerializer(), output -> writeReleased(withoutReader, output, source));
		final ReflectedReferenceSchema restored = readReleased(kryo, RELEASED_REFLECTED_UID,
			ReflectedReferenceSchema.class, output -> writeReleased(kryo, output, source));
		assertEquals(source, restored);
		assertTrue(restored.isIndexedInherited());
		assertEquals("blocks", restored.getReflectedReferenceName());
		assertTrue(restored.getSearchableThroughInScopes().isEmpty());
		assertEquals(0.5f, restored.getSearchableThroughWeight());
	}

	/** Null, empty and explicit modes each survive the WAL with a null, zero and explicit weight. */
	@Test
	@Tag(WAL)
	@DisplayName("set mutation WAL keeps null, empty, explicit scopes, zero and the shared weight distinct")
	void shouldRoundTripSetMutations() {
		final Kryo kryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		for (final ScopedReferenceSearchMode[] modes : new ScopedReferenceSearchMode[][]{
			null, ScopedReferenceSearchMode.EMPTY, SCOPED_MODES
		}) {
			for (final Float weight : new Float[]{null, 0.0f, 0.8f}) {
				final SetReferenceSchemaSearchableThroughMutation source =
					new SetReferenceSchemaSearchableThroughMutation("blocks", modes, weight);
				assertEquals(source, roundTrip(kryo, source, SetReferenceSchemaSearchableThroughMutation.class));
			}
		}
	}

	/** A create mutation keeps its composition in the WAL next to the trailing conflict resolution override. */
	@Test
	@Tag(WAL)
	@DisplayName("normal reference create WAL preserves composition modes, weight and conflict override")
	void shouldRoundTripCreateReferenceMutation() {
		final CreateReferenceSchemaMutation source = new CreateReferenceSchemaMutation(
			"blocks", "Shared text", "Use blocks", Cardinality.ZERO_OR_MORE, "ContentBlock", true, null, false,
			ScopedReferenceIndexType.DEFAULT, ScopedReferenceIndexedComponents.DEFAULT, Scope.NO_SCOPE,
			null, null, null, ConflictResolutionOverride.ENTITY, SCOPED_MODES, 0.2f
		);
		assertEquals(source, roundTrip(KryoFactory.createKryo(WalKryoConfigurer.INSTANCE), source,
			CreateReferenceSchemaMutation.class));
	}

	/** A reflected create mutation keeps its own composition in the WAL next to its inheritance filter. */
	@Test
	@Tag(WAL)
	@DisplayName("reflected reference create WAL preserves its independent composition modes and weight")
	void shouldRoundTripCreateReflectedReferenceMutation() {
		final CreateReflectedReferenceSchemaMutation source = new CreateReflectedReferenceSchemaMutation(
			"products", "Owners", "Use products", Cardinality.ZERO_OR_MORE, "Product", "blocks",
			null, null, null, null, null, null, AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
			new String[]{"code"}, SCOPED_MODES, 0.7f
		);
		assertEquals(source, roundTrip(KryoFactory.createKryo(WalKryoConfigurer.INSTANCE), source,
			CreateReflectedReferenceSchemaMutation.class));
	}

	/** A released create record reads with null composition, which applies as no mode and weight one half. */
	@Test
	@Tag(WAL)
	@DisplayName("released normal create WAL fixture preserves the trailing conflict override and defaults composition")
	void shouldReadReleasedCreateReferenceMutation() {
		final Kryo kryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		final CreateReferenceSchemaMutation source = new CreateReferenceSchemaMutation(
			"blocks", "Shared text", null, Cardinality.ZERO_OR_MORE, "ContentBlock", true, null, false,
			ScopedReferenceIndexType.DEFAULT, ScopedReferenceIndexedComponents.DEFAULT, Scope.NO_SCOPE,
			null, null, null, ConflictResolutionOverride.ENTITY
		);
		final Kryo withoutReader = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		assertMissingReaderFails(withoutReader, RELEASED_CREATE_REFERENCE_UID, CreateReferenceSchemaMutation.class,
			new CreateReferenceSchemaMutationSerializer(), output -> writeReleased(withoutReader, output, source));
		final CreateReferenceSchemaMutation restored = readReleased(kryo, RELEASED_CREATE_REFERENCE_UID,
			CreateReferenceSchemaMutation.class, output -> writeReleased(kryo, output, source));
		assertEquals(source, restored);
		assertEquals(ConflictResolutionOverride.ENTITY, restored.getConflictResolutionOverride());
		assertNull(restored.getSearchableThroughInScopes());
		assertNull(restored.getSearchableThroughWeight());
		final ReferenceSchemaContract resolved = restored.mutate(EntitySchema._internalBuild("Owner"), null);
		assertTrue(resolved.getSearchableThroughInScopes().isEmpty());
		assertEquals(0.5f, resolved.getSearchableThroughWeight());
	}

	/** A released reflected create record reads with null composition and keeps its inheritance filter. */
	@Test
	@Tag(WAL)
	@DisplayName("released reflected create WAL fixture preserves inheritance filter and defaults composition")
	void shouldReadReleasedCreateReflectedReferenceMutation() {
		final Kryo kryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		final CreateReflectedReferenceSchemaMutation source = new CreateReflectedReferenceSchemaMutation(
			"products", "Owners", null, Cardinality.ZERO_OR_MORE, "Product", "blocks", null, null,
			null, null, null, null, AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED, new String[]{"code"}
		);
		final Kryo withoutReader = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		assertMissingReaderFails(withoutReader, RELEASED_CREATE_REFLECTED_UID,
			CreateReflectedReferenceSchemaMutation.class, new CreateReflectedReferenceSchemaMutationSerializer(),
			output -> writeReleased(withoutReader, output, source));
		final CreateReflectedReferenceSchemaMutation restored = readReleased(kryo, RELEASED_CREATE_REFLECTED_UID,
			CreateReflectedReferenceSchemaMutation.class, output -> writeReleased(kryo, output, source));
		assertEquals(source, restored);
		assertArrayEquals(new String[]{"code"}, restored.getAttributeInheritanceFilter());
		assertNull(restored.getSearchableThroughInScopes());
		assertNull(restored.getSearchableThroughWeight());
		final ReferenceSchemaContract resolved = restored.mutate(EntitySchema._internalBuild("Owner"), null);
		assertTrue(resolved.getSearchableThroughInScopes().isEmpty());
		assertEquals(0.5f, resolved.getSearchableThroughWeight());
	}

	/** Released-format readers never write, so no new record can be stored in an old layout. */
	@Test
	@DisplayName("all four released-format serializers reject writes")
	void shouldKeepReleasedReadersReadOnly() {
		final Kryo kryo = schemaKryo(true);
		try (final Output output = new Output(1024, -1)) {
			assertThrows(UnsupportedOperationException.class,
				() -> new ReferenceSchemaSerializer_2026_2_2().write(kryo, output, reference()));
			assertThrows(UnsupportedOperationException.class,
				() -> new ReflectedReferenceSchemaSerializer_2026_2_2().write(kryo, output, reflected()));
			assertThrows(UnsupportedOperationException.class,
				() -> new CreateReferenceSchemaMutationSerializer_2026_2_2().write(kryo, output, null));
			assertThrows(UnsupportedOperationException.class,
				() -> new CreateReflectedReferenceSchemaMutationSerializer_2026_2_2().write(kryo, output, null));
		}
	}

	/** Writes the exact v2026.2.2 layout of ReferenceSchema, without composition fields. */
	private static void writeReleased(Kryo kryo, Output output, ReferenceSchema referenceSchema) {
		output.writeString(referenceSchema.getName());
		writeNameVariants(output, referenceSchema.getNameVariants());
		output.writeString(referenceSchema.getReferencedEntityType());
		output.writeBoolean(referenceSchema.isReferencedEntityTypeManaged());

		final Map<NamingConvention, String> entityTypeNameVariants = referenceSchema.isReferencedEntityTypeManaged() ?
			Collections.emptyMap() : referenceSchema.getEntityTypeNameVariants(IMPOSSIBLE_EXCEPTION_PRODUCER);
		writeNameVariants(output, entityTypeNameVariants);
		kryo.writeObject(output, referenceSchema.getCardinality());
		output.writeString(referenceSchema.getReferencedGroupType());
		output.writeBoolean(referenceSchema.isReferencedGroupTypeManaged());

		final Map<NamingConvention, String> groupTypeNameVariants = referenceSchema.isReferencedGroupTypeManaged() ?
			Collections.emptyMap() : referenceSchema.getGroupTypeNameVariants(IMPOSSIBLE_EXCEPTION_PRODUCER);
		writeNameVariants(output, groupTypeNameVariants);

		writeScopedReferenceIndexTypeArray(kryo, output, referenceSchema.getReferenceIndexTypeInScopes());
		writeIndexedComponentsMap(kryo, output, referenceSchema.getIndexedComponentsInScopes());
		writeScopeSet(kryo, output, referenceSchema.getFacetedInScopes());

		writeFacetedPartiallyMap(kryo, output, referenceSchema.getFacetedPartiallyInScopes());

		writeBucketedHistogramMap(kryo, output, referenceSchema.getAllHistogramIndexDefinitions());
		// reuse faceted partially serializer — same Map<Scope, Expression> shape
		writeFacetedPartiallyMap(kryo, output, referenceSchema.getBucketedPartiallyInScopes());

		kryo.writeObject(output, referenceSchema.getAttributes());

		if (referenceSchema.getDescription() != null) {
			output.writeBoolean(true);
			output.writeString(referenceSchema.getDescription());
		} else {
			output.writeBoolean(false);
		}
		if (referenceSchema.getDeprecationNotice() != null) {
			output.writeBoolean(true);
			output.writeString(referenceSchema.getDeprecationNotice());
		} else {
			output.writeBoolean(false);
		}

		writeSortableAttributeCompounds(kryo, output, referenceSchema.getSortableAttributeCompounds().values());

		kryo.writeObject(output, referenceSchema.getConflictResolutionOverride());
	}

	/** Writes the exact v2026.2.2 layout of ReflectedReferenceSchema, without composition fields. */
	private static void writeReleased(Kryo kryo, Output output, ReflectedReferenceSchema referenceSchema) {
		output.writeString(referenceSchema.getName());
		writeNameVariants(output, referenceSchema.getNameVariants());
		output.writeString(referenceSchema.getReferencedEntityType());
		output.writeString(referenceSchema.getReflectedReferenceName());

		kryo.writeObjectOrNull(
			output,
			referenceSchema.isCardinalityInherited() ? null : referenceSchema.getCardinality(),
			Cardinality.class
		);

		if (referenceSchema.isIndexedInherited()) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			writeScopedReferenceIndexTypeArray(kryo, output, referenceSchema.getReferenceIndexTypeInScopes());
		}
		if (referenceSchema.isIndexedComponentsInherited()) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			writeIndexedComponentsMap(kryo, output, referenceSchema.getIndexedComponentsInScopes());
		}
		if (referenceSchema.isFacetedInherited()) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			writeScopeSet(kryo, output, referenceSchema.getFacetedInScopes());
		}

		final Map<Scope, Expression> facetedPartiallyInScopes = referenceSchema.isFacetedInherited() ?
			null : referenceSchema.getFacetedPartiallyInScopes();
		if (facetedPartiallyInScopes == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			writeFacetedPartiallyMap(kryo, output, facetedPartiallyInScopes);
		}

		writeBucketedHistogramMap(kryo, output, referenceSchema.getAllHistogramIndexDefinitions());
		writeFacetedPartiallyMap(kryo, output, referenceSchema.getBucketedPartiallyInScopes());

		kryo.writeObject(output, referenceSchema.getDeclaredAttributes());

		kryo.writeObjectOrNull(
			output,
			referenceSchema.isDescriptionInherited() ? null : referenceSchema.getDescription(),
			String.class
		);

		kryo.writeObjectOrNull(
			output,
			referenceSchema.isDeprecatedInherited() ? null : referenceSchema.getDeprecationNotice(),
			String.class
		);

		writeSortableAttributeCompounds(kryo, output, referenceSchema.getDeclaredSortableAttributeCompounds().values());

		kryo.writeObject(output, referenceSchema.getAttributesInheritanceBehavior());
		output.writeVarInt(referenceSchema.getAttributeInheritanceFilter().length, true);
		for (String attributeName : referenceSchema.getAttributeInheritanceFilter()) {
			output.writeString(attributeName);
		}
	}

	/** Writes the exact v2026.2.2 layout of CreateReferenceSchemaMutation, without composition fields. */
	private static void writeReleased(Kryo kryo, Output output, CreateReferenceSchemaMutation mutation) {
		output.writeString(mutation.getName());
		output.writeString(mutation.getDescription());
		output.writeString(mutation.getDeprecationNotice());
		kryo.writeObject(output, mutation.getCardinality());
		output.writeString(mutation.getReferencedEntityType());
		output.writeBoolean(mutation.isReferencedEntityTypeManaged());
		output.writeString(mutation.getReferencedGroupType());
		output.writeBoolean(mutation.isReferencedGroupTypeManaged());
		MUTATION_FUNCTIONS.writeScopedReferenceIndexTypeArray(kryo, output, mutation.getIndexedInScopes());
		MUTATION_FUNCTIONS.writeScopeArray(kryo, output, mutation.getFacetedInScopes());
		// write indexed components with null-check
		output.writeBoolean(true);
		MUTATION_FUNCTIONS.writeScopedReferenceIndexedComponentsArray(
			kryo, output, mutation.getIndexedComponentsInScopes()
		);
		// write facetedPartially expressions
		writeScopedFacetedPartiallyArray(kryo, output, mutation.getFacetedPartiallyInScopes());
		// write bucketed histogram definitions
		writeScopedHistogramIndexDefinitionArray(kryo, output, mutation.getBucketedInScopes());
		// write bucketedPartially expressions
		writeScopedBucketedPartiallyArray(kryo, output, mutation.getBucketedPartiallyInScopes());
		kryo.writeObject(output, mutation.getConflictResolutionOverride());
	}

	/** Writes the exact v2026.2.2 layout of CreateReflectedReferenceSchemaMutation, without composition fields. */
	private static void writeReleased(Kryo kryo, Output output, CreateReflectedReferenceSchemaMutation mutation) {
		output.writeString(mutation.getName());
		output.writeString(mutation.getDescription());
		output.writeString(mutation.getDeprecationNotice());
		kryo.writeObjectOrNull(output, mutation.getCardinality(), Cardinality.class);
		output.writeString(mutation.getReferencedEntityType());
		output.writeString(mutation.getReflectedReferenceName());

		if (mutation.getIndexedInScopes() == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			MUTATION_FUNCTIONS.writeScopedReferenceIndexTypeArray(kryo, output, mutation.getIndexedInScopes());
		}
		if (mutation.getFacetedInScopes() == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			MUTATION_FUNCTIONS.writeScopeArray(kryo, output, mutation.getFacetedInScopes());
		}
		// write indexed components with null-check
		if (mutation.getIndexedComponentsInScopes() != null) {
			output.writeBoolean(true);
			MUTATION_FUNCTIONS.writeScopedReferenceIndexedComponentsArray(
				kryo, output, mutation.getIndexedComponentsInScopes()
			);
		} else {
			output.writeBoolean(false);
		}
		// write facetedPartially expressions
		writeScopedFacetedPartiallyArray(kryo, output, mutation.getFacetedPartiallyInScopes());
		// write bucketed histogram definitions with null-check
		if (mutation.getBucketedInScopes() == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			writeScopedHistogramIndexDefinitionArray(kryo, output, mutation.getBucketedInScopes());
		}
		// write bucketedPartially expressions
		writeScopedBucketedPartiallyArray(kryo, output, mutation.getBucketedPartiallyInScopes());

		kryo.writeObject(output, mutation.getAttributeInheritanceBehavior());

		final String[] attributesExcludedFromInheritance = mutation.getAttributeInheritanceFilter();
		output.writeVarInt(attributesExcludedFromInheritance.length, true);
		for (final String attribute : attributesExcludedFromInheritance) {
			output.writeString(attribute);
		}
	}
}
