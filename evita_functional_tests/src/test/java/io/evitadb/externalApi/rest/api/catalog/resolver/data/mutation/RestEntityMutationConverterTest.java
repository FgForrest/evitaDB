/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.rest.api.catalog.resolver.data.mutation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.entity.RemoveHierarchicalPlacementMutation;
import io.evitadb.api.requestResponse.data.mutation.entity.SetHierarchicalPlacementMutation;
import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.LocalMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.RemoveAssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.UpsertAssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ApplyDeltaAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ReferenceAttributeMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.RemoveAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.UpsertAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.entity.SetHierarchicalPlacementMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.RemovePriceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.SetPriceInnerRecordHandlingMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.UpsertPriceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.InsertReferenceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.ReferenceAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.RemoveReferenceGroupMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.RemoveReferenceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.SetReferenceGroupMutationDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.mutation.RestEntityUpsertMutationConverter;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import static io.evitadb.test.builder.JsonArrayBuilder.jsonArray;
import static io.evitadb.test.builder.JsonObjectBuilder.jsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RestEntityMutationConverterTest}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class RestEntityMutationConverterTest {

	private static final String ASSOCIATED_DATA_LABELS = "labels";
	private static final String ASSOCIATED_DATA_FILES = "files";
	private static final String ATTRIBUTE_QUANTITY = "quantity";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String PRICE_LIST_BASIC = "basic";
	private static final String REFERENCE_TAGS = "tags";

	private RestEntityUpsertMutationConverter converter;

	@BeforeEach
	void init() {
		final EntitySchemaContract entitySchema = new InternalEntitySchemaBuilder(
			CatalogSchema._internalBuild(TestConstants.TEST_CATALOG, Map.of(), entityType -> null),
			EntitySchema._internalBuild(Entities.PRODUCT)
		)
			.withAssociatedData(ASSOCIATED_DATA_LABELS, Dummy.class)
			.withAssociatedData(ASSOCIATED_DATA_FILES, Dummy.class)
			.withAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class)
			.withAttribute(ATTRIBUTE_CODE, String.class)
			.withPrice()
			.withReferenceTo(REFERENCE_TAGS, "tag", Cardinality.ZERO_OR_MORE)
			.toInstance();
		converter = new RestEntityUpsertMutationConverter(new ObjectMapper(), entitySchema);
	}

	@Test
	void shouldCorrectlyResolveIndividualLocalMutations() {
		final ArrayNode inputLocalMutations = jsonArray(
			jsonObject()
				.e(LocalMutationAggregateDescriptor.REMOVE_ASSOCIATED_DATA_MUTATION.name(), jsonObject()
					.e(RemoveAssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_LABELS))
				.build(),
			jsonObject()
				.e(LocalMutationAggregateDescriptor.UPSERT_ASSOCIATED_DATA_MUTATION.name(), jsonObject()
					.e(UpsertAssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_LABELS)
					.e(UpsertAssociatedDataMutationDescriptor.VALUE.name(), jsonObject()
						.e("s", "String")))
				.build(),
			jsonObject()
				.e(LocalMutationAggregateDescriptor.APPLY_DELTA_ATTRIBUTE_MUTATION.name(), jsonObject()
					.e(ApplyDeltaAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_QUANTITY)
					.e(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), "0.5"))
				.build(),
			jsonObject()
				.e(LocalMutationAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.name(), jsonObject()
					.e(RemoveAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE))
				.build(),
			jsonObject()
				.e(LocalMutationAggregateDescriptor.UPSERT_ATTRIBUTE_MUTATION.name(), jsonObject()
					.e(UpsertAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE)
					.e(UpsertAttributeMutationDescriptor.VALUE.name(), "phone"))
				.build(),
			jsonObject()
				.e(LocalMutationAggregateDescriptor.REMOVE_HIERARCHICAL_PLACEMENT_MUTATION.name(), true)
				.build(),
			jsonObject()
				.e(LocalMutationAggregateDescriptor.SET_HIERARCHICAL_PLACEMENT_MUTATION.name(), jsonObject()
					.e(SetHierarchicalPlacementMutationDescriptor.ORDER_AMONG_SIBLINGS.name(), 10))
				.build(),
			jsonObject()
				.e(LocalMutationAggregateDescriptor.SET_PRICE_INNER_RECORD_HANDLING_MUTATION.name(), jsonObject()
					.e(SetPriceInnerRecordHandlingMutationDescriptor.PRICE_INNER_RECORD_HANDLING.name(), "SUM"))
				.build(),
			jsonObject()
				.e(LocalMutationAggregateDescriptor.REMOVE_PRICE_MUTATION.name(), jsonObject()
					.e(RemovePriceMutationDescriptor.PRICE_ID.name(), 1)
					.e(RemovePriceMutationDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
					.e(RemovePriceMutationDescriptor.CURRENCY.name(), "CZK"))
				.build(),
			jsonObject()
				.e(LocalMutationAggregateDescriptor.UPSERT_PRICE_MUTATION.name(), jsonObject()
					.e(UpsertPriceMutationDescriptor.PRICE_ID.name(), 1)
					.e(UpsertPriceMutationDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
					.e(UpsertPriceMutationDescriptor.CURRENCY.name(), "CZK")
					.e(UpsertPriceMutationDescriptor.PRICE_WITHOUT_TAX.name(), "10")
					.e(UpsertPriceMutationDescriptor.TAX_RATE.name(), "10")
					.e(UpsertPriceMutationDescriptor.PRICE_WITH_TAX.name(), "11")
					.e(UpsertPriceMutationDescriptor.SELLABLE.name(), false))
				.build(),
			jsonObject()
				.e(LocalMutationAggregateDescriptor.INSERT_REFERENCE_MUTATION.name(), jsonObject()
					.e(InsertReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(InsertReferenceMutationDescriptor.PRIMARY_KEY.name(), 1)
					.e(InsertReferenceMutationDescriptor.CARDINALITY.name(), "ONE_OR_MORE")
					.e(InsertReferenceMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "Tag"))
				.build(),
			jsonObject()
				.e(LocalMutationAggregateDescriptor.REMOVE_REFERENCE_MUTATION.name(), jsonObject()
					.e(RemoveReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(RemoveReferenceMutationDescriptor.PRIMARY_KEY.name(), 1))
				.build(),
			jsonObject()
				.e(LocalMutationAggregateDescriptor.SET_REFERENCE_GROUP_MUTATION.name(), jsonObject()
					.e(SetReferenceGroupMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(SetReferenceGroupMutationDescriptor.PRIMARY_KEY.name(), 1)
					.e(SetReferenceGroupMutationDescriptor.GROUP_PRIMARY_KEY.name(), 2))
				.build(),
			jsonObject()
				.e(LocalMutationAggregateDescriptor.REMOVE_REFERENCE_GROUP_MUTATION.name(), jsonObject()
					.e(RemoveReferenceGroupMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(RemoveReferenceGroupMutationDescriptor.PRIMARY_KEY.name(), 1))
				.build(),
			jsonObject()
				.e(LocalMutationAggregateDescriptor.REFERENCE_ATTRIBUTE_MUTATION.name(), jsonObject()
					.e(ReferenceAttributeMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(ReferenceAttributeMutationDescriptor.PRIMARY_KEY.name(), 1)
					.e(ReferenceAttributeMutationDescriptor.ATTRIBUTE_MUTATION.name(), jsonObject()
						.e(ReferenceAttributeMutationAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.name(), jsonObject()
							.e(RemoveAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE))))
				.build()
		);


		final EntityMutation entityMutation = converter.convert(1, EntityExistence.MAY_EXIST, inputLocalMutations);
		assertEquals(Entities.PRODUCT, entityMutation.getEntityType());
		assertEquals(1, entityMutation.getEntityPrimaryKey());

		final Collection<? extends LocalMutation<?, ?>> localMutations = entityMutation.getLocalMutations();

		assertEquals(15, localMutations.size());
		final Iterator<? extends LocalMutation<?, ?>> localMutationsIterator = localMutations.iterator();
		assertTrue(localMutationsIterator.next() instanceof RemoveAssociatedDataMutation);
		assertTrue(localMutationsIterator.next() instanceof UpsertAssociatedDataMutation);
		assertTrue(localMutationsIterator.next() instanceof ApplyDeltaAttributeMutation);
		assertTrue(localMutationsIterator.next() instanceof RemoveAttributeMutation);
		assertTrue(localMutationsIterator.next() instanceof UpsertAttributeMutation);
		assertTrue(localMutationsIterator.next() instanceof RemoveHierarchicalPlacementMutation);
		assertTrue(localMutationsIterator.next() instanceof SetHierarchicalPlacementMutation);
		assertTrue(localMutationsIterator.next() instanceof SetPriceInnerRecordHandlingMutation);
		assertTrue(localMutationsIterator.next() instanceof RemovePriceMutation);
		assertTrue(localMutationsIterator.next() instanceof UpsertPriceMutation);
		assertTrue(localMutationsIterator.next() instanceof InsertReferenceMutation);
		assertTrue(localMutationsIterator.next() instanceof RemoveReferenceMutation);
		assertTrue(localMutationsIterator.next() instanceof SetReferenceGroupMutation);
		assertTrue(localMutationsIterator.next() instanceof RemoveReferenceGroupMutation);
		assertTrue(localMutationsIterator.next() instanceof ReferenceAttributeMutation);
	}

	@Test
	void shouldCorrectlyResolveIndividualLocalMutationsInOneGroup() {
		final ArrayNode inputLocalMutations = jsonArray(
			jsonObject()
				.e(LocalMutationAggregateDescriptor.REMOVE_ASSOCIATED_DATA_MUTATION.name(), jsonObject()
					.e(RemoveAssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_LABELS))
				.e(LocalMutationAggregateDescriptor.UPSERT_ASSOCIATED_DATA_MUTATION.name(), jsonObject()
					.e(UpsertAssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_LABELS)
					.e(UpsertAssociatedDataMutationDescriptor.VALUE.name(), jsonObject()
						.e("s", "String")))
				.e(LocalMutationAggregateDescriptor.APPLY_DELTA_ATTRIBUTE_MUTATION.name(), jsonObject()
					.e(ApplyDeltaAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_QUANTITY)
					.e(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), "0.5"))
				.e(LocalMutationAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.name(), jsonObject()
					.e(RemoveAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE))
				.e(LocalMutationAggregateDescriptor.UPSERT_ATTRIBUTE_MUTATION.name(), jsonObject()
					.e(UpsertAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE)
					.e(UpsertAttributeMutationDescriptor.VALUE.name(), "phone"))
				.e(LocalMutationAggregateDescriptor.REMOVE_HIERARCHICAL_PLACEMENT_MUTATION.name(), true)
				.e(LocalMutationAggregateDescriptor.SET_HIERARCHICAL_PLACEMENT_MUTATION.name(), jsonObject()
					.e(SetHierarchicalPlacementMutationDescriptor.ORDER_AMONG_SIBLINGS.name(), 10))
				.e(LocalMutationAggregateDescriptor.SET_PRICE_INNER_RECORD_HANDLING_MUTATION.name(), jsonObject()
					.e(SetPriceInnerRecordHandlingMutationDescriptor.PRICE_INNER_RECORD_HANDLING.name(), "SUM"))
				.e(LocalMutationAggregateDescriptor.REMOVE_PRICE_MUTATION.name(), jsonObject()
					.e(RemovePriceMutationDescriptor.PRICE_ID.name(), 1)
					.e(RemovePriceMutationDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
					.e(RemovePriceMutationDescriptor.CURRENCY.name(), "CZK"))
				.e(LocalMutationAggregateDescriptor.UPSERT_PRICE_MUTATION.name(), jsonObject()
					.e(UpsertPriceMutationDescriptor.PRICE_ID.name(), 1)
					.e(UpsertPriceMutationDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
					.e(UpsertPriceMutationDescriptor.CURRENCY.name(), "CZK")
					.e(UpsertPriceMutationDescriptor.PRICE_WITHOUT_TAX.name(), "10")
					.e(UpsertPriceMutationDescriptor.TAX_RATE.name(), "10")
					.e(UpsertPriceMutationDescriptor.PRICE_WITH_TAX.name(), "11")
					.e(UpsertPriceMutationDescriptor.SELLABLE.name(), false))
				.e(LocalMutationAggregateDescriptor.INSERT_REFERENCE_MUTATION.name(), jsonObject()
					.e(InsertReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(InsertReferenceMutationDescriptor.PRIMARY_KEY.name(), 1)
					.e(InsertReferenceMutationDescriptor.CARDINALITY.name(), "ONE_OR_MORE")
					.e(InsertReferenceMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "Tag"))
				.e(LocalMutationAggregateDescriptor.REMOVE_REFERENCE_MUTATION.name(), jsonObject()
					.e(RemoveReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(RemoveReferenceMutationDescriptor.PRIMARY_KEY.name(), 1))
				.e(LocalMutationAggregateDescriptor.SET_REFERENCE_GROUP_MUTATION.name(), jsonObject()
					.e(SetReferenceGroupMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(SetReferenceGroupMutationDescriptor.PRIMARY_KEY.name(), 1)
					.e(SetReferenceGroupMutationDescriptor.GROUP_PRIMARY_KEY.name(), 2))
				.e(LocalMutationAggregateDescriptor.REMOVE_REFERENCE_GROUP_MUTATION.name(), jsonObject()
					.e(RemoveReferenceGroupMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(RemoveReferenceGroupMutationDescriptor.PRIMARY_KEY.name(), 1))
				.e(LocalMutationAggregateDescriptor.REFERENCE_ATTRIBUTE_MUTATION.name(), jsonObject()
					.e(ReferenceAttributeMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(ReferenceAttributeMutationDescriptor.PRIMARY_KEY.name(), 1)
					.e(ReferenceAttributeMutationDescriptor.ATTRIBUTE_MUTATION.name(), jsonObject()
						.e(ReferenceAttributeMutationAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.name(), jsonObject()
							.e(RemoveAttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE))))
				.build()
		);

		final EntityMutation entityMutation = converter.convert(1, EntityExistence.MAY_EXIST, inputLocalMutations);
		assertEquals(Entities.PRODUCT, entityMutation.getEntityType());
		assertEquals(1, entityMutation.getEntityPrimaryKey());

		final Collection<? extends LocalMutation<?, ?>> localMutations = entityMutation.getLocalMutations();
		assertEquals(15, localMutations.size());

		final Iterator<? extends LocalMutation<?, ?>> localMutationsIterator = localMutations.iterator();
		assertTrue(localMutationsIterator.next() instanceof RemoveAssociatedDataMutation);
		assertTrue(localMutationsIterator.next() instanceof UpsertAssociatedDataMutation);
		assertTrue(localMutationsIterator.next() instanceof ApplyDeltaAttributeMutation);
		assertTrue(localMutationsIterator.next() instanceof RemoveAttributeMutation);
		assertTrue(localMutationsIterator.next() instanceof UpsertAttributeMutation);
		assertTrue(localMutationsIterator.next() instanceof RemoveHierarchicalPlacementMutation);
		assertTrue(localMutationsIterator.next() instanceof SetHierarchicalPlacementMutation);
		assertTrue(localMutationsIterator.next() instanceof SetPriceInnerRecordHandlingMutation);
		assertTrue(localMutationsIterator.next() instanceof RemovePriceMutation);
		assertTrue(localMutationsIterator.next() instanceof UpsertPriceMutation);
		assertTrue(localMutationsIterator.next() instanceof InsertReferenceMutation);
		assertTrue(localMutationsIterator.next() instanceof RemoveReferenceMutation);
		assertTrue(localMutationsIterator.next() instanceof SetReferenceGroupMutation);
		assertTrue(localMutationsIterator.next() instanceof RemoveReferenceGroupMutation);
		assertTrue(localMutationsIterator.next() instanceof ReferenceAttributeMutation);
	}

	@Test
	void shouldCorrectlyResolveMultipleLocalMutationsOfSameType() {
		final ArrayNode inputLocalMutations = jsonArray(
			jsonObject()
				.e(LocalMutationAggregateDescriptor.REMOVE_ASSOCIATED_DATA_MUTATION.name(), jsonObject()
					.e(RemoveAssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_LABELS))
				.build(),
			jsonObject()
				.e(LocalMutationAggregateDescriptor.REMOVE_ASSOCIATED_DATA_MUTATION.name(), jsonObject()
					.e(RemoveAssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_FILES))
				.build()
		);

		final EntityMutation entityMutation = converter.convert(1, EntityExistence.MAY_EXIST, inputLocalMutations);
		assertEquals(Entities.PRODUCT, entityMutation.getEntityType());
		assertEquals(1, entityMutation.getEntityPrimaryKey());
		final Collection<? extends LocalMutation<?, ?>> localMutations = entityMutation.getLocalMutations();
		assertEquals(2, localMutations.size());
		final Iterator<? extends LocalMutation<?, ?>> localMutationsIterator = localMutations.iterator();
		assertTrue(localMutationsIterator.next() instanceof RemoveAssociatedDataMutation);
		assertTrue(localMutationsIterator.next() instanceof RemoveAssociatedDataMutation);
	}

	@Data
	private static class Dummy implements Serializable {

		@Serial private static final long serialVersionUID = 4926339123678740470L;

		private String s;
	}
}