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

import io.evitadb.api.requestResponse.data.annotation.AssociatedData;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Reference;

import javax.annotation.Nonnull;

/**
 * V2 evolution: narrows every previously-true boolean / non-default enum to its default
 * value by overriding each property with bare `@Attribute` / `@AssociatedData` /
 * `@Reference` annotations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface GetterBasedEntityNarrowingEvolutionV2NarrowAll
	extends GetterBasedEntityNarrowingEvolutionV1 {

	@Override
	@Attribute
	String getNullableCode();

	@Override
	@Attribute
	String getLocalizedCode();

	@Override
	@Attribute
	String getRepresentativeCode();

	@Override
	@Attribute
	String getFilterableCode();

	@Override
	@Attribute
	String getSortableCode();

	@Override
	@Attribute
	String getUniqueCode();

	@Override
	@Reference(managed = false)
	@Nonnull
	Brand getFacetedReference();

	@Override
	@AssociatedData
	String getNullableAssoc();

	@Override
	@AssociatedData
	String getLocalizedAssoc();

}
