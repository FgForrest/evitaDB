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

package io.evitadb.externalApi.rest.api.catalog.builder;

/**
 * Description
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
// todo lho probably delete
class MutationCommonSchemasBuilderTest {
//	private static MutationCommonSchemasContext commonSchemasContext;
//
//	@BeforeAll
//	public static void buildContext() {
//		final CatalogSchemaBuildingContext buildingContext = Mockito.mock(CatalogSchemaBuildingContext.class);
//		commonSchemasContext = new MutationCommonSchemasBuilder(buildingContext).buildCommonSchemas();
//	}
//
//	@Test
//	void shouldBuildRemoveAssociatedDataMutationSchema() {
//		final var expectedSchema = "properties: name: type: string locale: type: string format: locale example: cs-CZ required: - name";
//		assertEquals(expectedSchema, deleteDescriptionsAndWriteApiObjectToOneLine(commonSchemasContext.getRemoveAssociatedDataMutationSchema()));
//	}
//
//	@Test
//	void shouldBuildUpsertAssociatedDataMutationSchema() {
//		final var expectedSchema = "properties: name: type: string locale: type: string format: locale example: cs-CZ value: anyOf:";
//		assertTrue(deleteDescriptionsAndWriteApiObjectToOneLine(commonSchemasContext.getUpsertAssociatedDataMutationSchema()).startsWith(expectedSchema));
//	}
//
//	@Test
//	@Disabled("TODO MVE: doesn't \"any\" type have some special toString?")
//	void shouldBuildApplyDeltaAttributeMutationSchema() {
//		final var expectedSchema = "properties: name: type: string locale: type: string format: locale example: cs-CZ delta: type: string format: decimal example: \"6584.25\" pattern: \"d+([.]d+)?\" requiredRangeAfterApplication: type: array format: range items: type: string format: decimal example: \"6584.25\" pattern: \"d+([.]d+)?\" maxItems: 2 minItems: 2 required: - delta - name";
//		assertEquals(expectedSchema, deleteDescriptionsAndWriteApiObjectToOneLine(commonSchemasContext.getApplyDeltaAttributeMutationSchema()));
//	}
//
//	@Test
//	void shouldBuildRemoveAttributeMutationSchema() {
//		final var expectedSchema = "properties: name: type: string locale: type: string format: locale example: cs-CZ required: - name";
//		assertEquals(expectedSchema, deleteDescriptionsAndWriteApiObjectToOneLine(commonSchemasContext.getRemoveAttributeMutationSchema()));
//	}
//
//	@Test
//	void shouldBuildUpsertAttributeMutationSchema() {
//		final var expectedSchema = "properties: name: type: string locale: type: string format: locale example: cs-CZ value: anyOf:";
//		assertTrue(deleteDescriptionsAndWriteApiObjectToOneLine(commonSchemasContext.getUpsertAttributeMutationSchema()).startsWith(expectedSchema));
//	}
//
//	@Test
//	void shouldBuildSetHierarchicalPlacementMutationSchema() {
//		final var expectedSchema = "properties: parentPrimaryKey: type: integer orderAmongSiblings: type: integer required: - orderAmongSiblings";
//		assertEquals(expectedSchema, deleteDescriptionsAndWriteApiObjectToOneLine(commonSchemasContext.getSetHierarchicalPlacementMutationSchema()));
//	}
//
//	@Test
//	void shouldBuildSetPriceInnerRecordHandlingMutation() {
//		final var expectedSchema = "properties: priceInnerRecordHandling: type: string enum: - SUM - FIRST_OCCURRENCE - NONE - UNKNOWN example: SUM required: - priceInnerRecordHandling";
//		assertEquals(expectedSchema, deleteDescriptionsAndWriteApiObjectToOneLine(commonSchemasContext.getSetPriceInnerRecordHandlingMutation()));
//	}
//
//	@Test
//	void shouldBuildRemovePriceMutationSchema() {
//		final var expectedSchema = "properties: priceId: type: integer priceList: type: string currency: type: string format: iso-4217 example: CZK required: - currency - priceId - priceList";
//		assertEquals(expectedSchema, deleteDescriptionsAndWriteApiObjectToOneLine(commonSchemasContext.getRemovePriceMutationSchema()));
//	}
//
//	@Test
//	void shouldBuildUpsertPriceMutationSchema() {
//		final var expectedSchema = "properties: priceId: type: integer priceList: type: string currency: type: string format: iso-4217 example: CZK innerRecordId: type: integer priceWithoutTax: type: string format: decimal example: \"6584.25\" pattern: \"d+([.]d+)?\" taxRate: type: string format: decimal example: \"6584.25\" pattern: \"d+([.]d+)?\" priceWithTax: type: string format: decimal example: \"6584.25\" pattern: \"d+([.]d+)?\" validity: type: array format: range items: type: string format: date-time example: 2022-09-27T13:28:27.357442951+02:00 maxItems: 2 minItems: 2 sellable: type: boolean required: - currency - priceId - priceList - priceWithTax - priceWithoutTax - sellable - taxRate";
//		assertEquals(expectedSchema, deleteDescriptionsAndWriteApiObjectToOneLine(commonSchemasContext.getUpsertPriceMutationSchema()));
//	}
//
//	@Test
//	void shouldBuildRemoveReferenceMutationSchema() {
//		final var expectedSchema = "properties: name: type: string primaryKey: type: integer required: - name - primaryKey";
//		assertEquals(expectedSchema, deleteDescriptionsAndWriteApiObjectToOneLine(commonSchemasContext.getRemoveReferenceMutationSchema()));
//	}
//
//	@Test
//	void shouldBuildInsertReferenceMutationSchema() {
//		final var expectedSchema = "properties: name: type: string primaryKey: type: integer cardinality: type: string enum: - ZERO_OR_ONE - ZERO_OR_MORE - EXACTLY_ONE - ONE_OR_MORE example: ZERO_OR_ONE referencedEntityType: type: string required: - name - primaryKey";
//		assertEquals(expectedSchema, deleteDescriptionsAndWriteApiObjectToOneLine(commonSchemasContext.getInsertReferenceMutationSchema()));
//	}
//
//	@Test
//	void shouldBuildRemoveReferenceGroupMutationSchema() {
//		final var expectedSchema = "properties: name: type: string primaryKey: type: integer required: - name - primaryKey";
//		assertEquals(expectedSchema, deleteDescriptionsAndWriteApiObjectToOneLine(commonSchemasContext.getRemoveReferenceGroupMutationSchema()));
//	}
//
//	@Test
//	void shouldBuildUpsertReferenceGroupMutationSchema() {
//		final var expectedSchema = "properties: name: type: string primaryKey: type: integer groupType: type: string groupPrimaryKey: type: integer required: - groupPrimaryKey - name - primaryKey";
//		assertEquals(expectedSchema, deleteDescriptionsAndWriteApiObjectToOneLine(commonSchemasContext.getUpsertReferenceGroupMutationSchema()));
//	}
//
//	@Test
//	void shouldBuildReferenceAttributesUpdateMutationSchema() {
//		final var expectedSchema = "properties: name: type: string primaryKey: type: integer attributeMutation: oneOf: - $ref: '#/components/schemas/ReferenceAttributeMutationAggregate' required: - attributeMutation - name - primaryKey";
//		assertEquals(expectedSchema, deleteDescriptionsAndWriteApiObjectToOneLine(commonSchemasContext.getReferenceAttributesUpdateMutationSchema()));
//	}
//
//	@Test
//	void shouldBuildReferenceAttributeMutationSchema() {
//		final var expectedSchema = "type: object properties: applyDeltaAttributeMutation: oneOf: - $ref: '#/components/schemas/ApplyDeltaAttributeMutation' removeAttributeMutation: oneOf: - $ref: '#/components/schemas/RemoveAttributeMutation' upsertAttributeMutation: oneOf: - $ref: '#/components/schemas/UpsertAttributeMutation'";
//		assertEquals(expectedSchema, deleteDescriptionsAndWriteApiObjectToOneLine(commonSchemasContext.getReferenceAttributeMutationSchema()));
//	}
//
//	public static String deleteDescriptionsAndWriteApiObjectToOneLine(Schema<Object> schema) {
//		schema.description(null);
//		schema.getProperties().values().forEach(property -> property.description(null));
//		return Yaml31.pretty(schema).replaceAll("\\n", " ").replaceAll(" +", " ").trim();
//	}
}