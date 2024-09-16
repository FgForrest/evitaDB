/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.store.entity.serializer;

import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import java.util.Deque;
import java.util.LinkedList;
import java.util.function.Supplier;

import static java.util.Optional.ofNullable;

/**
 * This context is used to pass actual {@link EntitySchema} reference to Kryo deserializer on deep levels
 * of the deserialization chain. Unfortunately the schema needs to be referenced on multiple places in the entity
 * to avoid circular references and also to allow schema validation as early as possible.
 *
 * Use method {@link #executeWithSchemaContext(EntitySchema, Runnable)} to pass current entity schema to the block of code
 * that deserialized sth entity contents from the binary stream.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class EntitySchemaContext {
	private static final ThreadLocal<Deque<EntitySchema>> ENTITY_SCHEMA_SUPPLIER = new ThreadLocal<>();

	/**
	 * This method allows setting context-sensitive entity schema to the deserialization process. This is necessary
	 * to correctly and consistently schema during deserialization.
	 */
	public static <T> T executeWithSchemaContext(@Nonnull EntitySchema entitySchema, @Nonnull Supplier<T> lambda) {
		final boolean entitySchemaMissing = ENTITY_SCHEMA_SUPPLIER.get() == null;
		final Deque<EntitySchema> existingSchemaSet;
		if (entitySchemaMissing) {
			existingSchemaSet = new LinkedList<>();
			ENTITY_SCHEMA_SUPPLIER.set(existingSchemaSet);
		} else {
			existingSchemaSet = ENTITY_SCHEMA_SUPPLIER.get();
		}

		try {
			existingSchemaSet.push(entitySchema);
			return lambda.get();
		} finally {
			existingSchemaSet.pop();
			if (entitySchemaMissing) {
				ENTITY_SCHEMA_SUPPLIER.remove();
			}
		}
	}

	/**
	 * Returns currently initialized {@link EntitySchema} for this entity. When the method is called outside
	 * {@link #executeWithSchemaContext(EntitySchema, Supplier)} context it throws exception.
	 */
	@Nonnull
	public static EntitySchema getEntitySchema() {
		return ofNullable(ENTITY_SCHEMA_SUPPLIER.get())
			.filter(it -> !it.isEmpty())
			.map(Deque::peek)
			.orElseThrow(() -> new GenericEvitaInternalError("Entity schema was not initialized in EntitySchemaContext!"));
	}

	private EntitySchemaContext() {
	}

}
