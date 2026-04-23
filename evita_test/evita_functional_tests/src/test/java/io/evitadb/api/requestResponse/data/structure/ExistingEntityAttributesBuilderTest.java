/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.InvalidDataTypeMutationException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ExistingEntityAttributesBuilder} verifying
 * modification operations on already existing
 * {@link EntityAttributes} containers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ExistingEntityAttributesBuilder")
class ExistingEntityAttributesBuilderTest
	extends AbstractBuilderTest {

	private EntityAttributes initialAttributes;

	@BeforeEach
	void setUp() {
		this.initialAttributes =
			new InitialEntityAttributesBuilder(
				new InternalEntitySchemaBuilder(
					CATALOG_SCHEMA, PRODUCT_SCHEMA
				)
					.verifySchemaButCreateOnTheFly()
					.withAttribute(
						"int", Integer.class
					)
					.withAttribute(
						"range",
						IntegerNumberRange.class
					)
					.withAttribute(
						"bigDecimal", BigDecimal.class
					)
					.withAttribute(
						"greetings", String.class,
						whichIs -> whichIs.localized()
					)
					.toInstance()
			)
				.setAttribute("int", 1)
				.setAttribute(
					"range",
					IntegerNumberRange.between(4, 8)
				)
				.setAttribute(
					"bigDecimal",
					new BigDecimal("1.123")
				)
				.setAttribute(
					"greetings",
					Locale.ENGLISH, "Hello"
				)
				.setAttribute(
					"greetings",
					Locale.GERMAN, "Tschüss"
				)
				.build();
	}

	@Nested
	@DisplayName("Overriding and setting attributes")
	class OverridingAndSettingTest {

		@Test
		@DisplayName(
			"Should override existing attribute"
				+ " with new value"
		)
		void shouldOverrideExistingAttributeWithNewValue() {
			final ExistingEntityAttributesBuilder builder =
				new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				).setAttribute("int", 10);

			assertEquals(
				Integer.valueOf(10),
				builder.getAttribute("int")
			);
			assertEquals(
				Integer.valueOf(1),
				initialAttributes.getAttribute("int")
			);

			final Attributes<EntityAttributeSchemaContract>
				newVersion = builder.build();

			assertEquals(
				Integer.valueOf(10),
				newVersion.getAttribute("int")
			);
			assertEquals(
				2L,
				newVersion.getAttributeValue(
					new AttributeKey("int")
				).orElseThrow().version()
			);
			assertEquals(
				Integer.valueOf(1),
				initialAttributes.getAttribute("int")
			);
		}

		@Test
		@DisplayName(
			"Should add new attribute"
				+ " to existing container"
		)
		void shouldAddNewAttribute() {
			final ExistingEntityAttributesBuilder builder =
				new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				).setAttribute("short", (short) 10);

			assertEquals(
				Short.valueOf((short) 10),
				builder.getAttribute("short")
			);

			final Attributes<EntityAttributeSchemaContract>
				newVersion = builder.build();

			assertEquals(
				Short.valueOf((short) 10),
				newVersion.getAttribute("short")
			);
		}

		@Test
		@DisplayName(
			"Should set localized attribute value"
		)
		void shouldSetLocalizedAttribute() {
			final ExistingEntityAttributesBuilder builder =
				new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				).setAttribute(
					"greetings",
					Locale.FRENCH, "Bonjour"
				);

			assertEquals(
				"Bonjour",
				builder.getAttribute(
					"greetings", Locale.FRENCH
				)
			);
			// original locales untouched
			assertEquals(
				"Hello",
				builder.getAttribute(
					"greetings", Locale.ENGLISH
				)
			);
			assertEquals(
				"Tschüss",
				builder.getAttribute(
					"greetings", Locale.GERMAN
				)
			);
		}

		@Test
		@DisplayName(
			"Should add localized array attribute"
		)
		void shouldAddLocalizedArray() {
			final ExistingEntityAttributesBuilder builder =
				new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				)
					.setAttribute(
						"localizedArray",
						Locale.ENGLISH,
						new Integer[]{1, 5}
					)
					.setAttribute(
						"localizedArray",
						new Locale("cs"),
						new Integer[]{6, 7}
					);

			assertArrayEquals(
				new Integer[]{1, 5},
				builder.getAttributeArray(
					"localizedArray", Locale.ENGLISH
				)
			);
			assertArrayEquals(
				new Integer[]{6, 7},
				builder.getAttributeArray(
					"localizedArray", new Locale("cs")
				)
			);
		}

		@Test
		@DisplayName(
			"Should create implicit schema"
				+ " for new attribute"
		)
		void shouldCreateImplicitSchemaForNewAttribute() {
			final ExistingEntityAttributesBuilder builder =
				new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				);
			builder.setAttribute("short", (short) 4);

			final Attributes<EntityAttributeSchemaContract>
				attributes = builder.build();

			assertTrue(
				attributes.getAttributeSchema("short")
					.isPresent()
			);
			assertEquals(
				Short.class,
				attributes.getAttributeSchema("short")
					.orElseThrow().getType()
			);
		}

		@Test
		@DisplayName(
			"Should skip no-op mutations"
				+ " when setting same value"
		)
		void shouldSkipNoOpMutations() {
			final ExistingEntityAttributesBuilder builder =
				new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				)
					.setAttribute("int", 1)
					.setAttribute(
						"range",
						IntegerNumberRange.between(5, 11)
					)
					.setAttribute(
						"range",
						IntegerNumberRange.between(4, 8)
					);

			assertEquals(
				0, builder.buildChangeSet().count()
			);
		}
	}

	@Nested
	@DisplayName("Removing attributes")
	class RemovingTest {

		@Test
		@DisplayName(
			"Should remove existing attribute"
		)
		void shouldRemoveExistingAttribute() {
			final ExistingEntityAttributesBuilder builder =
				new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				).removeAttribute("int");

			assertNull(builder.getAttribute("int"));
			assertEquals(
				Integer.valueOf(1),
				initialAttributes.getAttribute("int")
			);

			final Attributes<EntityAttributeSchemaContract>
				newVersion = builder.build();

			// removed value is still stored
			// but marked as dropped
			final AttributeValue attributeValue =
				newVersion.getAttributeValue(
					new AttributeKey("int")
				).orElseThrow();
			assertEquals(2L, attributeValue.version());
			assertTrue(attributeValue.dropped());

			assertEquals(
				Integer.valueOf(1),
				initialAttributes.getAttribute("int")
			);
		}

		@Test
		@DisplayName(
			"Should remove only targeted"
				+ " localized attribute"
		)
		void shouldRemoveLocalizedAttribute() {
			final ExistingEntityAttributesBuilder builder =
				new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				).removeAttribute(
					"greetings", Locale.GERMAN
				);

			assertNull(
				builder.getAttribute(
					"greetings", Locale.GERMAN
				)
			);
			assertEquals(
				"Hello",
				builder.getAttribute(
					"greetings", Locale.ENGLISH
				)
			);

			final Attributes<EntityAttributeSchemaContract>
				newVersion = builder.build();

			final AttributeValue germanGreeting =
				newVersion.getAttributeValue(
					new AttributeKey(
						"greetings", Locale.GERMAN
					)
				).orElseThrow();
			assertEquals(2L, germanGreeting.version());
			assertTrue(germanGreeting.dropped());

			final AttributeValue englishGreeting =
				newVersion.getAttributeValue(
					new AttributeKey(
						"greetings", Locale.ENGLISH
					)
				).orElseThrow();
			assertEquals(1L, englishGreeting.version());
			assertFalse(englishGreeting.dropped());
		}

		@Test
		@DisplayName(
			"Should retain untouched attributes"
				+ " after removal"
		)
		void shouldRetainUntouchedAttributesAfterRemoval() {
			final ExistingEntityAttributesBuilder builder =
				new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				).removeAttribute("int");

			final Attributes<EntityAttributeSchemaContract>
				newVersion = builder.build();

			assertEquals(
				IntegerNumberRange.between(4, 8),
				newVersion.getAttribute("range")
			);
			assertEquals(
				new BigDecimal("1.123"),
				newVersion.getAttribute("bigDecimal")
			);
			assertEquals(
				"Hello",
				newVersion.getAttribute(
					"greetings", Locale.ENGLISH
				)
			);
		}
	}

	@Nested
	@DisplayName("Delta mutations")
	class DeltaMutationsTest {

		@Test
		@DisplayName(
			"Should apply delta mutation"
				+ " to existing attribute"
		)
		void shouldApplyDeltaMutation() {
			final ExistingEntityAttributesBuilder builder =
				new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				).mutateAttribute(
					new ApplyDeltaAttributeMutation<>(
						"int", 2
					)
				);

			assertEquals(
				Integer.valueOf(3),
				builder.getAttribute("int")
			);

			final Attributes<EntityAttributeSchemaContract>
				newVersion = builder.build();

			assertEquals(
				Integer.valueOf(3),
				newVersion.getAttribute("int")
			);
		}

		@Test
		@DisplayName(
			"Should fail delta on non-existing"
				+ " attribute"
		)
		void shouldFailDeltaOnNonExistingAttribute() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				).addMutation(
					new ApplyDeltaAttributeMutation<>(
						"newInt", 10
					)
				)
			);
		}

		@Test
		@DisplayName(
			"Should combine upsert and deltas"
				+ " into correct value"
		)
		void shouldCombineUpsertAndDeltas() {
			final Attributes<EntityAttributeSchemaContract>
				newAttrs =
				new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				)
					.addMutation(
						new UpsertAttributeMutation(
							"newInt", 10
						)
					)
					.addMutation(
						new ApplyDeltaAttributeMutation<>(
							"newInt", -1
						)
					)
					.addMutation(
						new ApplyDeltaAttributeMutation<>(
							"newInt", -4
						)
					)
					.build();

			assertEquals(
				Integer.valueOf(5),
				newAttrs.getAttribute("newInt")
			);
		}

		@Test
		@DisplayName(
			"Should combine create and remove"
				+ " into absent attribute"
		)
		void shouldCombineCreateAndRemove() {
			final Attributes<EntityAttributeSchemaContract>
				newAttrs =
				new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				)
					.addMutation(
						new UpsertAttributeMutation(
							"newInt", 10
						)
					)
					.addMutation(
						new RemoveAttributeMutation(
							"newInt"
						)
					)
					.build();

			assertFalse(
				newAttrs.attributeValues.containsKey(
					new AttributeKey("newInt")
				)
			);
		}

		@Test
		@DisplayName(
			"Should fail on incompatible type"
				+ " mutation"
		)
		void shouldFailOnIncompatibleTypeMutation() {
			assertThrows(
				InvalidDataTypeMutationException.class,
				() -> new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				).addMutation(
					new UpsertAttributeMutation("int", "a")
				)
			);
		}

		@Test
		@DisplayName(
			"Should ignore remove mutation"
				+ " on non-existent attribute"
		)
		void shouldIgnoreRemoveOnNonExistent() {
			final ExistingEntityAttributesBuilder builder =
				new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				);
			builder.addMutation(
				new RemoveAttributeMutation("abc")
			);

			assertEquals(
				0L, builder.buildChangeSet().count()
			);
		}
	}

	@Nested
	@DisplayName("Schema validation")
	class SchemaValidationTest {

		@Test
		@DisplayName(
			"Should reject unknown attribute"
				+ " in strict mode"
		)
		void shouldRejectUnknownAttributeInStrictMode() {
			final EntitySchemaContract strictSchema =
				new InternalEntitySchemaBuilder(
					CATALOG_SCHEMA, PRODUCT_SCHEMA
				)
					.withAttribute(
						"string", String.class
					)
					.withAttribute(
						"int", Integer.class,
						AttributeSchemaEditor::localized
					)
					.withLocale(Locale.ENGLISH)
					.verifySchemaStrictly()
					.toInstance();

			final Attributes<EntityAttributeSchemaContract>
				attrs =
				new InitialEntityAttributesBuilder(
					strictSchema
				)
					.setAttribute("string", "Hi")
					.setAttribute(
						"int", Locale.ENGLISH, 5
					)
					.build();

			final ExistingEntityAttributesBuilder builder =
				new ExistingEntityAttributesBuilder(
					strictSchema, attrs
				);

			// unknown attribute
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setAttribute("new", "A")
			);
			// wrong type
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setAttribute("string", 1)
			);
			// non-localized set as localized
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setAttribute(
					"string",
					Locale.ENGLISH, "string"
				)
			);
			// localized set as non-localized
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setAttribute("int", 1)
			);
		}

		@Test
		@DisplayName(
			"Should allow new attributes"
				+ " with ADDING_ATTRIBUTES mode"
		)
		void shouldAllowNewAttributesWithAddingMode() {
			final EntitySchemaContract permissiveSchema =
				new InternalEntitySchemaBuilder(
					CATALOG_SCHEMA, PRODUCT_SCHEMA
				)
					.withAttribute(
						"string", String.class
					)
					.withAttribute(
						"int", Integer.class,
						AttributeSchemaEditor::localized
					)
					.withLocale(Locale.ENGLISH)
					.verifySchemaButAllow(
						EvolutionMode.ADDING_ATTRIBUTES
					)
					.toInstance();

			final Attributes<EntityAttributeSchemaContract>
				attrs =
				new InitialEntityAttributesBuilder(
					permissiveSchema
				)
					.setAttribute("string", "Hi")
					.setAttribute(
						"int", Locale.ENGLISH, 5
					)
					.build();

			final ExistingEntityAttributesBuilder builder =
				new ExistingEntityAttributesBuilder(
					permissiveSchema, attrs
				);

			// new attributes allowed
			builder.setAttribute("new", "A");
			builder.setAttribute(
				"newLocalized",
				Locale.ENGLISH, "B"
			);
			// unsupported locale still rejected
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setAttribute(
					"newLocalized",
					Locale.GERMAN, "string"
				)
			);
			// existing attribute type mismatch
			// still rejected
			assertThrows(
				InvalidMutationException.class,
				() -> builder.setAttribute("string", 1)
			);

			final Attributes<EntityAttributeSchemaContract>
				updated = builder
				.setAttribute("string", "Hi")
				.setAttribute(
					"int", Locale.ENGLISH, 5
				)
				.build();

			assertEquals(
				4,
				updated.getAttributeKeys().size()
			);
			assertEquals(
				"A", updated.getAttribute("new")
			);
			assertEquals(
				"B",
				updated.getAttribute(
					"newLocalized", Locale.ENGLISH
				)
			);
		}

		@Test
		@DisplayName(
			"Should allow new locales"
				+ " with ADDING_LOCALES mode"
		)
		void shouldAllowNewLocalesWithAddingLocalesMode() {
			final EntitySchemaContract localeSchema =
				new InternalEntitySchemaBuilder(
					CATALOG_SCHEMA, PRODUCT_SCHEMA
				)
					.withAttribute(
						"string", String.class
					)
					.withAttribute(
						"int", Integer.class,
						AttributeSchemaEditor::localized
					)
					.withLocale(Locale.ENGLISH)
					.verifySchemaButAllow(
						EvolutionMode.ADDING_LOCALES
					)
					.toInstance();

			final Attributes<EntityAttributeSchemaContract>
				attrs =
				new InitialEntityAttributesBuilder(
					localeSchema
				)
					.setAttribute("string", "Hi")
					.setAttribute(
						"int", Locale.ENGLISH, 5
					)
					.build();

			final Attributes<EntityAttributeSchemaContract>
				updated =
				new ExistingEntityAttributesBuilder(
					localeSchema, attrs
				)
					.setAttribute("string", "Hello")
					.setAttribute(
						"int", Locale.ENGLISH, 9
					)
					.setAttribute(
						"int", Locale.GERMAN, 10
					)
					.build();

			assertEquals(
				3,
				updated.getAttributeKeys().size()
			);
			assertEquals(
				"Hello",
				updated.getAttribute("string")
			);
			assertEquals(
				Integer.valueOf(9),
				updated.getAttribute(
					"int", Locale.ENGLISH
				)
			);
			assertEquals(
				Integer.valueOf(10),
				updated.getAttribute(
					"int", Locale.GERMAN
				)
			);
		}
	}

	@Nested
	@DisplayName("Change set and identity")
	class ChangeSetAndIdentityTest {

		@Test
		@DisplayName(
			"Should return accumulated mutation count"
		)
		void shouldReturnAccumulatedMutationCount() {
			final ExistingEntityAttributesBuilder builder =
				new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				)
					.setAttribute("int", 10)
					.removeAttribute("int")
					.setAttribute("short", (short) 5)
					.setAttribute(
						"greetings",
						Locale.FRENCH, "Bonjour"
					)
					.removeAttribute(
						"greetings", Locale.ENGLISH
					);

			assertEquals(
				4,
				(int) builder.buildChangeSet().count()
			);
		}

		@Test
		@DisplayName(
			"Should produce empty change set"
				+ " when no modifications"
		)
		void shouldProduceEmptyChangeSet() {
			final ExistingEntityAttributesBuilder builder =
				new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				);

			assertEquals(
				0, builder.buildChangeSet().count()
			);
		}

		@Test
		@DisplayName(
			"Should return same instance"
				+ " when no real changes"
		)
		void shouldReturnSameInstanceWhenNoRealChanges() {
			final Attributes<EntityAttributeSchemaContract>
				newAttributes =
				new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				)
					.setAttribute("int", 1)
					.setAttribute(
						"range",
						IntegerNumberRange.between(4, 8)
					)
					.setAttribute(
						"bigDecimal",
						new BigDecimal("1.123")
					)
					.setAttribute(
						"greetings",
						Locale.ENGLISH, "Hello"
					)
					.setAttribute(
						"greetings",
						Locale.GERMAN, "Tschüss"
					)
					.build();

			assertSame(initialAttributes, newAttributes);
		}

		@Test
		@DisplayName(
			"Should return new instance"
				+ " when real changes exist"
		)
		void shouldReturnNewInstanceWhenChangesExist() {
			final Attributes<EntityAttributeSchemaContract>
				newAttributes =
				new ExistingEntityAttributesBuilder(
					PRODUCT_SCHEMA, initialAttributes
				)
					.setAttribute("int", 99)
					.build();

			assertTrue(
				initialAttributes != newAttributes
			);
			assertEquals(
				Integer.valueOf(99),
				newAttributes.getAttribute("int")
			);
		}
	}
}
