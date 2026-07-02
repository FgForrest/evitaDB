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
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntityGroup;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;

import java.io.Serializable;

/**
 * Fixture entity declaring an **explicitly empty** `bucketed = {}` array on a
 * `@Reference` annotation — this is the new default of `@Reference#bucketed()`
 * since the array migration, and must be treated as "no histograms" without
 * producing any `ScopedHistogramIndexDefinition` entries and without raising
 * any validation error.
 */
@Entity
public interface GetterBasedEntityWithEmptyBucketedArray {

	String REFERENCE_PARAMETER_VALUES = "parameterValues";

	@PrimaryKey
	int getId();

	@Reference(
		name = REFERENCE_PARAMETER_VALUES,
		managed = false,
		indexed = ReferenceIndexType.FOR_FILTERING,
		bucketed = {}
	)
	ParameterValue[] getParameterValues();

	interface ParameterValue extends Serializable {

		@ReferencedEntity
		int getParameterValueId();

		@ReferencedEntityGroup
		int getParameterId();

	}

}
