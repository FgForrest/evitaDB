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

package io.evitadb.externalApi.api.catalog.dataApi.constraint;

import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.GroupHaving;
import io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ConstraintBuildContext;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for [ConstraintKeyBuilder] pinning the wire-format key shapes the GraphQL/REST schema builders
 * emit per descriptor.
 *
 * The tests here exercise:
 *
 * - The three documented base formats (generic, prefix-only, prefix+classifier+fullName).
 * - The parallel wire shapes of {@link EntityHaving} and {@link GroupHaving} — both use fullName
 *   `having` and rely on the property-type prefix (`entity` / `group`) to disambiguate.
 * - Defensive premise validation when a classifier is required but no supplier is provided.
 *
 * @author JNO, FG Forrest a.s. (c) 2026
 */
@Tag(EXTERNAL_API)
@Tag(QUERY)
@DisplayName("ConstraintKeyBuilder wire-format keys")
class ConstraintKeyBuilderTest {

	private ConstraintKeyBuilder keyBuilder;

	@BeforeEach
	void setUp() {
		this.keyBuilder = new ConstraintKeyBuilder();
	}

	@Nested
	@DisplayName("Base key formats")
	class BaseFormatsTest {

		@Test
		@DisplayName("should build plain key for generic constraint without classifier (root)")
		void shouldBuildPlainGenericKey() {
			// generic constraint -> empty prefix; fullName drops in unchanged
			final ConstraintDescriptor descriptor = ConstraintDescriptorProvider.getConstraint(And.class);
			final ConstraintBuildContext context = rootContext(genericProductLocator());

			final String key = ConstraintKeyBuilderTest.this.keyBuilder.build(context, descriptor, null);

			assertEquals("and", key);
		}

		@Test
		@DisplayName("should build prefix-only key for EntityHaving (property-type prefix + `having`)")
		void shouldBuildPrefixOnlyKeyForEntityHaving() {
			// EntityHaving has propertyType=ENTITY (prefix="entity") and fullName="having".
			// Rendered at REFERENCE parent (cross-domain) with no classifier — the raw join yields
			// the wire key "entityHaving".
			final ConstraintDescriptor descriptor = ConstraintDescriptorProvider.getConstraint(EntityHaving.class);
			final ConstraintBuildContext context = rootContext(referenceCategoryLocator());

			final String key = ConstraintKeyBuilderTest.this.keyBuilder.build(context, descriptor, null);

			assertEquals("entityHaving", key);
		}

		@Test
		@DisplayName("should build prefix-only key for GroupHaving (property-type prefix + `having`)")
		void shouldBuildPrefixOnlyKeyForGroupHaving() {
			// GroupHaving has propertyType=GROUP (prefix="group") and fullName="having" — mirrors
			// EntityHaving. Rendered at REFERENCE parent (cross-domain) with no classifier, the raw
			// join yields the wire key "groupHaving".
			final ConstraintDescriptor descriptor = ConstraintDescriptorProvider.getConstraint(GroupHaving.class);
			final ConstraintBuildContext context = rootContext(referenceCategoryLocator());

			final String key = ConstraintKeyBuilderTest.this.keyBuilder.build(context, descriptor, null);

			assertEquals("groupHaving", key);
		}

		@Test
		@DisplayName("should build prefix+classifier+fullName key when classifier supplied")
		void shouldBuildPropertyTypePrefixClassifierFullNameKey() {
			// AttributeEquals has ATTRIBUTE property type, requires a classifier supplier
			final ConstraintDescriptor descriptor = ConstraintDescriptorProvider.getConstraint(AttributeEquals.class);
			final ConstraintBuildContext context = rootContext(genericProductLocator());

			final String key = ConstraintKeyBuilderTest.this.keyBuilder.build(context, descriptor, () -> "code");

			assertEquals("attributeCodeEquals", key);
		}
	}

	@Nested
	@DisplayName("Premise validation")
	class PremiseValidationTest {

		@Test
		@DisplayName("should throw ExternalApiInternalError when classifier creator has no supplier")
		void shouldThrowWhenClassifierRequiredButSupplierMissing() {
			// AttributeEquals creator has a classifier parameter but no fixed implicit classifier;
			// passing null supplier must fail fast with an actionable message
			final ConstraintDescriptor descriptor = ConstraintDescriptorProvider.getConstraint(AttributeEquals.class);
			final ConstraintBuildContext context = rootContext(genericProductLocator());

			final ExternalApiInternalError ex = assertThrows(
				ExternalApiInternalError.class,
				() -> ConstraintKeyBuilderTest.this.keyBuilder.build(context, descriptor, null)
			);
			assertTrue(
				ex.getMessage().contains("requires classifier resolver"),
				"Unexpected message: " + ex.getMessage()
			);
		}
	}

	/**
	 * Builds a fresh root [ConstraintBuildContext] anchored at the given data locator — used to
	 * exercise the build-time key generation without going through schema construction.
	 *
	 * @param dataLocator initial data locator at the root of the constraint tree
	 * @return a root context with no parent locator
	 */
	@Nonnull
	private static ConstraintBuildContext rootContext(@Nonnull DataLocator dataLocator) {
		return new ConstraintBuildContext(dataLocator);
	}

	/**
	 * Convenience factory for a generic data locator targeting the standard Product entity — the
	 * canonical "neutral" parent locator for tests that don't need reference / hierarchy context.
	 *
	 * @return a generic locator over the managed Product entity
	 */
	@Nonnull
	private static GenericDataLocator genericProductLocator() {
		return new GenericDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT));
	}

	/**
	 * Convenience factory for a Product → Category reference locator — used to render constraints
	 * cross-domain so the simplification rule does not fire.
	 *
	 * @return a reference locator from Product to Category
	 */
	@Nonnull
	private static ReferenceDataLocator referenceCategoryLocator() {
		return new ReferenceDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT), Entities.CATEGORY);
	}
}
