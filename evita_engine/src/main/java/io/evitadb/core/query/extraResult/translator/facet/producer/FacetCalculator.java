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

package io.evitadb.core.query.extraResult.translator.facet.producer;

import io.evitadb.api.query.filter.UserFilter;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Facet calculator computes how many entities posses the specified facet respecting current {@link EvitaRequest}
 * filtering query except contents of the {@link UserFilter}. It means that it respects all mandatory filtering
 * constraints which gets enriched by additional query that represents single facet. The result of the query
 * represents the number of products having such facet.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface FacetCalculator {

	/**
	 * Method returns {@link Bitmap} of all entity primary keys that posses facet of `facetId`. The bitmap
	 * respects all mandatory filtering constraints which gets enriched by additional query that represents single
	 * facet.
	 *
	 * @param referenceSchema {@link ReferenceSchema} of the facet
	 * @param facetId         {@link EntityReference#getPrimaryKey()} of the facet
	 * @param facetGroupId    {@link GroupEntityReference#getPrimaryKey()} the facet is part of
	 * @param facetEntityIds  bitmaps that represent primary keys of all entities that posses this facet
	 * @return computed {@link Formula} that returns all entity primary keys, that posses the facet
	 */
	@Nonnull
	Formula createCountFormula(
		@Nonnull ReferenceSchemaContract referenceSchema,
		int facetId,
		@Nullable Integer facetGroupId,
		@Nonnull Bitmap[] facetEntityIds
	);

	/**
	 * Method returns {@link Bitmap} of all entity primary keys that posses any facet of group `facetGroupId`.
	 * The bitmap respects all mandatory filtering constraints which gets enriched by additional query that represents
	 * single facet.
	 *
	 * @param referenceSchema {@link ReferenceSchema} of the facet
	 * @param facetGroupId    {@link GroupEntityReference#getPrimaryKey()} the facet is part of
	 * @param facetEntityIds  bitmaps that represent primary keys of all entities that posses this facet
	 * @return computed {@link Formula} that returns all entity primary keys, that posses the facet
	 */
	@Nonnull
	Formula createGroupCountFormula(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer facetGroupId,
		@Nonnull Bitmap[] facetEntityIds
	);

}
