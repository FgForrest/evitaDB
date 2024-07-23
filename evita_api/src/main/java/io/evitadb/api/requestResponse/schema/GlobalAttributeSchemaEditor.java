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
 * the data that are available on the read-only {@link GlobalAttributeSchemaContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface GlobalAttributeSchemaEditor<T extends GlobalAttributeSchemaEditor<T>> extends EntityAttributeSchemaEditor<T> {

	/**
	 * When attribute is unique globally it is automatically filterable, and it is ensured there is exactly one single
	 * entity having certain value of this attribute in entire {@link io.evitadb.api.CatalogContract}.
	 * {@link AttributeSchemaContract#getType() Type} of the unique attribute must implement {@link Comparable}
	 * interface.
	 *
	 * As an example of unique attribute can be URL - there is no sense in having two entities with same URL, and it's
	 * better to have this ensured by the database engine.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T uniqueGlobally();

	/**
	 * When attribute is unique globally it is automatically filterable, and it is ensured there is exactly one single
	 * entity having certain value of this attribute in entire {@link io.evitadb.api.CatalogContract}.
	 * {@link AttributeSchemaContract#getType() Type} of the unique attribute must implement {@link Comparable}
	 * interface.
	 *
	 * As an example of unique attribute can be URL - there is no sense in having two entities with same URL, and it's
	 * better to have this ensured by the database engine.
	 *
	 * @param decider returns true when attribute should be unique globally
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T uniqueGlobally(@Nonnull BooleanSupplier decider);

	/**
	 * When attribute is unique globally it is automatically filterable, and it is ensured there is exactly one single
	 * entity having certain value of this attribute in entire {@link io.evitadb.api.CatalogContract}.
	 * {@link AttributeSchemaContract#getType() Type} of the unique attribute must implement {@link Comparable}
	 * interface.
	 *
	 * As an example of unique attribute can be URL - there is no sense in having two entities with same URL, and it's
	 * better to have this ensured by the database engine.
	 *
	 * This method differs from {@link #uniqueGlobally()} in that it is possible to have multiple entities with same
	 * value of this attribute as long as the attribute is {@link #isLocalized()} and the values relate to different
	 * locales.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T uniqueGloballyWithinLocale();

	/**
	 * When attribute is unique globally it is automatically filterable, and it is ensured there is exactly one single
	 * entity having certain value of this attribute in entire {@link io.evitadb.api.CatalogContract}.
	 * {@link AttributeSchemaContract#getType() Type} of the unique attribute must implement {@link Comparable}
	 * interface.
	 *
	 * As an example of unique attribute can be URL - there is no sense in having two entities with same URL, and it's
	 * better to have this ensured by the database engine.
	 *
	 * This method differs from {@link #uniqueGlobally(BooleanSupplier)} in that it is possible to have multiple
	 * entities with same value of this attribute as long as the attribute is {@link #isLocalized()} and the values
	 * relate to different locales.
	 *
	 * @param decider returns true when attribute should be unique globally
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T uniqueGloballyWithinLocale(@Nonnull BooleanSupplier decider);

	/**
	 * Interface that simply combines {@link GlobalAttributeSchemaEditor} and {@link GlobalAttributeSchemaContract}
	 * entity contracts together. Builder produces either {@link EntitySchemaMutation} that describes all changes
	 * to be made on {@link EntitySchemaContract} instance to get it to "up-to-date" state or can provide already built
	 * {@link EntitySchemaContract} that may not represent globally "up-to-date" state because it is based on
	 * the version of the entity known when builder was created.
	 *
	 * Mutation allows Evita to perform surgical updates on the latest version of the {@link EntitySchemaContract}
	 * object that is in the database at the time update request arrives.
	 */
	interface GlobalAttributeSchemaBuilder extends GlobalAttributeSchemaEditor<GlobalAttributeSchemaEditor.GlobalAttributeSchemaBuilder> {
	}

}
