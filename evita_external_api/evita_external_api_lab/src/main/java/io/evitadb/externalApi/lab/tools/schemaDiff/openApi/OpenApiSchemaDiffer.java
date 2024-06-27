/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.lab.tools.schemaDiff.openApi;

import io.evitadb.externalApi.lab.tools.schemaDiff.openApi.SchemaDiff.Change;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import org.openapitools.openapidiff.core.compare.OpenApiDiff;
import org.openapitools.openapidiff.core.model.ChangedOpenApi;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.List;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createLinkedHashSet;

/**
 * Compares and analyzes differences between two OpenAPI schemas.
 *
 * Note: This class is not thread-safe. New instance should be created for each comparison.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@NotThreadSafe
public class OpenApiSchemaDiffer {

	private final OpenAPI oldSchema;
	private final OpenAPI newSchema;

	private final Set<Change> breakingChanges = createLinkedHashSet(20);
	private final Set<Change> nonBreakingChanges = createLinkedHashSet(20);

	/**
	 * Compare and analyze differences between two OpenAPI schema definitions
	 *
	 * @param oldSchemaDefinition string representation of the old schema
	 * @param newSchemaDefinition string representation of the new schema
	 * @return diff of changes in new schema compared to the old schema
	 */
	@Nonnull
	public static SchemaDiff analyze(@Nonnull String oldSchemaDefinition, @Nonnull String newSchemaDefinition) {
		final OpenApiSchemaDiffer differ = new OpenApiSchemaDiffer(oldSchemaDefinition, newSchemaDefinition);
		differ.analyze();
		return new SchemaDiff(differ.breakingChanges, differ.nonBreakingChanges);
	}

	private OpenApiSchemaDiffer(@Nonnull String oldSchemaDefinition, @Nonnull String newSchemaDefinition) {
		this.oldSchema = parseOpenApiSchema(oldSchemaDefinition);
		this.newSchema = parseOpenApiSchema(newSchemaDefinition);
	}

	private void analyze() {
		final ChangedOpenApi rawDiffResult = OpenApiDiff.compare(oldSchema, newSchema);
		new StructuredMarkdownRender()
			.renderStructured(rawDiffResult)
			.forEach(change -> {
				if (change.breaking()) {
					breakingChanges.add(change);
				} else {
					nonBreakingChanges.add(change);
				}
			});
	}

	private static OpenAPI parseOpenApiSchema(@Nonnull String schemaDefinition) {
		return new OpenAPIV3Parser().readContents(schemaDefinition, List.of(), null).getOpenAPI();
	}
}
