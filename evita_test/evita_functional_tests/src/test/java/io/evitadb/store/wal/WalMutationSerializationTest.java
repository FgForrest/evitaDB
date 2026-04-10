/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.store.wal;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.RemoveParentMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaTypeMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.RemoveAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaLocalizedMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaNullableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.*;
import io.evitadb.api.requestResponse.schema.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.MakeCatalogAliveMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.AllowCurrencyInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.AllowEvolutionModeInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.AllowLocaleInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.DisallowCurrencyInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.DisallowEvolutionModeInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.DisallowLocaleInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithGeneratedPrimaryKeyMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReflectedReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaBucketedMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * This test verifies serialization and deserialization of all mutation serializers configured in WalKryoConfigurer.
 * It ensures that all mutation objects can be properly serialized and deserialized without data loss.
 *
 * @author Generated Test (generated), FG Forrest a.s. (c) 2024
 */
@DisplayName("WAL mutation serialization test")
public class WalMutationSerializationTest {
	private final Kryo kryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);

	@Test
	@DisplayName("Should serialize and deserialize associated data schema mutations")
	void shouldSerializeAssociatedDataSchemaMutations() {
		assertSerializationRound(new CreateAssociatedDataSchemaMutation("testAssociatedData", "Test description", "Test deprecation", String.class, true, false));
		assertSerializationRound(new ModifyAssociatedDataSchemaDeprecationNoticeMutation("testAssociatedData", "New deprecation notice"));
		assertSerializationRound(new ModifyAssociatedDataSchemaDescriptionMutation("testAssociatedData", "New description"));
		assertSerializationRound(new ModifyAssociatedDataSchemaNameMutation("testAssociatedData", "newAssociatedDataName"));
		assertSerializationRound(new ModifyAssociatedDataSchemaTypeMutation("testAssociatedData", String.class));
		assertSerializationRound(new RemoveAssociatedDataSchemaMutation("testAssociatedData"));
		assertSerializationRound(new SetAssociatedDataSchemaLocalizedMutation("testAssociatedData", true));
		assertSerializationRound(new SetAssociatedDataSchemaNullableMutation("testAssociatedData", true));
	}

	@Test
	@DisplayName("Should serialize and deserialize attribute schema mutations")
	void shouldSerializeAttributeSchemaMutations() {
		assertSerializationRound(new CreateAttributeSchemaMutation("testAttribute", "Test description", "Test deprecation", AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, true, true, true, true, true, String.class, "defaultValue", 2));
		assertSerializationRound(new ModifyAttributeSchemaDefaultValueMutation("testAttribute", "newDefaultValue"));
		assertSerializationRound(new ModifyAttributeSchemaDeprecationNoticeMutation("testAttribute", "New deprecation"));
		assertSerializationRound(new ModifyAttributeSchemaDescriptionMutation("testAttribute", "New description"));
		assertSerializationRound(new ModifyAttributeSchemaNameMutation("testAttribute", "newAttributeName"));
		assertSerializationRound(new RemoveAttributeSchemaMutation("testAttribute"));
		assertSerializationRound(new SetAttributeSchemaFilterableMutation("testAttribute", true));
		assertSerializationRound(new SetAttributeSchemaGloballyUniqueMutation("testAttribute", GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG));
		assertSerializationRound(new SetAttributeSchemaLocalizedMutation("testAttribute", true));
		assertSerializationRound(new SetAttributeSchemaNullableMutation("testAttribute", true));
		assertSerializationRound(new SetAttributeSchemaRepresentativeMutation("testAttribute", true));
		assertSerializationRound(new SetAttributeSchemaUniqueMutation("testAttribute", AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION));
	}

	@Test
	@DisplayName("Should serialize and deserialize catalog schema mutations")
	void shouldSerializeCatalogSchemaMutations() {
		assertSerializationRound(new AllowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES));
		assertSerializationRound(new CreateCatalogSchemaMutation("testCatalog"));
		assertSerializationRound(new CreateEntitySchemaMutation("testEntity"));
		assertSerializationRound(new DisallowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES));
		assertSerializationRound(new ModifyCatalogSchemaDescriptionMutation("New catalog description"));
		assertSerializationRound(new ModifyCatalogSchemaNameMutation("testCatalog", "newCatalogName", true));
		assertSerializationRound(new RemoveCatalogSchemaMutation("testCatalog"));
		assertSerializationRound(new RemoveEntitySchemaMutation("testEntity"));
	}

	@Test
	@DisplayName("Should serialize and deserialize entity schema mutations")
	void shouldSerializeEntitySchemaMutations() {
		assertSerializationRound(new AllowCurrencyInEntitySchemaMutation(Currency.getInstance("USD")));
		assertSerializationRound(new AllowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_ATTRIBUTES));
		assertSerializationRound(new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH));
		assertSerializationRound(new DisallowCurrencyInEntitySchemaMutation(Currency.getInstance("EUR")));
		assertSerializationRound(new DisallowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_ATTRIBUTES));
		assertSerializationRound(new DisallowLocaleInEntitySchemaMutation(Locale.FRENCH));
		assertSerializationRound(new ModifyEntitySchemaDeprecationNoticeMutation("Entity deprecation notice"));
		assertSerializationRound(new ModifyEntitySchemaDescriptionMutation("Entity description"));
		assertSerializationRound(new SetEntitySchemaWithGeneratedPrimaryKeyMutation(true));
	}

	@Test
	@DisplayName("Should serialize and deserialize data mutations")
	void shouldSerializeDataMutations() {
		assertSerializationRound(new RemoveAssociatedDataMutation("associatedData", Locale.ENGLISH));
		assertSerializationRound(new UpsertAssociatedDataMutation("associatedData", "value"));
		assertSerializationRound(new ApplyDeltaAttributeMutation<>("attribute", BigDecimal.TEN));
		assertSerializationRound(new RemoveAttributeMutation("attribute", Locale.ENGLISH));
		assertSerializationRound(new UpsertAttributeMutation("attribute", "value"));
		assertSerializationRound(new RemoveParentMutation());
		assertSerializationRound(new SetParentMutation(123));
		assertSerializationRound(new RemovePriceMutation(1, "priceList", Currency.getInstance("USD")));
		assertSerializationRound(new SetPriceInnerRecordHandlingMutation(PriceInnerRecordHandling.LOWEST_PRICE));
		assertSerializationRound(new EntityRemoveMutation("testEntity", 1));
		assertSerializationRound(new EntityUpsertMutation("testEntity", 1, EntityExistence.MAY_EXIST));
		assertSerializationRound(new SetEntityScopeMutation(Scope.LIVE));
	}

	@Test
	@DisplayName("Should serialize and deserialize transaction mutations")
	void shouldSerializeTransactionMutations() {
		assertSerializationRound(new TransactionMutation(UUID.randomUUID(), 1L, 1, 4_096L, OffsetDateTime.now()));
	}

	@Test
	@DisplayName("Should serialize and deserialize catalog engine mutations")
	void shouldSerializeCatalogEngineMutations() {
		assertSerializationRound(new MakeCatalogAliveMutation("testCatalog"));
		assertSerializationRound(new SetCatalogStateMutation("testCatalog", true));
		assertSerializationRound(new SetCatalogMutabilityMutation("testCatalog", true));
		assertSerializationRound(new DuplicateCatalogMutation("sourceCatalog", "targetCatalog"));
	}

	@Test
	@DisplayName("Should serialize and deserialize reference schema mutations")
	void shouldSerializeAndDeserializeReferenceSchemaMutations() {
		// CreateReferenceSchemaMutation with all bucketed fields populated
		final Expression bucketedValueExpr = ExpressionFactory.parse("$price * $quantity");
		assertSerializationRound(
			new CreateReferenceSchemaMutation(
				"testReference",
				"Test reference description",
				"Deprecated",
				Cardinality.ZERO_OR_MORE,
				"Product",
				true,
				"ProductGroup",
				true,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				new ScopedReferenceIndexedComponents[]{
					new ScopedReferenceIndexedComponents(
						Scope.LIVE, ReferenceIndexedComponents.DEFAULT_INDEXED_COMPONENTS
					)
				},
				new Scope[]{Scope.LIVE},
				new ScopedFacetedPartially[]{
					new ScopedFacetedPartially(Scope.LIVE, ExpressionFactory.parse("1 > 0"))
				},
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(Scope.LIVE, "priceHistogram", bucketedValueExpr)
				},
				new ScopedBucketedPartially[]{
					new ScopedBucketedPartially(Scope.LIVE, ExpressionFactory.parse("$active == 1"))
				}
			)
		);

		// CreateReflectedReferenceSchemaMutation with both bucketed fields populated
		assertSerializationRound(
			new CreateReflectedReferenceSchemaMutation(
				"reflectedRef",
				"Reflected reference description",
				null,
				Cardinality.ONE_OR_MORE,
				"Category",
				"productReference",
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)
				},
				new ScopedReferenceIndexedComponents[]{
					new ScopedReferenceIndexedComponents(
						Scope.LIVE, ReferenceIndexedComponents.DEFAULT_INDEXED_COMPONENTS
					)
				},
				new Scope[]{Scope.LIVE, Scope.ARCHIVED},
				new ScopedFacetedPartially[]{
					new ScopedFacetedPartially(Scope.LIVE, ExpressionFactory.parse("$visible == 1"))
				},
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(
						Scope.LIVE, "categoryHistogram", ExpressionFactory.parse("$score")
					),
					new ScopedHistogramIndexDefinition(Scope.ARCHIVED, "archivedHistogram", null)
				},
				new ScopedBucketedPartially[]{
					new ScopedBucketedPartially(Scope.LIVE, ExpressionFactory.parse("$status > 0"))
				},
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				new String[]{"name", "code"}
			)
		);

		// CreateReflectedReferenceSchemaMutation with null bucketed fields (inherited from source)
		assertSerializationRound(
			new CreateReflectedReferenceSchemaMutation(
				"inheritedRef",
				"Inherited reference",
				null,
				null,
				"Brand",
				"categoryRef",
				null,
				null,
				null,
				null,
				null,
				null,
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{"internalCode"}
			)
		);

		// SetReferenceSchemaFacetedMutation
		assertSerializationRound(
			new SetReferenceSchemaFacetedMutation(
				"testReference",
				new Scope[]{Scope.LIVE},
				new ScopedFacetedPartially[]{
					new ScopedFacetedPartially(Scope.LIVE, ExpressionFactory.parse("$visible == 1"))
				}
			)
		);

		// SetReferenceSchemaBucketedMutation with basic data
		assertSerializationRound(
			new SetReferenceSchemaBucketedMutation(
				"testReference",
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(
						Scope.LIVE, "histogramIndex", ExpressionFactory.parse("$price")
					)
				},
				new ScopedBucketedPartially[]{
					new ScopedBucketedPartially(Scope.LIVE, ExpressionFactory.parse("$active == 1"))
				}
			)
		);
	}

	@Test
	@DisplayName("Should serialize and deserialize SetReferenceSchemaBucketedMutation with various nullability scenarios")
	void shouldSerializeAndDeserializeSetReferenceSchemaBucketedMutationWithNullability() {
		// multi-scope: LIVE with valueExpression, ARCHIVED with null valueExpression
		final SetReferenceSchemaBucketedMutation multiScopeMutation = new SetReferenceSchemaBucketedMutation(
			"multiScopeRef",
			new ScopedHistogramIndexDefinition[]{
				new ScopedHistogramIndexDefinition(
					Scope.LIVE, "liveHistogram", ExpressionFactory.parse("$price * $quantity")
				),
				new ScopedHistogramIndexDefinition(Scope.ARCHIVED, "archivedHistogram", null)
			},
			new ScopedBucketedPartially[]{
				new ScopedBucketedPartially(Scope.LIVE, ExpressionFactory.parse("$active == 1")),
				new ScopedBucketedPartially(Scope.ARCHIVED, null)
			}
		);
		assertSerializationRound(multiScopeMutation);

		// null bucketedInScopes (inherited semantics)
		final SetReferenceSchemaBucketedMutation nullBucketedMutation = new SetReferenceSchemaBucketedMutation(
			"inheritedRef",
			null,
			null
		);
		final SetReferenceSchemaBucketedMutation deserialized3 = deserialize(nullBucketedMutation);
		assertEquals(nullBucketedMutation, deserialized3);
		assertNull(deserialized3.getBucketedInScopes(), "bucketedInScopes should be null after round-trip");

		// empty arrays (non-null but zero-length — "not bucketed")
		final SetReferenceSchemaBucketedMutation emptyArrayMutation = new SetReferenceSchemaBucketedMutation(
			"notBucketedRef",
			ScopedHistogramIndexDefinition.EMPTY,
			ScopedBucketedPartially.EMPTY
		);
		final SetReferenceSchemaBucketedMutation deserialized4 = deserialize(emptyArrayMutation);
		assertEquals(emptyArrayMutation, deserialized4);
		assertArrayEquals(
			ScopedHistogramIndexDefinition.EMPTY, deserialized4.getBucketedInScopes(),
			"bucketedInScopes should be empty array, not null"
		);
		assertArrayEquals(
			ScopedBucketedPartially.EMPTY, deserialized4.getBucketedPartiallyInScopes(),
			"bucketedPartiallyInScopes should be empty array, not null"
		);

		// non-null bucketedInScopes with null bucketedPartiallyInScopes
		final SetReferenceSchemaBucketedMutation nullPartiallyMutation = new SetReferenceSchemaBucketedMutation(
			"partiallyNullRef",
			new ScopedHistogramIndexDefinition[]{
				new ScopedHistogramIndexDefinition(Scope.LIVE, "histogram", null)
			},
			null
		);
		final SetReferenceSchemaBucketedMutation deserialized5 = deserialize(nullPartiallyMutation);
		assertEquals(nullPartiallyMutation, deserialized5);
		assertNull(
			deserialized5.getBucketedPartiallyInScopes(),
			"bucketedPartiallyInScopes should be null after round-trip"
		);
	}

	/**
	 * Performs a serialization round-trip and returns the deserialized mutation instance,
	 * allowing callers to perform additional assertions on the result.
	 *
	 * @param mutation the mutation to serialize and deserialize
	 * @return the deserialized mutation instance
	 */
	@Nonnull
	private <T> T deserialize(@Nonnull T mutation) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.kryo.writeObject(output, mutation);
		}
		try (final Input input = new Input(os.toByteArray())) {
			@SuppressWarnings("unchecked")
			final T deserialized = (T) this.kryo.readObject(input, mutation.getClass());
			return deserialized;
		}
	}

	/**
	 * Performs a serialization round-trip test on the given object.
	 * Serializes the object, then deserializes it and verifies equality.
	 *
	 * @param object the object to test serialization for
	 */
	private void assertSerializationRound(@Nonnull Object object) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.kryo.writeObject(output, object);
		}
		try (final Input input = new Input(os.toByteArray())) {
			final Object deserialized = this.kryo.readObject(input, object.getClass());
			assertEquals(object, deserialized);
		}
	}
}
