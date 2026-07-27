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

package io.evitadb.api.requestResponse.schema.model;

import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.Expression;
import io.evitadb.api.requestResponse.data.annotation.Histogram;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntityGroup;
import io.evitadb.api.requestResponse.data.annotation.ScopeReferenceSettings;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.dataType.Scope;

import java.io.Serializable;

/**
 * Fixture entity declaring two `@Histogram` entries in the `bucketed` array of a
 * `@ScopeReferenceSettings` annotation for `Scope.ARCHIVED`, plus a second
 * `@ScopeReferenceSettings` for `Scope.LIVE` with no histograms at all.
 *
 * The analyzer must emit two distinct `ScopedHistogramIndexDefinition` entries
 * both tagged `Scope.ARCHIVED`, and zero entries for `Scope.LIVE` — proving the
 * per-scope histogram list is keyed by scope and does not bleed across scopes.
 */
@Entity
public interface GetterBasedEntityWithPerScopeMultipleBucketed {

	String REFERENCE_PARAMETER_VALUES = "parameterValues";
	String HISTOGRAM_PRICE = "priceHist";
	String HISTOGRAM_QUANTITY = "quantityHist";
	String EXPR_PRICE = "$reference.referencedEntity?.attributes['price'] ?? 0.0";
	String EXPR_QUANTITY = "$reference.referencedEntity?.attributes['quantity'] ?? 0.0";

	@PrimaryKey
	int getId();

	@Reference(
		name = REFERENCE_PARAMETER_VALUES,
		managed = false,
		scope = {
			@ScopeReferenceSettings(
				scope = Scope.ARCHIVED,
				indexed = ReferenceIndexType.FOR_FILTERING,
				bucketed = {
					@Histogram(
						nameOfTheIndex = HISTOGRAM_PRICE,
						value = @Expression(EXPR_PRICE)
					),
					@Histogram(
						nameOfTheIndex = HISTOGRAM_QUANTITY,
						value = @Expression(EXPR_QUANTITY)
					)
				}
			),
			@ScopeReferenceSettings(
				scope = Scope.LIVE,
				indexed = ReferenceIndexType.FOR_FILTERING
			)
		}
	)
	ParameterValue[] getParameterValues();

	interface ParameterValue extends Serializable {

		@ReferencedEntity
		int getParameterValueId();

		@ReferencedEntityGroup
		int getParameterId();

	}

}
