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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.exception;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown when there is attempt to order by an attribute that is not marked as `sortable`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AttributeNotSortableException extends EvitaInvalidUsageException {

	@Serial private static final long serialVersionUID = 6859221281683133623L;

	public AttributeNotSortableException(@Nonnull String attributeName, @Nonnull CatalogSchemaContract catalogSchema) {
		super(
			"Global attribute schema with name `" + attributeName + "` in catalog `" + catalogSchema.getName() + "` is not " +
				"marked as \"sortable\" and cannot be ordered by. Ordering by it without having an index would be slow."
		);
	}

	public AttributeNotSortableException(@Nonnull String attributeName, @Nonnull EntitySchemaContract entitySchema) {
		super(
			"Attribute with name `" + attributeName + "` in entity `" + entitySchema.getName() + "` is not " +
				"marked as \"sortable\" and cannot be ordered by. Ordering by it without having an index would be slow."
		);
	}

	public AttributeNotSortableException(@Nonnull String attributeName, @Nonnull ReferenceSchemaContract referenceSchema, @Nonnull EntitySchemaContract entitySchema) {
		super(
			"Attribute with name `" + attributeName + "` in reference `" + referenceSchema.getName() + "` of entity " +
				"`" + entitySchema.getName() + "` is not " +
				"marked as \"sortable\" and cannot be ordered by. Ordering by it without having an index would be slow."
		);
	}

}
