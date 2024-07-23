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

package io.evitadb.api.exception;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown when client code tries to define the attribute with same name as existing catalog attribute.
 * This is not allowed and client must choose different name or reuse the already defined attribute on catalog level.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AttributeAlreadyPresentInCatalogSchemaException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -3205733087469383992L;
	@Getter private final String catalogName;
	@Getter private final AttributeSchemaContract existingSchema;

	public AttributeAlreadyPresentInCatalogSchemaException(@Nonnull String catalogName, @Nonnull AttributeSchemaContract existingSchema) {
		super("Attribute `" + existingSchema.getName() + "` is already defined as global attribute of catalog `" + catalogName + "`, use `withGlobalAttribute` method to reuse it!");
		this.catalogName = catalogName;
		this.existingSchema = existingSchema;
	}

}
