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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.ReferencesEditor.ReferencesBuilder;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies contract of {@link ExistingReferenceBuilder}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ExistingReferenceBuilder")
class ExistingReferenceBuilderTest extends AbstractBuilderTest {
	private ReferenceContract initialReference;
	private final HashMap<String, AttributeSchemaContract>
		attributeTypes = new HashMap<>(4);

	@BeforeEach
	void setUp() {
		this.initialReference = new InitialReferenceBuilder(
			PRODUCT_SCHEMA,
			ReferencesBuilder.createImplicitSchema(
				PRODUCT_SCHEMA,
				"brand",
				"brand",
				Cardinality.ZERO_OR_ONE,
				null
			),
			"brand",
			5,
			-4,
			this.attributeTypes
		)
			.setAttribute("brandPriority", 154L)
			.setAttribute(
				"country", Locale.ENGLISH, "Great Britain"
			)
			.setAttribute(
				"country", Locale.CANADA, "Canada"
			)
			.setGroup("group", 78)
			.build();
	}

	@Nested
	@DisplayName("Modifying attributes")
	class ModifyingAttributesTest {

		@Test
		@DisplayName("should modify attributes")
		void shouldModifyAttributes() {
			final ReferenceBuilder builder =
				new ExistingReferenceBuilder(
					ExistingReferenceBuilderTest.this
						.initialReference,
					PRODUCT_SCHEMA,
					ExistingReferenceBuilderTest.this
						.attributeTypes
				)
					.setAttribute("brandPriority", 155L)
					.removeAttribute(
						"country", Locale.ENGLISH
					)
					.setAttribute("newAttribute", "Hi");

			assertEquals(
				155L,
				(Long) builder.getAttribute(
					"brandPriority"
				)
			);
			assertEquals(
				"Canada",
				builder.getAttribute(
					"country", Locale.CANADA
				)
			);
			assertNull(
				builder.getAttribute(
					"country", Locale.ENGLISH
				)
			);
			assertEquals(
				"Hi",
				builder.getAttribute("newAttribute")
			);

			final ReferenceContract reference =
				builder.build();

			assertEquals(
				155L,
				(Long) reference.getAttribute(
					"brandPriority"
				)
			);
			assertEquals(
				"Canada",
				reference.getAttribute(
					"country", Locale.CANADA
				)
			);
			assertEquals(
				"Great Britain",
				reference.getAttribute(
					"country", Locale.ENGLISH
				)
			);
			assertEquals(
				"Hi",
				reference.getAttribute("newAttribute")
			);

			final AttributeValue gbCountry =
				reference.getAttributeValue(
					"country", Locale.ENGLISH
				).orElseThrow();
			assertTrue(gbCountry.dropped());
		}

		@Test
		@DisplayName(
			"should set new attribute on existing ref"
		)
		void shouldSetNewAttributeOnExistingReference() {
			final ReferenceBuilder builder =
				new ExistingReferenceBuilder(
					ExistingReferenceBuilderTest.this
						.initialReference,
					PRODUCT_SCHEMA,
					ExistingReferenceBuilderTest.this
						.attributeTypes
				)
					.setAttribute("website", "example.com");

			assertEquals(
				"example.com",
				builder.getAttribute("website")
			);

			final ReferenceContract reference =
				builder.build();
			assertEquals(
				"example.com",
				reference.getAttribute("website")
			);
			// original attributes remain untouched
			assertEquals(
				154L,
				(Long) reference.getAttribute(
					"brandPriority"
				)
			);
		}

		@Test
		@DisplayName(
			"should override localized attribute"
		)
		void shouldOverrideLocalizedAttribute() {
			final ReferenceBuilder builder =
				new ExistingReferenceBuilder(
					ExistingReferenceBuilderTest.this
						.initialReference,
					PRODUCT_SCHEMA,
					ExistingReferenceBuilderTest.this
						.attributeTypes
				)
					.setAttribute(
						"country", Locale.ENGLISH, "UK"
					);

			assertEquals(
				"UK",
				builder.getAttribute(
					"country", Locale.ENGLISH
				)
			);
			assertEquals(
				"Canada",
				builder.getAttribute(
					"country", Locale.CANADA
				)
			);
		}
	}

	@Nested
	@DisplayName("No-op and deduplication")
	class NoOpAndDeduplicationTest {

		@Test
		@DisplayName(
			"should skip mutations that mean no change"
		)
		void shouldSkipMutationsThatMeansNoChange() {
			final ReferenceBuilder builder =
				new ExistingReferenceBuilder(
					ExistingReferenceBuilderTest.this
						.initialReference,
					PRODUCT_SCHEMA,
					ExistingReferenceBuilderTest.this
						.attributeTypes
				)
					.setAttribute("brandPriority", 154L)
					.setAttribute(
						"country",
						Locale.ENGLISH,
						"Changed name"
					)
					.setAttribute(
						"country",
						Locale.ENGLISH,
						"Great Britain"
					)
					.setGroup("group", 78);

			assertEquals(
				0, builder.buildChangeSet().count()
			);
		}
	}

	@Nested
	@DisplayName("Group management")
	class GroupManagementTest {

		@Test
		@DisplayName("should modify reference group")
		void shouldModifyReferenceGroup() {
			final ReferenceBuilder builder =
				new ExistingReferenceBuilder(
					ExistingReferenceBuilderTest.this
						.initialReference,
					PRODUCT_SCHEMA,
					ExistingReferenceBuilderTest.this
						.attributeTypes
				)
					.setGroup("newGroup", 77);

			assertEquals(
				new GroupEntityReference(
					"newGroup", 77, 2, false
				),
				builder.getGroup().orElse(null)
			);

			final ReferenceContract reference =
				builder.build();

			assertEquals(
				new GroupEntityReference(
					"newGroup", 77, 2, false
				),
				reference.getGroup().orElse(null)
			);
		}

		@Test
		@DisplayName("should remove reference group")
		void shouldRemoveReferenceGroup() {
			final ReferenceBuilder builder =
				new ExistingReferenceBuilder(
					ExistingReferenceBuilderTest.this
						.initialReference,
					PRODUCT_SCHEMA,
					ExistingReferenceBuilderTest.this
						.attributeTypes
				)
					.removeGroup();

			assertTrue(builder.getGroup().isEmpty());

			final ReferenceContract reference =
				builder.build();

			assertTrue(reference.getGroup().isEmpty());
		}

		@Test
		@DisplayName(
			"should set group on reference without group"
		)
		void shouldSetGroupOnReferenceWithoutGroup() {
			// build a reference without group first
			final ReferenceContract noGroupRef =
				new InitialReferenceBuilder(
					PRODUCT_SCHEMA,
					ReferencesBuilder.createImplicitSchema(
						PRODUCT_SCHEMA,
						"tag",
						"tag",
						Cardinality.ZERO_OR_MORE,
						null
					),
					"tag",
					1,
					-1,
					ExistingReferenceBuilderTest.this
						.attributeTypes
				)
					.build();

			assertTrue(noGroupRef.getGroup().isEmpty());

			final ReferenceBuilder builder =
				new ExistingReferenceBuilder(
					noGroupRef,
					PRODUCT_SCHEMA,
					ExistingReferenceBuilderTest.this
						.attributeTypes
				)
					.setGroup("myGroup", 42);

			assertEquals(
				42,
				builder.getGroup()
					.orElseThrow()
					.getPrimaryKey()
			);
		}

		@Test
		@DisplayName(
			"should skip no-op group set"
		)
		void shouldSkipNoOpGroupSet() {
			final ReferenceBuilder builder =
				new ExistingReferenceBuilder(
					ExistingReferenceBuilderTest.this
						.initialReference,
					PRODUCT_SCHEMA,
					ExistingReferenceBuilderTest.this
						.attributeTypes
				)
					.setGroup("group", 78);

			assertEquals(
				0, builder.buildChangeSet().count()
			);
		}
	}

	@Nested
	@DisplayName("Change set")
	class ChangeSetTest {

		@Test
		@DisplayName(
			"should build change set for attribute changes"
		)
		void shouldBuildChangeSetForAttributeChanges() {
			final ReferenceBuilder builder =
				new ExistingReferenceBuilder(
					ExistingReferenceBuilderTest.this
						.initialReference,
					PRODUCT_SCHEMA,
					ExistingReferenceBuilderTest.this
						.attributeTypes
				)
					.setAttribute("brandPriority", 200L)
					.setAttribute("newAttr", "value");

			assertEquals(
				2, builder.buildChangeSet().count()
			);
		}

		@Test
		@DisplayName(
			"should build change set for group change"
		)
		void shouldBuildChangeSetForGroupChange() {
			final ReferenceBuilder builder =
				new ExistingReferenceBuilder(
					ExistingReferenceBuilderTest.this
						.initialReference,
					PRODUCT_SCHEMA,
					ExistingReferenceBuilderTest.this
						.attributeTypes
				)
					.setGroup("newGroup", 99);

			assertEquals(
				1, builder.buildChangeSet().count()
			);
		}

		@Test
		@DisplayName(
			"should build empty change set"
		)
		void shouldBuildEmptyChangeSet() {
			final ReferenceBuilder builder =
				new ExistingReferenceBuilder(
					ExistingReferenceBuilderTest.this
						.initialReference,
					PRODUCT_SCHEMA,
					ExistingReferenceBuilderTest.this
						.attributeTypes
				);

			assertEquals(
				0, builder.buildChangeSet().count()
			);
		}
	}

	@Nested
	@DisplayName("Build behavior")
	class BuildBehaviorTest {

		@Test
		@DisplayName(
			"should build reference with dropped attribute"
		)
		void shouldBuildReferenceWithDroppedAttribute() {
			final ReferenceContract reference =
				new ExistingReferenceBuilder(
					ExistingReferenceBuilderTest.this
						.initialReference,
					PRODUCT_SCHEMA,
					ExistingReferenceBuilderTest.this
						.attributeTypes
				)
					.removeAttribute("brandPriority")
					.build();

			final AttributeValue brandPriority =
				reference.getAttributeValue(
					"brandPriority"
				).orElseThrow();
			assertTrue(brandPriority.dropped());
			assertEquals(2L, brandPriority.version());
		}

		@Test
		@DisplayName(
			"should increment version on attr change"
		)
		void shouldIncrementVersionOnAttributeChange() {
			final ReferenceContract reference =
				new ExistingReferenceBuilder(
					ExistingReferenceBuilderTest.this
						.initialReference,
					PRODUCT_SCHEMA,
					ExistingReferenceBuilderTest.this
						.attributeTypes
				)
					.setAttribute("brandPriority", 999L)
					.build();

			assertEquals(
				2L,
				reference.getAttributeValue(
					"brandPriority"
				).orElseThrow().version()
			);
		}
	}

	@Nested
	@DisplayName("Identity")
	class IdentityTest {

		@Test
		@DisplayName(
			"should return original when nothing changed"
		)
		void shouldReturnOriginalReferenceInstanceWhenNothingHasChanged() {
			final ReferenceContract reference =
				new ExistingReferenceBuilder(
					ExistingReferenceBuilderTest.this
						.initialReference,
					PRODUCT_SCHEMA,
					ExistingReferenceBuilderTest.this
						.attributeTypes
				)
					.setAttribute("brandPriority", 154L)
					.setAttribute(
						"country",
						Locale.ENGLISH,
						"Great Britain"
					)
					.setAttribute(
						"country",
						Locale.CANADA,
						"Canada"
					)
					.setGroup("group", 78)
					.build();

			assertSame(
				ExistingReferenceBuilderTest.this
					.initialReference,
				reference
			);
		}

		@Test
		@DisplayName(
			"should return new instance after modification"
		)
		void shouldReturnNewInstanceAfterModification() {
			final ReferenceContract reference =
				new ExistingReferenceBuilder(
					ExistingReferenceBuilderTest.this
						.initialReference,
					PRODUCT_SCHEMA,
					ExistingReferenceBuilderTest.this
						.attributeTypes
				)
					.setAttribute("brandPriority", 999L)
					.build();

			assertNotSame(
				ExistingReferenceBuilderTest.this
					.initialReference,
				reference
			);
			assertEquals(
				999L,
				(Long) reference.getAttribute(
					"brandPriority"
				)
			);
		}
	}
}
