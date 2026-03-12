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

import io.evitadb.exception.ExpressionEvaluationException;
import one.edee.oss.proxycian.PredicateMethodClassification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ExpressionProxyDescriptor} verifying that each `*OrThrowException()` method correctly returns the
 * value when present or throws {@link ExpressionEvaluationException} when null.
 */
@DisplayName("Expression proxy descriptor")
class ExpressionProxyDescriptorTest {

	/**
	 * Creates a minimal entity partials array for descriptor construction.
	 *
	 * @return a non-empty array with the CatchAllPartial instances
	 */
	@SuppressWarnings("rawtypes")
	@Nonnull
	private static PredicateMethodClassification<?, ?, ?>[] minimalEntityPartials() {
		return new PredicateMethodClassification[]{
			CatchAllPartial.OBJECT_METHODS,
			CatchAllPartial.INSTANCE
		};
	}

	/**
	 * Tests for {@link ExpressionProxyDescriptor#referencePartialsOrThrowException()}.
	 */
	@Nested
	@DisplayName("Reference partials")
	class ReferencePartialsTest {

		@Test
		@DisplayName("Should return reference partials when present")
		void shouldReturnReferencePartialsWhenPresent() {
			final PredicateMethodClassification<?, ?, ?>[] refPartials = minimalEntityPartials();
			final ExpressionProxyDescriptor descriptor = new ExpressionProxyDescriptor(
				minimalEntityPartials(), refPartials, StoragePartRecipe.EMPTY,
				false, false, null, null, null, null
			);

			final PredicateMethodClassification<?, ?, ?>[] result =
				descriptor.referencePartialsOrThrowException();

			assertSame(refPartials, result, "Should return the same reference partials array");
		}

		@Test
		@DisplayName("Should throw ExpressionEvaluationException when reference partials are null")
		void shouldThrowExpressionEvaluationExceptionWhenReferencePartialsNull() {
			final ExpressionProxyDescriptor descriptor = new ExpressionProxyDescriptor(
				minimalEntityPartials(), null, StoragePartRecipe.EMPTY,
				false, false, null, null, null, null
			);

			final ExpressionEvaluationException exception = assertThrows(
				ExpressionEvaluationException.class,
				descriptor::referencePartialsOrThrowException
			);
			assertTrue(
				exception.getPrivateMessage().contains("Reference proxy partials"),
				"Private message should mention 'Reference proxy partials'"
			);
		}
	}

	/**
	 * Tests for {@link ExpressionProxyDescriptor#referencedEntityPartialsOrThrowException()}.
	 */
	@Nested
	@DisplayName("Referenced entity partials")
	class ReferencedEntityPartialsTest {

		@Test
		@DisplayName("Should return referenced entity partials when present")
		void shouldReturnReferencedEntityPartialsWhenPresent() {
			final PredicateMethodClassification<?, ?, ?>[] refEntityPartials = minimalEntityPartials();
			final ExpressionProxyDescriptor descriptor = new ExpressionProxyDescriptor(
				minimalEntityPartials(), null, StoragePartRecipe.EMPTY,
				true, false, refEntityPartials, null, null, null
			);

			final PredicateMethodClassification<?, ?, ?>[] result =
				descriptor.referencedEntityPartialsOrThrowException();

			assertSame(refEntityPartials, result,
				"Should return the same referenced entity partials array");
		}

		@Test
		@DisplayName("Should throw ExpressionEvaluationException when referenced entity partials are null")
		void shouldThrowExpressionEvaluationExceptionWhenReferencedEntityPartialsNull() {
			final ExpressionProxyDescriptor descriptor = new ExpressionProxyDescriptor(
				minimalEntityPartials(), null, StoragePartRecipe.EMPTY,
				true, false, null, null, null, null
			);

			final ExpressionEvaluationException exception = assertThrows(
				ExpressionEvaluationException.class,
				descriptor::referencedEntityPartialsOrThrowException
			);
			assertTrue(
				exception.getPrivateMessage().contains("Referenced entity proxy partials"),
				"Private message should mention 'Referenced entity proxy partials'"
			);
		}
	}

	/**
	 * Tests for {@link ExpressionProxyDescriptor#groupEntityPartialsOrThrowException()}.
	 */
	@Nested
	@DisplayName("Group entity partials")
	class GroupEntityPartialsTest {

		@Test
		@DisplayName("Should return group entity partials when present")
		void shouldReturnGroupEntityPartialsWhenPresent() {
			final PredicateMethodClassification<?, ?, ?>[] grpEntityPartials = minimalEntityPartials();
			final ExpressionProxyDescriptor descriptor = new ExpressionProxyDescriptor(
				minimalEntityPartials(), null, StoragePartRecipe.EMPTY,
				false, true, null, grpEntityPartials, null, null
			);

			final PredicateMethodClassification<?, ?, ?>[] result =
				descriptor.groupEntityPartialsOrThrowException();

			assertSame(grpEntityPartials, result,
				"Should return the same group entity partials array");
		}

		@Test
		@DisplayName("Should throw ExpressionEvaluationException when group entity partials are null")
		void shouldThrowExpressionEvaluationExceptionWhenGroupEntityPartialsNull() {
			final ExpressionProxyDescriptor descriptor = new ExpressionProxyDescriptor(
				minimalEntityPartials(), null, StoragePartRecipe.EMPTY,
				false, true, null, null, null, null
			);

			final ExpressionEvaluationException exception = assertThrows(
				ExpressionEvaluationException.class,
				descriptor::groupEntityPartialsOrThrowException
			);
			assertTrue(
				exception.getPrivateMessage().contains("Group entity proxy partials"),
				"Private message should mention 'Group entity proxy partials'"
			);
		}
	}

	/**
	 * Tests for {@link ExpressionProxyDescriptor#referencedEntityRecipeOrThrowException()}.
	 */
	@Nested
	@DisplayName("Referenced entity recipe")
	class ReferencedEntityRecipeTest {

		@Test
		@DisplayName("Should return referenced entity recipe when present")
		void shouldReturnReferencedEntityRecipeWhenPresent() {
			final StoragePartRecipe recipe = StoragePartRecipe.EMPTY;
			final ExpressionProxyDescriptor descriptor = new ExpressionProxyDescriptor(
				minimalEntityPartials(), null, StoragePartRecipe.EMPTY,
				true, false, null, null, recipe, null
			);

			final StoragePartRecipe result = descriptor.referencedEntityRecipeOrThrowException();

			assertSame(recipe, result, "Should return the same recipe instance");
		}

		@Test
		@DisplayName("Should throw ExpressionEvaluationException when referenced entity recipe is null")
		void shouldThrowExpressionEvaluationExceptionWhenReferencedEntityRecipeNull() {
			final ExpressionProxyDescriptor descriptor = new ExpressionProxyDescriptor(
				minimalEntityPartials(), null, StoragePartRecipe.EMPTY,
				true, false, null, null, null, null
			);

			final ExpressionEvaluationException exception = assertThrows(
				ExpressionEvaluationException.class,
				descriptor::referencedEntityRecipeOrThrowException
			);
			assertTrue(
				exception.getPrivateMessage().contains("Referenced entity storage recipe"),
				"Private message should mention 'Referenced entity storage recipe'"
			);
		}
	}

	/**
	 * Tests for {@link ExpressionProxyDescriptor#groupEntityRecipeOrThrowException()}.
	 */
	@Nested
	@DisplayName("Group entity recipe")
	class GroupEntityRecipeTest {

		@Test
		@DisplayName("Should return group entity recipe when present")
		void shouldReturnGroupEntityRecipeWhenPresent() {
			final StoragePartRecipe recipe = StoragePartRecipe.EMPTY;
			final ExpressionProxyDescriptor descriptor = new ExpressionProxyDescriptor(
				minimalEntityPartials(), null, StoragePartRecipe.EMPTY,
				false, true, null, null, null, recipe
			);

			final StoragePartRecipe result = descriptor.groupEntityRecipeOrThrowException();

			assertSame(recipe, result, "Should return the same recipe instance");
		}

		@Test
		@DisplayName("Should throw ExpressionEvaluationException when group entity recipe is null")
		void shouldThrowExpressionEvaluationExceptionWhenGroupEntityRecipeNull() {
			final ExpressionProxyDescriptor descriptor = new ExpressionProxyDescriptor(
				minimalEntityPartials(), null, StoragePartRecipe.EMPTY,
				false, true, null, null, null, null
			);

			final ExpressionEvaluationException exception = assertThrows(
				ExpressionEvaluationException.class,
				descriptor::groupEntityRecipeOrThrowException
			);
			assertTrue(
				exception.getPrivateMessage().contains("Group entity storage recipe"),
				"Private message should mention 'Group entity storage recipe'"
			);
		}
	}
}
