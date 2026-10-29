/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.api.requestResponse.data.annotation.AnalysisStepDef;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.ScopeAttributeSettings;
import io.evitadb.api.requestResponse.data.annotation.StepParameter;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import java.io.Serializable;

/** Model declaring normalization on entity, reference and global attributes alongside scoped indexing. */
@Entity(name = GetterBasedEntityNormalizedEvolutionV1.ENTITY_NAME)
public interface GetterBasedEntityNormalizedEvolutionV1 {
	/** Shared entity name for normalization evolution tests. */
	String ENTITY_NAME = "NormalizedEvolution";

	/**
	 * Primary key of the model.
	 *
	 * @return primary key of the entity
	 */
	@PrimaryKey
	int getId();

	/**
	 * Entity attribute with an ordered normalization chain and archived-only filtering.
	 *
	 * @return value of the `code` attribute
	 */
	@Attribute(
		normalized = {
			@AnalysisStepDef(step = AnalysisSteps.LOWERCASE),
			@AnalysisStepDef(
				step = AnalysisSteps.REMOVE_CHARACTERS,
				parameters = @StepParameter(name = AnalysisSteps.CHARACTERS, value = "- ")
			)
		},
		scope = @ScopeAttributeSettings(scope = Scope.ARCHIVED, filterable = true)
	)
	@Nonnull
	String getCode();

	/**
	 * Global attribute with normalization shared across its uses.
	 *
	 * @return value of the global `normalizedGlobalCode` attribute
	 */
	@Attribute(global = true, normalized = @AnalysisStepDef(step = AnalysisSteps.ASCII_FOLDING))
	@Nonnull
	String getNormalizedGlobalCode();

	/**
	 * Unmanaged reference carrying an array attribute.
	 *
	 * @return the unmanaged `brand` reference
	 */
	@Reference(managed = false)
	@Nonnull
	Brand getBrand();

	/** Reference model used by the annotation mapping tests. */
	interface Brand extends Serializable {
		/**
		 * Key of the referenced brand.
		 *
		 * @return primary key of the referenced brand
		 */
		@ReferencedEntity
		int getBrand();

		/**
		 * Array attribute with trimming described by annotation.
		 *
		 * @return values of the `labels` reference attribute
		 */
		@Attribute(normalized = @AnalysisStepDef(step = AnalysisSteps.TRIM))
		@Nonnull
	String[] getLabels();
	}
}
