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

package io.evitadb.test.client.query;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.BeforeEach;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Abstract test class for testing implementations of {@link ConstraintToJsonConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
abstract class AbstractConstraintToJsonConverterTest {

	protected final static JsonNodeFactory jsonNodeFactory = new JsonNodeFactory(true);

	protected Map<String, EntitySchemaContract> entitySchemaIndex;
	protected CatalogSchemaContract catalogSchema;

	@BeforeEach
	void init() {
		this.entitySchemaIndex = new HashMap<>();
		this.catalogSchema = CatalogSchema._internalBuild(
			TestConstants.TEST_CATALOG,
			Map.of(),
			null,
			EnumSet.allOf(CatalogEvolutionMode.class),
			new EntitySchemaProvider() {
				@Nonnull
				@Override
				public Collection<EntitySchemaContract> getEntitySchemas() {
					return AbstractConstraintToJsonConverterTest.this.entitySchemaIndex.values();
				}

				@Nonnull
				@Override
				public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
					return Optional.ofNullable(AbstractConstraintToJsonConverterTest.this.entitySchemaIndex.get(entityType));
				}
			}
		);

		final EntitySchemaContract productSchema = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			EntitySchema._internalBuild(Entities.PRODUCT)
		)
			.withPrice()
			.withAttribute("CODE", String.class)
			.withAttribute("AGE", Integer.class)
			.withReferenceToEntity(Entities.CATEGORY, Entities.CATEGORY, Cardinality.ONE_OR_MORE, thatIs -> thatIs
				.withAttribute("CODE", String.class)
				.withGroupTypeRelatedToEntity("categoryGroup"))
			.withReferenceToEntity(Entities.BRAND, Entities.BRAND, Cardinality.EXACTLY_ONE, thatIs -> thatIs
				.withGroupTypeRelatedToEntity("brandGroup"))
			.toInstance();

		this.entitySchemaIndex.put(Entities.PRODUCT, new EntitySchemaDecorator(() -> this.catalogSchema, (EntitySchema) productSchema));

		final EntitySchemaContract categorySchema = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			EntitySchema._internalBuild(Entities.CATEGORY)
		)
			.withPrice()
			.withAttribute("NAME", String.class)
			.withReferenceToEntity("RELATED_PRODUCTS", Entities.PRODUCT, Cardinality.ONE_OR_MORE, thatIs -> thatIs.withAttribute("ORDER", Integer.class))
			.toInstance();
		this.entitySchemaIndex.put(Entities.CATEGORY, new EntitySchemaDecorator(() -> this.catalogSchema, (EntitySchema) categorySchema));

		final EntitySchemaContract brandSchema = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			EntitySchema._internalBuild(Entities.BRAND)
		)
			.toInstance();
		this.entitySchemaIndex.put(Entities.BRAND, new EntitySchemaDecorator(() -> this.catalogSchema, (EntitySchema) brandSchema));

		// managed group entity schemas — needed because GroupHaving's @Child(domain = GROUP_ENTITY)
		// switches the constraint resolver into the group entity's schema lookup path
		final EntitySchemaContract categoryGroupSchema = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			EntitySchema._internalBuild("categoryGroup")
		)
			.withAttribute("NAME", String.class)
			.toInstance();
		this.entitySchemaIndex.put("categoryGroup", new EntitySchemaDecorator(() -> this.catalogSchema, (EntitySchema) categoryGroupSchema));

		final EntitySchemaContract brandGroupSchema = new InternalEntitySchemaBuilder(
			this.catalogSchema,
			EntitySchema._internalBuild("brandGroup")
		)
			.toInstance();
		this.entitySchemaIndex.put("brandGroup", new EntitySchemaDecorator(() -> this.catalogSchema, (EntitySchema) brandGroupSchema));
	}
}
