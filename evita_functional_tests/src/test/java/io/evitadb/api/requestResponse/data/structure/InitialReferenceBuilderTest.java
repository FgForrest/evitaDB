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

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.mutation.SchemaEvolvingLocalMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaDecorator;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies contract of {@link InitialReferenceBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class InitialReferenceBuilderTest extends AbstractBuilderTest {

	@Test
	void shouldCreateReference() {
		final ReferenceBuilder builder = new InitialReferenceBuilder(
			PRODUCT_SCHEMA,
			Reference.createImplicitSchema("brand", "brand", Cardinality.ZERO_OR_ONE, null),
			"brand",
			5,
			-1
		)
			.setAttribute("brandPriority", 154L)
			.setAttribute("country", Locale.ENGLISH, "Great Britain")
			.setAttribute("country", Locale.CANADA, "Canada")
			.setGroup("group", 78);

		assertEquals(
			new ReferenceKey("brand", 5),
			builder.getReferenceKey()
		);
		assertEquals(
			new GroupEntityReference("group", 78, 1, false),
			builder.getGroup().orElse(null)
		);
		assertEquals(154L, (Long) builder.getAttribute("brandPriority"));
		assertEquals("Great Britain", builder.getAttribute("country", Locale.ENGLISH));
		assertEquals("Canada", builder.getAttribute("country", Locale.CANADA));

		final ReferenceContract reference = builder.build();

		assertEquals(
			new ReferenceKey("brand", 5),
			reference.getReferenceKey()
		);
		assertEquals(
			new GroupEntityReference("group", 78, 1, false),
			reference.getGroup().orElse(null)
		);
		assertEquals(154L, (Long) reference.getAttribute("brandPriority"));
		assertEquals("Great Britain", reference.getAttribute("country", Locale.ENGLISH));
		assertEquals("Canada", reference.getAttribute("country", Locale.CANADA));

		final CatalogSchemaBuilder catalogSchemaBuilder = new CatalogSchemaDecorator(CATALOG_SCHEMA).openForWrite();
		final EntitySchemaBuilder entitySchemaBuilder = new EntitySchemaDecorator(() -> CATALOG_SCHEMA, PRODUCT_SCHEMA).openForWrite();
		builder.buildChangeSet()
			.filter(SchemaEvolvingLocalMutation.class::isInstance)
			.forEach(it -> ((SchemaEvolvingLocalMutation<?, ?>) it).verifyOrEvolveSchema(
				catalogSchemaBuilder, entitySchemaBuilder
			)
		);

		final EntitySchemaContract updatedSchema = entitySchemaBuilder.toInstance();
		final Map<String, AttributeSchemaContract> brandRefAttributes = updatedSchema.getReference("brand").orElseThrow().getAttributes();
		assertFalse(brandRefAttributes.isEmpty());
		final AttributeSchemaContract brandPriority = brandRefAttributes.get("brandPriority");
		assertNotNull(brandPriority);
		assertEquals(Long.class, brandPriority.getType());
		assertFalse(brandPriority.isLocalized());

		final AttributeSchemaContract brandCountry = brandRefAttributes.get("country");
		assertNotNull(brandCountry);
		assertEquals(String.class, brandCountry.getType());
		assertTrue(brandCountry.isLocalized());
	}

	@Test
	void shouldOverwriteReferenceData() {
		final ReferenceBuilder builder = new InitialReferenceBuilder(
			PRODUCT_SCHEMA,
			Reference.createImplicitSchema("brand", "brand", Cardinality.ZERO_OR_ONE, null),
			"brand",
			5,
			-4
		)
			.setAttribute("brandPriority", 154L)
			.setAttribute("brandPriority", 155L)
			.setAttribute("country", Locale.ENGLISH, "Great Britain")
			.setAttribute("country", Locale.ENGLISH, "Great Britain #2")
			.setAttribute("country", Locale.CANADA, "Canada")
			.setAttribute("country", Locale.CANADA, "Canada #2")
			.setGroup("group", 78)
			.setGroup("group", 79);

		assertEquals(
			new ReferenceKey("brand", 5),
			builder.getReferenceKey()
		);
		assertEquals(
			new GroupEntityReference("group", 79, 1, false),
			builder.getGroup().orElse(null)
		);
		assertEquals(155L, (Long) builder.getAttribute("brandPriority"));
		assertEquals("Great Britain #2", builder.getAttribute("country", Locale.ENGLISH));
		assertEquals("Canada #2", builder.getAttribute("country", Locale.CANADA));

		final ReferenceContract reference = builder.build();

		assertEquals(
			new ReferenceKey("brand", 5),
			reference.getReferenceKey()
		);
		assertEquals(
			new GroupEntityReference("group", 79, 1, false),
			reference.getGroup().orElse(null)
		);
		assertEquals(155L, (Long) reference.getAttribute("brandPriority"));
		assertEquals("Great Britain #2", reference.getAttribute("country", Locale.ENGLISH));
		assertEquals("Canada #2", reference.getAttribute("country", Locale.CANADA));
	}


	@Test
	void shouldSetAndRemoveGroupWithoutType() {
		final ReferenceBuilder builder = new InitialReferenceBuilder(
			PRODUCT_SCHEMA,
			Reference.createImplicitSchema("brand", "brand", Cardinality.ZERO_OR_ONE, null),
			"brand",
			5,
			-1
		).setGroup("brandGroup", 42);

		assertTrue(builder.getGroup().isPresent());
		assertEquals(42, builder.getGroup().orElseThrow().primaryKey());
		assertEquals("brandGroup", builder.getGroup().orElseThrow().getType());

		builder.removeGroup();
		assertTrue(builder.getGroup().isEmpty());
	}

	@Test
	void shouldProduceProperChangeSetForReference() {
		final ReferenceBuilder builder = new InitialReferenceBuilder(
			PRODUCT_SCHEMA,
			Reference.createImplicitSchema("brand", "brand", Cardinality.ZERO_OR_ONE, null),
			"brand",
			5,
			-1
		)
			.setAttribute("brandPriority", 154L)
			.setGroup("group", 78);

		final List<? extends ReferenceMutation<?>> mutations = builder.buildChangeSet().toList();

		assertEquals(3, mutations.size());
		assertTrue(mutations.stream().anyMatch(InsertReferenceMutation.class::isInstance));
		assertTrue(mutations.stream().anyMatch(SetReferenceGroupMutation.class::isInstance));
		assertTrue(builder.buildChangeSet().anyMatch(ReferenceAttributeMutation.class::isInstance));
	}

}
