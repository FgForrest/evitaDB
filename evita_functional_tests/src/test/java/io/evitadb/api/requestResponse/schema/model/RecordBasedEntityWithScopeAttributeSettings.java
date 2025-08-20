/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.ScopeAttributeSettings;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;

/**
 * Example record for ClassSchemaAnalyzerTest demonstrating ScopeAttributeSettings usage with record components.
 * This entity shows how to configure different attribute settings for different scopes using record component annotations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Entity
public record RecordBasedEntityWithScopeAttributeSettings(
	@PrimaryKey
	int id,

	@Attribute
	@Nonnull
	String code,

	/**
	 * Attribute with scope-specific settings - filterable and sortable in LIVE scope only.
	 */
	@Attribute(
		scope = {
			@ScopeAttributeSettings(
				scope = Scope.LIVE,
				filterable = true,
				sortable = true
			)
		}
	)
	String marketingName,

	/**
	 * Attribute with different settings for different scopes.
	 */
	@Attribute(
		scope = {
			@ScopeAttributeSettings(
				scope = Scope.LIVE,
				filterable = true,
				sortable = true
			),
			@ScopeAttributeSettings(
				scope = Scope.ARCHIVED,
				filterable = true,
				sortable = false
			)
		}
	)
	String productCode,

	/**
	 * Attribute with no scope settings - should use defaults (LIVE scope only).
	 */
	@Attribute(filterable = true, sortable = true)
	String defaultAttribute
) {
}