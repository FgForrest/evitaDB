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

import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when
 * {@link io.evitadb.api.EvitaSessionContract#defineEntitySchemaFromModelClass(Class)} fails to
 * generate or modify an entity schema from a Java class due to structural errors, invalid
 * annotations, or incompatible types.
 *
 * evitaDB supports reflection-based schema generation, where annotated Java classes (records,
 * POJOs, or interfaces) serve as schema blueprints. The {@link io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer}
 * inspects the class structure and annotations to produce an entity schema.
 *
 * This exception is raised when the analyzer encounters issues such as:
 *
 * - Invalid or conflicting annotations (e.g., `@Attribute`, `@Reference`, `@AssociatedData`)
 * - Unsupported data types for attributes or associated data
 * - Missing required annotations or malformed annotation parameters
 * - Reflection access failures (e.g., inaccessible fields or methods)
 * - Circular dependencies in nested data structures
 * - Type mismatches between declared types and evitaDB's supported types
 *
 * The underlying cause is wrapped in this exception to provide context about which specific
 * validation or reflection operation failed. This allows developers to diagnose and fix the
 * model class structure.
 *
 * **Resolution**: Review the model class for annotation correctness, type compatibility, and
 * accessibility. Ensure all annotated fields and methods conform to evitaDB's schema generation
 * requirements.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class SchemaClassInvalidException extends SchemaAlteringException {
	@Serial private static final long serialVersionUID = -5406849919777450870L;
	/**
	 * The Java class that failed schema analysis.
	 */
	@Getter private final Class<?> modelClass;

	/**
	 * Constructs a new exception indicating that schema generation from a model class failed.
	 *
	 * @param modelClass the Java class that could not be analyzed for schema generation
	 * @param cause      the underlying exception that caused the analysis to fail, providing details
	 *                   about what went wrong during reflection or validation
	 */
	public SchemaClassInvalidException(Class<?> modelClass, @Nonnull Throwable cause) {
		super("Failed to examine class `" + modelClass + "` and alter the entity collection schema.", cause);
		this.modelClass = modelClass;
	}

}
