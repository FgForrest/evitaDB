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
import javax.annotation.Nullable;

/**
 * Interface provides methods to (re)define schema description.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface NamedSchemaEditor<S extends NamedSchemaEditor<S>> extends NamedSchemaContract {

	/**
	 * Schema is best described by the passed string. Description is expected to be written using
	 * <a href="https://www.markdownguide.org/basic-syntax/">MarkDown syntax</a>. The description should be targeted
	 * on client API developers or users of your data store to facilitate their orientation.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	S withDescription(@Nullable String description);

}
