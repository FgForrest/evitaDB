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

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.core.expression.proxy.entity.EntityAttributePartial;
import io.evitadb.core.expression.proxy.entity.EntityPrimaryKeyPartial;
import io.evitadb.core.expression.proxy.entity.EntityReferencesPartial;
import io.evitadb.core.expression.proxy.entity.EntitySchemaPartial;
import io.evitadb.core.expression.proxy.entity.EntityVersionAndDroppablePartial;
import io.evitadb.core.expression.proxy.reference.GroupEntityPartial;
import io.evitadb.core.expression.proxy.reference.ReferenceAttributePartial;
import io.evitadb.core.expression.proxy.reference.ReferenceIdentityPartial;
import io.evitadb.core.expression.proxy.reference.ReferencedEntityPartial;
import io.evitadb.dataType.expression.ExpressionNode;
import one.edee.oss.proxycian.PredicateMethodClassification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ExpressionProxyFactory} verifying that the full pipeline from expression string to
 * {@link ExpressionProxyDescriptor} produces correct partial arrays and storage part recipes.
 */
@DisplayName("Expression proxy factory")
class ExpressionProxyFactoryTest {

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
	@DisplayName("Should produce attribute partials and global attributes recipe for attribute expression")
	void shouldProduceAttributePartialsForAttributeExpression() {
		final ExpressionNode expression = ExpressionFactory.parse("$entity.attributes['code'] > 5");
		final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);

		assertTrue(
			descriptor.entityRecipe().needsGlobalAttributes(),
			"Global attributes should be needed"
		);
		assertFalse(
			descriptor.entityRecipe().needsEntityBody(),
			"Body should not be needed for attribute-only expression"
		);
		assertTrue(
			containsPartial(descriptor.entityPartials(), EntityAttributePartial.GET_ATTRIBUTE),
			"Attribute partial should be present"
		);
		assertNull(
			descriptor.referencePartials(),
			"No reference partials expected for attribute-only expression"
		);
		assertFalse(descriptor.needsReferencedEntityProxy());
		assertFalse(descriptor.needsGroupEntityProxy());
	}

	@Test
	@DisplayName("Should produce body recipe and PK partial for primary key expression")
	void shouldProduceBodyRecipeForPrimaryKeyExpression() {
		final ExpressionNode expression = ExpressionFactory.parse("$entity.primaryKey > 0");
		final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);

		assertTrue(
			descriptor.entityRecipe().needsEntityBody(),
			"Entity body should be needed for primaryKey access"
		);
		assertTrue(
			containsPartial(descriptor.entityPartials(), EntityPrimaryKeyPartial.GET_PRIMARY_KEY),
			"PrimaryKeyPartial should be present"
		);
	}

	@Test
	@DisplayName("Should produce reference partials and references recipe for reference expression")
	void shouldProduceReferencePartialsForReferenceExpression() {
		final ExpressionNode expression = ExpressionFactory.parse(
			"$entity.references['brand'].attributes['order'] > 0"
		);
		final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);

		assertTrue(
			descriptor.entityRecipe().needsReferences(),
			"References should be needed"
		);
		assertNotNull(
			descriptor.referencePartials(),
			"Reference partials should be non-null"
		);
		assertTrue(
			containsPartial(descriptor.entityPartials(), EntityReferencesPartial.GET_REFERENCES_BY_NAME),
			"Entity references partial should be present"
		);
		assertTrue(
			containsPartial(descriptor.referencePartials(), ReferenceAttributePartial.GET_ATTRIBUTE),
			"Reference attribute partial should be present"
		);
		assertTrue(
			containsPartial(descriptor.referencePartials(), ReferenceIdentityPartial.GET_REFERENCE_KEY),
			"Reference identity should always be included"
		);
	}

	@Test
	@DisplayName("Should set needsReferencedEntityProxy flag for referenced entity sub-path")
	void shouldSetNestedFlagForReferencedEntityExpression() {
		final ExpressionNode expression = ExpressionFactory.parse(
			"true || $entity.references['brand'].*[$.referencedEntity]"
		);
		final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);

		assertTrue(
			descriptor.needsReferencedEntityProxy(),
			"needsReferencedEntityProxy should be true"
		);
		assertNotNull(descriptor.referencePartials());
		assertTrue(
			containsPartial(descriptor.referencePartials(), ReferencedEntityPartial.GET_REFERENCED_ENTITY),
			"ReferencedEntityPartial should be present"
		);
	}

	@Test
	@DisplayName("Should set needsGroupEntityProxy flag for group entity sub-path")
	void shouldSetNestedFlagForGroupEntityExpression() {
		final ExpressionNode expression = ExpressionFactory.parse(
			"true || $entity.references['brand'].*[$.groupEntity]"
		);
		final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);

		assertTrue(
			descriptor.needsGroupEntityProxy(),
			"needsGroupEntityProxy should be true"
		);
		assertNotNull(descriptor.referencePartials());
		assertTrue(
			containsPartial(descriptor.referencePartials(), GroupEntityPartial.GET_GROUP_ENTITY),
			"GroupEntityPartial should be present"
		);
	}

	@Test
	@DisplayName("Should produce union of partials for mixed entity and reference expression")
	void shouldProduceUnionOfPartialsForMixedExpression() {
		final ExpressionNode expression = ExpressionFactory.parse(
			"$entity.attributes['code'] + $entity.references['brand'].attributes['order']"
		);
		final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);

		// entity-level
		assertTrue(descriptor.entityRecipe().needsGlobalAttributes());
		assertTrue(descriptor.entityRecipe().needsReferences());
		assertTrue(
			containsPartial(descriptor.entityPartials(), EntityAttributePartial.GET_ATTRIBUTE),
			"Entity attribute partial should be present"
		);
		assertTrue(
			containsPartial(descriptor.entityPartials(), EntityReferencesPartial.GET_REFERENCES_BY_NAME),
			"Entity references partial should be present"
		);

		// reference-level
		assertNotNull(descriptor.referencePartials());
		assertTrue(
			containsPartial(descriptor.referencePartials(), ReferenceAttributePartial.GET_ATTRIBUTE),
			"Reference attribute partial should be present"
		);
	}

	@Test
	@DisplayName("Should produce minimal descriptor with always-included partials for constant expression")
	void shouldProduceMinimalDescriptorForConstantExpression() {
		final ExpressionNode expression = ExpressionFactory.parse("1 + 2 > 3");
		final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);

		assertNull(
			descriptor.referencePartials(),
			"No reference partials for constant expression"
		);
		assertFalse(descriptor.entityRecipe().needsEntityBody());
		assertFalse(descriptor.entityRecipe().needsGlobalAttributes());
		assertFalse(descriptor.entityRecipe().needsReferences());
		assertFalse(descriptor.needsReferencedEntityProxy());
		assertFalse(descriptor.needsGroupEntityProxy());

		// always-included still present
		assertTrue(
			containsPartial(descriptor.entityPartials(), EntitySchemaPartial.GET_SCHEMA),
			"Schema partial must always be included"
		);
		assertTrue(
			containsPartial(descriptor.entityPartials(), CatchAllPartial.INSTANCE),
			"CatchAll must always be included"
		);
	}

	@Test
	@DisplayName("Should always include schema and version partials in every descriptor")
	void shouldAlwaysIncludeSchemaAndVersionPartials() {
		final ExpressionNode expression = ExpressionFactory.parse("$entity.primaryKey > 0");
		final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);

		assertTrue(
			containsPartial(descriptor.entityPartials(), EntitySchemaPartial.GET_SCHEMA),
			"GET_SCHEMA must always be present"
		);
		assertTrue(
			containsPartial(descriptor.entityPartials(), EntitySchemaPartial.GET_TYPE),
			"GET_TYPE must always be present"
		);
		assertTrue(
			containsPartial(descriptor.entityPartials(), EntityVersionAndDroppablePartial.VERSION),
			"VERSION must always be present"
		);
		assertTrue(
			containsPartial(descriptor.entityPartials(), EntityVersionAndDroppablePartial.DROPPED),
			"DROPPED must always be present"
		);
		assertTrue(
			containsPartial(descriptor.entityPartials(), CatchAllPartial.OBJECT_METHODS),
			"OBJECT_METHODS must always be present"
		);
		assertTrue(
			containsPartial(descriptor.entityPartials(), CatchAllPartial.INSTANCE),
			"INSTANCE must always be present"
		);
	}

	@Test
	@DisplayName("Should produce nested entity partials for referenced entity expression")
	void shouldProduceNestedEntityPartialsForReferencedEntityExpression() {
		final ExpressionNode expression = ExpressionFactory.parse(
			"$entity.references['brand'].referencedEntity.attributes['name'] == 'Nike'"
		);
		final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);

		assertTrue(
			descriptor.needsReferencedEntityProxy(),
			"needsReferencedEntityProxy should be true"
		);
		assertNotNull(
			descriptor.referencedEntityPartials(),
			"Referenced entity partials should not be null"
		);
		assertNotNull(
			descriptor.referencedEntityRecipe(),
			"Referenced entity recipe should not be null"
		);
		assertTrue(
			descriptor.referencedEntityRecipe().needsGlobalAttributes(),
			"Nested entity recipe should need global attributes for attribute access"
		);
		assertFalse(
			descriptor.referencedEntityRecipe().needsEntityBody(),
			"Nested entity recipe should not need body when only attributes are accessed"
		);
		assertFalse(
			descriptor.referencedEntityRecipe().needsReferences(),
			"Nested entity recipe should not need references"
		);
		assertTrue(
			containsPartial(descriptor.referencePartials(), ReferencedEntityPartial.GET_REFERENCED_ENTITY),
			"ReferencedEntityPartial should be present in reference partials"
		);
		assertTrue(
			containsPartial(descriptor.referencedEntityPartials(), EntityAttributePartial.GET_ATTRIBUTE),
			"EntityAttributePartial should be present in nested entity partials"
		);
	}
}
