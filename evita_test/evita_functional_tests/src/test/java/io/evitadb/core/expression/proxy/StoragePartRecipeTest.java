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
import io.evitadb.dataType.expression.ExpressionNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StoragePartRecipe} verifying the record construction, the EMPTY constant, and the recipe flags
 * produced by {@link ExpressionProxyFactory#buildDescriptor(ExpressionNode)} for various expression paths.
 */
@DisplayName("Storage part recipe")
class StoragePartRecipeTest {

	@Test
	@DisplayName("EMPTY recipe requires no storage parts")
	void shouldRequireNoStoragePartsForEmptyRecipe() {
		final StoragePartRecipe recipe = StoragePartRecipe.EMPTY;

		assertFalse(recipe.needsEntityBody(), "EMPTY should not need entity body");
		assertFalse(recipe.needsGlobalAttributes(), "EMPTY should not need global attributes");
		assertTrue(recipe.neededAttributeLocales().isEmpty(), "EMPTY should have no attribute locales");
		assertFalse(recipe.needsReferences(), "EMPTY should not need references");
		assertTrue(recipe.neededAssociatedDataNames().isEmpty(), "EMPTY should have no associated data names");
		assertTrue(
			recipe.neededAssociatedDataLocales().isEmpty(), "EMPTY should have no associated data locales"
		);
	}

	@Test
	@DisplayName("recipe with all flags set")
	void shouldSetAllFlagsInRecipe() {
		final StoragePartRecipe recipe = new StoragePartRecipe(
			true, true,
			Set.of(Locale.ENGLISH, Locale.GERMAN),
			true,
			Set.of("description", "specs"),
			Set.of(Locale.FRENCH)
		);

		assertTrue(recipe.needsEntityBody());
		assertTrue(recipe.needsGlobalAttributes());
		assertEquals(Set.of(Locale.ENGLISH, Locale.GERMAN), recipe.neededAttributeLocales());
		assertTrue(recipe.needsReferences());
		assertEquals(Set.of("description", "specs"), recipe.neededAssociatedDataNames());
		assertEquals(Set.of(Locale.FRENCH), recipe.neededAssociatedDataLocales());
	}

	/**
	 * Tests verifying that {@link PathToPartialMapper} produces {@link StoragePartRecipe} instances with correct
	 * flags for various expression paths. Each test parses an expression via
	 * {@link ExpressionProxyFactory#buildDescriptor(ExpressionNode)} and asserts the resulting entity recipe flags.
	 */
	@Nested
	@DisplayName("Recipe flags from expression paths")
	class RecipeFlagsFromExpressionPathsTest {

		@Test
		@DisplayName("primary key path sets needsEntityBody flag")
		void shouldSetNeedsEntityBodyForPrimaryKeyPath() {
			final StoragePartRecipe recipe = buildEntityRecipe("$entity.primaryKey > 0");

			assertTrue(
				recipe.needsEntityBody(),
				"Primary key access should require entity body"
			);
		}

		@Test
		@DisplayName("parent path sets needsEntityBody flag")
		void shouldSetNeedsEntityBodyForParentPath() {
			final StoragePartRecipe recipe = buildEntityRecipe("true || $entity.parent");

			assertTrue(
				recipe.needsEntityBody(),
				"Parent access should require entity body"
			);
		}

		@Test
		@DisplayName("attribute path sets needsGlobalAttributes flag")
		void shouldSetNeedsGlobalAttributesForAttributePath() {
			final StoragePartRecipe recipe = buildEntityRecipe("$entity.attributes['code'] == 'ABC'");

			assertTrue(
				recipe.needsGlobalAttributes(),
				"Attribute access should require global attributes"
			);
		}

		@Test
		@DisplayName("localized attribute path sets neededAttributeLocales")
		void shouldSetNeededAttributeLocalesForLocalizedPath() {
			final StoragePartRecipe recipe = buildEntityRecipe(
				"true || $entity.localizedAttributes['name']"
			);

			assertFalse(
				recipe.neededAttributeLocales().isEmpty(),
				"Localized attribute access should populate neededAttributeLocales"
			);
		}

		@Test
		@DisplayName("references path sets needsReferences flag")
		void shouldSetNeedsReferencesForReferencesPath() {
			final StoragePartRecipe recipe = buildEntityRecipe(
				"true || $entity.references['brand']"
			);

			assertTrue(
				recipe.needsReferences(),
				"References access should require references storage part"
			);
		}

		@Test
		@DisplayName("associated data path populates neededAssociatedDataNames")
		void shouldSetNeededAssociatedDataNamesForAssociatedDataPath() {
			final StoragePartRecipe recipe = buildEntityRecipe(
				"true || $entity.associatedData['desc']"
			);

			assertTrue(
				recipe.neededAssociatedDataNames().contains("desc"),
				"Associated data access should include 'desc' in neededAssociatedDataNames"
			);
		}

		@Test
		@DisplayName("attribute-only expression excludes unnecessary storage parts")
		void shouldExcludeUnnecessaryStoragePartsForAttributeOnlyExpression() {
			final StoragePartRecipe recipe = buildEntityRecipe("$entity.attributes['code'] == 'ABC'");

			assertFalse(
				recipe.needsReferences(),
				"Attribute-only expression should not need references"
			);
			assertTrue(
				recipe.neededAssociatedDataNames().isEmpty(),
				"Attribute-only expression should not need associated data"
			);
			assertFalse(
				recipe.needsEntityBody(),
				"Attribute-only expression should not need entity body"
			);
		}
	}

	/**
	 * Tests verifying locale sentinel behavior in recipe generation for localized data paths.
	 */
	@Nested
	@DisplayName("Locale sentinel behavior")
	class LocaleSentinelBehaviorTest {

		@Test
		@DisplayName("localized attribute path includes Locale.ROOT sentinel in neededAttributeLocales")
		void shouldIncludeLocaleRootSentinelForLocalizedAttributes() {
			// Locale.ROOT sentinel signals the instantiator to resolve actual entity locales from the body part
			final StoragePartRecipe recipe = buildEntityRecipe(
				"true || $entity.localizedAttributes['description']"
			);

			assertTrue(
				recipe.neededAttributeLocales().contains(Locale.ROOT),
				"neededAttributeLocales should contain Locale.ROOT sentinel for localized attributes"
			);
		}

		@Test
		@DisplayName("localized associated data path populates neededAssociatedDataLocales with Locale.ROOT sentinel")
		void shouldPopulateAssociatedDataLocalesForLocalizedPath() {
			final StoragePartRecipe recipe = buildEntityRecipe(
				"true || $entity.localizedAssociatedData['label']"
			);

			assertTrue(
				recipe.neededAssociatedDataLocales().contains(Locale.ROOT),
				"neededAssociatedDataLocales should contain Locale.ROOT sentinel for localized associated data"
			);
			assertTrue(
				recipe.neededAssociatedDataNames().contains("label"),
				"neededAssociatedDataNames should contain the data name"
			);
			assertTrue(
				recipe.needsEntityBody(),
				"Entity body should be needed to resolve available locales at runtime"
			);
		}
	}

	/**
	 * Parses the given expression string, builds a proxy descriptor, and returns the entity recipe.
	 *
	 * @param expression the expression string to parse
	 * @return the entity {@link StoragePartRecipe} from the built descriptor
	 */
	@Nonnull
	private static StoragePartRecipe buildEntityRecipe(@Nonnull String expression) {
		final ExpressionNode expressionNode = ExpressionFactory.parse(expression);
		final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expressionNode);
		return descriptor.entityRecipe();
	}
}
