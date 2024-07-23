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

import javax.annotation.Nonnull;
import java.util.function.BooleanSupplier;

/**
 * Interface follows the <a href="https://en.wikipedia.org/wiki/Builder_pattern">builder pattern</a> allowing to alter
 * the data that are available on the read-only {@link AssociatedDataSchemaContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface AssociatedDataSchemaEditor extends
	AssociatedDataSchemaContract, NamedSchemaWithDeprecationEditor<AssociatedDataSchemaEditor> {

	/**
	 * Localized associated data has to be ALWAYS used in connection with specific {@link java.util.Locale}. In other
	 * words - it cannot be stored unless associated locale is also provided.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	AssociatedDataSchemaEditor localized();

	/**
	 * Localized associated data has to be ALWAYS used in connection with specific {@link java.util.Locale}. In other
	 * words - it cannot be stored unless associated locale is also provided.
	 *
	 * @param decider returns true when attribute should be localized
	 * @return builder to continue with configuration
	 */
	@Nonnull
	AssociatedDataSchemaEditor localized(@Nonnull BooleanSupplier decider);

	/**
	 * When attribute is nullable, its values may be missing in the entities. Otherwise, the system will enforce
	 * non-null checks upon upserting of the entity.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	AssociatedDataSchemaEditor nullable();

	/**
	 * When attribute is nullable, its values may be missing in the entities. Otherwise, the system will enforce
	 * non-null checks upon upserting of the entity.
	 *
	 * @param decider returns true when attribute should be localized
	 * @return builder to continue with configuration
	 */
	@Nonnull
	AssociatedDataSchemaEditor nullable(@Nonnull BooleanSupplier decider);

}
