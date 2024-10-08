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

package io.evitadb.api.exception;

import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown when schema receives alteration command that cannot be performed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class InvalidSchemaMutationException extends SchemaAlteringException {
	@Serial private static final long serialVersionUID = -570962599377067302L;

	public InvalidSchemaMutationException(@Nonnull String message) {
		super(message);
	}

	public InvalidSchemaMutationException(@Nonnull String entityType, @Nonnull CatalogEvolutionMode necessaryEvolutionMode) {
		this(
			"The entity collection `" + entityType + "` doesn't exist and would be automatically created," +
				" providing that catalog schema allows `" + necessaryEvolutionMode + "`" +
				" evolution mode."
		);
	}

}
