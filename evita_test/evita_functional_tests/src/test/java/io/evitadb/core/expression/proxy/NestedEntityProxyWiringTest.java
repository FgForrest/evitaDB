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

package io.evitadb.core.expression.proxy;

import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.expression.proxy.ExpressionProxyInstantiator.InstantiationResult;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.spi.store.catalog.persistence.accessor.EntityStoragePartAccessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AssociatedDataStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.PricesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.EXPRESSION;
import static io.evitadb.test.TestTags.PROXY;

/**
 * Tests for nested entity proxy wiring in {@link ExpressionProxyInstantiator}. Verifies that
 * `$reference.referencedEntity.*` and `$reference.groupEntity?.*` expression paths result in nested
 * entity proxies being wired into the reference proxy's {@link ReferenceProxyState}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Nested entity proxy wiring")
@Tag(ENGINE)
@Tag(EXPRESSION)
@Tag(PROXY)
class NestedEntityProxyWiringTest {

	private static final int ENTITY_PK = 42;
	private static final int REFERENCED_ENTITY_PK = 100;
	private static final int GROUP_ENTITY_PK = 7;
	private static final String ENTITY_TYPE = "Product";
	private static final String REFERENCED_ENTITY_TYPE = "Brand";
	private static final String GROUP_ENTITY_TYPE = "ParameterGroup";
	private static final String REFERENCE_NAME = "brand";

	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		"testCatalog",
		NamingConvention.generate("testCatalog"),
		EnumSet.allOf(CatalogEvolutionMode.class),
		EmptyEntitySchemaAccessor.INSTANCE
	);

	/**
	 * Builds entity schema with a reference to the given referenced entity type and optional group type.
	 *
	 * @param entityType           the entity type name
	 * @param referencedEntityType the referenced entity type, or `null` if no reference needed
	 * @param groupType            the group entity type, or `null` if no group
	 * @return entity schema
	 */
	@Nonnull
	private static EntitySchemaContract buildSchema(
		@Nonnull String entityType,
		@Nullable String referencedEntityType,
		@Nullable String groupType
	) {
		final InternalEntitySchemaBuilder builder = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, EntitySchema._internalBuild(entityType)
		);
		if (referencedEntityType != null) {
			if (groupType != null) {
				builder.withReferenceToEntity(
					REFERENCE_NAME, referencedEntityType, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs.withGroupTypeRelatedToEntity(groupType)
				);
			} else {
				builder.withReferenceTo(REFERENCE_NAME, referencedEntityType, Cardinality.ZERO_OR_MORE);
			}
		}
		return builder.toInstance();
	}

	/**
	 * Builds entity schema with a single filterable string attribute.
	 *
	 * @param entityType    the entity type name
	 * @param attributeName the attribute name
	 * @return entity schema
	 */
	@Nonnull
	private static EntitySchemaContract buildSchemaWithAttribute(
		@Nonnull String entityType,
		@Nonnull String attributeName
	) {
		return new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, EntitySchema._internalBuild(entityType)
		)
			.withAttribute(attributeName, String.class)
			.toInstance();
	}

	/**
	 * Creates an {@link AttributesStoragePart} with the given attribute values.
	 *
	 * @param entityPk the entity primary key
	 * @param values   attribute values
	 * @return attributes storage part
	 */
	@Nonnull
	private static AttributesStoragePart createAttributesPart(
		int entityPk,
		@Nonnull AttributeValue... values
	) {
		final AttributesStoragePart part = new AttributesStoragePart(entityPk);
		for (final AttributeValue value : values) {
			part.upsertAttribute(
				value.key(),
				AttributeSchema._internalBuild(value.key().attributeName(), String.class, false),
				existing -> value
			);
		}
		return part;
	}

	/**
	 * Creates a schema resolver function that maps entity type names to their schemas.
	 *
	 * @param schemas map of entity type name to schema
	 * @return schema resolver function
	 */
	@Nonnull
	private static Function<String, EntitySchemaContract> schemaResolver(
		@Nonnull Map<String, EntitySchemaContract> schemas
	) {
		return name -> {
			final EntitySchemaContract schema = schemas.get(name);
			if (schema == null) {
				throw new IllegalStateException("No schema for entity type: " + name);
			}
			return schema;
		};
	}

	/**
	 * Builds an {@link ExpressionProxyDescriptor} for the given expression string.
	 *
	 * @param expression the expression string to parse and analyze
	 * @return the proxy descriptor
	 */
	@Nonnull
	private static ExpressionProxyDescriptor buildDescriptor(@Nonnull String expression) {
		return ExpressionProxyFactory.buildDescriptor(
			io.evitadb.api.query.expression.ExpressionFactory.parse(expression)
		);
	}

	@Test
	@DisplayName("Should wire referenced entity proxy into reference proxy")
	void shouldWireReferencedEntityProxyIntoReferenceProxy() {
		final EntitySchemaContract ownerSchema = buildSchema(ENTITY_TYPE, REFERENCED_ENTITY_TYPE, null);
		final EntitySchemaContract brandSchema = buildSchemaWithAttribute(REFERENCED_ENTITY_TYPE, "name");

		final TestStoragePartAccessor accessor = new TestStoragePartAccessor();

		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(
			ownerSchema, ownerSchema.getReferenceOrThrowException(REFERENCE_NAME), refKey, null
		);
		accessor.registerReferences(ENTITY_TYPE, ENTITY_PK,
			new ReferencesStoragePart(ENTITY_PK, 1, new Reference[]{ref}, -1));
		accessor.registerBody(REFERENCED_ENTITY_TYPE, REFERENCED_ENTITY_PK,
			new EntityBodyStoragePart(REFERENCED_ENTITY_PK));
		accessor.registerGlobalAttributes(REFERENCED_ENTITY_TYPE, REFERENCED_ENTITY_PK,
			createAttributesPart(REFERENCED_ENTITY_PK, new AttributeValue(new AttributeKey("name"), "Nike")));

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].referencedEntity.attributes['name'] == 'Nike'"
		);

		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, ownerSchema, ENTITY_PK,
			ownerSchema.getReferenceOrThrowException(REFERENCE_NAME), refKey, accessor,
			schemaResolver(Map.of(REFERENCED_ENTITY_TYPE, brandSchema)), Scope.LIVE
		);

		assertNotNull(result.referenceProxy(), "Reference proxy should not be null");
		final Optional<SealedEntity> referencedEntity = result.referenceProxy().getReferencedEntity();
		assertTrue(referencedEntity.isPresent(), "Referenced entity should be wired");
		assertEquals("Nike", referencedEntity.get().getAttribute("name"),
			"Nested entity proxy should return correct attribute value");
	}

	@Test
	@DisplayName("Should fetch referenced entity storage parts using referenced entity type and PK")
	void shouldFetchReferencedEntityStoragePartsIndependently() {
		final EntitySchemaContract ownerSchema = buildSchema(ENTITY_TYPE, REFERENCED_ENTITY_TYPE, null);
		final EntitySchemaContract brandSchema = buildSchemaWithAttribute(REFERENCED_ENTITY_TYPE, "name");

		final TestStoragePartAccessor accessor = new TestStoragePartAccessor();

		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(
			ownerSchema, ownerSchema.getReferenceOrThrowException(REFERENCE_NAME), refKey, null
		);
		accessor.registerReferences(ENTITY_TYPE, ENTITY_PK,
			new ReferencesStoragePart(ENTITY_PK, 1, new Reference[]{ref}, -1));
		accessor.registerBody(REFERENCED_ENTITY_TYPE, REFERENCED_ENTITY_PK,
			new EntityBodyStoragePart(REFERENCED_ENTITY_PK));
		accessor.registerGlobalAttributes(REFERENCED_ENTITY_TYPE, REFERENCED_ENTITY_PK,
			createAttributesPart(REFERENCED_ENTITY_PK, new AttributeValue(new AttributeKey("name"), "Nike")));

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].referencedEntity.attributes['name'] == 'Nike'"
		);

		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, ownerSchema, ENTITY_PK,
			ownerSchema.getReferenceOrThrowException(REFERENCE_NAME), refKey, accessor,
			schemaResolver(Map.of(REFERENCED_ENTITY_TYPE, brandSchema)), Scope.LIVE
		);

		// implicitly verified: the proxy reads from the REFERENCED entity type storage —
		// if it read from the owner's storage, the attribute value would not be found
		assertNotNull(result.referenceProxy());
		final Optional<SealedEntity> referencedEntity = result.referenceProxy().getReferencedEntity();
		assertTrue(referencedEntity.isPresent());
		assertEquals("Nike", referencedEntity.get().getAttribute("name"));
	}

	@Test
	@DisplayName("Should compute independent partial set for nested referenced entity")
	void shouldComputeIndependentPartialSetForReferencedEntity() {
		// expression only accesses primaryKey on the referenced entity — no attributes needed
		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].referencedEntity.primaryKey > 0"
		);

		assertTrue(descriptor.needsReferencedEntityProxy());
		assertNotNull(descriptor.referencedEntityPartials());
		assertNotNull(descriptor.referencedEntityRecipe());
		// the nested recipe should need body (for primaryKey) but NOT global attributes
		assertTrue(descriptor.referencedEntityRecipe().needsEntityBody(),
			"Nested recipe should need entity body for primaryKey access");
		assertFalse(descriptor.referencedEntityRecipe().needsGlobalAttributes(),
			"Nested recipe should NOT need attributes when only primaryKey is accessed");
	}

	@Test
	@DisplayName("Should wire group entity proxy into reference proxy")
	void shouldWireGroupEntityProxyIntoReferenceProxy() {
		final EntitySchemaContract ownerSchema = buildSchema(ENTITY_TYPE, REFERENCED_ENTITY_TYPE, GROUP_ENTITY_TYPE);
		final EntitySchemaContract groupSchema = buildSchemaWithAttribute(GROUP_ENTITY_TYPE, "type");

		final TestStoragePartAccessor accessor = new TestStoragePartAccessor();

		final GroupEntityReference groupRef = new GroupEntityReference(GROUP_ENTITY_TYPE, GROUP_ENTITY_PK);
		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(
			ownerSchema, ownerSchema.getReferenceOrThrowException(REFERENCE_NAME), refKey, groupRef
		);
		accessor.registerReferences(ENTITY_TYPE, ENTITY_PK,
			new ReferencesStoragePart(ENTITY_PK, 1, new Reference[]{ref}, -1));
		accessor.registerBody(GROUP_ENTITY_TYPE, GROUP_ENTITY_PK,
			new EntityBodyStoragePart(GROUP_ENTITY_PK));
		accessor.registerGlobalAttributes(GROUP_ENTITY_TYPE, GROUP_ENTITY_PK,
			createAttributesPart(GROUP_ENTITY_PK, new AttributeValue(new AttributeKey("type"), "CHECKBOX")));

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].groupEntity.attributes['type'] == 'CHECKBOX'"
		);

		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, ownerSchema, ENTITY_PK,
			ownerSchema.getReferenceOrThrowException(REFERENCE_NAME), refKey, accessor,
			schemaResolver(Map.of(GROUP_ENTITY_TYPE, groupSchema)), Scope.LIVE
		);

		assertNotNull(result.referenceProxy());
		final Optional<SealedEntity> groupEntity = result.referenceProxy().getGroupEntity();
		assertTrue(groupEntity.isPresent(), "Group entity should be wired");
		assertEquals("CHECKBOX", groupEntity.get().getAttribute("type"),
			"Nested group entity proxy should return correct attribute value");
	}

	@Test
	@DisplayName("Should use group entity type and PK for group entity proxy storage fetching")
	void shouldUseGroupEntityTypeAndPkForGroupProxy() {
		final EntitySchemaContract ownerSchema = buildSchema(ENTITY_TYPE, REFERENCED_ENTITY_TYPE, GROUP_ENTITY_TYPE);
		final EntitySchemaContract groupSchema = buildSchema(GROUP_ENTITY_TYPE, null, null);

		final TestStoragePartAccessor accessor = new TestStoragePartAccessor();

		final GroupEntityReference groupRef = new GroupEntityReference(GROUP_ENTITY_TYPE, GROUP_ENTITY_PK);
		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(
			ownerSchema, ownerSchema.getReferenceOrThrowException(REFERENCE_NAME), refKey, groupRef
		);
		accessor.registerReferences(ENTITY_TYPE, ENTITY_PK,
			new ReferencesStoragePart(ENTITY_PK, 1, new Reference[]{ref}, -1));
		accessor.registerBody(GROUP_ENTITY_TYPE, GROUP_ENTITY_PK,
			new EntityBodyStoragePart(GROUP_ENTITY_PK));

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].groupEntity.primaryKey > 0"
		);

		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, ownerSchema, ENTITY_PK,
			ownerSchema.getReferenceOrThrowException(REFERENCE_NAME), refKey, accessor,
			schemaResolver(Map.of(GROUP_ENTITY_TYPE, groupSchema)), Scope.LIVE
		);

		// implicitly verified: if the proxy used the wrong entity type / PK, the body part
		// would not be found and the group entity proxy would be null
		assertNotNull(result.referenceProxy());
		final Optional<SealedEntity> groupEntity = result.referenceProxy().getGroupEntity();
		assertTrue(groupEntity.isPresent(), "Group entity should be wired when body is registered");
		assertEquals(GROUP_ENTITY_PK, groupEntity.get().getPrimaryKey(),
			"Group entity proxy should return correct PK");
	}

	@Test
	@DisplayName("Should return empty group entity when reference has no group")
	void shouldReturnEmptyGroupEntityWhenReferenceHasNoGroup() {
		final EntitySchemaContract ownerSchema = buildSchema(ENTITY_TYPE, REFERENCED_ENTITY_TYPE, GROUP_ENTITY_TYPE);
		final EntitySchemaContract groupSchema = buildSchema(GROUP_ENTITY_TYPE, null, null);

		final TestStoragePartAccessor accessor = new TestStoragePartAccessor();

		// reference WITHOUT a group
		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(
			ownerSchema, ownerSchema.getReferenceOrThrowException(REFERENCE_NAME), refKey, null
		);
		accessor.registerReferences(ENTITY_TYPE, ENTITY_PK,
			new ReferencesStoragePart(ENTITY_PK, 1, new Reference[]{ref}, -1));

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].groupEntity.primaryKey > 0"
		);

		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, ownerSchema, ENTITY_PK,
			ownerSchema.getReferenceOrThrowException(REFERENCE_NAME), refKey, accessor,
			schemaResolver(Map.of(GROUP_ENTITY_TYPE, groupSchema)), Scope.LIVE
		);

		assertNotNull(result.referenceProxy());
		final Optional<SealedEntity> groupEntity = result.referenceProxy().getGroupEntity();
		assertTrue(groupEntity.isEmpty(), "Group entity should be empty when reference has no group");
	}

	@Test
	@DisplayName("Should implement SealedEntity interface on nested proxy")
	void shouldImplementSealedEntityInterface() {
		final EntitySchemaContract ownerSchema = buildSchema(ENTITY_TYPE, REFERENCED_ENTITY_TYPE, null);
		final EntitySchemaContract brandSchema = buildSchema(REFERENCED_ENTITY_TYPE, null, null);

		final TestStoragePartAccessor accessor = new TestStoragePartAccessor();

		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(
			ownerSchema, ownerSchema.getReferenceOrThrowException(REFERENCE_NAME), refKey, null
		);
		accessor.registerReferences(ENTITY_TYPE, ENTITY_PK,
			new ReferencesStoragePart(ENTITY_PK, 1, new Reference[]{ref}, -1));
		accessor.registerBody(REFERENCED_ENTITY_TYPE, REFERENCED_ENTITY_PK,
			new EntityBodyStoragePart(REFERENCED_ENTITY_PK));

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"true || $entity.references['brand'].*[$.referencedEntity]"
		);

		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, ownerSchema, ENTITY_PK,
			ownerSchema.getReferenceOrThrowException(REFERENCE_NAME), refKey, accessor,
			schemaResolver(Map.of(REFERENCED_ENTITY_TYPE, brandSchema)), Scope.LIVE
		);

		assertNotNull(result.referenceProxy());
		final Optional<SealedEntity> referencedEntity = result.referenceProxy().getReferencedEntity();
		assertTrue(referencedEntity.isPresent());
		assertInstanceOf(SealedEntity.class, referencedEntity.get(),
			"Nested entity proxy must implement SealedEntity");
	}

	@Test
	@DisplayName("Should throw ExpressionEvaluationException for SealedEntity-only methods via CatchAll")
	void shouldThrowForSealedEntityMethodsViaCatchAll() {
		final EntitySchemaContract ownerSchema = buildSchema(ENTITY_TYPE, REFERENCED_ENTITY_TYPE, null);
		final EntitySchemaContract brandSchema = buildSchema(REFERENCED_ENTITY_TYPE, null, null);

		final TestStoragePartAccessor accessor = new TestStoragePartAccessor();

		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(
			ownerSchema, ownerSchema.getReferenceOrThrowException(REFERENCE_NAME), refKey, null
		);
		accessor.registerReferences(ENTITY_TYPE, ENTITY_PK,
			new ReferencesStoragePart(ENTITY_PK, 1, new Reference[]{ref}, -1));
		accessor.registerBody(REFERENCED_ENTITY_TYPE, REFERENCED_ENTITY_PK,
			new EntityBodyStoragePart(REFERENCED_ENTITY_PK));

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"true || $entity.references['brand'].*[$.referencedEntity]"
		);

		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, ownerSchema, ENTITY_PK,
			ownerSchema.getReferenceOrThrowException(REFERENCE_NAME), refKey, accessor,
			schemaResolver(Map.of(REFERENCED_ENTITY_TYPE, brandSchema)), Scope.LIVE
		);

		final SealedEntity nestedProxy = result.referenceProxy().getReferencedEntity().orElseThrow();
		// methods not backed by partials should throw ExpressionEvaluationException via CatchAll
		assertThrows(ExpressionEvaluationException.class, nestedProxy::getAllLocales,
			"SealedEntity method not backed by a partial should throw ExpressionEvaluationException");
		assertThrows(ExpressionEvaluationException.class, nestedProxy::getReferences,
			"SealedEntity method not backed by a partial should throw ExpressionEvaluationException");
	}

	/**
	 * Simple map-backed implementation of {@link EntityStoragePartAccessor} for testing.
	 * Routes storage part lookups based on `(entityType, entityPK)` tuples. Returns
	 * empty default parts for unregistered entities.
	 */
	private static final class TestStoragePartAccessor implements EntityStoragePartAccessor {

		private final Map<String, EntityBodyStoragePart> bodies = new HashMap<>(8);
		private final Map<String, AttributesStoragePart> globalAttributes = new HashMap<>(8);
		private final Map<String, ReferencesStoragePart> references = new HashMap<>(8);

		/**
		 * Generates a composite key for the `(entityType, entityPK)` pair.
		 */
		@Nonnull
		private static String key(@Nonnull String entityType, int entityPK) {
			return entityType + "#" + entityPK;
		}

		/**
		 * Registers an entity body storage part.
		 */
		void registerBody(@Nonnull String entityType, int entityPK, @Nonnull EntityBodyStoragePart part) {
			this.bodies.put(key(entityType, entityPK), part);
		}

		/**
		 * Registers global attributes for a specific entity.
		 */
		void registerGlobalAttributes(
			@Nonnull String entityType, int entityPK, @Nonnull AttributesStoragePart part
		) {
			this.globalAttributes.put(key(entityType, entityPK), part);
		}

		/**
		 * Registers references for a specific entity.
		 */
		void registerReferences(@Nonnull String entityType, int entityPK, @Nonnull ReferencesStoragePart part) {
			this.references.put(key(entityType, entityPK), part);
		}

		@Nonnull
		@Override
		public EntityBodyStoragePart getEntityStoragePart(
			@Nonnull String entityType, int entityPrimaryKey, @Nonnull EntityExistence expects
		) {
			final EntityBodyStoragePart part = this.bodies.get(key(entityType, entityPrimaryKey));
			return part != null ? part : new EntityBodyStoragePart(entityPrimaryKey);
		}

		@Nonnull
		@Override
		public AttributesStoragePart getAttributeStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
			final AttributesStoragePart part = this.globalAttributes.get(key(entityType, entityPrimaryKey));
			return part != null ? part : new AttributesStoragePart(entityPrimaryKey);
		}

		@Nonnull
		@Override
		public AttributesStoragePart getAttributeStoragePart(
			@Nonnull String entityType, int entityPrimaryKey, @Nonnull Locale locale
		) {
			return new AttributesStoragePart(entityPrimaryKey, locale);
		}

		@Nonnull
		@Override
		public AssociatedDataStoragePart getAssociatedDataStoragePart(
			@Nonnull String entityType, int entityPrimaryKey, @Nonnull AssociatedDataKey key
		) {
			return new AssociatedDataStoragePart(entityPrimaryKey, key);
		}

		@Nonnull
		@Override
		public ReferencesStoragePart getReferencesStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
			final ReferencesStoragePart part = this.references.get(key(entityType, entityPrimaryKey));
			return part != null ? part : new ReferencesStoragePart(entityPrimaryKey);
		}

		@Nonnull
		@Override
		public PricesStoragePart getPriceStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
			return new PricesStoragePart(entityPrimaryKey);
		}
	}

}
