/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ReflectedReferenceSchema}.
 */
@DisplayName("ReflectedReferenceSchema")
class ReflectedReferenceSchemaTest {

	/**
	 * Creates a standard original {@link ReferenceSchema} to serve as the reflected reference
	 * target in binding tests.
	 *
	 * @return a {@link ReferenceSchema} for entity type "Product" with LIVE scope indexed and faceted
	 */
	private static ReferenceSchema createOriginalReference() {
		return ReferenceSchema._internalBuild(
			"productRef",
			NamingConvention.generate("productRef"),
			"Original description",
			"Original deprecation",
			Cardinality.ZERO_OR_MORE,
			"Product",
			Collections.emptyMap(),
			true,
			null,
			Collections.emptyMap(),
			false,
			Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
			ReferenceSchema.defaultIndexedComponents(
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
			),
			EnumSet.of(Scope.LIVE),
			Collections.emptyMap(),
			Collections.emptyMap(),
			Collections.emptyMap()
		);
	}

	/**
	 * Creates a {@link ReferenceSchema} with the given attributes to serve as
	 * the reflected reference target in attribute inheritance tests.
	 *
	 * @param attributes the attribute schemas to include
	 * @return a {@link ReferenceSchema} with the given attributes
	 */
	private static ReferenceSchema createOriginalReferenceWithAttributes(
		Map<String, AttributeSchemaContract> attributes
	) {
		return ReferenceSchema._internalBuild(
			"productRef",
			NamingConvention.generate("productRef"),
			null, null,
			Cardinality.ZERO_OR_MORE,
			"Product",
			Collections.emptyMap(),
			true,
			null,
			Collections.emptyMap(),
			false,
			Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
			ReferenceSchema.defaultIndexedComponents(
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
			),
			Collections.emptySet(),
			Collections.emptyMap(),
			attributes,
			Collections.emptyMap()
		);
	}

	/**
	 * Creates a simple {@link AttributeSchema} with the given name and type.
	 *
	 * @param name the attribute name
	 * @param type the attribute type
	 * @return a new {@link AttributeSchema}
	 */
	private static AttributeSchema createAttribute(String name, Class<? extends java.io.Serializable> type) {
		return AttributeSchema._internalBuild(name, type, false);
	}

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("should build minimal reflected reference schema")
		void shouldBuildMinimalSchema() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected", "Brand", "brandRef"
			);

			assertEquals("reflected", schema.getName());
			assertEquals("Brand", schema.getReferencedEntityType());
			assertEquals("brandRef", schema.getReflectedReferenceName());
			assertTrue(schema.isDescriptionInherited());
			assertTrue(schema.isDeprecatedInherited());
			assertTrue(schema.isCardinalityInherited());
			assertTrue(schema.isIndexedInherited());
			assertTrue(schema.isFacetedInherited());
			assertEquals(
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				schema.getAttributesInheritanceBehavior()
			);
			assertEquals(0, schema.getAttributeInheritanceFilter().length);
			assertFalse(schema.isReflectedReferenceAvailable());
		}

		@Test
		@DisplayName("should build with explicit properties")
		void shouldBuildWithExplicitProperties() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				"Explicit description",
				"Deprecated",
				"Brand",
				"brandRef",
				Cardinality.ZERO_OR_ONE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				new Scope[]{Scope.LIVE},
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{"excluded"}
			);

			assertFalse(schema.isDescriptionInherited());
			assertFalse(schema.isDeprecatedInherited());
			assertFalse(schema.isCardinalityInherited());
			assertFalse(schema.isIndexedInherited());
			assertFalse(schema.isFacetedInherited());
			assertEquals("Explicit description", schema.getDescription());
			assertEquals("Deprecated", schema.getDeprecationNotice());
			assertEquals(Cardinality.ZERO_OR_ONE, schema.getCardinality());
			assertTrue(schema.isIndexedInScope(Scope.LIVE));
			assertTrue(schema.isFacetedInScope(Scope.LIVE));
			assertEquals(
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				schema.getAttributesInheritanceBehavior()
			);
			assertArrayEquals(new String[]{"excluded"}, schema.getAttributeInheritanceFilter());
		}

		@Test
		@DisplayName("should build with all inheritance flags via full constructor")
		void shouldBuildWithAllInheritanceFlags() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				NamingConvention.generate("reflected"),
				null, null,
				"Brand",
				NamingConvention.generate("Brand"),
				null,
				Collections.emptyMap(),
				false,
				"brandRef",
				null,
				null,
				null,
				null,
				null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				true,  // descriptionInherited
				true,  // deprecatedInherited
				true,  // cardinalityInherited
				true,  // indexedInherited
				true,  // indexedComponentsInherited
				true,  // facetedInherited
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null,
				null   // no reflected reference
			);

			assertTrue(schema.isDescriptionInherited());
			assertTrue(schema.isDeprecatedInherited());
			assertTrue(schema.isCardinalityInherited());
			assertTrue(schema.isIndexedInherited());
			assertTrue(schema.isIndexedComponentsInherited());
			assertTrue(schema.isFacetedInherited());
		}
	}

	@Nested
	@DisplayName("Inheritance flags")
	class InheritanceFlags {

		@Test
		@DisplayName("should mark description inherited when null")
		void shouldMarkDescriptionInheritedWhenNull() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null,  // description null => inherited
				null,
				"Brand",
				"brandRef",
				Cardinality.ZERO_OR_ONE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

			assertTrue(schema.isDescriptionInherited());
		}

		@Test
		@DisplayName("should mark description explicit when provided")
		void shouldMarkDescriptionExplicitWhenProvided() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				"My description",
				null,
				"Brand",
				"brandRef",
				Cardinality.ZERO_OR_ONE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

			assertFalse(schema.isDescriptionInherited());
		}

		@Test
		@DisplayName("should mark cardinality inherited when null")
		void shouldMarkCardinalityInheritedWhenNull() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null, null,
				"Brand",
				"brandRef",
				null,  // cardinality null => inherited
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

			assertTrue(schema.isCardinalityInherited());
		}

		@Test
		@DisplayName("should mark indexed inherited when null")
		void shouldMarkIndexedInheritedWhenNull() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null, null,
				"Brand",
				"brandRef",
				Cardinality.ZERO_OR_ONE,
				null,  // indexed null => inherited
				null,
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

			assertTrue(schema.isIndexedInherited());
		}

		@Test
		@DisplayName("should mark faceted inherited when null")
		void shouldMarkFacetedInheritedWhenNull() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null, null,
				"Brand",
				"brandRef",
				Cardinality.ZERO_OR_ONE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				null,  // faceted null => inherited
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

			assertTrue(schema.isFacetedInherited());
		}

		@Test
		@DisplayName("should mark indexed components inherited when both indexed and components null")
		void shouldMarkIndexedComponentsInheritedWhenNull() {
			// indexed components are only inherited when indexed scopes are also inherited (both null)
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null, null,
				"Brand",
				"brandRef",
				Cardinality.ZERO_OR_ONE,
				null,  // indexed scopes null => inherited
				null,  // indexed components null => inherited (because indexed scopes are also null)
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

			assertTrue(schema.isIndexedComponentsInherited());
		}
	}

	@Nested
	@DisplayName("Accessor guards")
	class AccessorGuards {

		@Test
		@DisplayName("should throw when description inherited and no reflected reference")
		void shouldThrowWhenDescriptionInheritedAndNoReflectedRef() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected", "Brand", "brandRef"
			);

			assertTrue(schema.isDescriptionInherited());
			assertThrows(Exception.class, schema::getDescription);
		}

		@Test
		@DisplayName("should throw when deprecation inherited and no reflected reference")
		void shouldThrowWhenDeprecationInheritedAndNoReflectedRef() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected", "Brand", "brandRef"
			);

			assertTrue(schema.isDeprecatedInherited());
			assertThrows(Exception.class, schema::getDeprecationNotice);
		}

		@Test
		@DisplayName("should throw when cardinality inherited and no reflected reference")
		void shouldThrowWhenCardinalityInheritedAndNoReflectedRef() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected", "Brand", "brandRef"
			);

			assertTrue(schema.isCardinalityInherited());
			assertThrows(Exception.class, schema::getCardinality);
		}

		@Test
		@DisplayName("should throw when group type accessed without reflected reference")
		void shouldThrowWhenGroupTypeAccessedWithoutReflectedRef() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected", "Brand", "brandRef"
			);

			// getReferencedGroupType always requires reflected reference
			assertThrows(Exception.class, schema::getReferencedGroupType);
		}

		@Test
		@DisplayName("should throw when indexed inherited and no reflected reference")
		void shouldThrowWhenIndexedInheritedAndNoReflectedRef() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected", "Brand", "brandRef"
			);

			assertTrue(schema.isIndexedInherited());
			assertThrows(Exception.class, () -> schema.isIndexedInScope(Scope.LIVE));
		}

		@Test
		@DisplayName("should throw when faceted inherited and no reflected reference")
		void shouldThrowWhenFacetedInheritedAndNoReflectedRef() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected", "Brand", "brandRef"
			);

			assertTrue(schema.isFacetedInherited());
			assertThrows(Exception.class, () -> schema.isFacetedInScope(Scope.LIVE));
		}

		@Test
		@DisplayName("should throw when attributes inherited and no reflected reference")
		void shouldThrowWhenAttributesInheritedAndNoReflectedRef() {
			// INHERIT_ALL_EXCEPT with non-empty filter requires reflected reference
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null, null,
				"Brand",
				"brandRef",
				Cardinality.ZERO_OR_ONE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{}
			);

			assertThrows(Exception.class, schema::getAttributes);
		}

		@Test
		@DisplayName("should not throw for attributes when INHERIT_ONLY_SPECIFIED with empty filter")
		void shouldNotThrowForAttributesWhenInheritOnlySpecifiedEmpty() {
			// INHERIT_ONLY_SPECIFIED with empty filter effectively means no inheritance
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected", "Brand", "brandRef"
			);

			// Should not throw because no attributes are actually inherited
			assertNotNull(schema.getAttributes());
			assertTrue(schema.getAttributes().isEmpty());
		}
	}

	@Nested
	@DisplayName("WithReferencedSchema")
	class WithReferencedSchemaTests {

		@Test
		@DisplayName("should inherit description from original reference")
		void shouldInheritDescriptionFromOriginal() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null,  // description inherited
				null,
				"Product",
				"productRef",
				Cardinality.ZERO_OR_ONE,
				null,
				null,
				null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

			final ReferenceSchema original = createOriginalReference();
			final ReflectedReferenceSchema bound = schema.withReferencedSchema(original);

			assertEquals("Original description", bound.getDescription());
			assertTrue(bound.isDescriptionInherited());
			assertTrue(bound.isReflectedReferenceAvailable());
		}

		@Test
		@DisplayName("should inherit cardinality from original reference")
		void shouldInheritCardinalityFromOriginal() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null, null,
				"Product",
				"productRef",
				null,  // cardinality inherited
				null,
				null,
				null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

			final ReferenceSchema original = createOriginalReference();
			final ReflectedReferenceSchema bound = schema.withReferencedSchema(original);

			assertEquals(Cardinality.ZERO_OR_MORE, bound.getCardinality());
			assertTrue(bound.isCardinalityInherited());
		}

		@Test
		@DisplayName("should inherit indexed scopes from original reference")
		void shouldInheritIndexedScopesFromOriginal() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null, null,
				"Product",
				"productRef",
				Cardinality.ZERO_OR_ONE,
				null,  // indexed inherited
				null,
				null,  // faceted inherited
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

			final ReferenceSchema original = createOriginalReference();
			final ReflectedReferenceSchema bound = schema.withReferencedSchema(original);

			assertTrue(bound.isIndexedInScope(Scope.LIVE));
			assertFalse(bound.isIndexedInScope(Scope.ARCHIVED));
			assertTrue(bound.isIndexedInherited());
		}

		@Test
		@DisplayName("should keep explicit values when not inherited")
		void shouldKeepExplicitValuesWhenNotInherited() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				"My own description",  // explicit
				"My own deprecation",  // explicit
				"Product",
				"productRef",
				Cardinality.ZERO_OR_ONE,  // explicit
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

			final ReferenceSchema original = createOriginalReference();
			final ReflectedReferenceSchema bound = schema.withReferencedSchema(original);

			// explicit values should be preserved
			assertEquals("My own description", bound.getDescription());
			assertEquals("My own deprecation", bound.getDeprecationNotice());
			assertEquals(Cardinality.ZERO_OR_ONE, bound.getCardinality());
			assertFalse(bound.isDescriptionInherited());
			assertFalse(bound.isDeprecatedInherited());
			assertFalse(bound.isCardinalityInherited());
		}

		@Test
		@DisplayName("should merge attributes from original reference")
		void shouldMergeAttributesFromOriginal() {
			final AttributeSchema ownAttr = createAttribute("ownAttr", String.class);
			final AttributeSchema inheritedAttr = createAttribute("inheritedAttr", Integer.class);

			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null, null,
				"Product",
				"productRef",
				Cardinality.ZERO_OR_ONE,
				null, null, null,
				Map.of("ownAttr", ownAttr),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{}
			);

			final ReferenceSchema original = createOriginalReferenceWithAttributes(
				Map.of("inheritedAttr", inheritedAttr)
			);
			final ReflectedReferenceSchema bound = schema.withReferencedSchema(original);

			final Map<String, AttributeSchemaContract> allAttrs = bound.getAttributes();
			assertEquals(2, allAttrs.size());
			assertTrue(allAttrs.containsKey("ownAttr"));
			assertTrue(allAttrs.containsKey("inheritedAttr"));
		}
	}

	@Nested
	@DisplayName("Attribute inheritance")
	class AttributeInheritanceTests {

		@Test
		@DisplayName("should inherit only specified attributes")
		void shouldInheritOnlySpecifiedAttributes() {
			final AttributeSchema attrA = createAttribute("attrA", String.class);
			final AttributeSchema attrB = createAttribute("attrB", Integer.class);

			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null, null,
				"Product",
				"productRef",
				Cardinality.ZERO_OR_ONE,
				null, null, null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				new String[]{"attrA"}
			);

			final ReferenceSchema original = createOriginalReferenceWithAttributes(
				Map.of("attrA", attrA, "attrB", attrB)
			);
			final ReflectedReferenceSchema bound = schema.withReferencedSchema(original);

			final Map<String, AttributeSchemaContract> allAttrs = bound.getAttributes();
			assertEquals(1, allAttrs.size());
			assertTrue(allAttrs.containsKey("attrA"));
			assertFalse(allAttrs.containsKey("attrB"));
		}

		@Test
		@DisplayName("should inherit all except specified attributes")
		void shouldInheritAllExceptSpecified() {
			final AttributeSchema attrA = createAttribute("attrA", String.class);
			final AttributeSchema attrB = createAttribute("attrB", Integer.class);

			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null, null,
				"Product",
				"productRef",
				Cardinality.ZERO_OR_ONE,
				null, null, null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{"attrB"}
			);

			final ReferenceSchema original = createOriginalReferenceWithAttributes(
				Map.of("attrA", attrA, "attrB", attrB)
			);
			final ReflectedReferenceSchema bound = schema.withReferencedSchema(original);

			final Map<String, AttributeSchemaContract> allAttrs = bound.getAttributes();
			assertEquals(1, allAttrs.size());
			assertTrue(allAttrs.containsKey("attrA"));
			assertFalse(allAttrs.containsKey("attrB"));
		}

		@Test
		@DisplayName("should return declared attributes only")
		void shouldReturnDeclaredAttributesOnly() {
			final AttributeSchema ownAttr = createAttribute("ownAttr", String.class);
			final AttributeSchema inheritedAttr = createAttribute("inheritedAttr", Integer.class);

			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null, null,
				"Product",
				"productRef",
				Cardinality.ZERO_OR_ONE,
				null, null, null,
				Map.of("ownAttr", ownAttr),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{}
			);

			final ReferenceSchema original = createOriginalReferenceWithAttributes(
				Map.of("inheritedAttr", inheritedAttr)
			);
			final ReflectedReferenceSchema bound = schema.withReferencedSchema(original);

			// getDeclaredAttributes should exclude inherited ones
			final Map<String, AttributeSchemaContract> declared = bound.getDeclaredAttributes();
			assertEquals(1, declared.size());
			assertTrue(declared.containsKey("ownAttr"));
			assertFalse(declared.containsKey("inheritedAttr"));
		}

		@Test
		@DisplayName("should return declared sortable compounds only")
		void shouldReturnDeclaredSortableCompoundsOnly() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null, null,
				"Product",
				"productRef",
				Cardinality.ZERO_OR_ONE,
				null, null, null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

			final ReferenceSchema original = createOriginalReference();
			final ReflectedReferenceSchema bound = schema.withReferencedSchema(original);

			// with no inherited sortable compounds, declared should match all
			final Map<String, SortableAttributeCompoundSchemaContract> declared =
				bound.getDeclaredSortableAttributeCompounds();
			assertTrue(declared.isEmpty());
		}

		@Test
		@DisplayName("should invert Predecessor types on inherited attributes")
		void shouldInvertPredecessorTypes() {
			final AttributeSchema predecessorAttr = createAttribute("ordering", Predecessor.class);

			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null, null,
				"Product",
				"productRef",
				Cardinality.ZERO_OR_ONE,
				null, null, null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{}
			);

			final ReferenceSchema original = createOriginalReferenceWithAttributes(
				Map.of("ordering", predecessorAttr)
			);
			final ReflectedReferenceSchema bound = schema.withReferencedSchema(original);

			final AttributeSchemaContract inheritedOrdering = bound.getAttributes().get("ordering");
			assertNotNull(inheritedOrdering);
			// Predecessor should be inverted to ReferencedEntityPredecessor
			assertSame(ReferencedEntityPredecessor.class, inheritedOrdering.getPlainType());
		}
	}

	@Nested
	@DisplayName("With-copy methods")
	class WithCopyMethods {

		@Test
		@DisplayName("should set description inherited when null")
		void shouldSetDescriptionInheritedWhenNull() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				"Some description",
				null,
				"Brand",
				"brandRef",
				Cardinality.ZERO_OR_ONE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

			assertFalse(schema.isDescriptionInherited());

			final ReflectedReferenceSchemaContract updated =
				schema.withDescription(null);

			assertTrue(updated.isDescriptionInherited());
		}

		@Test
		@DisplayName("should set description explicit when provided")
		void shouldSetDescriptionExplicitWhenProvided() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected", "Brand", "brandRef"
			);

			assertTrue(schema.isDescriptionInherited());

			final ReflectedReferenceSchemaContract updated =
				schema.withDescription("Explicit");

			assertFalse(updated.isDescriptionInherited());
		}

		@Test
		@DisplayName("should set cardinality inherited when null")
		void shouldSetCardinalityInheritedWhenNull() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null, null,
				"Brand",
				"brandRef",
				Cardinality.ZERO_OR_ONE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

			assertFalse(schema.isCardinalityInherited());

			final ReflectedReferenceSchemaContract updated =
				schema.withCardinality(null);

			assertTrue(updated.isCardinalityInherited());
		}

		@Test
		@DisplayName("should set indexed inherited when null")
		void shouldSetIndexedInheritedWhenNull() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null, null,
				"Brand",
				"brandRef",
				Cardinality.ZERO_OR_ONE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

			assertFalse(schema.isIndexedInherited());

			final ReflectedReferenceSchemaContract updated =
				schema.withIndexed(null);

			assertTrue(updated.isIndexedInherited());
		}

		@Test
		@DisplayName("should set faceted inherited when null")
		void shouldSetFacetedInheritedWhenNull() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null, null,
				"Brand",
				"brandRef",
				Cardinality.ZERO_OR_ONE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				new Scope[]{Scope.LIVE},
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

			assertFalse(schema.isFacetedInherited());

			final ReflectedReferenceSchemaContract updated =
				schema.withFaceted(null);

			assertTrue(updated.isFacetedInherited());
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal for same parameters")
		void shouldBeEqualForSameParameters() {
			final ReflectedReferenceSchema a = ReflectedReferenceSchema._internalBuild(
				"reflected", "Brand", "brandRef"
			);
			final ReflectedReferenceSchema b = ReflectedReferenceSchema._internalBuild(
				"reflected", "Brand", "brandRef"
			);

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not be equal when reflected reference name differs")
		void shouldNotBeEqualWhenReflectedReferenceNameDiffers() {
			final ReflectedReferenceSchema a = ReflectedReferenceSchema._internalBuild(
				"reflected", "Brand", "brandRef"
			);
			final ReflectedReferenceSchema b = ReflectedReferenceSchema._internalBuild(
				"reflected", "Brand", "otherRef"
			);

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("should not be equal when inheritance flags differ")
		void shouldNotBeEqualWhenInheritanceFlagsDiffer() {
			// schema with inherited description (null)
			final ReflectedReferenceSchema inherited = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null,
				null,
				"Brand",
				"brandRef",
				Cardinality.ZERO_OR_ONE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

			// schema with explicit description
			final ReflectedReferenceSchema explicit = ReflectedReferenceSchema._internalBuild(
				"reflected",
				"Explicit description",
				null,
				"Brand",
				"brandRef",
				Cardinality.ZERO_OR_ONE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);

			assertNotEquals(inherited, explicit);
		}

		@Test
		@DisplayName("should not be equal when attribute inheritance behavior differs")
		void shouldNotBeEqualWhenAttributeInheritanceBehaviorDiffers() {
			final ReflectedReferenceSchema a = ReflectedReferenceSchema._internalBuild(
				"reflected",
				"desc", null,
				"Brand",
				"brandRef",
				Cardinality.ZERO_OR_ONE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);
			final ReflectedReferenceSchema b = ReflectedReferenceSchema._internalBuild(
				"reflected",
				"desc", null,
				"Brand",
				"brandRef",
				Cardinality.ZERO_OR_ONE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				Scope.NO_SCOPE,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				null
			);

			assertNotEquals(a, b);
		}
	}

	@Nested
	@DisplayName("WithUpdatedReferencedGroupType")
	class WithUpdatedReferencedGroupTypeTests {

		@Test
		@DisplayName("should throw on withUpdatedReferencedGroupType")
		void shouldThrowOnUpdatedReferencedGroupType() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected", "Brand", "brandRef"
			);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> schema.withUpdatedReferencedGroupType("SomeGroup")
			);
		}
	}

	@Nested
	@DisplayName("Inherited attributes tracking")
	class InheritedAttributesTracking {

		@Test
		@DisplayName("should report empty inherited attributes when no reflected reference")
		void shouldReportEmptyInheritedAttributesWhenNoReflectedRef() {
			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected", "Brand", "brandRef"
			);

			final Set<String> inherited = schema.getInheritedAttributes();
			assertTrue(inherited.isEmpty());
		}

		@Test
		@DisplayName("should track inherited attribute names after binding")
		void shouldTrackInheritedAttributeNamesAfterBinding() {
			final AttributeSchema attrA = createAttribute("attrA", String.class);
			final AttributeSchema attrB = createAttribute("attrB", Integer.class);

			final ReflectedReferenceSchema schema = ReflectedReferenceSchema._internalBuild(
				"reflected",
				null, null,
				"Product",
				"productRef",
				Cardinality.ZERO_OR_ONE,
				null, null, null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				new String[]{"attrA"}
			);

			final ReferenceSchema original = createOriginalReferenceWithAttributes(
				Map.of("attrA", attrA, "attrB", attrB)
			);
			final ReflectedReferenceSchema bound = schema.withReferencedSchema(original);

			final Set<String> inherited = bound.getInheritedAttributes();
			assertEquals(1, inherited.size());
			assertTrue(inherited.contains("attrA"));
			assertFalse(inherited.contains("attrB"));
		}
	}

	@Nested
	@DisplayName("Validation")
	class Validation {

		/**
		 * Builds an {@link EntitySchema} for "Product" that contains a reference "productRef"
		 * targeting the given entity type, along with the matching catalog schema.
		 *
		 * @param ownerEntityType the entity type that the "productRef" reference targets
		 * @param originalReference the original reference schema to include in Product
		 * @return array of [CatalogSchema, ownerEntitySchema]
		 */
		private Object[] buildCatalogAndSchemas(
			@Nonnull String ownerEntityType,
			@Nonnull ReferenceSchemaContract originalReference
		) {
			final EntitySchema ownerSchema = EntitySchema._internalBuild(ownerEntityType);
			final EntitySchema productSchema = EntitySchema._internalBuild(
				1, "Product",
				NamingConvention.generate("Product"),
				null, null,
				true, false, (Set<Scope>) null, false, (Set<Scope>) null, 2,
				Collections.emptySet(), Collections.emptySet(),
				Collections.emptyMap(), Collections.emptyMap(),
				Map.of("productRef", originalReference),
				EnumSet.allOf(EvolutionMode.class),
				Collections.emptyMap()
			);
			final CatalogSchema catalogSchema = CatalogSchema._internalBuild(
				"testCatalog",
				NamingConvention.generate("testCatalog"),
				EnumSet.allOf(CatalogEvolutionMode.class),
				new EntitySchemaProvider() {
					@Nonnull
					@Override
					public Collection<EntitySchemaContract> getEntitySchemas() {
						return List.of(ownerSchema, productSchema);
					}

					@Nonnull
					@Override
					public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
						if ("Product".equals(entityType)) {
							return Optional.of(productSchema);
						}
						if (ownerEntityType.equals(entityType)) {
							return Optional.of(ownerSchema);
						}
						return Optional.empty();
					}
				}
			);
			return new Object[]{catalogSchema, ownerSchema};
		}

		/**
		 * Verifies that validate() reports an error when facetedPartially is configured
		 * for a scope that is not faceted.
		 */
		@Test
		@DisplayName("should fail when facetedPartially set for non-faceted scope")
		void shouldFailValidationWhenFacetedPartiallySetForNonFacetedScope() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			// Original reference from Product -> Category
			final ReferenceSchema originalReference = ReferenceSchema._internalBuild(
				"productRef",
				NamingConvention.generate("productRef"),
				null, null, Cardinality.ZERO_OR_MORE,
				"Category",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				EnumSet.of(Scope.LIVE),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			// Build reflected reference with facetedPartially in LIVE but NOT faceted
			final ReflectedReferenceSchema reflected = ReflectedReferenceSchema._internalBuild(
				"reflected",
				NamingConvention.generate("reflected"),
				null, null,
				"Product",
				Collections.emptyMap(),
				null,
				Collections.emptyMap(),
				false,
				"productRef",
				Cardinality.ZERO_OR_MORE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				new Scope[0], // NOT faceted in any scope
				new ScopedFacetedPartially[]{
					new ScopedFacetedPartially(Scope.LIVE, expression) // but facetedPartially set
				},
				Collections.emptyMap(),
				Collections.emptyMap(),
				false, false, false, false, false,
				false, // facetedInherited = false
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null,
				originalReference
			);

			final Object[] schemas = buildCatalogAndSchemas("Category", originalReference);
			final CatalogSchema catalogSchema = (CatalogSchema) schemas[0];
			final EntitySchema ownerSchema = (EntitySchema) schemas[1];

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> reflected.validate(catalogSchema, ownerSchema)
			);
			assertTrue(
				ex.getMessage().contains("FacetedPartially expression is defined for scope"),
				"Expected error about facetedPartially for non-faceted scope, got: " + ex.getMessage()
			);
		}

		/**
		 * Verifies that validate() reports an error when faceted is inherited
		 * but facetedPartially expressions differ from the original reference.
		 */
		@Test
		@DisplayName("should fail when facetedPartially differs from inherited")
		void shouldFailValidationWhenFacetedPartiallyDiffersFromInherited() {
			final Expression originalExpr = ExpressionFactory.parse("1 > 0");
			final Expression differentExpr = ExpressionFactory.parse("2 > 1");

			// Original reference with facetedPartially in LIVE
			final ReferenceSchema originalReference = ReferenceSchema._internalBuild(
				"productRef",
				NamingConvention.generate("productRef"),
				null, null, Cardinality.ZERO_OR_MORE,
				"Category",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				EnumSet.of(Scope.LIVE),
				Map.of(Scope.LIVE, originalExpr),
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			// Build reflected reference with facetedInherited=true but different facetedPartially
			final ReflectedReferenceSchema reflected = ReflectedReferenceSchema._internalBuild(
				"reflected",
				NamingConvention.generate("reflected"),
				null, null,
				"Product",
				Collections.emptyMap(),
				null,
				Collections.emptyMap(),
				false,
				"productRef",
				Cardinality.ZERO_OR_MORE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				new Scope[]{Scope.LIVE}, // faceted in LIVE (but will be "inherited")
				new ScopedFacetedPartially[]{
					new ScopedFacetedPartially(Scope.LIVE, differentExpr) // different from original
				},
				Collections.emptyMap(),
				Collections.emptyMap(),
				false, false, false, false, false,
				true, // facetedInherited = true
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null,
				originalReference
			);

			final Object[] schemas = buildCatalogAndSchemas("Category", originalReference);
			final CatalogSchema catalogSchema = (CatalogSchema) schemas[0];
			final EntitySchema ownerSchema = (EntitySchema) schemas[1];

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> reflected.validate(catalogSchema, ownerSchema)
			);
			assertTrue(
				ex.getMessage().contains("FacetedPartially expressions differ from the original reference"),
				"Expected error about differing facetedPartially, got: " + ex.getMessage()
			);
		}

		/**
		 * Verifies that validate() reports an error when faceted is inherited
		 * but facetedInScopes differs from the original reference.
		 */
		@Test
		@DisplayName("should fail when facetedInScopes differs from inherited")
		void shouldFailValidationWhenFacetedInScopesDifferFromInherited() {
			// Original reference faceted in LIVE only
			final ReferenceSchema originalReference = ReferenceSchema._internalBuild(
				"productRef",
				NamingConvention.generate("productRef"),
				null, null, Cardinality.ZERO_OR_MORE,
				"Category",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				EnumSet.of(Scope.LIVE),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			// Build reflected reference with facetedInherited=true but NO faceted scopes (differs from original)
			final ReflectedReferenceSchema reflected = ReflectedReferenceSchema._internalBuild(
				"reflected",
				NamingConvention.generate("reflected"),
				null, null,
				"Product",
				Collections.emptyMap(),
				null,
				Collections.emptyMap(),
				false,
				"productRef",
				Cardinality.ZERO_OR_MORE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				new Scope[0], // empty — differs from original's LIVE
				null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				false, false, false, false, false,
				true, // facetedInherited = true
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null,
				originalReference
			);

			final Object[] schemas = buildCatalogAndSchemas("Category", originalReference);
			final CatalogSchema catalogSchema = (CatalogSchema) schemas[0];
			final EntitySchema ownerSchema = (EntitySchema) schemas[1];

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> reflected.validate(catalogSchema, ownerSchema)
			);
			assertTrue(
				ex.getMessage().contains("Faceted scopes differ from the original reference"),
				"Expected error about differing faceted scopes, got: " + ex.getMessage()
			);
		}

		/**
		 * Verifies that validate() reports an error when indexed is inherited
		 * but indexedInScopes differs from the original reference.
		 */
		@Test
		@DisplayName("should fail when indexedInScopes differs from inherited")
		void shouldFailValidationWhenIndexedInScopesDifferFromInherited() {
			// Original reference indexed in LIVE with FOR_FILTERING
			final ReferenceSchema originalReference = ReferenceSchema._internalBuild(
				"productRef",
				NamingConvention.generate("productRef"),
				null, null, Cardinality.ZERO_OR_MORE,
				"Category",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				Collections.emptySet(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			// Build reflected reference with indexedInherited=true but different indexed scopes
			final ReflectedReferenceSchema reflected = ReflectedReferenceSchema._internalBuild(
				"reflected",
				NamingConvention.generate("reflected"),
				null, null,
				"Product",
				Collections.emptyMap(),
				null,
				Collections.emptyMap(),
				false,
				"productRef",
				Cardinality.ZERO_OR_MORE,
				// indexed in LIVE with FOR_SORTING — differs from original's FOR_FILTERING
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)
				},
				null,
				new Scope[0],
				null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				false, false, false,
				true, // indexedInherited = true
				false,
				false,
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null,
				originalReference
			);

			final Object[] schemas = buildCatalogAndSchemas("Category", originalReference);
			final CatalogSchema catalogSchema = (CatalogSchema) schemas[0];
			final EntitySchema ownerSchema = (EntitySchema) schemas[1];

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> reflected.validate(catalogSchema, ownerSchema)
			);
			assertTrue(
				ex.getMessage().contains("Indexed scopes differ from the original reference"),
				"Expected error about differing indexed scopes, got: " + ex.getMessage()
			);
		}

		/**
		 * Verifies that validate() reports an error when indexed components are inherited
		 * but indexedComponentsInScopes differs from the original reference.
		 */
		@Test
		@DisplayName("should fail when indexedComponentsInScopes differs from inherited")
		void shouldFailValidationWhenIndexedComponentsDifferFromInherited() {
			// Original reference with default indexed components for LIVE
			final ReferenceSchema originalReference = ReferenceSchema._internalBuild(
				"productRef",
				NamingConvention.generate("productRef"),
				null, null, Cardinality.ZERO_OR_MORE,
				"Category",
				Collections.emptyMap(),
				true,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				Collections.emptySet(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			// Build reflected reference with indexedComponentsInherited=true but different components
			final ReflectedReferenceSchema reflected = ReflectedReferenceSchema._internalBuild(
				"reflected",
				NamingConvention.generate("reflected"),
				null, null,
				"Product",
				Collections.emptyMap(),
				null,
				Collections.emptyMap(),
				false,
				"productRef",
				Cardinality.ZERO_OR_MORE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				// explicitly set different components than original
				new ScopedReferenceIndexedComponents[]{
					new ScopedReferenceIndexedComponents(
						Scope.LIVE,
						new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY}
					)
				},
				new Scope[0],
				null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				false, false, false, false,
				true, // indexedComponentsInherited = true
				false,
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null,
				originalReference
			);

			final Object[] schemas = buildCatalogAndSchemas("Category", originalReference);
			final CatalogSchema catalogSchema = (CatalogSchema) schemas[0];
			final EntitySchema ownerSchema = (EntitySchema) schemas[1];

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> reflected.validate(catalogSchema, ownerSchema)
			);
			assertTrue(
				ex.getMessage().contains("Indexed components differ from the original reference"),
				"Expected error about differing indexed components, got: " + ex.getMessage()
			);
		}
	}
}
