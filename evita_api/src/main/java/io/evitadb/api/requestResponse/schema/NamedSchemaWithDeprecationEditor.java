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

/**
 * Interface provides methods to make schema deprecated or revert such deprecation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface NamedSchemaWithDeprecationEditor<S extends NamedSchemaWithDeprecationEditor<S>> extends NamedSchemaEditor<S> {

	/**
	 * Marking schema as deprecated allows you to inform users of your client API that this type of schema is
	 * planned for removal in the future. You should also describe the reasons behind this decision in the form
	 * of deprecation notice. Description is expected to be written using
	 * <a href="https://www.markdownguide.org/basic-syntax/">MarkDown syntax</a>.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	S deprecated(@Nonnull String deprecationNotice);

	/**
	 * This method should be used carefully only in case some reference were marked as deprecated by mistake.
	 * Use it with caution, this may really confuse the users of your client API.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	S notDeprecatedAnymore();

}
