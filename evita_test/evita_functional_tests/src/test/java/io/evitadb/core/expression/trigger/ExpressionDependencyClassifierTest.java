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

package io.evitadb.core.expression.trigger;

import io.evitadb.api.query.expression.object.accessor.entity.EntityContractAccessor;
import io.evitadb.api.query.expression.object.accessor.entity.ReferenceContractAccessor;
import io.evitadb.api.query.expression.visitor.ElementPathItem;
import io.evitadb.api.query.expression.visitor.IdentifierPathItem;
import io.evitadb.api.query.expression.visitor.PathItem;
import io.evitadb.api.query.expression.visitor.VariablePathItem;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.expression.trigger.ExpressionDependencyClassifier.DependencyKey;
import io.evitadb.core.expression.trigger.ExpressionDependencyClassifier.LocalDependencies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.EXPRESSION;

/**
 * Unit tests for {@link ExpressionDependencyClassifier} — verifies path classification logic using
 * directly constructed {@link PathItem} lists. Complements factory-level integration tests in
 * {@link FacetExpressionTriggerFactoryTest} by covering all branches including defensive guards,
 * localized variants, and error paths.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ExpressionDependencyClassifier")
@Tag(ENGINE)
@Tag(EXPRESSION)
class ExpressionDependencyClassifierTest {

	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCED_ENTITY_TYPE = "parameterType";
	private static final String GROUP_ENTITY_TYPE = "parameterGroup";
	private static final String REFERENCE_NAME = "parameter";

	@Nested
	@DisplayName("detectDependencyType — cross-entity detection")
	class DetectDependencyTypeTest {

		@Test
		@DisplayName("Should return null for path shorter than four elements")
		void shouldReturnNullForPathShorterThanFourElements() {
			// path: [$entity, parentEntity, attributes] — only 3 elements
			final List<PathItem> path = List.of(
				new VariablePathItem(EntityContractAccessor.ENTITY_VARIABLE_NAME),
				new IdentifierPathItem(EntityContractAccessor.PARENT_ENTITY_PROPERTY),
				new IdentifierPathItem(EntityContractAccessor.ATTRIBUTES_PROPERTY)
			);

			assertNull(ExpressionDependencyClassifier.detectDependencyType(path));
		}

		@Test
		@DisplayName("Should return null for non-Variable first path item")
		void shouldReturnNullForNonVariableFirstPathItem() {
			final List<PathItem> path = List.of(
				new IdentifierPathItem("notAVariable"),
				new IdentifierPathItem(ReferenceContractAccessor.REFERENCED_ENTITY_PROPERTY),
				new IdentifierPathItem(EntityContractAccessor.ATTRIBUTES_PROPERTY),
				new ElementPathItem("code")
			);

			assertNull(ExpressionDependencyClassifier.detectDependencyType(path));
		}

		@Test
		@DisplayName("Should return null for local entity attributes path")
		void shouldReturnNullForLocalEntityAttributesPath() {
			// $entity.attributes['status'] — local, not cross-entity
			final List<PathItem> path = entityAttributePath("status");

			assertNull(ExpressionDependencyClassifier.detectDependencyType(path));
		}

		@Test
		@DisplayName("Should return null for local reference attributes path")
		void shouldReturnNullForLocalReferenceAttributesPath() {
			// $reference.attributes['order'] — local, not cross-entity
			final List<PathItem> path = referenceAttributePath("order");

			assertNull(ExpressionDependencyClassifier.detectDependencyType(path));
		}

		@Test
		@DisplayName("Should return null when referenced entity accesses unknown property")
		void shouldReturnNullForReferencedEntityWithUnknownProperty() {
			// $reference.referencedEntity.unknownProperty['x'] — unrecognized third element
			final List<PathItem> path = List.of(
				new VariablePathItem(ReferenceContractAccessor.REFERENCE_VARIABLE_NAME),
				new IdentifierPathItem(ReferenceContractAccessor.REFERENCED_ENTITY_PROPERTY),
				new IdentifierPathItem("unknownProperty"),
				new ElementPathItem("x")
			);

			assertNull(ExpressionDependencyClassifier.detectDependencyType(path));
		}

		@Test
		@DisplayName("Should return REFERENCED_ENTITY_ATTRIBUTE for $reference.referencedEntity.attributes path")
		void shouldReturnReferencedEntityAttributeForReferencedEntityAttributesPath() {
			final List<PathItem> path = referencedEntityAttributePath("code");

			assertEquals(
				DependencyType.REFERENCED_ENTITY_ATTRIBUTE,
				ExpressionDependencyClassifier.detectDependencyType(path)
			);
		}

		@Test
		@DisplayName("Should return REFERENCED_ENTITY_ATTRIBUTE for localizedAttributes variant")
		void shouldReturnReferencedEntityAttributeForLocalizedAttributesPath() {
			final List<PathItem> path = List.of(
				new VariablePathItem(ReferenceContractAccessor.REFERENCE_VARIABLE_NAME),
				new IdentifierPathItem(ReferenceContractAccessor.REFERENCED_ENTITY_PROPERTY),
				new IdentifierPathItem(EntityContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY),
				new ElementPathItem("name")
			);

			assertEquals(
				DependencyType.REFERENCED_ENTITY_ATTRIBUTE,
				ExpressionDependencyClassifier.detectDependencyType(path)
			);
		}

		@Test
		@DisplayName("Should return GROUP_ENTITY_ATTRIBUTE for $reference.groupEntity.attributes path")
		void shouldReturnGroupEntityAttributeForGroupEntityAttributesPath() {
			final List<PathItem> path = groupEntityAttributePath("status");

			assertEquals(
				DependencyType.GROUP_ENTITY_ATTRIBUTE,
				ExpressionDependencyClassifier.detectDependencyType(path)
			);
		}

		@Test
		@DisplayName("Should return REFERENCED_ENTITY_REFERENCE_ATTRIBUTE for referenced entity references path")
		void shouldReturnReferencedEntityReferenceAttributeForReferencedEntityReferencesPath() {
			final List<PathItem> path = referencedEntityReferenceAttributePath("tags", "visible");

			assertEquals(
				DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE,
				ExpressionDependencyClassifier.detectDependencyType(path)
			);
		}

		@Test
		@DisplayName("Should return GROUP_ENTITY_REFERENCE_ATTRIBUTE for group entity references path")
		void shouldReturnGroupEntityReferenceAttributeForGroupEntityReferencesPath() {
			final List<PathItem> path = groupEntityReferenceAttributePath("links", "weight");

			assertEquals(
				DependencyType.GROUP_ENTITY_REFERENCE_ATTRIBUTE,
				ExpressionDependencyClassifier.detectDependencyType(path)
			);
		}

		@Test
		@DisplayName("Should return PARENT_ENTITY_ATTRIBUTE for $entity.parentEntity.attributes path")
		void shouldReturnParentEntityAttributeForParentEntityAttributesPath() {
			final List<PathItem> path = parentEntityAttributePath("code");

			assertEquals(
				DependencyType.PARENT_ENTITY_ATTRIBUTE,
				ExpressionDependencyClassifier.detectDependencyType(path)
			);
		}

		@Test
		@DisplayName("Should return PARENT_ENTITY_REFERENCE_ATTRIBUTE for parent entity references path")
		void shouldReturnParentEntityReferenceAttributeForParentEntityReferencesPath() {
			final List<PathItem> path = parentEntityReferenceAttributePath("singleTag", "weight");

			assertEquals(
				DependencyType.PARENT_ENTITY_REFERENCE_ATTRIBUTE,
				ExpressionDependencyClassifier.detectDependencyType(path)
			);
		}

		@Test
		@DisplayName("Should return null for $entity.parentEntity with non-attributes/non-references property")
		void shouldReturnNullForParentEntityWithUnknownProperty() {
			// $entity.parentEntity.unknownProperty['x']
			final List<PathItem> path = List.of(
				new VariablePathItem(EntityContractAccessor.ENTITY_VARIABLE_NAME),
				new IdentifierPathItem(EntityContractAccessor.PARENT_ENTITY_PROPERTY),
				new IdentifierPathItem("unknownProperty"),
				new ElementPathItem("x")
			);

			assertNull(ExpressionDependencyClassifier.detectDependencyType(path));
		}

		@Test
		@DisplayName("Should return null for $reference with non-referencedEntity/non-groupEntity property")
		void shouldReturnNullForReferenceWithUnknownEntityProperty() {
			// $reference.someOtherProperty.attributes['x']
			final List<PathItem> path = List.of(
				new VariablePathItem(ReferenceContractAccessor.REFERENCE_VARIABLE_NAME),
				new IdentifierPathItem("someOtherProperty"),
				new IdentifierPathItem(EntityContractAccessor.ATTRIBUTES_PROPERTY),
				new ElementPathItem("x")
			);

			assertNull(ExpressionDependencyClassifier.detectDependencyType(path));
		}

	}

	@Nested
	@DisplayName("classifyPaths — dependency bucketing")
	class ClassifyPathsTest {

		@Test
		@DisplayName("Should return empty map for empty path list")
		void shouldReturnEmptyMapForEmptyPathList() {
			final LinkedHashMap<DependencyKey, Set<String>> result =
				ExpressionDependencyClassifier.classifyPaths(List.of());

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("Should return empty map for local-only paths")
		void shouldReturnEmptyMapForLocalOnlyPaths() {
			final LinkedHashMap<DependencyKey, Set<String>> result =
				ExpressionDependencyClassifier.classifyPaths(List.of(
					entityAttributePath("status"),
					referenceAttributePath("order")
				));

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("Should bucket a single cross-entity path")
		void shouldBucketSingleCrossEntityPath() {
			final LinkedHashMap<DependencyKey, Set<String>> result =
				ExpressionDependencyClassifier.classifyPaths(List.of(
					referencedEntityAttributePath("code")
				));

			assertEquals(1, result.size());
			final DependencyKey key = new DependencyKey(DependencyType.REFERENCED_ENTITY_ATTRIBUTE, null);
			assertEquals(Set.of("code"), result.get(key));
		}

		@Test
		@DisplayName("Should merge attributes under the same dependency key")
		void shouldMergeSameDependencyKeyAttributes() {
			final LinkedHashMap<DependencyKey, Set<String>> result =
				ExpressionDependencyClassifier.classifyPaths(List.of(
					referencedEntityAttributePath("code"),
					referencedEntityAttributePath("name")
				));

			assertEquals(1, result.size());
			final DependencyKey key = new DependencyKey(DependencyType.REFERENCED_ENTITY_ATTRIBUTE, null);
			assertEquals(Set.of("code", "name"), result.get(key));
		}

		@Test
		@DisplayName("Should split different dependency keys into separate buckets")
		void shouldSplitDifferentDependencyKeys() {
			final LinkedHashMap<DependencyKey, Set<String>> result =
				ExpressionDependencyClassifier.classifyPaths(List.of(
					referencedEntityAttributePath("code"),
					groupEntityAttributePath("status")
				));

			assertEquals(2, result.size());
			assertEquals(
				Set.of("code"),
				result.get(new DependencyKey(DependencyType.REFERENCED_ENTITY_ATTRIBUTE, null))
			);
			assertEquals(
				Set.of("status"),
				result.get(new DependencyKey(DependencyType.GROUP_ENTITY_ATTRIBUTE, null))
			);
		}

		@Test
		@DisplayName("Should separate reference-attribute paths by reference name")
		void shouldSeparateByReferenceName() {
			final LinkedHashMap<DependencyKey, Set<String>> result =
				ExpressionDependencyClassifier.classifyPaths(List.of(
					referencedEntityReferenceAttributePath("tags", "visible"),
					referencedEntityReferenceAttributePath("links", "weight")
				));

			assertEquals(2, result.size());
			assertEquals(
				Set.of("visible"),
				result.get(new DependencyKey(DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE, "tags"))
			);
			assertEquals(
				Set.of("weight"),
				result.get(new DependencyKey(DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE, "links"))
			);
		}

		@Test
		@DisplayName("Should skip cross-entity path with no extractable attribute (defensive)")
		void shouldSkipPathWithNoExtractableAttribute() {
			// construct a path that passes detectDependencyType (4+ items, correct structure)
			// but fails extractDependentAttribute (no attributes/localizedAttributes after position 2)
			// $reference.referencedEntity.references['tags'] — missing the attributes['x'] part
			final List<PathItem> malformedPath = List.of(
				new VariablePathItem(ReferenceContractAccessor.REFERENCE_VARIABLE_NAME),
				new IdentifierPathItem(ReferenceContractAccessor.REFERENCED_ENTITY_PROPERTY),
				new IdentifierPathItem(EntityContractAccessor.REFERENCES_PROPERTY),
				new ElementPathItem("tags")
			);

			final LinkedHashMap<DependencyKey, Set<String>> result =
				ExpressionDependencyClassifier.classifyPaths(List.of(malformedPath));

			// the path is skipped (warning logged), so the result is empty
			assertTrue(result.isEmpty());
		}

	}

	@Nested
	@DisplayName("extractDependentAttribute")
	class ExtractDependentAttributeTest {

		@Test
		@DisplayName("Should extract attribute from entity-attribute path (start index 2)")
		void shouldExtractAttributeFromEntityAttributePath() {
			final List<PathItem> path = referencedEntityAttributePath("code");

			assertEquals(
				"code",
				ExpressionDependencyClassifier.extractDependentAttribute(path, DependencyType.REFERENCED_ENTITY_ATTRIBUTE)
			);
		}

		@Test
		@DisplayName("Should extract attribute from reference-attribute path (start index 4)")
		void shouldExtractAttributeFromReferenceAttributePath() {
			final List<PathItem> path = referencedEntityReferenceAttributePath("tags", "visible");

			assertEquals(
				"visible",
				ExpressionDependencyClassifier.extractDependentAttribute(
					path, DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE
				)
			);
		}

		@Test
		@DisplayName("Should return null when no attribute access is found in path")
		void shouldReturnNullWhenNoAttributeAccessInPath() {
			// a path with the right length but no attributes/localizedAttributes identifier
			final List<PathItem> path = List.of(
				new VariablePathItem(ReferenceContractAccessor.REFERENCE_VARIABLE_NAME),
				new IdentifierPathItem(ReferenceContractAccessor.REFERENCED_ENTITY_PROPERTY),
				new IdentifierPathItem("somethingElse"),
				new ElementPathItem("x")
			);

			assertNull(
				ExpressionDependencyClassifier.extractDependentAttribute(path, DependencyType.REFERENCED_ENTITY_ATTRIBUTE)
			);
		}

		@Test
		@DisplayName("Should extract attribute from localizedAttributes variant")
		void shouldExtractAttributeFromLocalizedAttributesPath() {
			final List<PathItem> path = List.of(
				new VariablePathItem(ReferenceContractAccessor.REFERENCE_VARIABLE_NAME),
				new IdentifierPathItem(ReferenceContractAccessor.REFERENCED_ENTITY_PROPERTY),
				new IdentifierPathItem(EntityContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY),
				new ElementPathItem("description")
			);

			assertEquals(
				"description",
				ExpressionDependencyClassifier.extractDependentAttribute(path, DependencyType.REFERENCED_ENTITY_ATTRIBUTE)
			);
		}

	}

	@Nested
	@DisplayName("extractDependentReferenceName")
	class ExtractDependentReferenceNameTest {

		@Test
		@DisplayName("Should extract reference name from references path")
		void shouldExtractReferenceNameFromReferencesPath() {
			final List<PathItem> path = referencedEntityReferenceAttributePath("tags", "visible");

			assertEquals("tags", ExpressionDependencyClassifier.extractDependentReferenceName(path));
		}

		@Test
		@DisplayName("Should return null for entity-attribute path (no references element)")
		void shouldReturnNullForEntityAttributePath() {
			final List<PathItem> path = referencedEntityAttributePath("code");

			assertNull(ExpressionDependencyClassifier.extractDependentReferenceName(path));
		}

		@Test
		@DisplayName("Should return null for path with 3 or fewer elements")
		void shouldReturnNullForShortPath() {
			final List<PathItem> path = List.of(
				new VariablePathItem(EntityContractAccessor.ENTITY_VARIABLE_NAME),
				new IdentifierPathItem(EntityContractAccessor.ATTRIBUTES_PROPERTY),
				new ElementPathItem("status")
			);

			assertNull(ExpressionDependencyClassifier.extractDependentReferenceName(path));
		}

	}

	@Nested
	@DisplayName("isAttributesProperty and isAssociatedDataProperty")
	class PropertyCheckTest {

		@Test
		@DisplayName("Should match 'attributes' property")
		void shouldMatchAttributesProperty() {
			assertTrue(ExpressionDependencyClassifier.isAttributesProperty(EntityContractAccessor.ATTRIBUTES_PROPERTY));
		}

		@Test
		@DisplayName("Should match 'localizedAttributes' property")
		void shouldMatchLocalizedAttributesProperty() {
			assertTrue(
				ExpressionDependencyClassifier.isAttributesProperty(EntityContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY)
			);
		}

		@Test
		@DisplayName("Should not match non-attributes property")
		void shouldNotMatchNonAttributesProperty() {
			assertFalse(ExpressionDependencyClassifier.isAttributesProperty("references"));
			assertFalse(ExpressionDependencyClassifier.isAttributesProperty("associatedData"));
		}

		@Test
		@DisplayName("Should match 'associatedData' property")
		void shouldMatchAssociatedDataProperty() {
			assertTrue(
				ExpressionDependencyClassifier.isAssociatedDataProperty(EntityContractAccessor.ASSOCIATED_DATA_PROPERTY)
			);
		}

		@Test
		@DisplayName("Should match 'localizedAssociatedData' property")
		void shouldMatchLocalizedAssociatedDataProperty() {
			assertTrue(
				ExpressionDependencyClassifier.isAssociatedDataProperty(
					EntityContractAccessor.LOCALIZED_ASSOCIATED_DATA_PROPERTY
				)
			);
		}

		@Test
		@DisplayName("Should not match non-associated-data property")
		void shouldNotMatchNonAssociatedDataProperty() {
			assertFalse(ExpressionDependencyClassifier.isAssociatedDataProperty("attributes"));
			assertFalse(ExpressionDependencyClassifier.isAssociatedDataProperty("references"));
		}

	}

	@Nested
	@DisplayName("extractLocalDependencies")
	class ExtractLocalDependenciesTest {

		@Test
		@DisplayName("Should extract entity attributes from $entity.attributes path")
		void shouldExtractEntityAttributes() {
			final LocalDependencies result = ExpressionDependencyClassifier.extractLocalDependencies(
				List.of(entityAttributePath("status"))
			);

			assertEquals(Set.of("status"), result.entityAttributes());
			assertTrue(result.referenceAttributes().isEmpty());
			assertTrue(result.associatedData().isEmpty());
			assertFalse(result.usesParent());
		}

		@Test
		@DisplayName("Should extract entity attributes from $entity.localizedAttributes path")
		void shouldExtractEntityLocalizedAttributes() {
			final List<PathItem> path = List.of(
				new VariablePathItem(EntityContractAccessor.ENTITY_VARIABLE_NAME),
				new IdentifierPathItem(EntityContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY),
				new ElementPathItem("name")
			);

			final LocalDependencies result = ExpressionDependencyClassifier.extractLocalDependencies(List.of(path));

			assertEquals(Set.of("name"), result.entityAttributes());
		}

		@Test
		@DisplayName("Should extract reference attributes from $reference.attributes path")
		void shouldExtractReferenceAttributes() {
			final LocalDependencies result = ExpressionDependencyClassifier.extractLocalDependencies(
				List.of(referenceAttributePath("order"))
			);

			assertTrue(result.entityAttributes().isEmpty());
			assertEquals(Set.of("order"), result.referenceAttributes());
			assertTrue(result.associatedData().isEmpty());
			assertFalse(result.usesParent());
		}

		@Test
		@DisplayName("Should extract reference attributes from $reference.localizedAttributes path")
		void shouldExtractReferenceLocalizedAttributes() {
			final List<PathItem> path = List.of(
				new VariablePathItem(ReferenceContractAccessor.REFERENCE_VARIABLE_NAME),
				new IdentifierPathItem(EntityContractAccessor.LOCALIZED_ATTRIBUTES_PROPERTY),
				new ElementPathItem("label")
			);

			final LocalDependencies result = ExpressionDependencyClassifier.extractLocalDependencies(List.of(path));

			assertEquals(Set.of("label"), result.referenceAttributes());
		}

		@Test
		@DisplayName("Should extract associated data from $entity.associatedData path")
		void shouldExtractAssociatedData() {
			final List<PathItem> path = List.of(
				new VariablePathItem(EntityContractAccessor.ENTITY_VARIABLE_NAME),
				new IdentifierPathItem(EntityContractAccessor.ASSOCIATED_DATA_PROPERTY),
				new ElementPathItem("description")
			);

			final LocalDependencies result = ExpressionDependencyClassifier.extractLocalDependencies(List.of(path));

			assertTrue(result.entityAttributes().isEmpty());
			assertTrue(result.referenceAttributes().isEmpty());
			assertEquals(Set.of("description"), result.associatedData());
			assertFalse(result.usesParent());
		}

		@Test
		@DisplayName("Should extract localized associated data from $entity.localizedAssociatedData path")
		void shouldExtractLocalizedAssociatedData() {
			final List<PathItem> path = List.of(
				new VariablePathItem(EntityContractAccessor.ENTITY_VARIABLE_NAME),
				new IdentifierPathItem(EntityContractAccessor.LOCALIZED_ASSOCIATED_DATA_PROPERTY),
				new ElementPathItem("longDescription")
			);

			final LocalDependencies result = ExpressionDependencyClassifier.extractLocalDependencies(List.of(path));

			assertEquals(Set.of("longDescription"), result.associatedData());
		}

		@Test
		@DisplayName("Should detect parent entity usage from $entity.parentEntity path")
		void shouldDetectParentEntityUsage() {
			final List<PathItem> path = List.of(
				new VariablePathItem(EntityContractAccessor.ENTITY_VARIABLE_NAME),
				new IdentifierPathItem(EntityContractAccessor.PARENT_ENTITY_PROPERTY)
			);

			final LocalDependencies result = ExpressionDependencyClassifier.extractLocalDependencies(List.of(path));

			assertTrue(result.entityAttributes().isEmpty());
			assertTrue(result.referenceAttributes().isEmpty());
			assertTrue(result.associatedData().isEmpty());
			assertTrue(result.usesParent());
		}

		@Test
		@DisplayName("Should combine multiple local dependencies from multiple paths")
		void shouldCombineMultipleLocalDependencies() {
			final LocalDependencies result = ExpressionDependencyClassifier.extractLocalDependencies(List.of(
				entityAttributePath("status"),
				entityAttributePath("priority"),
				referenceAttributePath("order"),
				List.of(
					new VariablePathItem(EntityContractAccessor.ENTITY_VARIABLE_NAME),
					new IdentifierPathItem(EntityContractAccessor.ASSOCIATED_DATA_PROPERTY),
					new ElementPathItem("description")
				),
				List.of(
					new VariablePathItem(EntityContractAccessor.ENTITY_VARIABLE_NAME),
					new IdentifierPathItem(EntityContractAccessor.PARENT_ENTITY_PROPERTY)
				)
			));

			assertEquals(Set.of("status", "priority"), result.entityAttributes());
			assertEquals(Set.of("order"), result.referenceAttributes());
			assertEquals(Set.of("description"), result.associatedData());
			assertTrue(result.usesParent());
		}

		@Test
		@DisplayName("Should return empty sets for cross-entity-only paths")
		void shouldReturnEmptyForCrossEntityOnlyPaths() {
			final LocalDependencies result = ExpressionDependencyClassifier.extractLocalDependencies(
				List.of(referencedEntityAttributePath("code"))
			);

			assertTrue(result.entityAttributes().isEmpty());
			assertTrue(result.referenceAttributes().isEmpty());
			assertTrue(result.associatedData().isEmpty());
			assertFalse(result.usesParent());
		}

		@Test
		@DisplayName("Should skip paths shorter than two elements")
		void shouldSkipPathsShorterThanTwoElements() {
			final LocalDependencies result = ExpressionDependencyClassifier.extractLocalDependencies(List.of(
				List.of(new VariablePathItem(EntityContractAccessor.ENTITY_VARIABLE_NAME)),
				List.of()
			));

			assertTrue(result.entityAttributes().isEmpty());
			assertTrue(result.referenceAttributes().isEmpty());
			assertTrue(result.associatedData().isEmpty());
			assertFalse(result.usesParent());
		}

		@Test
		@DisplayName("Should skip paths with non-Variable first item")
		void shouldSkipPathsWithNonVariableFirstItem() {
			final List<PathItem> path = List.of(
				new IdentifierPathItem("notAVariable"),
				new IdentifierPathItem(EntityContractAccessor.ATTRIBUTES_PROPERTY),
				new ElementPathItem("status")
			);

			final LocalDependencies result = ExpressionDependencyClassifier.extractLocalDependencies(List.of(path));

			assertTrue(result.entityAttributes().isEmpty());
		}

		@Test
		@DisplayName("Should skip entity attribute path without element at position 2")
		void shouldSkipEntityAttributePathWithoutElement() {
			// $entity.attributes — no element access following
			final List<PathItem> path = List.of(
				new VariablePathItem(EntityContractAccessor.ENTITY_VARIABLE_NAME),
				new IdentifierPathItem(EntityContractAccessor.ATTRIBUTES_PROPERTY)
			);

			final LocalDependencies result = ExpressionDependencyClassifier.extractLocalDependencies(List.of(path));

			assertTrue(result.entityAttributes().isEmpty());
		}

	}

	@Nested
	@DisplayName("resolveMutatedEntityType")
	class ResolveMutatedEntityTypeTest {

		@Test
		@DisplayName("Should return referenced entity type for REFERENCED_ENTITY_ATTRIBUTE")
		void shouldReturnReferencedEntityTypeForReferencedEntityAttribute() {
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithGroup();

			assertEquals(
				REFERENCED_ENTITY_TYPE,
				ExpressionDependencyClassifier.resolveMutatedEntityType(
					ENTITY_TYPE, refSchema, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
				)
			);
		}

		@Test
		@DisplayName("Should return referenced entity type for REFERENCED_ENTITY_REFERENCE_ATTRIBUTE")
		void shouldReturnReferencedEntityTypeForReferencedEntityReferenceAttribute() {
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithGroup();

			assertEquals(
				REFERENCED_ENTITY_TYPE,
				ExpressionDependencyClassifier.resolveMutatedEntityType(
					ENTITY_TYPE, refSchema, DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE
				)
			);
		}

		@Test
		@DisplayName("Should return group entity type for GROUP_ENTITY_ATTRIBUTE")
		void shouldReturnGroupEntityTypeForGroupEntityAttribute() {
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithGroup();

			assertEquals(
				GROUP_ENTITY_TYPE,
				ExpressionDependencyClassifier.resolveMutatedEntityType(
					ENTITY_TYPE, refSchema, DependencyType.GROUP_ENTITY_ATTRIBUTE
				)
			);
		}

		@Test
		@DisplayName("Should return group entity type for GROUP_ENTITY_REFERENCE_ATTRIBUTE")
		void shouldReturnGroupEntityTypeForGroupEntityReferenceAttribute() {
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithGroup();

			assertEquals(
				GROUP_ENTITY_TYPE,
				ExpressionDependencyClassifier.resolveMutatedEntityType(
					ENTITY_TYPE, refSchema, DependencyType.GROUP_ENTITY_REFERENCE_ATTRIBUTE
				)
			);
		}

		@Test
		@DisplayName("Should return owner entity type for PARENT_ENTITY_ATTRIBUTE")
		void shouldReturnOwnerEntityTypeForParentEntityAttribute() {
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithoutGroup();

			assertEquals(
				ENTITY_TYPE,
				ExpressionDependencyClassifier.resolveMutatedEntityType(
					ENTITY_TYPE, refSchema, DependencyType.PARENT_ENTITY_ATTRIBUTE
				)
			);
		}

		@Test
		@DisplayName("Should return owner entity type for PARENT_ENTITY_REFERENCE_ATTRIBUTE")
		void shouldReturnOwnerEntityTypeForParentEntityReferenceAttribute() {
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithoutGroup();

			assertEquals(
				ENTITY_TYPE,
				ExpressionDependencyClassifier.resolveMutatedEntityType(
					ENTITY_TYPE, refSchema, DependencyType.PARENT_ENTITY_REFERENCE_ATTRIBUTE
				)
			);
		}

		@Test
		@DisplayName("Should throw when group type is null for GROUP_ENTITY_ATTRIBUTE dependency")
		void shouldThrowWhenGroupTypeIsNullForGroupEntityDependency() {
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithoutGroup();

			assertThrows(
				IllegalStateException.class,
				() -> ExpressionDependencyClassifier.resolveMutatedEntityType(
					ENTITY_TYPE, refSchema, DependencyType.GROUP_ENTITY_ATTRIBUTE
				)
			);
		}

		@Test
		@DisplayName("Should throw when group type is null for GROUP_ENTITY_REFERENCE_ATTRIBUTE dependency")
		void shouldThrowWhenGroupTypeIsNullForGroupEntityReferenceAttributeDependency() {
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithoutGroup();

			assertThrows(
				IllegalStateException.class,
				() -> ExpressionDependencyClassifier.resolveMutatedEntityType(
					ENTITY_TYPE, refSchema, DependencyType.GROUP_ENTITY_REFERENCE_ATTRIBUTE
				)
			);
		}

	}

	// --- Path construction helpers ---

	/**
	 * Constructs a local entity attribute path: `$entity.attributes['attrName']`.
	 *
	 * @param attrName the attribute name
	 * @return path items representing the entity attribute access
	 */
	@Nonnull
	private static List<PathItem> entityAttributePath(@Nonnull String attrName) {
		return List.of(
			new VariablePathItem(EntityContractAccessor.ENTITY_VARIABLE_NAME),
			new IdentifierPathItem(EntityContractAccessor.ATTRIBUTES_PROPERTY),
			new ElementPathItem(attrName)
		);
	}

	/**
	 * Constructs a local reference attribute path: `$reference.attributes['attrName']`.
	 *
	 * @param attrName the attribute name
	 * @return path items representing the reference attribute access
	 */
	@Nonnull
	private static List<PathItem> referenceAttributePath(@Nonnull String attrName) {
		return List.of(
			new VariablePathItem(ReferenceContractAccessor.REFERENCE_VARIABLE_NAME),
			new IdentifierPathItem(EntityContractAccessor.ATTRIBUTES_PROPERTY),
			new ElementPathItem(attrName)
		);
	}

	/**
	 * Constructs a cross-entity referenced entity attribute path:
	 * `$reference.referencedEntity.attributes['attrName']`.
	 *
	 * @param attrName the attribute name on the referenced entity
	 * @return path items representing the cross-entity attribute access
	 */
	@Nonnull
	private static List<PathItem> referencedEntityAttributePath(@Nonnull String attrName) {
		return List.of(
			new VariablePathItem(ReferenceContractAccessor.REFERENCE_VARIABLE_NAME),
			new IdentifierPathItem(ReferenceContractAccessor.REFERENCED_ENTITY_PROPERTY),
			new IdentifierPathItem(EntityContractAccessor.ATTRIBUTES_PROPERTY),
			new ElementPathItem(attrName)
		);
	}

	/**
	 * Constructs a cross-entity group entity attribute path:
	 * `$reference.groupEntity.attributes['attrName']`.
	 *
	 * @param attrName the attribute name on the group entity
	 * @return path items representing the cross-entity group attribute access
	 */
	@Nonnull
	private static List<PathItem> groupEntityAttributePath(@Nonnull String attrName) {
		return List.of(
			new VariablePathItem(ReferenceContractAccessor.REFERENCE_VARIABLE_NAME),
			new IdentifierPathItem(ReferenceContractAccessor.GROUP_ENTITY_PROPERTY),
			new IdentifierPathItem(EntityContractAccessor.ATTRIBUTES_PROPERTY),
			new ElementPathItem(attrName)
		);
	}

	/**
	 * Constructs a cross-entity parent entity attribute path:
	 * `$entity.parentEntity.attributes['attrName']`.
	 *
	 * @param attrName the attribute name on the parent entity
	 * @return path items representing the parent entity attribute access
	 */
	@Nonnull
	private static List<PathItem> parentEntityAttributePath(@Nonnull String attrName) {
		return List.of(
			new VariablePathItem(EntityContractAccessor.ENTITY_VARIABLE_NAME),
			new IdentifierPathItem(EntityContractAccessor.PARENT_ENTITY_PROPERTY),
			new IdentifierPathItem(EntityContractAccessor.ATTRIBUTES_PROPERTY),
			new ElementPathItem(attrName)
		);
	}

	/**
	 * Constructs a cross-entity referenced entity reference-attribute path:
	 * `$reference.referencedEntity.references['refName'].attributes['attrName']`.
	 *
	 * @param refName  the reference name on the referenced entity
	 * @param attrName the attribute name on that reference
	 * @return path items representing the cross-entity reference attribute access
	 */
	@Nonnull
	private static List<PathItem> referencedEntityReferenceAttributePath(
		@Nonnull String refName,
		@Nonnull String attrName
	) {
		return List.of(
			new VariablePathItem(ReferenceContractAccessor.REFERENCE_VARIABLE_NAME),
			new IdentifierPathItem(ReferenceContractAccessor.REFERENCED_ENTITY_PROPERTY),
			new IdentifierPathItem(EntityContractAccessor.REFERENCES_PROPERTY),
			new ElementPathItem(refName),
			new IdentifierPathItem(EntityContractAccessor.ATTRIBUTES_PROPERTY),
			new ElementPathItem(attrName)
		);
	}

	/**
	 * Constructs a cross-entity group entity reference-attribute path:
	 * `$reference.groupEntity.references['refName'].attributes['attrName']`.
	 *
	 * @param refName  the reference name on the group entity
	 * @param attrName the attribute name on that reference
	 * @return path items representing the cross-entity group reference attribute access
	 */
	@Nonnull
	private static List<PathItem> groupEntityReferenceAttributePath(
		@Nonnull String refName,
		@Nonnull String attrName
	) {
		return List.of(
			new VariablePathItem(ReferenceContractAccessor.REFERENCE_VARIABLE_NAME),
			new IdentifierPathItem(ReferenceContractAccessor.GROUP_ENTITY_PROPERTY),
			new IdentifierPathItem(EntityContractAccessor.REFERENCES_PROPERTY),
			new ElementPathItem(refName),
			new IdentifierPathItem(EntityContractAccessor.ATTRIBUTES_PROPERTY),
			new ElementPathItem(attrName)
		);
	}

	/**
	 * Constructs a cross-entity parent entity reference-attribute path:
	 * `$entity.parentEntity.references['refName'].attributes['attrName']`.
	 *
	 * @param refName  the reference name on the parent entity
	 * @param attrName the attribute name on that reference
	 * @return path items representing the parent entity reference attribute access
	 */
	@Nonnull
	private static List<PathItem> parentEntityReferenceAttributePath(
		@Nonnull String refName,
		@Nonnull String attrName
	) {
		return List.of(
			new VariablePathItem(EntityContractAccessor.ENTITY_VARIABLE_NAME),
			new IdentifierPathItem(EntityContractAccessor.PARENT_ENTITY_PROPERTY),
			new IdentifierPathItem(EntityContractAccessor.REFERENCES_PROPERTY),
			new ElementPathItem(refName),
			new IdentifierPathItem(EntityContractAccessor.ATTRIBUTES_PROPERTY),
			new ElementPathItem(attrName)
		);
	}

	// --- Schema construction helpers ---

	/**
	 * Builds a reference schema with a referenced entity type and a group entity type.
	 *
	 * @return a reference schema contract with both referenced and group types set
	 */
	@Nonnull
	private static ReferenceSchemaContract buildReferenceSchemaWithGroup() {
		return ReferenceSchema._internalBuild(
			REFERENCE_NAME, REFERENCED_ENTITY_TYPE, true,
			Cardinality.ZERO_OR_MORE, GROUP_ENTITY_TYPE, true,
			null, null
		);
	}

	/**
	 * Builds a reference schema with a referenced entity type but no group entity type.
	 *
	 * @return a reference schema contract without a group type
	 */
	@Nonnull
	private static ReferenceSchemaContract buildReferenceSchemaWithoutGroup() {
		return ReferenceSchema._internalBuild(
			REFERENCE_NAME, REFERENCED_ENTITY_TYPE, true,
			Cardinality.ZERO_OR_MORE, null, false,
			null, null
		);
	}

}
