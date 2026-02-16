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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferencesEditor.ReferencesBuilder;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ExistingReferenceAttributesBuilder} verifying
 * modification operations on already existing
 * {@link ReferenceAttributes} containers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ExistingReferenceAttributesBuilder")
class ExistingReferenceAttributesBuilderTest
	extends AbstractBuilderTest {

	private ReferenceSchemaContract referenceSchema;
	private ReferenceAttributes initialAttributes;
	private Map<String, AttributeSchemaContract> attributeTypes;

	@BeforeEach
	void setUp() {
		this.referenceSchema =
			ReferencesBuilder.createImplicitSchema(
				PRODUCT_SCHEMA,
				"brand", "brand",
				Cardinality.ZERO_OR_MORE, null
			);
		this.attributeTypes = new HashMap<>(4);

		this.initialAttributes =
			new InitialReferenceAttributesBuilder(
				PRODUCT_SCHEMA,
				this.referenceSchema,
				this.attributeTypes
			)
				.setAttribute("priority", 154L)
				.setAttribute(
					"country",
					Locale.ENGLISH,
					"Great Britain"
				)
				.setAttribute(
					"country",
					Locale.GERMAN, "Germany"
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
		void shouldOverrideExistingAttribute() {
			final ExistingReferenceAttributesBuilder
				builder =
				new ExistingReferenceAttributesBuilder(
					PRODUCT_SCHEMA,
					referenceSchema,
					initialAttributes
						.getAttributeValues(),
					attributeTypes
				).setAttribute("priority", 200L);

			assertEquals(
				Long.valueOf(200L),
				builder.getAttribute("priority")
			);

			final Attributes<AttributeSchemaContract>
				newVersion = builder.build();

			assertEquals(
				Long.valueOf(200L),
				newVersion.getAttribute("priority")
			);
			assertEquals(
				2L,
				newVersion.getAttributeValue(
					new AttributeKey("priority")
				).orElseThrow().version()
			);
		}

		@Test
		@DisplayName(
			"Should skip no-op mutations"
				+ " when setting same value"
		)
		void shouldSkipNoOpMutations() {
			final ExistingReferenceAttributesBuilder
				builder =
				new ExistingReferenceAttributesBuilder(
					PRODUCT_SCHEMA,
					referenceSchema,
					initialAttributes
						.getAttributeValues(),
					attributeTypes
				)
					.setAttribute("priority", 154L)
					.setAttribute(
						"country",
						Locale.ENGLISH,
						"Great Britain"
					);

			assertEquals(
				0, builder.buildChangeSet().count()
			);
		}

		@Test
		@DisplayName(
			"Should add new attribute"
				+ " to existing container"
		)
		void shouldAddNewAttribute() {
			final ExistingReferenceAttributesBuilder
				builder =
				new ExistingReferenceAttributesBuilder(
					PRODUCT_SCHEMA,
					referenceSchema,
					initialAttributes
						.getAttributeValues(),
					attributeTypes
				).setAttribute("score", 42);

			assertEquals(
				Integer.valueOf(42),
				builder.getAttribute("score")
			);

			final Attributes<AttributeSchemaContract>
				newVersion = builder.build();

			assertEquals(
				Integer.valueOf(42),
				newVersion.getAttribute("score")
			);
		}

		@Test
		@DisplayName(
			"Should add localized attribute"
		)
		void shouldAddLocalizedAttribute() {
			final ExistingReferenceAttributesBuilder
				builder =
				new ExistingReferenceAttributesBuilder(
					PRODUCT_SCHEMA,
					referenceSchema,
					initialAttributes
						.getAttributeValues(),
					attributeTypes
				).setAttribute(
					"country",
					Locale.FRENCH, "France"
				);

			assertEquals(
				"France",
				builder.getAttribute(
					"country", Locale.FRENCH
				)
			);
			// existing locales untouched
			assertEquals(
				"Great Britain",
				builder.getAttribute(
					"country", Locale.ENGLISH
				)
			);
			assertEquals(
				"Germany",
				builder.getAttribute(
					"country", Locale.GERMAN
				)
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
			final ExistingReferenceAttributesBuilder
				builder =
				new ExistingReferenceAttributesBuilder(
					PRODUCT_SCHEMA,
					referenceSchema,
					initialAttributes
						.getAttributeValues(),
					attributeTypes
				).removeAttribute("priority");

			assertNull(
				builder.getAttribute("priority")
			);

			final Attributes<AttributeSchemaContract>
				newVersion = builder.build();

			final AttributeValue attributeValue =
				newVersion.getAttributeValue(
					new AttributeKey("priority")
				).orElseThrow();
			assertEquals(
				2L, attributeValue.version()
			);
			assertTrue(attributeValue.dropped());
		}

		@Test
		@DisplayName(
			"Should remove only targeted"
				+ " localized attribute"
		)
		void shouldRemoveLocalizedAttribute() {
			final ExistingReferenceAttributesBuilder
				builder =
				new ExistingReferenceAttributesBuilder(
					PRODUCT_SCHEMA,
					referenceSchema,
					initialAttributes
						.getAttributeValues(),
					attributeTypes
				).removeAttribute(
					"country", Locale.GERMAN
				);

			assertNull(
				builder.getAttribute(
					"country", Locale.GERMAN
				)
			);
			assertEquals(
				"Great Britain",
				builder.getAttribute(
					"country", Locale.ENGLISH
				)
			);

			final Attributes<AttributeSchemaContract>
				newVersion = builder.build();

			final AttributeValue german =
				newVersion.getAttributeValue(
					new AttributeKey(
						"country", Locale.GERMAN
					)
				).orElseThrow();
			assertEquals(2L, german.version());
			assertTrue(german.dropped());

			final AttributeValue english =
				newVersion.getAttributeValue(
					new AttributeKey(
						"country", Locale.ENGLISH
					)
				).orElseThrow();
			assertEquals(1L, english.version());
			assertFalse(english.dropped());
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
			final ExistingReferenceAttributesBuilder
				builder =
				new ExistingReferenceAttributesBuilder(
					PRODUCT_SCHEMA,
					referenceSchema,
					initialAttributes
						.getAttributeValues(),
					attributeTypes
				)
					.setAttribute("priority", 999L)
					.removeAttribute(
						"country", Locale.ENGLISH
					)
					.setAttribute(
						"country",
						Locale.FRENCH, "France"
					);

			assertEquals(
				3,
				(int) builder.buildChangeSet().count()
			);
		}

		@Test
		@DisplayName(
			"Should not differ"
				+ " when no real changes"
		)
		void shouldNotDifferWhenNoRealChanges() {
			final ExistingReferenceAttributesBuilder
				builder =
				new ExistingReferenceAttributesBuilder(
					PRODUCT_SCHEMA,
					referenceSchema,
					initialAttributes
						.getAttributeValues(),
					attributeTypes
				)
					.setAttribute("priority", 154L)
					.setAttribute(
						"country",
						Locale.ENGLISH,
						"Great Britain"
					)
					.setAttribute(
						"country",
						Locale.GERMAN, "Germany"
					);

			final Attributes<AttributeSchemaContract>
				newAttributes = builder.build();

			assertFalse(builder.differs(newAttributes));
		}

		@Test
		@DisplayName(
			"Should produce empty change set"
				+ " when no modifications"
		)
		void shouldProduceEmptyChangeSet() {
			final ExistingReferenceAttributesBuilder
				builder =
				new ExistingReferenceAttributesBuilder(
					PRODUCT_SCHEMA,
					referenceSchema,
					initialAttributes
						.getAttributeValues(),
					attributeTypes
				);

			assertEquals(
				0, builder.buildChangeSet().count()
			);
		}

		@Test
		@DisplayName(
			"Should differ"
				+ " when real changes exist"
		)
		void shouldDifferWhenChangesExist() {
			final ExistingReferenceAttributesBuilder
				builder =
				new ExistingReferenceAttributesBuilder(
					PRODUCT_SCHEMA,
					referenceSchema,
					initialAttributes
						.getAttributeValues(),
					attributeTypes
				)
					.setAttribute("priority", 999L);

			final Attributes<AttributeSchemaContract>
				newAttributes = builder.build();

			assertTrue(builder.differs(newAttributes));
			assertEquals(
				Long.valueOf(999L),
				newAttributes.getAttribute("priority")
			);
		}
	}
}
