/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint;

import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintPropertyType;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ReferenceDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint.ConstraintDescriptorParser.ParsedConstraintDescriptor;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.wildfly.common.Assert.assertTrue;

/**
 * Tests for {@link ConstraintDescriptorParser}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class ConstraintDescriptorParserTest {

	private static ConstraintDescriptorParser parser;

	@BeforeAll
	static void setup() {
		final Map<String, EntitySchemaContract> entitySchemaIndex = new HashMap<>();
		final CatalogSchemaContract catalogSchema = CatalogSchema._internalBuild(TestConstants.TEST_CATALOG, Map.of(), entitySchemaIndex::get);

		final EntitySchemaContract productSchema = new InternalEntitySchemaBuilder(
			catalogSchema,
			EntitySchema._internalBuild(Entities.PRODUCT)
		)
			.withPrice()
			.withAttribute("code", String.class)
			.withAttribute("age", Integer.class)
			.withReferenceToEntity(Entities.CATEGORY, Entities.CATEGORY, Cardinality.ONE_OR_MORE, thatIs -> thatIs.withAttribute("order", Integer.class))
			.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.EXACTLY_ONE)
			.toInstance();
		entitySchemaIndex.put(Entities.PRODUCT, productSchema);

		final EntitySchemaContract categorySchema = new InternalEntitySchemaBuilder(
			catalogSchema,
			EntitySchema._internalBuild(Entities.CATEGORY)
		)
			.withPrice()
			.withAttribute("code", String.class)
			.toInstance();
		entitySchemaIndex.put(Entities.CATEGORY, categorySchema);

		parser = new ConstraintDescriptorParser(catalogSchema, ConstraintType.FILTER);
	}

	@Test
	void shouldCorrectlyParseConstraintKey() {
		final Optional<ParsedConstraintDescriptor> parsedAttributeConstraint = parser.parse(
			new ConstraintResolveContext(new EntityDataLocator(Entities.PRODUCT)),
			"attributeCodeEquals"
		);
		assertEquals(
			parsedAttributeConstraint.orElseThrow(),
			new ParsedConstraintDescriptor(
				"attributeCodeEquals",
				ConstraintPropertyType.ATTRIBUTE,
				"code",
				"equals",
				ConstraintDescriptorProvider.getConstraints(AttributeEquals.class).iterator().next(),
				new EntityDataLocator(Entities.PRODUCT)
			)
		);

		final Optional<ParsedConstraintDescriptor> parsedEntityFromReferenceConstraint = parser.parse(
			new ConstraintResolveContext(new ReferenceDataLocator(Entities.PRODUCT, Entities.CATEGORY)),
			"entityHaving"
		);
		assertEquals(
			parsedEntityFromReferenceConstraint.orElseThrow(),
			new ParsedConstraintDescriptor(
				"entityHaving",
				ConstraintPropertyType.ENTITY,
				null,
				"having",
				ConstraintDescriptorProvider.getConstraints(EntityHaving.class).iterator().next(),
				new EntityDataLocator(Entities.CATEGORY)
			)
		);

		final Optional<ParsedConstraintDescriptor> parsedHierarchyConstraint = parser.parse(
			new ConstraintResolveContext(new EntityDataLocator(Entities.PRODUCT)),
			"hierarchyCategoryWithin"
		);
		assertEquals(
			parsedHierarchyConstraint.orElseThrow(),
			new ParsedConstraintDescriptor(
				"hierarchyCategoryWithin",
				ConstraintPropertyType.HIERARCHY,
				Entities.CATEGORY,
				"within",
				ConstraintDescriptorProvider.getConstraints(HierarchyWithin.class)
					.stream()
					.filter(it -> it.fullName().equals("within"))
					.findFirst()
					.orElseThrow(),
				new HierarchyDataLocator(Entities.PRODUCT, Entities.CATEGORY)
			)
		);
	}

	@Test
	void shouldNotParseConstraintKey() {
		assertTrue(
			parser.parse(
				new ConstraintResolveContext(new EntityDataLocator(Entities.PRODUCT)),
				"attributeEquals"
			).isEmpty()
		);
		assertTrue(
			parser.parse(
				new ConstraintResolveContext(new EntityDataLocator(Entities.PRODUCT)),
				"attributeCodeNot"
			).isEmpty()
		);
	}
}
