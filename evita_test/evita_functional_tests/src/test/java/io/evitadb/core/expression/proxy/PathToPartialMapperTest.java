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

import io.evitadb.api.query.expression.visitor.ElementPathItem;
import io.evitadb.api.query.expression.visitor.IdentifierPathItem;
import io.evitadb.api.query.expression.visitor.PathItem;
import io.evitadb.api.query.expression.visitor.VariablePathItem;
import io.evitadb.core.expression.proxy.PathToPartialMapper.MappingResult;
import io.evitadb.core.expression.proxy.entity.EntityAssociatedDataPartial;
import io.evitadb.core.expression.proxy.entity.EntityAttributePartial;
import io.evitadb.core.expression.proxy.entity.EntityParentPartial;
import io.evitadb.core.expression.proxy.entity.EntityPrimaryKeyPartial;
import io.evitadb.core.expression.proxy.entity.EntityReferencesPartial;
import io.evitadb.core.expression.proxy.entity.EntitySchemaPartial;
import io.evitadb.core.expression.proxy.entity.EntityVersionAndDroppablePartial;
import io.evitadb.core.expression.proxy.reference.GroupEntityPartial;
import io.evitadb.core.expression.proxy.reference.GroupReferencePartial;
import io.evitadb.core.expression.proxy.reference.ReferenceAttributePartial;
import io.evitadb.core.expression.proxy.reference.ReferenceIdentityPartial;
import io.evitadb.core.expression.proxy.reference.ReferenceVersionAndDroppablePartial;
import io.evitadb.core.expression.proxy.reference.ReferencedEntityPartial;
import io.evitadb.exception.ExpressionEvaluationException;
import one.edee.oss.proxycian.PredicateMethodClassification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PathToPartialMapper} verifying that expression paths are correctly mapped to proxy partials
 * and storage part recipes.
 */
@DisplayName("Path to partial mapper")
class PathToPartialMapperTest {

	/**
	 * Creates a path from the given path items.
	 *
	 * @param items path items to compose
	 * @return list of path items representing a single path
	 */
	@Nonnull
	private static List<PathItem> path(@Nonnull PathItem... items) {
		return List.of(items);
	}

	/**
	 * Checks whether the given partials array contains the specified partial instance (identity check).
	 *
	 * @param partials the array to search
	 * @param partial  the partial to find
	 * @return true if the partial is found
	 */
	private static boolean containsPartial(
		@Nonnull PredicateMethodClassification<?, ?, ?>[] partials,
		@Nonnull PredicateMethodClassification<?, ?, ?> partial
	) {
		for (final PredicateMethodClassification<?, ?, ?> p : partials) {
			if (p == partial) {
				return true;
			}
		}
		return false;
	}

	@Test
	@DisplayName("empty paths produce minimal entity partials with always-included partials")
	void shouldProduceMinimalResultForEmptyPaths() {
		final MappingResult result = PathToPartialMapper.map(Collections.emptyList());

		assertNotNull(result.entityPartials());
		assertNull(result.referencePartials(), "No reference partials expected for empty paths");
		assertFalse(result.entityRecipe().needsEntityBody());
		assertFalse(result.entityRecipe().needsGlobalAttributes());
		assertFalse(result.entityRecipe().needsReferences());
		assertFalse(result.needsReferencedEntityProxy());
		assertFalse(result.needsGroupEntityProxy());

		// always-included: schema, version/droppable, catch-all
		assertTrue(
			containsPartial(result.entityPartials(), EntitySchemaPartial.GET_SCHEMA),
			"Schema partial must always be included"
		);
		assertTrue(
			containsPartial(result.entityPartials(), EntityVersionAndDroppablePartial.VERSION),
			"Version partial must always be included"
		);
		assertTrue(
			containsPartial(result.entityPartials(), CatchAllPartial.INSTANCE),
			"CatchAll must always be last"
		);
	}

	@Test
	@DisplayName("$entity.primaryKey → body needed + PrimaryKeyPartial")
	void shouldProduceBodyRecipeForPrimaryKeyPath() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(new VariablePathItem("entity"), new IdentifierPathItem("primaryKey"))
		));

		assertTrue(result.entityRecipe().needsEntityBody(), "Body should be needed for primaryKey");
		assertTrue(
			containsPartial(result.entityPartials(), EntityPrimaryKeyPartial.GET_PRIMARY_KEY),
			"PrimaryKeyPartial should be present"
		);
	}

	@Test
	@DisplayName("$entity.attributes['code'] → global attributes needed + AttributePartial")
	void shouldProduceAttributePartialsForSingleAttributePath() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(
				new VariablePathItem("entity"),
				new IdentifierPathItem("attributes"),
				new ElementPathItem("code")
			)
		));

		assertTrue(
			result.entityRecipe().needsGlobalAttributes(), "Global attributes should be needed"
		);
		assertFalse(result.entityRecipe().needsEntityBody(), "Body should not be needed");
		assertTrue(
			containsPartial(result.entityPartials(), EntityAttributePartial.GET_ATTRIBUTE),
			"GET_ATTRIBUTE should be present"
		);
		assertTrue(
			containsPartial(result.entityPartials(), EntityAttributePartial.ATTRIBUTES_AVAILABLE),
			"ATTRIBUTES_AVAILABLE should be present"
		);
	}

	@Test
	@DisplayName("$entity.localizedAttributes['name'] → attribute partials + body needed + locale sentinel")
	void shouldProduceAttributePartialsForLocalizedAttributePath() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(
				new VariablePathItem("entity"),
				new IdentifierPathItem("localizedAttributes"),
				new ElementPathItem("name")
			)
		));

		assertTrue(
			result.entityRecipe().needsGlobalAttributes(),
			"Global attributes should be needed for localized attribute access"
		);
		assertTrue(
			result.entityRecipe().needsEntityBody(),
			"Entity body should be needed to resolve available locales"
		);
		assertTrue(
			result.entityRecipe().neededAttributeLocales().contains(Locale.ROOT),
			"Locale.ROOT sentinel should signal all locale-specific attribute parts"
		);
		assertTrue(
			containsPartial(result.entityPartials(), EntityAttributePartial.GET_ATTRIBUTE),
			"GET_ATTRIBUTE should be present for localized attributes"
		);
		assertTrue(
			containsPartial(result.entityPartials(), EntityAttributePartial.ATTRIBUTES_AVAILABLE),
			"ATTRIBUTES_AVAILABLE should be present for localized attributes"
		);
	}

	@Test
	@DisplayName("$entity.associatedData['desc'] → associated data partials + name in recipe")
	void shouldProduceAssociatedDataPartialsForAssociatedDataPath() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(
				new VariablePathItem("entity"),
				new IdentifierPathItem("associatedData"),
				new ElementPathItem("description")
			)
		));

		assertTrue(
			containsPartial(result.entityPartials(), EntityAssociatedDataPartial.GET_ASSOCIATED_DATA),
			"GET_ASSOCIATED_DATA should be present"
		);
		assertTrue(
			result.entityRecipe().neededAssociatedDataNames().contains("description"),
			"Recipe should contain the associated data name"
		);
	}

	@Test
	@DisplayName("$entity.localizedAssociatedData['desc'] → same associated data partials as non-localized")
	void shouldProduceAssociatedDataPartialsForLocalizedAssociatedDataPath() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(
				new VariablePathItem("entity"),
				new IdentifierPathItem("localizedAssociatedData"),
				new ElementPathItem("desc")
			)
		));

		assertTrue(
			containsPartial(
				result.entityPartials(), EntityAssociatedDataPartial.GET_ASSOCIATED_DATA
			),
			"GET_ASSOCIATED_DATA should be present for localized associated data"
		);
		assertTrue(
			containsPartial(
				result.entityPartials(), EntityAssociatedDataPartial.ASSOCIATED_DATA_AVAILABLE
			),
			"ASSOCIATED_DATA_AVAILABLE should be present for localized associated data"
		);
		assertTrue(
			result.entityRecipe().neededAssociatedDataNames().contains("desc"),
			"Recipe should contain the localized associated data name"
		);
	}

	@Test
	@DisplayName("$entity.parent → body needed + ParentPartial")
	void shouldProduceParentPartialsForParentPath() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(new VariablePathItem("entity"), new IdentifierPathItem("parent"))
		));

		assertTrue(result.entityRecipe().needsEntityBody(), "Body should be needed for parent");
		assertTrue(
			containsPartial(result.entityPartials(), EntityParentPartial.PARENT_AVAILABLE),
			"ParentPartial should be present"
		);
	}

	@Test
	@DisplayName("$entity.references['brand'] → references needed + ReferencesPartial + reference partials")
	void shouldProduceReferencePartialsForReferencePath() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(
				new VariablePathItem("entity"),
				new IdentifierPathItem("references"),
				new ElementPathItem("brand")
			)
		));

		assertTrue(result.entityRecipe().needsReferences(), "References should be needed");
		assertTrue(
			containsPartial(result.entityPartials(), EntityReferencesPartial.GET_REFERENCES_BY_NAME),
			"GET_REFERENCES_BY_NAME should be present"
		);
		assertNotNull(result.referencePartials(), "Reference partials should be present");
		assertTrue(
			containsPartial(result.referencePartials(), ReferenceIdentityPartial.GET_REFERENCE_KEY),
			"Reference identity partial should always be included"
		);
		assertTrue(
			containsPartial(result.referencePartials(), ReferenceVersionAndDroppablePartial.VERSION),
			"Reference version partial should always be included"
		);
	}

	@Test
	@DisplayName("$entity.references['brand'].attributes['order'] → reference attribute partial included")
	void shouldIncludeReferenceAttributePartialForReferenceAttributeSubPath() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(
				new VariablePathItem("entity"),
				new IdentifierPathItem("references"),
				new ElementPathItem("brand"),
				new IdentifierPathItem("attributes"),
				new ElementPathItem("order")
			)
		));

		assertNotNull(result.referencePartials());
		assertTrue(
			containsPartial(result.referencePartials(), ReferenceAttributePartial.GET_ATTRIBUTE),
			"Reference attribute partial should be present"
		);
		assertTrue(
			containsPartial(result.referencePartials(), ReferenceAttributePartial.ATTRIBUTES_AVAILABLE),
			"Reference attributes available should be present"
		);
	}

	@Test
	@DisplayName("$entity.references['brand'].referencedEntity → nested referenced entity flag set")
	void shouldSetNestedFlagForReferencedEntitySubPath() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(
				new VariablePathItem("entity"),
				new IdentifierPathItem("references"),
				new ElementPathItem("brand"),
				new IdentifierPathItem("referencedEntity")
			)
		));

		assertTrue(
			result.needsReferencedEntityProxy(), "Nested referenced entity proxy flag should be set"
		);
		assertFalse(result.needsGroupEntityProxy(), "Group entity flag should not be set");
		assertNotNull(result.referencePartials());
		assertTrue(
			containsPartial(result.referencePartials(), ReferencedEntityPartial.GET_REFERENCED_ENTITY),
			"ReferencedEntityPartial should be present"
		);
	}

	@Test
	@DisplayName("$entity.references['brand'].groupEntity → nested group entity flag set")
	void shouldSetNestedFlagForGroupEntitySubPath() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(
				new VariablePathItem("entity"),
				new IdentifierPathItem("references"),
				new ElementPathItem("brand"),
				new IdentifierPathItem("groupEntity")
			)
		));

		assertTrue(result.needsGroupEntityProxy(), "Nested group entity proxy flag should be set");
		assertFalse(
			result.needsReferencedEntityProxy(), "Referenced entity flag should not be set"
		);
		assertNotNull(result.referencePartials());
		assertTrue(
			containsPartial(result.referencePartials(), GroupEntityPartial.GET_GROUP_ENTITY),
			"GroupEntityPartial should be present"
		);
	}

	@Test
	@DisplayName("$entity.references['brand'].group → GroupReferencePartial included")
	void shouldIncludeGroupReferencePartialForGroupSubPath() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(
				new VariablePathItem("entity"),
				new IdentifierPathItem("references"),
				new ElementPathItem("brand"),
				new IdentifierPathItem("group")
			)
		));

		assertNotNull(result.referencePartials());
		assertTrue(
			containsPartial(result.referencePartials(), GroupReferencePartial.GET_GROUP),
			"GroupReferencePartial should be present"
		);
	}

	@Test
	@DisplayName("$reference.referencedPrimaryKey → reference identity partial included")
	void shouldMapReferencePrimaryKeyToIdentityPartial() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(
				new VariablePathItem("reference"),
				new IdentifierPathItem("referencedPrimaryKey")
			)
		));

		assertNotNull(
			result.referencePartials(), "Reference partials should be present"
		);
		assertTrue(
			containsPartial(
				result.referencePartials(), ReferenceIdentityPartial.GET_REFERENCE_KEY
			),
			"ReferenceIdentityPartial GET_REFERENCE_KEY should be present"
		);
		assertTrue(
			containsPartial(
				result.referencePartials(),
				ReferenceIdentityPartial.GET_REFERENCED_PRIMARY_KEY
			),
			"ReferenceIdentityPartial GET_REFERENCED_PRIMARY_KEY should be present"
		);
	}

	@Test
	@DisplayName("multiple paths produce union of partials and recipe flags")
	void shouldProduceUnionOfPartialsForMultiplePaths() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(new VariablePathItem("entity"), new IdentifierPathItem("primaryKey")),
			path(
				new VariablePathItem("entity"),
				new IdentifierPathItem("attributes"),
				new ElementPathItem("code")
			),
			path(
				new VariablePathItem("entity"),
				new IdentifierPathItem("references"),
				new ElementPathItem("brand"),
				new IdentifierPathItem("attributes"),
				new ElementPathItem("order")
			)
		));

		// entity recipe: body + global attrs + references
		assertTrue(result.entityRecipe().needsEntityBody(), "Body needed for primaryKey");
		assertTrue(
			result.entityRecipe().needsGlobalAttributes(), "Global attributes needed for attributes"
		);
		assertTrue(result.entityRecipe().needsReferences(), "References needed for references");

		// entity partials: PK + attribute + references
		assertTrue(containsPartial(result.entityPartials(), EntityPrimaryKeyPartial.GET_PRIMARY_KEY));
		assertTrue(containsPartial(result.entityPartials(), EntityAttributePartial.GET_ATTRIBUTE));
		assertTrue(
			containsPartial(result.entityPartials(), EntityReferencesPartial.GET_REFERENCES_BY_NAME)
		);

		// reference partials: attribute
		assertNotNull(result.referencePartials());
		assertTrue(
			containsPartial(result.referencePartials(), ReferenceAttributePartial.GET_ATTRIBUTE)
		);
	}

	@Test
	@DisplayName("entity and $reference paths produce both entity and reference partials")
	void shouldProduceSeparateDescriptorsForEntityAndReferencePaths() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(
				new VariablePathItem("entity"),
				new IdentifierPathItem("attributes"),
				new ElementPathItem("code")
			),
			path(
				new VariablePathItem("reference"),
				new IdentifierPathItem("attributes"),
				new ElementPathItem("order")
			)
		));

		// entity partials should contain attribute partial
		assertTrue(
			containsPartial(result.entityPartials(), EntityAttributePartial.GET_ATTRIBUTE),
			"Entity attribute partial should be present"
		);

		// reference partials should be non-null and contain attribute partial
		assertNotNull(
			result.referencePartials(),
			"Reference partials should be present for $reference path"
		);
		assertTrue(
			containsPartial(
				result.referencePartials(), ReferenceAttributePartial.GET_ATTRIBUTE
			),
			"Reference attribute partial should be present"
		);
	}

	@Test
	@DisplayName("always includes schema and version partials even with specific paths")
	void shouldAlwaysIncludeSchemaAndVersionPartials() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(new VariablePathItem("entity"), new IdentifierPathItem("primaryKey"))
		));

		assertTrue(containsPartial(result.entityPartials(), EntitySchemaPartial.GET_SCHEMA));
		assertTrue(containsPartial(result.entityPartials(), EntitySchemaPartial.GET_TYPE));
		assertTrue(containsPartial(result.entityPartials(), EntityVersionAndDroppablePartial.VERSION));
		assertTrue(containsPartial(result.entityPartials(), EntityVersionAndDroppablePartial.DROPPED));
		assertTrue(containsPartial(result.entityPartials(), EntityVersionAndDroppablePartial.GET_SCOPE));
		assertTrue(
			containsPartial(result.entityPartials(), EntityVersionAndDroppablePartial.GET_ALL_LOCALES)
		);
		assertTrue(
			containsPartial(result.entityPartials(), EntityVersionAndDroppablePartial.GET_LOCALES)
		);
		assertTrue(containsPartial(result.entityPartials(), CatchAllPartial.OBJECT_METHODS));
		assertTrue(containsPartial(result.entityPartials(), CatchAllPartial.INSTANCE));
	}

	@Test
	@DisplayName("CatchAll.INSTANCE is always last in entity partials")
	void shouldPlaceCatchAllLastInEntityPartials() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(new VariablePathItem("entity"), new IdentifierPathItem("primaryKey"))
		));

		final PredicateMethodClassification<?, ?, ?>[] partials = result.entityPartials();
		assertSame(
			CatchAllPartial.INSTANCE,
			partials[partials.length - 1],
			"CatchAll.INSTANCE must be the last entity partial"
		);
		assertSame(
			CatchAllPartial.OBJECT_METHODS,
			partials[partials.length - 2],
			"CatchAll.OBJECT_METHODS must be second to last"
		);
	}

	@Test
	@DisplayName("CatchAll.INSTANCE is always last in reference partials")
	void shouldPlaceCatchAllLastInReferencePartials() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(
				new VariablePathItem("entity"),
				new IdentifierPathItem("references"),
				new ElementPathItem("brand")
			)
		));

		final PredicateMethodClassification<?, ?, ?>[] partials = result.referencePartials();
		assertNotNull(partials);
		assertSame(
			CatchAllPartial.INSTANCE,
			partials[partials.length - 1],
			"CatchAll.INSTANCE must be the last reference partial"
		);
		assertSame(
			CatchAllPartial.OBJECT_METHODS,
			partials[partials.length - 2],
			"CatchAll.OBJECT_METHODS must be second to last"
		);
	}

	@Test
	@DisplayName("unknown variable throws ExpressionEvaluationException")
	void shouldThrowExceptionForUnknownVariable() {
		assertThrows(
			ExpressionEvaluationException.class,
			() -> PathToPartialMapper.map(List.of(
				path(new VariablePathItem("unknown"), new IdentifierPathItem("primaryKey"))
			)),
			"Unknown variable should throw ExpressionEvaluationException"
		);
	}

	@Test
	@DisplayName("$entity.type → handled by always-included schema partial, no body needed")
	void shouldNotNeedBodyForTypePath() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(new VariablePathItem("entity"), new IdentifierPathItem("type"))
		));

		assertFalse(result.entityRecipe().needsEntityBody(), "Body should not be needed for type");
		assertTrue(
			containsPartial(result.entityPartials(), EntitySchemaPartial.GET_TYPE),
			"GET_TYPE should be present (always included)"
		);
	}

	@Test
	@DisplayName("$entity.version → body needed, handled by always-included version partial")
	void shouldNeedBodyForVersionPath() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(new VariablePathItem("entity"), new IdentifierPathItem("version"))
		));

		assertTrue(result.entityRecipe().needsEntityBody(), "Body should be needed for version");
	}

	@Test
	@DisplayName("$entity.scope → body needed, handled by always-included version partial")
	void shouldNeedBodyForScopePath() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(new VariablePathItem("entity"), new IdentifierPathItem("scope"))
		));

		assertTrue(result.entityRecipe().needsEntityBody(), "Body should be needed for scope");
	}

	@Test
	@DisplayName("duplicate paths do not duplicate partials")
	void shouldNotDuplicatePartialsForDuplicatePaths() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(new VariablePathItem("entity"), new IdentifierPathItem("primaryKey")),
			path(new VariablePathItem("entity"), new IdentifierPathItem("primaryKey"))
		));

		// count how many times GET_PRIMARY_KEY appears
		int count = 0;
		for (final PredicateMethodClassification<?, ?, ?> p : result.entityPartials()) {
			if (p == EntityPrimaryKeyPartial.GET_PRIMARY_KEY) {
				count++;
			}
		}
		assertEquals(1, count, "GET_PRIMARY_KEY should appear exactly once (deduplicated)");
	}

	@Test
	@DisplayName("reference identity partials are always included when references accessed")
	void shouldAlwaysIncludeReferenceIdentityPartials() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(
				new VariablePathItem("entity"),
				new IdentifierPathItem("references"),
				new ElementPathItem("brand")
			)
		));

		assertNotNull(result.referencePartials());
		assertTrue(
			containsPartial(result.referencePartials(), ReferenceIdentityPartial.GET_REFERENCE_KEY)
		);
		assertTrue(
			containsPartial(
				result.referencePartials(), ReferenceIdentityPartial.GET_REFERENCED_ENTITY_TYPE
			)
		);
		assertTrue(
			containsPartial(
				result.referencePartials(), ReferenceIdentityPartial.GET_REFERENCE_CARDINALITY
			)
		);
		assertTrue(
			containsPartial(result.referencePartials(), ReferenceIdentityPartial.GET_REFERENCE_SCHEMA)
		);
		assertTrue(
			containsPartial(
				result.referencePartials(), ReferenceIdentityPartial.GET_REFERENCED_PRIMARY_KEY
			)
		);
		assertTrue(
			containsPartial(result.referencePartials(), ReferenceIdentityPartial.GET_REFERENCE_NAME)
		);
		assertTrue(
			containsPartial(
				result.referencePartials(), ReferenceIdentityPartial.GET_REFERENCE_SCHEMA_OR_THROW
			)
		);
	}

	@Test
	@DisplayName("bare $entity path produces minimal result with just schema")
	void shouldProduceMinimalResultForBareEntityPath() {
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(new VariablePathItem("entity"))
		));

		assertFalse(result.entityRecipe().needsEntityBody());
		assertFalse(result.entityRecipe().needsGlobalAttributes());
		assertFalse(result.entityRecipe().needsReferences());
		assertNull(result.referencePartials());
	}

	@Test
	@DisplayName("$reference.referencedEntity.attributes['name'] → nested entity attribute partials produced")
	void shouldMapReferenceVariableReferencedEntityAttributePath() {
		// For $reference.referencedEntity.attributes['name'], the path is:
		// [0]=$reference, [1]=referencedEntity, [2]=attributes, [3]=['name']
		final MappingResult result = PathToPartialMapper.map(List.of(
			path(
				new VariablePathItem("reference"),
				new IdentifierPathItem("referencedEntity"),
				new IdentifierPathItem("attributes"),
				new ElementPathItem("name")
			)
		));

		// The referencedEntity flag should be set
		assertTrue(result.needsReferencedEntityProxy(),
			"Nested referenced entity proxy flag should be set");

		// The nested entity attribute partials MUST be produced
		assertNotNull(result.referencedEntityPartials(),
			"Referenced entity partials should not be null");
		assertTrue(
			containsPartial(result.referencedEntityPartials(), EntityAttributePartial.GET_ATTRIBUTE),
			"Nested entity GET_ATTRIBUTE partial should be present for $reference.referencedEntity.attributes path"
		);
		assertTrue(
			result.referencedEntityRecipe().needsGlobalAttributes(),
			"Nested entity recipe should require global attributes"
		);
	}
}
