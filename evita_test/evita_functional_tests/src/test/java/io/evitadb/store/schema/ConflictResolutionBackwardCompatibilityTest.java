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
import io.evitadb.api.CatalogContract;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.store.catalog.CatalogHeaderKryoConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.store.wal.schema.associatedData.CreateAssociatedDataSchemaMutationSerializer;
import io.evitadb.store.wal.schema.attribute.CreateAttributeSchemaMutationSerializer;
import io.evitadb.store.wal.schema.attribute.CreateGlobalAttributeSchemaMutationSerializer;
import io.evitadb.store.wal.schema.reference.CreateReferenceSchemaMutationSerializer;
import io.evitadb.store.schema.serializer.AssociatedDataSchemaSerializer;
import io.evitadb.store.schema.serializer.AttributeSchemaSerializer;
import io.evitadb.store.schema.serializer.EntityAttributeSchemaSerializer;
import io.evitadb.store.schema.serializer.GlobalAttributeSchemaSerializer;
import io.evitadb.store.schema.serializer.ReferenceSchemaSerializer;
import io.evitadb.store.shared.serializer.dataType.HeterogeneousMapSerializer;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.CatalogSchemaStoragePart;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies backward-compatible deserialization of schema types and schema mutations that gained the conflict-resolution
 * field. When that field was added the {@code serialVersionUID} of every affected class was bumped, so any record
 * persisted by the immediately-preceding format must still be readable through the version-routing
 * {@link io.evitadb.store.entity.serializer.SerialVersionBasedSerializer}, defaulting the (absent) override to
 * {@link ConflictResolutionOverride#INHERITED}.
 *
 * Each test emulates a pre-conflict on-disk record: it writes the routing envelope's leading {@code serialVersionUID}
 * as the orphaned pre-conflict value, followed by the payload produced by the current serializer (the conflict field
 * was strictly appended, so the pre-conflict payload is a byte-exact prefix of the current one, which the
 * backward-compatible reader stops short of). The record is then read back through the fully configured Kryo, exercising
 * the real registration/routing path — not just the current serializer.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(WAL)
@Tag(SCHEMA)
class ConflictResolutionBackwardCompatibilityTest {

	/**
	 * Pre-conflict {@code serialVersionUID} of {@link ReferenceSchema} (storage), orphaned by the conflict field bump.
	 */
	private static final long REFERENCE_SCHEMA_PRE_CONFLICT_UID = 5443565766311111159L;
	/**
	 * Pre-conflict {@code serialVersionUID} of {@link CreateReferenceSchemaMutation} (WAL), orphaned by the bump.
	 */
	private static final long CREATE_REFERENCE_MUTATION_PRE_CONFLICT_UID = -4158068801437475008L;
	/**
	 * Pre-conflict {@code serialVersionUID} of {@link CreateAttributeSchemaMutation} (WAL) — covered by its `_2026_1`
	 * backward-compatible reader, which previously had no read-coverage.
	 */
	private static final long CREATE_ATTRIBUTE_MUTATION_PRE_CONFLICT_UID = -469815390440407270L;
	/**
	 * Pre-conflict {@code serialVersionUID} of {@link CreateGlobalAttributeSchemaMutation} (WAL) — covered by `_2026_1`.
	 */
	private static final long CREATE_GLOBAL_ATTRIBUTE_MUTATION_PRE_CONFLICT_UID = 496202593310308290L;
	/**
	 * Pre-conflict {@code serialVersionUID} of {@link CreateAssociatedDataSchemaMutation} (WAL) — covered by `_2026_1`.
	 */
	private static final long CREATE_ASSOCIATED_DATA_MUTATION_PRE_CONFLICT_UID = -7368528015832499968L;
	/**
	 * Pre-2026.2 {@code serialVersionUID} of {@link CatalogSchema} (storage), bumped by the conflict-resolution field.
	 */
	private static final long CATALOG_SCHEMA_PRE_CONFLICT_UID = -1582409928666780012L;
	/**
	 * Pre-conflict {@code serialVersionUID} of {@link AttributeSchema} (storage), covered by its `_2026_1` reader.
	 */
	private static final long ATTRIBUTE_SCHEMA_PRE_CONFLICT_UID = -4825670975814791474L;
	/**
	 * Pre-conflict {@code serialVersionUID} of {@link GlobalAttributeSchema} (storage), covered by `_2026_1`.
	 */
	private static final long GLOBAL_ATTRIBUTE_SCHEMA_PRE_CONFLICT_UID = -6027390261318420826L;
	/**
	 * Pre-conflict {@code serialVersionUID} of {@link EntityAttributeSchema} (storage), covered by `_2026_1`.
	 */
	private static final long ENTITY_ATTRIBUTE_SCHEMA_PRE_CONFLICT_UID = 8168305590483159082L;
	/**
	 * Pre-conflict {@code serialVersionUID} of {@link AssociatedDataSchema} (storage), covered by `_2026_1`.
	 */
	private static final long ASSOCIATED_DATA_SCHEMA_PRE_CONFLICT_UID = -995599294301442064L;
	/**
	 * Pre-conflict {@code serialVersionUID} of {@link EntitySchema} (storage), bumped by the conflict-resolution field.
	 */
	private static final long ENTITY_SCHEMA_PRE_CONFLICT_UID = 8694215716025515883L;

	@Test
	@DisplayName("pre-conflict CatalogSchema reads back through the composed catalog kryo as inherited")
	void shouldReadPreConflictCatalogSchemaThroughComposedCatalogKryo() {
		// Reproduce the real catalog-storage kryo: SchemaKryoConfigurer registers CatalogSchema first, then
		// CatalogHeaderKryoConfigurer re-registers it and wins by class (Kryo's classToRegistration is last-write-wins).
		// The backward-compatible reader must therefore live on the header registration too; without it this read throws
		// StoredVersionNotSupportedException for every catalog persisted before the 2026.2 bump.
		final Kryo kryo = KryoFactory.createKryo(
			SchemaKryoConfigurer.INSTANCE
				.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
				.andThen(SharedClassesConfigurer.INSTANCE)
		);
		// The conflict field is written mid-stream (before evolution modes and attributes), so a pre-conflict record is
		// NOT a byte prefix of the current format — its bytes are rendered explicitly in the pre-2026.2 layout.
		final byte[] preConflictRecord = renderPreConflictCatalogSchema(kryo);

		// the pre-conflict reader resolves nested entity schemas from a deserialization-context catalog (unused by the
		// assertions here, so a mock suffices) — mirrors DefaultCatalogPersistenceService's read path
		final CatalogSchema deserialized;
		try (final Input input = new Input(new ByteArrayInputStream(preConflictRecord))) {
			deserialized = CatalogSchemaStoragePart.deserializeWithCatalog(
				Mockito.mock(CatalogContract.class),
				() -> kryo.readObject(input, CatalogSchema.class)
			);
		}

		// preceding fields survive intact — proves the reader is byte-aligned …
		assertEquals(TestConstants.TEST_CATALOG, deserialized.getName());
		// … and the absent conflict resolution reads back as empty (inherited)
		assertTrue(deserialized.getConflictResolution().isEmpty());
	}

	@Test
	@DisplayName("pre-conflict ReferenceSchema (storage) reads back with INHERITED override")
	void shouldReadPreConflictReferenceSchemaAsInheritedOverride() {
		final Kryo kryo = KryoFactory.createKryo(
			SchemaKryoConfigurer.INSTANCE.andThen(SharedClassesConfigurer.INSTANCE)
		);
		final ReferenceSchema referenceSchema = buildReferenceSchemaWithOverride(ConflictResolutionOverride.ENTITY);

		final ReferenceSchema deserialized = readThroughBackwardCompatibleRoute(
			kryo,
			REFERENCE_SCHEMA_PRE_CONFLICT_UID,
			new ReferenceSchemaSerializer(),
			ReferenceSchema.class,
			referenceSchema
		);

		// preceding fields must survive intact — proves the reader is byte-aligned, not merely defaulting …
		assertEquals(Entities.BRAND, deserialized.getName());
		assertEquals(Cardinality.ZERO_OR_ONE, deserialized.getCardinality());
		// … and the absent override defaults to INHERITED
		assertEquals(ConflictResolutionOverride.INHERITED, deserialized.getConflictResolutionOverride());
	}

	@Test
	@DisplayName("pre-conflict AttributeSchema (storage) reads back with INHERITED override")
	void shouldReadPreConflictAttributeSchemaAsInheritedOverride() {
		final Kryo kryo = KryoFactory.createKryo(
			SchemaKryoConfigurer.INSTANCE.andThen(SharedClassesConfigurer.INSTANCE)
		);
		final AttributeSchema attributeSchema = AttributeSchema._internalBuild(
			"code", String.class, false, ConflictResolutionOverride.ENTITY
		);

		final AttributeSchema deserialized = readThroughBackwardCompatibleRoute(
			kryo,
			ATTRIBUTE_SCHEMA_PRE_CONFLICT_UID,
			new AttributeSchemaSerializer(),
			AttributeSchema.class,
			attributeSchema
		);

		// preceding fields must survive intact — proves the reader is byte-aligned, not merely defaulting …
		assertEquals("code", deserialized.getName());
		// … and the absent override defaults to INHERITED
		assertEquals(ConflictResolutionOverride.INHERITED, deserialized.getConflictResolutionOverride());
	}

	@Test
	@DisplayName("pre-conflict GlobalAttributeSchema (storage) reads back with INHERITED override")
	void shouldReadPreConflictGlobalAttributeSchemaAsInheritedOverride() {
		final Kryo kryo = KryoFactory.createKryo(
			SchemaKryoConfigurer.INSTANCE.andThen(SharedClassesConfigurer.INSTANCE)
		);
		final GlobalAttributeSchema attributeSchema = GlobalAttributeSchema._internalBuild(
			"url", String.class, false, ConflictResolutionOverride.GRANULAR
		);

		final GlobalAttributeSchema deserialized = readThroughBackwardCompatibleRoute(
			kryo,
			GLOBAL_ATTRIBUTE_SCHEMA_PRE_CONFLICT_UID,
			new GlobalAttributeSchemaSerializer(),
			GlobalAttributeSchema.class,
			attributeSchema
		);

		// preceding fields must survive intact — proves the reader is byte-aligned, not merely defaulting …
		assertEquals("url", deserialized.getName());
		// … and the absent override defaults to INHERITED
		assertEquals(ConflictResolutionOverride.INHERITED, deserialized.getConflictResolutionOverride());
	}

	@Test
	@DisplayName("pre-conflict EntityAttributeSchema (storage) reads back with INHERITED override")
	void shouldReadPreConflictEntityAttributeSchemaAsInheritedOverride() {
		final Kryo kryo = KryoFactory.createKryo(
			SchemaKryoConfigurer.INSTANCE.andThen(SharedClassesConfigurer.INSTANCE)
		);
		final EntityAttributeSchema attributeSchema = EntityAttributeSchema._internalBuild(
			"name", String.class, false, ConflictResolutionOverride.ENTITY
		);

		final EntityAttributeSchema deserialized = readThroughBackwardCompatibleRoute(
			kryo,
			ENTITY_ATTRIBUTE_SCHEMA_PRE_CONFLICT_UID,
			new EntityAttributeSchemaSerializer(),
			EntityAttributeSchema.class,
			attributeSchema
		);

		// preceding fields must survive intact — proves the reader is byte-aligned, not merely defaulting …
		assertEquals("name", deserialized.getName());
		// … and the absent override defaults to INHERITED
		assertEquals(ConflictResolutionOverride.INHERITED, deserialized.getConflictResolutionOverride());
	}

	@Test
	@DisplayName("pre-conflict AssociatedDataSchema (storage) reads back with INHERITED override")
	void shouldReadPreConflictAssociatedDataSchemaAsInheritedOverride() {
		final Kryo kryo = KryoFactory.createKryo(
			SchemaKryoConfigurer.INSTANCE.andThen(SharedClassesConfigurer.INSTANCE)
		);
		final AssociatedDataSchema associatedDataSchema = (AssociatedDataSchema) AssociatedDataSchema._internalBuild(
			"labels", String.class, ConflictResolutionOverride.ENTITY
		);

		final AssociatedDataSchema deserialized = readThroughBackwardCompatibleRoute(
			kryo,
			ASSOCIATED_DATA_SCHEMA_PRE_CONFLICT_UID,
			new AssociatedDataSchemaSerializer(),
			AssociatedDataSchema.class,
			associatedDataSchema
		);

		// preceding fields must survive intact — proves the reader is byte-aligned, not merely defaulting …
		assertEquals("labels", deserialized.getName());
		// … and the absent override defaults to INHERITED
		assertEquals(ConflictResolutionOverride.INHERITED, deserialized.getConflictResolutionOverride());
	}

	@Test
	@DisplayName("pre-conflict EntitySchema (storage) reads back with an empty (inherited) conflict resolution")
	void shouldReadPreConflictEntitySchemaAsInheritedResolution() {
		final Kryo kryo = KryoFactory.createKryo(
			SchemaKryoConfigurer.INSTANCE.andThen(SharedClassesConfigurer.INSTANCE)
		);
		// The conflict field is written mid-stream (after the deprecation notice, before the sortable attribute
		// compounds), so a pre-conflict record is NOT a byte prefix of the current format — its bytes are rendered
		// explicitly in the pre-conflict layout.
		final byte[] preConflictRecord = renderPreConflictEntitySchema(kryo);

		final EntitySchema deserialized;
		try (final Input input = new Input(new ByteArrayInputStream(preConflictRecord))) {
			deserialized = kryo.readObject(input, EntitySchema.class);
		}

		// a preceding field survives intact …
		assertEquals(Entities.PRODUCT, deserialized.getName());
		// … the sortable attribute compound following the conflict field survives intact — proves the reader is
		// byte-aligned across the skipped conflict field, not merely defaulting …
		assertTrue(deserialized.getSortableAttributeCompound("codeName").isPresent());
		// … and the absent conflict resolution reads back as empty (inherited)
		assertTrue(deserialized.getConflictResolution().isEmpty());
	}

	@Test
	@DisplayName("pre-conflict CreateReferenceSchemaMutation (WAL) reads back with INHERITED override")
	void shouldReadPreConflictCreateReferenceSchemaMutationAsInheritedOverride() {
		final Kryo walKryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		final CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
			"brand", null, null, Cardinality.ZERO_OR_ONE, "brand", true, null, false,
			null, null, null, null, null, null,
			ConflictResolutionOverride.ENTITY
		);

		final CreateReferenceSchemaMutation deserialized = readThroughBackwardCompatibleRoute(
			walKryo,
			CREATE_REFERENCE_MUTATION_PRE_CONFLICT_UID,
			new CreateReferenceSchemaMutationSerializer(),
			CreateReferenceSchemaMutation.class,
			mutation
		);

		// preceding fields must survive intact — proves the reader is byte-aligned, not merely defaulting …
		assertEquals("brand", deserialized.getName());
		assertEquals(Cardinality.ZERO_OR_ONE, deserialized.getCardinality());
		assertEquals("brand", deserialized.getReferencedEntityType());
		// … and the absent override defaults to INHERITED
		assertEquals(ConflictResolutionOverride.INHERITED, deserialized.getConflictResolutionOverride());
	}

	@Test
	@DisplayName("pre-conflict CreateAttributeSchemaMutation (WAL) reads back with INHERITED override")
	void shouldReadPreConflictCreateAttributeSchemaMutationAsInheritedOverride() {
		final Kryo walKryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		final CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
			"code", null, null, null, null, null,
			false, false, false, String.class, null, 0,
			ConflictResolutionOverride.GRANULAR
		);

		final CreateAttributeSchemaMutation deserialized = readThroughBackwardCompatibleRoute(
			walKryo,
			CREATE_ATTRIBUTE_MUTATION_PRE_CONFLICT_UID,
			new CreateAttributeSchemaMutationSerializer(),
			CreateAttributeSchemaMutation.class,
			mutation
		);

		assertEquals("code", deserialized.getName());
		assertEquals(ConflictResolutionOverride.INHERITED, deserialized.getConflictResolutionOverride());
	}

	@Test
	@DisplayName("pre-conflict CreateGlobalAttributeSchemaMutation (WAL) reads back with INHERITED override")
	void shouldReadPreConflictCreateGlobalAttributeSchemaMutationAsInheritedOverride() {
		final Kryo walKryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		final CreateGlobalAttributeSchemaMutation mutation = new CreateGlobalAttributeSchemaMutation(
			"url", null, null, null, null, null, null,
			false, false, false, String.class, null, 0,
			ConflictResolutionOverride.GRANULAR
		);

		final CreateGlobalAttributeSchemaMutation deserialized = readThroughBackwardCompatibleRoute(
			walKryo,
			CREATE_GLOBAL_ATTRIBUTE_MUTATION_PRE_CONFLICT_UID,
			new CreateGlobalAttributeSchemaMutationSerializer(),
			CreateGlobalAttributeSchemaMutation.class,
			mutation
		);

		assertEquals("url", deserialized.getName());
		assertEquals(ConflictResolutionOverride.INHERITED, deserialized.getConflictResolutionOverride());
	}

	@Test
	@DisplayName("pre-conflict CreateAssociatedDataSchemaMutation (WAL) reads back with INHERITED override")
	void shouldReadPreConflictCreateAssociatedDataSchemaMutationAsInheritedOverride() {
		final Kryo walKryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		final CreateAssociatedDataSchemaMutation mutation = new CreateAssociatedDataSchemaMutation(
			"labels", null, null, String.class, false, false,
			ConflictResolutionOverride.ENTITY
		);

		final CreateAssociatedDataSchemaMutation deserialized = readThroughBackwardCompatibleRoute(
			walKryo,
			CREATE_ASSOCIATED_DATA_MUTATION_PRE_CONFLICT_UID,
			new CreateAssociatedDataSchemaMutationSerializer(),
			CreateAssociatedDataSchemaMutation.class,
			mutation
		);

		assertEquals("labels", deserialized.getName());
		assertEquals(ConflictResolutionOverride.INHERITED, deserialized.getConflictResolutionOverride());
	}

	/**
	 * Builds a standalone {@link ReferenceSchema} carrying the given override by declaring it on a throwaway product
	 * entity schema and extracting the reference.
	 *
	 * @param override the per-reference conflict resolution override to declare
	 * @return the concrete reference schema DTO
	 */
	@Nonnull
	private static ReferenceSchema buildReferenceSchemaWithOverride(@Nonnull ConflictResolutionOverride override) {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CatalogSchema._internalBuild(
				TestConstants.TEST_CATALOG,
				NamingConvention.generate(TestConstants.TEST_CATALOG),
				null,
				EnumSet.allOf(CatalogEvolutionMode.class),
				EmptyEntitySchemaAccessor.INSTANCE
			),
			EntitySchema._internalBuild(Entities.PRODUCT)
		)
			.withReferenceToEntity(
				Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.withConflictResolutionOverride(override)
			)
			.toInstance();
		return (ReferenceSchema) schema.getReference(Entities.BRAND).orElseThrow();
	}

	/**
	 * Emulates reading a record persisted by the pre-conflict format and routes it through the fully configured Kryo.
	 * The routing envelope's leading {@code serialVersionUID} is written as the orphaned pre-conflict value, followed by
	 * the payload produced by the current serializer. Because the conflict field was strictly appended, the pre-conflict
	 * payload is a byte-exact prefix of the current payload; the backward-compatible reader selected by the routing stops
	 * short of the trailing conflict bytes, reproducing a genuine old on-disk record. The object is then read back
	 * through {@code kryo.readObject}, exercising the real registration and version-routing path.
	 *
	 * @param kryo             the fully configured Kryo whose registered version-routing serializer must select the
	 *                         pre-conflict reader
	 * @param orphanedUid      the pre-conflict {@code serialVersionUID} stored in the envelope
	 * @param currentSerializer the current serializer used to render the payload prefix deterministically
	 * @param type             the deserialized type
	 * @param object           the object whose current-format payload is rendered
	 * @param <T>              the schema/mutation type
	 * @return the object read back through the version-routing path
	 */
	/**
	 * Renders a {@link CatalogSchema} record in the pre-2026.2 on-disk layout — the current layout minus the
	 * conflict-resolution field, which the current serializer writes mid-stream (before evolution modes and attributes)
	 * rather than appended, so a pre-conflict record is not a byte prefix of the current one. The variable sub-objects
	 * (evolution modes, attributes) are produced from a real {@link CatalogSchema} through the same Kryo so their encoding
	 * matches the registered collection serializers exactly. The record is prefixed with the orphaned pre-conflict
	 * {@code serialVersionUID} so the version-routing serializer selects the backward-compatible reader.
	 *
	 * @param kryo the fully configured Kryo whose collection serializers render the sub-objects
	 * @return the raw bytes of a pre-conflict catalog-schema record
	 */
	@Nonnull
	private static byte[] renderPreConflictCatalogSchema(@Nonnull Kryo kryo) {
		final CatalogSchema source = CatalogSchema._internalBuild(
			TestConstants.TEST_CATALOG,
			NamingConvention.generate(TestConstants.TEST_CATALOG),
			null,
			EnumSet.allOf(CatalogEvolutionMode.class),
			EmptyEntitySchemaAccessor.INSTANCE
		);
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(1_024);
		try (final Output output = new Output(baos)) {
			output.writeLong(CATALOG_SCHEMA_PRE_CONFLICT_UID);
			output.writeInt(source.version());
			output.writeString(source.getName());
			final Map<NamingConvention, String> nameVariants = source.getNameVariants();
			output.writeVarInt(nameVariants.size(), true);
			for (final Map.Entry<NamingConvention, String> entry : nameVariants.entrySet()) {
				output.writeVarInt(entry.getKey().ordinal(), true);
				output.writeString(entry.getValue());
			}
			output.writeBoolean(false); // description is null — pre-conflict layout, no conflict field follows
			kryo.writeObject(output, source.getCatalogEvolutionMode());
			kryo.writeObject(output, source.getAttributes());
		}
		return baos.toByteArray();
	}

	/**
	 * Renders an {@link EntitySchema} record in the pre-2026.2 on-disk layout — the current layout minus the
	 * conflict-resolution field, which the current serializer writes mid-stream (after the deprecation notice, before
	 * the sortable attribute compounds) rather than appended, so a pre-conflict record is not a byte prefix of the
	 * current one. Every field is derived from a real {@link EntitySchema} and written in the exact order and encoding
	 * of the current serializer (attributes and references through an equivalent {@link HeterogeneousMapSerializer}),
	 * omitting only the conflict-presence boolean. The record is prefixed with the orphaned pre-conflict
	 * {@code serialVersionUID} so the version-routing serializer selects the backward-compatible reader.
	 *
	 * The source schema carries a sortable attribute compound; because that compound is the sole field written after
	 * the skipped conflict field, its intact survival on read-back is the byte-alignment witness.
	 *
	 * @param kryo the fully configured Kryo whose collection serializers render the sub-objects
	 * @return the raw bytes of a pre-conflict entity-schema record
	 */
	@Nonnull
	private static byte[] renderPreConflictEntitySchema(@Nonnull Kryo kryo) {
		final EntitySchema source = (EntitySchema) new InternalEntitySchemaBuilder(
			CatalogSchema._internalBuild(
				TestConstants.TEST_CATALOG,
				NamingConvention.generate(TestConstants.TEST_CATALOG),
				null,
				EnumSet.allOf(CatalogEvolutionMode.class),
				EmptyEntitySchemaAccessor.INSTANCE
			),
			EntitySchema._internalBuild(Entities.PRODUCT)
		)
			.withAttribute("code", String.class, whichIs -> whichIs.sortable())
			.withAttribute("name", String.class, whichIs -> whichIs.sortable())
			.withSortableAttributeCompound(
				"codeName",
				new AttributeElement("code", OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new AttributeElement("name", OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			)
			.toInstance();

		// attributes and references travel through a heterogeneous map serializer in the real writer; mirror it exactly
		final HeterogeneousMapSerializer<Object, Object> heterogeneousSerializer =
			new HeterogeneousMapSerializer<>(LinkedHashMap::new);

		final ByteArrayOutputStream baos = new ByteArrayOutputStream(2_048);
		try (final Output output = new Output(baos)) {
			output.writeLong(ENTITY_SCHEMA_PRE_CONFLICT_UID);
			output.writeInt(source.version());
			output.writeString(source.getName());
			// name variants (mirrors EntitySchemaSerializer.writeNameVariants, which is not visible from this package)
			final Map<NamingConvention, String> nameVariants = source.getNameVariants();
			output.writeVarInt(nameVariants.size(), true);
			for (final Map.Entry<NamingConvention, String> entry : nameVariants.entrySet()) {
				output.writeVarInt(entry.getKey().ordinal(), true);
				output.writeString(entry.getValue());
			}
			output.writeBoolean(source.isWithGeneratedPrimaryKey());
			output.writeBoolean(source.isWithHierarchy());
			writeScopeSet(kryo, output, source.getHierarchyIndexedInScopes());
			output.writeBoolean(source.isWithPrice());
			writeScopeSet(kryo, output, source.getPriceIndexedInScopes());
			output.writeInt(source.getIndexedPricePlaces(), true);
			kryo.writeObject(output, source.getLocales());
			kryo.writeObject(output, source.getCurrencies());
			kryo.writeObject(output, source.getAttributes(), heterogeneousSerializer);
			kryo.writeObject(output, source.getAssociatedData());
			kryo.writeObject(output, source.getReferences(), heterogeneousSerializer);
			kryo.writeObject(output, source.getEvolutionMode());
			output.writeBoolean(false); // description is null — pre-conflict layout
			output.writeBoolean(false); // deprecationNotice is null — pre-conflict layout, no conflict field follows
			// the sortable attribute compounds are the sole field written after the (now absent) conflict field
			final Collection<EntitySortableAttributeCompoundSchemaContract> compounds =
				source.getSortableAttributeCompounds().values();
			output.writeVarInt(compounds.size(), true);
			for (final EntitySortableAttributeCompoundSchemaContract compound : compounds) {
				kryo.writeObject(output, compound);
			}
		}
		return baos.toByteArray();
	}

	/**
	 * Writes an {@link EnumSet} of {@link Scope} exactly as {@code EntitySchemaSerializer.writeScopeSet} does — a
	 * var-int size prefix followed by each scope through the registered enum serializer. Replicated here because that
	 * helper is package-private to the serializer package and unreachable from this test's package.
	 *
	 * @param kryo   the Kryo whose registered {@link Scope} serializer encodes each element
	 * @param output the output to write to
	 * @param scopes the scope set to serialize
	 */
	private static void writeScopeSet(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull Set<Scope> scopes) {
		output.writeVarInt(scopes.size(), true);
		for (final Scope scope : scopes) {
			kryo.writeObject(output, scope);
		}
	}

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
