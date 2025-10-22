/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.mutation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.RemoveParentMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.LocalMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.AssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.UpsertAssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ApplyDeltaAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.AttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ReferenceAttributeMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.UpsertAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.entity.SetParentMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.PriceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.SetPriceInnerRecordHandlingMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.UpsertPriceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.InsertReferenceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.ReferenceAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.ReferenceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.SetReferenceGroupMutationDescriptor;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GraphQLEntityUpsertMutationFactory}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class GraphQLEntityUpsertMutationFactoryTest {

	private static final String ASSOCIATED_DATA_LABELS = "labels";
	private static final String ASSOCIATED_DATA_FILES = "files";
	private static final String ATTRIBUTE_QUANTITY = "quantity";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String PRICE_LIST_BASIC = "basic";
	private static final String REFERENCE_TAGS = "tags";

	private GraphQLEntityUpsertMutationFactory converter;

	@BeforeEach
	void init() {
		final EntitySchemaContract entitySchema = new InternalEntitySchemaBuilder(
			CatalogSchema._internalBuild(TestConstants.TEST_CATALOG, Map.of(), EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE),
			EntitySchema._internalBuild(Entities.PRODUCT)
		)
			.withAssociatedData(ASSOCIATED_DATA_LABELS, Dummy.class)
			.withAssociatedData(ASSOCIATED_DATA_FILES, Dummy.class)
			.withAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class)
			.withAttribute(ATTRIBUTE_CODE, String.class)
			.withPrice()
			.withReferenceTo(REFERENCE_TAGS, "tag", Cardinality.ZERO_OR_MORE)
			.toInstance();
		this.converter = new GraphQLEntityUpsertMutationFactory(new ObjectMapper(), entitySchema);
	}

	@Test
	void shouldCorrectlyResolveIndividualLocalMutations() {
		final List<Map<String, Object>> inputLocalMutations = List.of(
			map()
				.e(
					LocalMutationInputAggregateDescriptor.REMOVE_ASSOCIATED_DATA_MUTATION.name(), map()
					.e(AssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_LABELS))
				.build(),
			map()
				.e(
					LocalMutationInputAggregateDescriptor.UPSERT_ASSOCIATED_DATA_MUTATION.name(), map()
					.e(AssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_LABELS)
					.e(UpsertAssociatedDataMutationDescriptor.VALUE.name(), map()
						.e("s", "String")))
				.build(),
			map()
				.e(
					LocalMutationInputAggregateDescriptor.APPLY_DELTA_ATTRIBUTE_MUTATION.name(), map()
					.e(AttributeMutationDescriptor.NAME.name(), ATTRIBUTE_QUANTITY)
					.e(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), "0.5"))
				.build(),
			map()
				.e(
					LocalMutationInputAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.name(), map()
					.e(AttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE))
				.build(),
			map()
				.e(
					LocalMutationInputAggregateDescriptor.UPSERT_ATTRIBUTE_MUTATION.name(), map()
					.e(AttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE)
					.e(UpsertAttributeMutationDescriptor.VALUE.name(), "phone"))
				.build(),
			map()
				.e(LocalMutationInputAggregateDescriptor.REMOVE_PARENT_MUTATION.name(), true)
				.build(),
			map()
				.e(
					LocalMutationInputAggregateDescriptor.SET_PARENT_MUTATION.name(), map()
					.e(SetParentMutationDescriptor.PARENT_PRIMARY_KEY.name(), 10))
				.build(),
			map()
				.e(
					LocalMutationInputAggregateDescriptor.SET_PRICE_INNER_RECORD_HANDLING_MUTATION.name(), map()
					.e(SetPriceInnerRecordHandlingMutationDescriptor.PRICE_INNER_RECORD_HANDLING.name(), "SUM"))
				.build(),
			map()
				.e(
					LocalMutationInputAggregateDescriptor.REMOVE_PRICE_MUTATION.name(), map()
					.e(PriceMutationDescriptor.PRICE_ID.name(), 1)
					.e(PriceMutationDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
					.e(PriceMutationDescriptor.CURRENCY.name(), "CZK"))
				.build(),
			map()
				.e(
					LocalMutationInputAggregateDescriptor.UPSERT_PRICE_MUTATION.name(), map()
					.e(PriceMutationDescriptor.PRICE_ID.name(), 1)
					.e(PriceMutationDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
					.e(PriceMutationDescriptor.CURRENCY.name(), "CZK")
					.e(UpsertPriceMutationDescriptor.PRICE_WITHOUT_TAX.name(), "10")
					.e(UpsertPriceMutationDescriptor.TAX_RATE.name(), "10")
					.e(UpsertPriceMutationDescriptor.PRICE_WITH_TAX.name(), "11")
					.e(UpsertPriceMutationDescriptor.INDEXED.name(), false))
				.build(),
			map()
				.e(
					LocalMutationInputAggregateDescriptor.INSERT_REFERENCE_MUTATION.name(), map()
					.e(ReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(ReferenceMutationDescriptor.PRIMARY_KEY.name(), 1)
					.e(InsertReferenceMutationDescriptor.CARDINALITY.name(), "ONE_OR_MORE")
					.e(InsertReferenceMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "Tag"))
				.build(),
			map()
				.e(
					LocalMutationInputAggregateDescriptor.REMOVE_REFERENCE_MUTATION.name(), map()
					.e(ReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(ReferenceMutationDescriptor.PRIMARY_KEY.name(), 1))
				.build(),
			map()
				.e(
					LocalMutationInputAggregateDescriptor.SET_REFERENCE_GROUP_MUTATION.name(), map()
					.e(ReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(ReferenceMutationDescriptor.PRIMARY_KEY.name(), 1)
					.e(SetReferenceGroupMutationDescriptor.GROUP_PRIMARY_KEY.name(), 2))
				.build(),
			map()
				.e(
					LocalMutationInputAggregateDescriptor.REMOVE_REFERENCE_GROUP_MUTATION.name(), map()
					.e(ReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(ReferenceMutationDescriptor.PRIMARY_KEY.name(), 1))
				.build(),
			map()
				.e(
					LocalMutationInputAggregateDescriptor.REFERENCE_ATTRIBUTE_MUTATION.name(), map()
					.e(ReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(ReferenceMutationDescriptor.PRIMARY_KEY.name(), 1)
					.e(ReferenceAttributeMutationDescriptor.ATTRIBUTE_MUTATION.name(), map()
						.e(
							ReferenceAttributeMutationInputAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.name(), map()
							.e(AttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE))))
				.build()
		);

		final EntityMutation entityMutation = this.converter.createFromInput(1, EntityExistence.MAY_EXIST, inputLocalMutations);
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
		assertTrue(localMutationsIterator.next() instanceof RemoveParentMutation);
		assertTrue(localMutationsIterator.next() instanceof SetParentMutation);
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
		final List<Map<String, Object>> inputLocalMutations = List.of(
			map()
				.e(
					LocalMutationInputAggregateDescriptor.REMOVE_ASSOCIATED_DATA_MUTATION.name(), map()
					.e(AssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_LABELS))
				.e(
					LocalMutationInputAggregateDescriptor.UPSERT_ASSOCIATED_DATA_MUTATION.name(), map()
					.e(AssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_LABELS)
					.e(UpsertAssociatedDataMutationDescriptor.VALUE.name(), map()
						.e("s", "String")))
				.e(
					LocalMutationInputAggregateDescriptor.APPLY_DELTA_ATTRIBUTE_MUTATION.name(), map()
					.e(AttributeMutationDescriptor.NAME.name(), ATTRIBUTE_QUANTITY)
					.e(ApplyDeltaAttributeMutationDescriptor.DELTA.name(), "0.5"))
				.e(
					LocalMutationInputAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.name(), map()
					.e(AttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE))
				.e(
					LocalMutationInputAggregateDescriptor.UPSERT_ATTRIBUTE_MUTATION.name(), map()
					.e(AttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE)
					.e(UpsertAttributeMutationDescriptor.VALUE.name(), "phone"))
				.e(LocalMutationInputAggregateDescriptor.REMOVE_PARENT_MUTATION.name(), true)
				.e(
					LocalMutationInputAggregateDescriptor.SET_PARENT_MUTATION.name(), map()
					.e(SetParentMutationDescriptor.PARENT_PRIMARY_KEY.name(), 10))
				.e(
					LocalMutationInputAggregateDescriptor.SET_PRICE_INNER_RECORD_HANDLING_MUTATION.name(), map()
					.e(SetPriceInnerRecordHandlingMutationDescriptor.PRICE_INNER_RECORD_HANDLING.name(), "SUM"))
				.e(
					LocalMutationInputAggregateDescriptor.REMOVE_PRICE_MUTATION.name(), map()
					.e(PriceMutationDescriptor.PRICE_ID.name(), 1)
					.e(PriceMutationDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
					.e(PriceMutationDescriptor.CURRENCY.name(), "CZK"))
				.e(
					LocalMutationInputAggregateDescriptor.UPSERT_PRICE_MUTATION.name(), map()
					.e(PriceMutationDescriptor.PRICE_ID.name(), 1)
					.e(PriceMutationDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
					.e(PriceMutationDescriptor.CURRENCY.name(), "CZK")
					.e(UpsertPriceMutationDescriptor.PRICE_WITHOUT_TAX.name(), "10")
					.e(UpsertPriceMutationDescriptor.TAX_RATE.name(), "10")
					.e(UpsertPriceMutationDescriptor.PRICE_WITH_TAX.name(), "11")
					.e(UpsertPriceMutationDescriptor.INDEXED.name(), false))
				.e(
					LocalMutationInputAggregateDescriptor.INSERT_REFERENCE_MUTATION.name(), map()
					.e(ReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(ReferenceMutationDescriptor.PRIMARY_KEY.name(), 1)
					.e(InsertReferenceMutationDescriptor.CARDINALITY.name(), "ONE_OR_MORE")
					.e(InsertReferenceMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "Tag"))
				.e(
					LocalMutationInputAggregateDescriptor.REMOVE_REFERENCE_MUTATION.name(), map()
					.e(ReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(ReferenceMutationDescriptor.PRIMARY_KEY.name(), 1))
				.e(
					LocalMutationInputAggregateDescriptor.SET_REFERENCE_GROUP_MUTATION.name(), map()
					.e(ReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(ReferenceMutationDescriptor.PRIMARY_KEY.name(), 1)
					.e(SetReferenceGroupMutationDescriptor.GROUP_PRIMARY_KEY.name(), 2))
				.e(
					LocalMutationInputAggregateDescriptor.REMOVE_REFERENCE_GROUP_MUTATION.name(), map()
					.e(ReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(ReferenceMutationDescriptor.PRIMARY_KEY.name(), 1))
				.e(
					LocalMutationInputAggregateDescriptor.REFERENCE_ATTRIBUTE_MUTATION.name(), map()
					.e(ReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(ReferenceMutationDescriptor.PRIMARY_KEY.name(), 1)
					.e(ReferenceAttributeMutationDescriptor.ATTRIBUTE_MUTATION.name(), map()
						.e(
							ReferenceAttributeMutationInputAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.name(), map()
							.e(AttributeMutationDescriptor.NAME.name(), ATTRIBUTE_CODE))))
				.build()
		);

		final EntityMutation entityMutation = this.converter.createFromInput(1, EntityExistence.MAY_EXIST, inputLocalMutations);
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
		assertTrue(localMutationsIterator.next() instanceof RemoveParentMutation);
		assertTrue(localMutationsIterator.next() instanceof SetParentMutation);
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
		final List<Map<String, Object>> inputLocalMutations = List.of(
			map()
				.e(
					LocalMutationInputAggregateDescriptor.REMOVE_ASSOCIATED_DATA_MUTATION.name(), map()
					.e(AssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_LABELS))
				.build(),
			map()
				.e(
					LocalMutationInputAggregateDescriptor.REMOVE_ASSOCIATED_DATA_MUTATION.name(), map()
					.e(AssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_FILES))
				.build()
		);

		final EntityMutation entityMutation = this.converter.createFromInput(1, EntityExistence.MAY_EXIST, inputLocalMutations);
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
