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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;

import javax.annotation.Nonnull;
import java.util.function.BooleanSupplier;

/**
 * Interface follows the <a href="https://en.wikipedia.org/wiki/Builder_pattern">builder pattern</a> allowing to alter
 * the data that are available on the read-only {@link EntityAttributeSchemaContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface EntityAttributeSchemaEditor<T extends EntityAttributeSchemaEditor<T>> extends AttributeSchemaEditor<T> {

	/**
	 * If an attribute is flagged as representative, it should be used in developer tools along with the entity's
	 * primary key to describe the entity or reference to that entity. The flag is completely optional and doesn't
	 * affect the core functionality of the database in any way. However, if it's used correctly, it can be very
	 * helpful to developers in quickly finding their way around the data. There should be very few representative
	 * attributes in the entity type, and the unique ones are usually the best to choose.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T representative();

	/**
	 * If an attribute is flagged as representative, it should be used in developer tools along with the entity's
	 * primary key to describe the entity or reference to that entity. The flag is completely optional and doesn't
	 * affect the core functionality of the database in any way. However, if it's used correctly, it can be very
	 * helpful to developers in quickly finding their way around the data. There should be very few representative
	 * attributes in the entity type, and the unique ones are usually the best to choose.
	 *
	 * @param decider returns true when attribute should be representative
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T representative(@Nonnull BooleanSupplier decider);

	/**
	 * Interface that simply combines {@link EntityAttributeSchemaEditor} and {@link GlobalAttributeSchemaContract}
	 * entity contracts together. Builder produces either {@link EntitySchemaMutation} that describes all changes
	 * to be made on {@link EntitySchemaContract} instance to get it to "up-to-date" state or can provide already built
	 * {@link EntitySchemaContract} that may not represent globally "up-to-date" state because it is based on
	 * the version of the entity known when builder was created.
	 *
	 * Mutation allows Evita to perform surgical updates on the latest version of the {@link EntitySchemaContract}
	 * object that is in the database at the time update request arrives.
	 */
	interface EntityAttributeSchemaBuilder extends EntityAttributeSchemaEditor<EntityAttributeSchemaBuilder> {
	}

}
