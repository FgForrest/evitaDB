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
import io.evitadb.api.requestResponse.data.annotation.ReferenceRef;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntityGroup;
import io.evitadb.api.requestResponse.data.annotation.ScopeReferenceSettings;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;

/**
 * Fixture entity reproducing the bug where @Reference annotations carrying
 * per-scope `facetedPartially`, `bucketed` (histogram) and `bucketedPartially`
 * expressions end up losing those fields in the emitted
 * {@link io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation}.
 *
 * Mirrors the real-world `WithPublishedParameters` trait: a managed reference
 * (default `managed=true`) pointing to a @ReferencedEntity whose body is a
 * full @Entity type, with @ScopeReferenceSettings declaring `facetedPartially`,
 * `bucketed` (@Histogram) and `bucketedPartially` on the LIVE scope.
 */
@Entity
public interface GetterBasedEntityWithFacetedPartiallyAndBucketed {

	String REFERENCE_PARAMETER_VALUES = "parameterValues";
	String INTERVAL_REFERENCE_PARAMETER_VALUES = "intervalParameterValues";

	@PrimaryKey
	int getId();

	@Reference(
		name = REFERENCE_PARAMETER_VALUES,
		scope = {
			@ScopeReferenceSettings(
				scope = Scope.LIVE,
				indexed = ReferenceIndexType.FOR_FILTERING,
				facetedPartially = @Expression(
					"$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX'"
				),
				bucketed = @Histogram(
					nameOfTheIndex = INTERVAL_REFERENCE_PARAMETER_VALUES,
					value = @Expression(
						"$reference.referencedEntity?.attributes['basicUnitValue'] ?? 0.0"
					)
				),
				bucketedPartially = @Expression(
					"$reference.groupEntity?.attributes['inputWidgetType'] == 'INTERVAL_INPUT'"
				)
			),
			@ScopeReferenceSettings(
				scope = Scope.ARCHIVED,
				indexed = ReferenceIndexType.FOR_FILTERING
			)
		}
	)
	ParameterValue[] getParameterValues();

	@ReferenceRef(REFERENCE_PARAMETER_VALUES)
	@Nonnull
	Optional<List<ParameterValue>> getParameterValuesIfAvailable();

	interface ParameterValue {

		@ReferencedEntity
		int getParameterValueId();

		@ReferencedEntityGroup
		int getParameterId();

		@ReferencedEntity
		ReferencedParameterValue getParameterValue();

		@ReferencedEntityGroup
		Parameter getParameter();

	}

	@Entity
	interface ReferencedParameterValue {

		@PrimaryKey
		int getId();

	}

	@Entity
	interface Parameter {

		@PrimaryKey
		int getId();

	}

}
