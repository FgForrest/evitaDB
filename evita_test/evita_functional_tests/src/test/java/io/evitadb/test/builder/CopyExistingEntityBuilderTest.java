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

package io.evitadb.test.builder;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that {@link CopyExistingEntityBuilder} rebuilds an entity read from one evitaDB for
 * insertion into another - copying attributes verbatim, and re-keying unique String attributes when
 * the primary key is overridden so the copy stays uniquely identifiable.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(CONTRACT)
@Tag(ATTRIBUTE)
@DisplayName("CopyExistingEntityBuilder")
class CopyExistingEntityBuilderTest {

	private static final String CODE = "CODE";
	private static final String NAME = "NAME";
	private static final String EAN = "EAN";

	/**
	 * Builds a bare catalog schema usable as the context for the entity schemas below.
	 */
	@Nonnull
	private static CatalogSchemaContract catalogSchema(@Nonnull Map<String, EntitySchemaContract> index) {
		return CatalogSchema._internalBuild(
			TestConstants.TEST_CATALOG,
			Map.of(),
			null,
			EnumSet.allOf(CatalogEvolutionMode.class),
			new EntitySchemaProvider() {
				@Nonnull
				@Override
				public Collection<EntitySchemaContract> getEntitySchemas() {
					return index.values();
				}

				@Nonnull
				@Override
				public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
					return Optional.ofNullable(index.get(entityType));
				}
			}
		);
	}

	/**
	 * Builds a source entity with a unique String {@link #CODE} attribute and a plain {@link #NAME}.
	 */
	@Nonnull
	private static EntityContract sourceEntityWithUniqueCode(int primaryKey, @Nonnull String code) {
		final Map<String, EntitySchemaContract> index = new HashMap<>();
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			catalogSchema(index),
			EntitySchema._internalBuild(Entities.PRODUCT)
		)
			.withAttribute(CODE, String.class, whichIs -> whichIs.unique())
			.withAttribute(NAME, String.class)
			.toInstance();
		index.put(Entities.PRODUCT, schema);

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema, primaryKey);
		builder.setAttribute(CODE, code);
		builder.setAttribute(NAME, "irrelevant");
		return builder.toInstance();
	}

	@Test
	@DisplayName("should copy attributes unchanged when the primary key is not overridden")
	void shouldCopyAttributesWhenPrimaryKeyUnchanged() {
		final EntityContract source = sourceEntityWithUniqueCode(1, "apple");
		final CopyExistingEntityBuilder copy = new CopyExistingEntityBuilder(source);
		final String code = (String) copy.getAttribute(CODE);
		final String name = (String) copy.getAttribute(NAME);
		assertEquals("apple", code);
		assertEquals("irrelevant", name);
	}

	@Test
	@DisplayName("should suffix a unique String attribute when the primary key is overridden")
	void shouldSuffixUniqueStringAttributeWhenPrimaryKeyOverridden() {
		final EntityContract source = sourceEntityWithUniqueCode(1, "apple");
		final CopyExistingEntityBuilder copy = new CopyExistingEntityBuilder(source, 999);
		final String code = (String) copy.getAttribute(CODE);
		assertEquals("apple_999", code);
	}

	@Test
	@DisplayName("should not suffix when the overridden primary key equals the original one")
	void shouldNotSuffixWhenPrimaryKeyUnchanged() {
		final EntityContract source = sourceEntityWithUniqueCode(42, "apple");
		final CopyExistingEntityBuilder copy = new CopyExistingEntityBuilder(source, 42);
		final String code = (String) copy.getAttribute(CODE);
		assertEquals("apple", code);
	}

	@Test
	@DisplayName("should reject altering a unique non-String attribute with a new primary key")
	void shouldThrowWhenAlteringUniqueNonStringAttributeWithNewPrimaryKey() {
		final Map<String, EntitySchemaContract> index = new HashMap<>();
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			catalogSchema(index),
			EntitySchema._internalBuild(Entities.PRODUCT)
		)
			.withAttribute(EAN, Integer.class, whichIs -> whichIs.unique())
			.toInstance();
		index.put(Entities.PRODUCT, schema);

		final InitialEntityBuilder builder = new InitialEntityBuilder(schema, 1);
		builder.setAttribute(EAN, 123);
		final EntityContract source = builder.toInstance();

		assertThrows(
			EvitaInvalidUsageException.class,
			() -> new CopyExistingEntityBuilder(source, 999)
		);
	}
}
