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

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown when two parallel processes try to alter the schema of the entity type collection.
 * This problem can be resolved only by repeating the schema alter operation based on the current version of the schema.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ConcurrentSchemaUpdateException extends SchemaAlteringException {
	@Serial private static final long serialVersionUID = 1176617132327079985L;

	public ConcurrentSchemaUpdateException(@Nonnull CatalogSchemaContract currentSchema, @Nonnull CatalogSchemaContract newSchema) {
		super(
			"Cannot update catalog schema `" + currentSchema.getName() + "` - someone else altered the schema in the meanwhile (current version is " +
				currentSchema.version() + ", yours is " + newSchema.version() + ")."
		);
	}

	public ConcurrentSchemaUpdateException(@Nonnull EntitySchemaContract currentSchema, @Nonnull EntitySchemaContract newSchema) {
		super(
			"Cannot update entity schema `" + currentSchema.getName() + "` - someone else altered the schema in the meanwhile (current version is " +
				currentSchema.version() + ", yours is " + newSchema.version() + ")."
		);
	}

}
