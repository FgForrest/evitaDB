/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.rest.api.openApi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * OpenAPI schema validator.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class OpenApiReferenceValidator {
	private final HashSet<String> visitedSchemas;
	@Getter private final HashSet<String> missingSchemas;
	@SuppressWarnings("rawtypes") private final Map<String, Schema> schemas;

	public OpenApiReferenceValidator(@Nonnull OpenAPI openAPI) {
		this.schemas = openAPI.getComponents().getSchemas();
		this.visitedSchemas = createHashSet(100);
		this.missingSchemas = createHashSet(100);
	}

	/**
	 * Validates schema references i.e. for each reference must exist real schema object.
	 *
	 * @return <code>true</code> when schema is valid.
	 */
	public Set<String> validateSchemaReferences() {
		this.schemas.values().forEach(this::validateSchemaReferences);
		return this.missingSchemas;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void validateSchemaReferences(@Nonnull Schema schema) {
		if (this.visitedSchemas.contains(schema.getName())) {
			return;
		} else if (schema.getName() != null) {
			this.visitedSchemas.add(schema.getName());
		}

		if (schema instanceof ArraySchema arraySchema) {
			validateSchemaReferences(arraySchema.getItems());
		} else if (schema.get$ref() != null) {
			final String schemaName = SchemaUtils.getSchemaNameFromReference(schema.get$ref());
			if (!this.schemas.containsKey(schemaName)) {
				this.missingSchemas.add(schemaName);
			}
		} else {
			if (schema.getProperties() != null) {
				final Collection<Schema> values = schema.getProperties().values();
				values.forEach(this::validateSchemaReferences);
			}

			if(schema.getAllOf() != null) {
				final List<Schema> allOf = schema.getAllOf();
				allOf.forEach(this::validateSchemaReferences);
			}

			if(schema.getAnyOf() != null) {
				final List<Schema> anyOf = schema.getAnyOf();
				anyOf.forEach(this::validateSchemaReferences);
			}

			if(schema.getOneOf() != null) {
				final List<Schema> oneOf = schema.getOneOf();
				oneOf.forEach(this::validateSchemaReferences);
			}
		}
	}
}
