/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.requestResponse.schema.model.evolution;

import io.evitadb.api.requestResponse.data.annotation.Attribute;

import javax.annotation.Nonnull;

/**
 * V2 evolution: narrows the `code` attribute from `nullable = true` back to non-null by using
 * the default `@Attribute` settings.
 *
 * Mirrors the V1 entity name so the analyzer re-evaluates the same existing schema.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface GetterBasedEntityNullableEvolutionV2NarrowToNonNull
	extends GetterBasedEntityNullableEvolutionV1 {

	@Override
	@Attribute
	@Nonnull
	String getCode();

}
