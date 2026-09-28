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

package io.evitadb.core.query.filter.translator.reference;

import io.evitadb.api.query.filter.GroupHaving;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.core.exception.ReferenceComponentNotIndexedException;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.groupHaving;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the error messages of the indexed-component guards in {@link HavingTranslatorHelper} for the shapes the
 * functional tests never reach: a reflected reference that inherits its indexed components, whose remedy must point
 * at the reference it reflects rather than tell the user to add a component it has no list of its own to add to, and
 * a group-component refusal in which every queried scope lacks the component, which leaves no scope to narrow the
 * query to.
 *
 * The schemas are built directly - the map overload of `_internalBuild` the Kryo reader uses, bound with the plain
 * {@link ReflectedReferenceSchema#withReferencedSchema} catalog load performs - because these are shapes a stored
 * catalog loads with, and the guards judge the schema alone.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("HavingTranslatorHelper - indexed-component guards")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REFERENCE)
class HavingTranslatorHelperTest {

	private static final String PRODUCT = "Product";
	private static final String CATEGORY = "Category";
	private static final String CATEGORY_GROUP = "CategoryGroup";
	/**
	 * The reference on the product collection every fixture declares.
	 */
	private static final String REF_CATEGORIES = "categories";
	/**
	 * The reflected reference on the category collection, mirroring {@link #REF_CATEGORIES}.
	 */
	private static final String REF_PRODUCTS = "productsInCategory";

	@Nested
	@DisplayName("Entity component")
	class EntityComponent {

		/**
		 * A reflected reference inheriting its components has no component list of its own, so the remedy must send
		 * the user to the reference it reflects, or to declaring the components explicitly - not ask them to add a
		 * component to a list that does not exist.
		 */
		@Test
		@DisplayName("should point a reflected reference inheriting its components at the reference it reflects")
		void shouldPointAnInheritingReflectedReferenceAtItsSource() {
			final ReflectedReferenceSchema reflected = inheritingReflectedReference(
				liveOnlySource(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY)
			);
			assertTrue(
				reflected.isIndexedComponentsInherited(),
				"The premise is a reflected reference inheriting its components"
			);

			final ReferenceComponentNotIndexedException exception = assertThrows(
				ReferenceComponentNotIndexedException.class,
				() -> HavingTranslatorHelper.assertEntityComponentIndexed(
					EntitySchema._internalBuild(CATEGORY), reflected, Scope.LIVE, EnumSet.of(Scope.LIVE)
				)
			);

			final String message = exception.getMessage();
			assertTrue(
				message.contains(
					"inherits its indexed components from reference `" + REF_CATEGORIES +
						"` of entity `" + PRODUCT + "`"
				),
				"The refusal must point at the reference the components are inherited from, was: " + message
			);
			assertTrue(
				message.contains("declare the indexed components of reference `" + REF_PRODUCTS + "` explicitly"),
				"The refusal must offer declaring the components explicitly, was: " + message
			);
			assertTrue(
				message.contains("REFERENCED_ENTITY"),
				"The refusal must name the missing component, was: " + message
			);
			assertFalse(
				message.contains("Fix the schema: add `REFERENCED_ENTITY` to `indexedComponentsInScopes`"),
				"A reference that declares no components of its own cannot have one added to them, was: " + message
			);
		}

		/**
		 * A scope the reference is not indexed in is left to the lookups, which refuse it with a message naming the
		 * real problem - the reference is not indexed there at all.
		 */
		@Test
		@DisplayName("should stay silent for a scope the reference is not indexed in")
		void shouldStaySilentForAScopeTheReferenceIsNotIndexedIn() {
			final ReferenceSchema reference = liveOnlySource(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY);

			assertDoesNotThrow(
				() -> HavingTranslatorHelper.assertEntityComponentIndexed(
					EntitySchema._internalBuild(PRODUCT), reference, Scope.ARCHIVED, EnumSet.of(Scope.ARCHIVED)
				)
			);
		}

	}

	@Nested
	@DisplayName("Group component")
	class GroupComponent {

		/**
		 * The source carries the default components - `REFERENCED_ENTITY` alone - so a `groupHaving` over a reflected
		 * reference inheriting them is refused in ordinary use, not only over legacy data, and the remedy must point
		 * at the source.
		 */
		@Test
		@DisplayName("should point a reflected reference inheriting its components at the reference it reflects")
		void shouldPointAnInheritingReflectedReferenceAtItsSource() {
			final ReflectedReferenceSchema reflected = inheritingReflectedReference(
				liveOnlySource(ReferenceIndexedComponents.REFERENCED_ENTITY)
			);
			assertTrue(
				reflected.isIndexedComponentsInherited(),
				"The premise is a reflected reference inheriting its components"
			);

			final ReferenceComponentNotIndexedException exception = assertThrows(
				ReferenceComponentNotIndexedException.class,
				() -> HavingTranslatorHelper.assertGroupComponentIndexed(
					groupHavingCode(), EntitySchema._internalBuild(CATEGORY), reflected, EnumSet.of(Scope.LIVE)
				)
			);

			final String message = exception.getMessage();
			assertTrue(
				message.contains("inherits its indexed components from reference `" + REF_CATEGORIES + "`"),
				"The refusal must point at the reference the components are inherited from, was: " + message
			);
			assertTrue(
				message.contains("REFERENCED_GROUP_ENTITY") && message.contains("explicitly"),
				"The refusal must offer declaring the group component explicitly, was: " + message
			);
			assertFalse(
				message.contains("Add `REFERENCED_GROUP_ENTITY` to `indexedComponentsInScopes`"),
				"A reference that declares no components of its own cannot have one added to them, was: " + message
			);
		}

		/**
		 * Every queried scope lacks the group component, so the refusal names them all and offers no narrowing - there
		 * is no queried scope left that could answer.
		 */
		@Test
		@DisplayName("should list every missing scope and offer no workaround when no queried scope can answer")
		void shouldListEveryMissingScopeAndOfferNoWorkaroundWhenNoQueriedScopeCanAnswer() {
			final Map<Scope, ReferenceIndexType> bothScopes = new EnumMap<>(Scope.class);
			bothScopes.put(Scope.LIVE, ReferenceIndexType.FOR_FILTERING);
			bothScopes.put(Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING);
			final ReferenceSchema reference = reference(
				bothScopes,
				Map.of(
					Scope.LIVE, Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY),
					Scope.ARCHIVED, Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY)
				)
			);

			final ReferenceComponentNotIndexedException exception = assertThrows(
				ReferenceComponentNotIndexedException.class,
				() -> HavingTranslatorHelper.assertGroupComponentIndexed(
					groupHavingCode(), EntitySchema._internalBuild(PRODUCT), reference,
					EnumSet.of(Scope.LIVE, Scope.ARCHIVED)
				)
			);

			final String message = exception.getMessage();
			assertTrue(
				message.contains("`LIVE, ARCHIVED`"),
				"The refusal must list every scope lacking the component, in declaration order, was: " + message
			);
			assertFalse(
				message.contains("As a workaround"),
				"No queried scope can answer, so the refusal must offer no narrowing workaround, was: " + message
			);
		}

	}

	/**
	 * Builds the `groupHaving` the group guard quotes back in its message.
	 *
	 * @return the constraint
	 */
	@Nonnull
	private static GroupHaving groupHavingCode() {
		return groupHaving(attributeEquals("code", "a"));
	}

	/**
	 * Builds {@link #REF_CATEGORIES} indexed in {@link Scope#LIVE} alone with the given components.
	 *
	 * @param components the components of the LIVE scope
	 * @return the reference schema
	 */
	@Nonnull
	private static ReferenceSchema liveOnlySource(@Nonnull ReferenceIndexedComponents... components) {
		final Map<Scope, ReferenceIndexType> liveOnly = new EnumMap<>(Scope.class);
		liveOnly.put(Scope.LIVE, ReferenceIndexType.FOR_FILTERING);
		return reference(liveOnly, Map.of(Scope.LIVE, Set.of(components)));
	}

	/**
	 * Builds {@link #REF_CATEGORIES} from product to category, with a referenced group type, exactly as the Kryo
	 * reader does - the components are taken verbatim, nothing is defaulted.
	 *
	 * @param indexedInScopes           the index type per scope
	 * @param indexedComponentsInScopes the components per scope
	 * @return the reference schema
	 */
	@Nonnull
	private static ReferenceSchema reference(
		@Nonnull Map<Scope, ReferenceIndexType> indexedInScopes,
		@Nonnull Map<Scope, Set<ReferenceIndexedComponents>> indexedComponentsInScopes
	) {
		return ReferenceSchema._internalBuild(
			REF_CATEGORIES, NamingConvention.generate(REF_CATEGORIES),
			null, null,
			Cardinality.ZERO_OR_MORE,
			CATEGORY, Collections.emptyMap(), true,
			CATEGORY_GROUP, Collections.emptyMap(), true,
			indexedInScopes,
			indexedComponentsInScopes,
			Collections.emptySet(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			ConflictResolutionOverride.INHERITED
		);
	}

	/**
	 * Builds {@link #REF_PRODUCTS} inheriting both its indexed scopes and its indexed components, bound to `source`
	 * the way catalog load binds it.
	 *
	 * @param source the reference the reflected one mirrors
	 * @return the bound reflected reference schema
	 */
	@Nonnull
	private static ReflectedReferenceSchema inheritingReflectedReference(@Nonnull ReferenceSchema source) {
		return ReflectedReferenceSchema._internalBuild(
			REF_PRODUCTS, NamingConvention.generate(REF_PRODUCTS),
			null, null,
			PRODUCT, REF_CATEGORIES,
			null,
			null, null, null, null, null, null,
			Collections.emptyMap(),
			Collections.emptyMap(),
			AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
			null
		).withReferencedSchema(source);
	}

}
