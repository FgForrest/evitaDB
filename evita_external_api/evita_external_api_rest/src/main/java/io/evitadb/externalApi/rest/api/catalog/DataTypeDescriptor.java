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

package io.evitadb.externalApi.rest.api.catalog;

import io.evitadb.externalApi.rest.exception.OpenApiInternalError;
import io.evitadb.utils.Assert;
import lombok.Builder;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Descriptor for data type.
 *
 * @param name name of data type
 * @param description description of data type
 * @param type type of data type
 *
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
@Builder
public record DataTypeDescriptor(@Nonnull String name,
                                 @Nonnull String description,
                                 @Nonnull Class<? extends Serializable> type) {

	public DataTypeDescriptor {
		Assert.isPremiseValid(
			!name.isEmpty(),
			() -> new OpenApiInternalError("Name of data type cannot be empty.")
		);
		Assert.isPremiseValid(
			!description.isEmpty(),
			() -> new OpenApiInternalError("Description of data type cannot be empty.")
		);
		Assert.isPremiseValid(
			type != null,
			() -> new OpenApiInternalError("type of data type cannot be empty.")
		);
	}
}
