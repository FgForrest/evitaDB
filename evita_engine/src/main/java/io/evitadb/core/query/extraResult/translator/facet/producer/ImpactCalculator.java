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
import io.evitadb.api.requestResponse.extraResult.FacetSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Impact calculator is responsible for computation of {@link RequestImpact} data for each facet that is assigned to
 * any of the product matching current {@link EvitaRequest}. The impact captures the situation how
 * many entities would be added/removed if the facet had been selected.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface ImpactCalculator {
	/**
	 * Blank implementation of this interface always return null {@link RequestImpact} - meaning, that the data was
	 * not computed.
	 */
	ImpactCalculator NO_IMPACT = (entityType, facetId, facetGroupId, required, facetEntityIds) -> null;

	/**
	 * Computes and returns {@link RequestImpact} data. The impact object captures the situation how many products
	 * would be added/removed if the facet had been selected.
	 *
	 * @param referenceSchema {@link ReferenceSchema} of the facet
	 * @param facetId         {@link EntityReference#getPrimaryKey()} of the facet
	 * @param facetGroupId    {@link GroupEntityReference#getPrimaryKey()} the facet is part of
	 * @param required        true if facet is currently selected within {@link UserFilter} of the {@link EvitaRequest}
	 * @param facetEntityIds  bitmaps that represent primary keys of all entities that posses this facet
	 * @return computed {@link RequestImpact} object
	 */
	@Nullable
	RequestImpact calculateImpact(
		@Nonnull ReferenceSchemaContract referenceSchema,
		int facetId,
		@Nullable Integer facetGroupId,
		boolean required,
		@Nonnull Bitmap[] facetEntityIds
	);

}
