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

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.NamedContract;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.EnumMap;
import java.util.Map;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HistogramIndexDefinition}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("HistogramIndexDefinition")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(HISTOGRAM)
class HistogramIndexDefinitionTest {

	/**
	 * Verifies that a {@link HistogramIndexDefinition} can be constructed with both
	 * a non-null name and a non-null expression, and that both fields are accessible.
	 */
	@Test
	@DisplayName("should construct with non-null name and expression")
	void shouldConstructHistogramIndexDefinition() {
		final Expression expr = ExpressionFactory.parse("$price * $quantity");
		final HistogramIndexDefinition def = HistogramIndexDefinition.of("priceHistogram", expr);

		assertEquals("priceHistogram", def.nameOfTheIndex());
		assertNotNull(def.valueExpression());
		assertEquals(expr.toExpressionString(), def.valueExpression().toExpressionString());
	}

	/**
	 * Verifies that a null valueExpression is allowed and accessible after construction.
	 */
	@Test
	@DisplayName("should allow null value expression")
	void shouldAllowNullValueExpression() {
		final HistogramIndexDefinition def = HistogramIndexDefinition.of("hist", null);

		assertEquals("hist", def.nameOfTheIndex());
		assertNull(def.valueExpression());
	}

	/**
	 * Verifies that constructing with a null nameOfTheIndex throws {@link EvitaInvalidUsageException}
	 * with a message indicating the name must not be null.
	 */
	@Test
	@DisplayName("should reject null nameOfTheIndex")
	void shouldRejectNullNameOfTheIndex() {
		final EvitaInvalidUsageException exception = assertThrows(
			EvitaInvalidUsageException.class,
			() -> HistogramIndexDefinition.of(null, null)
		);
		assertTrue(
			exception.getMessage().contains("must not be null"),
			"Expected message to contain 'must not be null' but was: " + exception.getMessage()
		);
	}

	/**
	 * Verifies that constructing with a blank nameOfTheIndex throws {@link EvitaInvalidUsageException}
	 * with a message indicating the name must not be blank.
	 */
	@Test
	@DisplayName("should reject blank nameOfTheIndex")
	void shouldRejectBlankNameOfTheIndex() {
		final EvitaInvalidUsageException exception = assertThrows(
			EvitaInvalidUsageException.class,
			() -> HistogramIndexDefinition.of("  ", null)
		);
		assertTrue(
			exception.getMessage().contains("must not be blank"),
			"Expected message to contain 'must not be blank' but was: " + exception.getMessage()
		);
	}

	/**
	 * Verifies that two definitions with the same name and expression are equal and have consistent
	 * hash codes, while two definitions with different names are not equal.
	 */
	@Test
	@DisplayName("should obey record equals and hashCode")
	void shouldObeyRecordEqualsAndHashCode() {
		final Expression expr = ExpressionFactory.parse("$price");
		final HistogramIndexDefinition a = HistogramIndexDefinition.of("hist", expr);
		final HistogramIndexDefinition b = HistogramIndexDefinition.of("hist", expr);
		final HistogramIndexDefinition c = HistogramIndexDefinition.of("other", expr);

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, c);
	}

	@Nested
	@DisplayName("Name variants")
	class NameVariantsTest {

		/**
		 * Verifies that {@link HistogramIndexDefinition#of(String, Expression)} generates a variant
		 * entry for every single {@link NamingConvention} declared in the enum. This guards against
		 * a future addition of a new naming convention that the factory would silently fail to populate.
		 */
		@Test
		@DisplayName("should populate all naming conventions when built via factory")
		void shouldPopulateAllNamingConventionsWhenBuiltViaFactory() {
			final HistogramIndexDefinition def = HistogramIndexDefinition.of("priceHistogram", null);

			final Map<NamingConvention, String> variants = def.getNameVariants();
			assertEquals(
				NamingConvention.values().length, variants.size(),
				"nameVariants must contain an entry for every NamingConvention"
			);
			for (final NamingConvention convention : NamingConvention.values()) {
				assertNotNull(
					variants.get(convention),
					"Missing variant for convention " + convention
				);
				assertFalse(
					variants.get(convention).isBlank(),
					"Blank variant for convention " + convention
				);
			}
		}

		/**
		 * Verifies that the variants generated by the factory match the canonical
		 * {@link NamingConvention#generate(String)} output byte-for-byte. This pins the factory
		 * to the single source of truth for variant generation.
		 */
		@Test
		@DisplayName("should match NamingConvention.generate output exactly")
		void shouldMatchNamingConventionGenerateOutputExactly() {
			final String canonicalName = "myComplexHistogramName";
			final HistogramIndexDefinition def = HistogramIndexDefinition.of(canonicalName, null);

			assertEquals(
				NamingConvention.generate(canonicalName), def.getNameVariants(),
				"Factory-built variants must be identical to NamingConvention.generate output"
			);
		}

		/**
		 * Verifies that {@link HistogramIndexDefinition#getNameVariant(NamingConvention)} returns the
		 * expected variant for every known convention. The assertion compares against
		 * {@link NamingConvention#generate(String)} to avoid hard-coding casing rules.
		 */
		@Test
		@DisplayName("should return per-convention variant via getNameVariant")
		void shouldReturnPerConventionVariantViaGetNameVariant() {
			final String canonicalName = "priceBucketIndex";
			final HistogramIndexDefinition def = HistogramIndexDefinition.of(canonicalName, null);
			final Map<NamingConvention, String> expected = NamingConvention.generate(canonicalName);

			for (final NamingConvention convention : NamingConvention.values()) {
				assertEquals(
					expected.get(convention), def.getNameVariant(convention),
					"Variant mismatch for convention " + convention
				);
			}
		}

		/**
		 * Verifies that {@link HistogramIndexDefinition#getName()} returns the canonical name — the same
		 * value that was passed into the factory — and not a variant. This locks the contract of
		 * {@link NamedContract#getName()} for this record against accidental aliasing.
		 */
		@Test
		@DisplayName("should return canonical name via getName")
		void shouldReturnCanonicalNameViaGetName() {
			final HistogramIndexDefinition def = HistogramIndexDefinition.of("priceHistogram", null);

			assertEquals("priceHistogram", def.getName());
			assertEquals(def.nameOfTheIndex(), def.getName());
		}
	}

	@Nested
	@DisplayName("Explicit record constructor")
	class ExplicitConstructorTest {

		/**
		 * Verifies that the explicit 3-arg record constructor accepts a user-supplied variant map
		 * and preserves its entries verbatim (i.e. it does not silently overwrite them with
		 * {@link NamingConvention#generate(String)}). This is important for deserialization paths
		 * that reconstruct a definition from persisted variant data.
		 */
		@Test
		@DisplayName("should preserve user-supplied variants verbatim")
		void shouldPreserveUserSuppliedVariantsVerbatim() {
			final Map<NamingConvention, String> customVariants = new EnumMap<>(NamingConvention.class);
			// intentionally non-canonical variants: the constructor must not second-guess them
			customVariants.put(NamingConvention.CAMEL_CASE, "customCamel");
			customVariants.put(NamingConvention.PASCAL_CASE, "CustomPascal");
			customVariants.put(NamingConvention.SNAKE_CASE, "custom_snake");
			customVariants.put(NamingConvention.UPPER_SNAKE_CASE, "CUSTOM_UPPER");
			customVariants.put(NamingConvention.KEBAB_CASE, "custom-kebab");

			final HistogramIndexDefinition def = new HistogramIndexDefinition(
				"hist", customVariants, null, null
			);

			assertEquals("customCamel", def.getNameVariant(NamingConvention.CAMEL_CASE));
			assertEquals("CustomPascal", def.getNameVariant(NamingConvention.PASCAL_CASE));
			assertEquals("custom_snake", def.getNameVariant(NamingConvention.SNAKE_CASE));
			assertEquals("CUSTOM_UPPER", def.getNameVariant(NamingConvention.UPPER_SNAKE_CASE));
			assertEquals("custom-kebab", def.getNameVariant(NamingConvention.KEBAB_CASE));
		}

		/**
		 * Verifies that the compact constructor wraps the supplied map in an unmodifiable view. This
		 * guards an important invariant: callers that keep a reference to the original mutable map
		 * must not be able to mutate the definition's variants after construction.
		 */
		@Test
		@DisplayName("should expose unmodifiable variants map")
		void shouldExposeUnmodifiableVariantsMap() {
			final Map<NamingConvention, String> mutable = new EnumMap<>(NamingConvention.class);
			mutable.put(NamingConvention.CAMEL_CASE, "hist");
			mutable.put(NamingConvention.PASCAL_CASE, "Hist");
			mutable.put(NamingConvention.SNAKE_CASE, "hist");
			mutable.put(NamingConvention.UPPER_SNAKE_CASE, "HIST");
			mutable.put(NamingConvention.KEBAB_CASE, "hist");

			final HistogramIndexDefinition def = new HistogramIndexDefinition("hist", mutable, null, null);

			assertThrows(
				UnsupportedOperationException.class,
				() -> def.getNameVariants().put(NamingConvention.CAMEL_CASE, "replaced"),
				"variants map returned by getNameVariants must be unmodifiable"
			);
			assertThrows(
				UnsupportedOperationException.class,
				() -> def.nameVariants().remove(NamingConvention.CAMEL_CASE),
				"variants map accessed via nameVariants() must be unmodifiable"
			);
		}

		/**
		 * Verifies that the compact constructor rejects a null variants map with a specific error
		 * message. This is the only null check on the variants parameter; without it the record
		 * would NPE later from `getNameVariant`.
		 */
		@Test
		@DisplayName("should reject null variants map")
		void shouldRejectNullVariantsMap() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> new HistogramIndexDefinition("hist", null, null, null)
			);
			assertTrue(
				exception.getMessage().contains("Name variants must not be null"),
				"Expected message about null variants, got: " + exception.getMessage()
			);
		}

		/**
		 * Verifies that the compact constructor accepts an empty variants map. The validation only
		 * rejects a null map; the caller is trusted to supply content. A subsequent lookup on an
		 * unpopulated convention must return null (rather than throwing). This encodes the current
		 * contract — useful for deserialization where a legacy payload may omit some conventions.
		 */
		@Test
		@DisplayName("should return null for convention missing in supplied variants map")
		void shouldReturnNullForConventionMissingInSuppliedVariantsMap() {
			final Map<NamingConvention, String> partial = new EnumMap<>(NamingConvention.class);
			partial.put(NamingConvention.CAMEL_CASE, "hist");

			final HistogramIndexDefinition def = new HistogramIndexDefinition("hist", partial, null, null);

			assertEquals("hist", def.getNameVariant(NamingConvention.CAMEL_CASE));
			assertNull(def.getNameVariant(NamingConvention.KEBAB_CASE));
		}

		/**
		 * Verifies that the compact constructor also rejects blank names when invoked directly with
		 * a user-supplied variants map. The validation logic must not be bypassed by the 3-arg path.
		 */
		@Test
		@DisplayName("should reject blank name in explicit constructor")
		void shouldRejectBlankNameInExplicitConstructor() {
			final Map<NamingConvention, String> variants = NamingConvention.generate("placeholder");
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new HistogramIndexDefinition("", variants, null, null)
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new HistogramIndexDefinition("\t\n ", variants, null, null)
			);
		}
	}

	@Nested
	@DisplayName("Equals and hashCode with name variants")
	class EqualsWithVariantsTest {

		/**
		 * Verifies that two definitions constructed through the factory with the same canonical name
		 * are equal — they share equal variant maps generated from the same source of truth.
		 */
		@Test
		@DisplayName("should be equal when factory-built from same name")
		void shouldBeEqualWhenFactoryBuiltFromSameName() {
			final HistogramIndexDefinition a = HistogramIndexDefinition.of("priceHistogram", null);
			final HistogramIndexDefinition b = HistogramIndexDefinition.of("priceHistogram", null);

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
			assertEquals(a.getNameVariants(), b.getNameVariants());
		}

		/**
		 * Verifies that two definitions with the same canonical name but different custom variant
		 * maps are NOT equal. Records compare components structurally, so the variants map is a
		 * first-class participant in equality — important because downstream code relies on
		 * equals-based comparison for schema diffs.
		 */
		@Test
		@DisplayName("should not be equal when variant maps differ")
		void shouldNotBeEqualWhenVariantMapsDiffer() {
			final Map<NamingConvention, String> customVariants = copyWithOverride(
				NamingConvention.generate("hist"),
				NamingConvention.KEBAB_CASE,
				"totally-different"
			);

			final HistogramIndexDefinition canonical = HistogramIndexDefinition.of("hist", null);
			final HistogramIndexDefinition withCustomVariants = new HistogramIndexDefinition(
				"hist", customVariants, null, null
			);

			assertNotEquals(
				canonical, withCustomVariants,
				"definitions with different variant maps must not be equal"
			);
			assertNotEquals(
				canonical.hashCode(), withCustomVariants.hashCode(),
				"different variant content should propagate to hashCode"
			);
		}

		/**
		 * Verifies that `hashCode` is consistent with `equals` across an unrelated but
		 * structurally-equal construction path: factory-generated vs. manually-constructed using
		 * the exact same variants map. Records already guarantee this, but because the compact
		 * constructor rewraps the map, we verify the rewrap does not perturb the hash.
		 */
		@Test
		@DisplayName("should produce equal hashCode for equivalent factory and manual construction")
		void shouldProduceEqualHashCodeForEquivalentFactoryAndManualConstruction() {
			final Map<NamingConvention, String> generated = NamingConvention.generate("hist");
			final HistogramIndexDefinition viaFactory = HistogramIndexDefinition.of("hist", null);
			final HistogramIndexDefinition viaManual = new HistogramIndexDefinition(
				"hist", new EnumMap<>(generated), null, null
			);

			assertEquals(viaFactory, viaManual);
			assertEquals(viaFactory.hashCode(), viaManual.hashCode());
		}
	}

	@Nested
	@DisplayName("Per-histogram partition selector component")
	class PerHistogramConditionComponentTest {

		/**
		 * Verifies that the convenience 2-arg factory does not invent an `assignedWhen` value —
		 * when the caller does not supply one, the slot remains `null`. This pins the
		 * default-null contract that downstream code relies on to distinguish
		 * "no per-histogram restriction" from "explicit partition selector".
		 */
		@Test
		@DisplayName("should default assignedWhen to null when built via 2-arg factory")
		void shouldDefaultAssignedWhenToNullWhenBuiltViaTwoArgFactory() {
			final HistogramIndexDefinition def = HistogramIndexDefinition.of("hist", null);

			assertNull(def.assignedWhen());
		}

		/**
		 * Verifies that the 3-arg factory propagates a non-null `assignedWhen` expression
		 * into the resulting record and does not cross-wire it into `valueExpression`. The two
		 * expression slots are independent — the test asserts both at the same time so a future
		 * accidental swap (which would still type-check) is caught.
		 */
		@Test
		@DisplayName("should expose assignedWhen when built via 3-arg factory")
		void shouldExposeAssignedWhenWhenBuiltViaThreeArgFactory() {
			final Expression expr = ExpressionFactory.parse("$entity.attributes['x'] > 0");

			final HistogramIndexDefinition def = HistogramIndexDefinition.of("hist", null, expr);

			assertNotNull(def.assignedWhen());
			assertEquals(
				expr.toExpressionString(), def.assignedWhen().toExpressionString(),
				"3-arg factory must preserve the assignedWhen expression verbatim"
			);
			assertNull(
				def.valueExpression(),
				"valueExpression slot must remain null when only assignedWhen is supplied"
			);
		}

		/**
		 * Verifies that the explicit 4-arg record constructor preserves a non-null
		 * `assignedWhen` and that the `valueExpression` slot stays null when the caller
		 * does not supply one. This pins the contract of the canonical constructor used by
		 * deserialization paths.
		 */
		@Test
		@DisplayName("should expose assignedWhen when built via explicit constructor")
		void shouldExposeAssignedWhenWhenBuiltViaExplicitConstructor() {
			final Expression expr = ExpressionFactory.parse("$entity.attributes['x'] > 0");

			final HistogramIndexDefinition def = new HistogramIndexDefinition(
				"hist", NamingConvention.generate("hist"), null, expr
			);

			assertNotNull(def.assignedWhen());
			assertEquals(expr.toExpressionString(), def.assignedWhen().toExpressionString());
			assertNull(def.valueExpression());
		}

	}

	/**
	 * Shared helper. Copies a source variants map and overrides a single entry with a custom value.
	 * Used to build "nearly identical" variant maps for equals-comparison tests.
	 *
	 * @param source       the source variants map (not mutated)
	 * @param convention   the naming convention whose variant should be overridden
	 * @param customValue  the replacement variant value
	 * @return a new modifiable {@link EnumMap} with the override applied
	 */
	@Nonnull
	private static Map<NamingConvention, String> copyWithOverride(
		@Nonnull Map<NamingConvention, String> source,
		@Nonnull NamingConvention convention,
		@Nonnull String customValue
	) {
		final Map<NamingConvention, String> copy = new EnumMap<>(NamingConvention.class);
		copy.putAll(source);
		copy.put(convention, customValue);
		return copy;
	}

}
