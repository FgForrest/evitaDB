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
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Base V1 field-based class for schema evolution tests.
 * Defines a minimal schema with primary key, one attribute, and one reference.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity(name = FieldBasedEntityEvolutionV1.ENTITY_NAME)
@Data
@NoArgsConstructor
public class FieldBasedEntityEvolutionV1 {

	public static final String ENTITY_NAME = "FieldBasedEntityEvolution";

	@PrimaryKey
	private int id;

	@Attribute
	@Nonnull
	private String code;

	@Reference
	private Brand marketingBrand;

	/**
	 * Reference target class with one attribute.
	 */
	@Data
	@NoArgsConstructor
	public static class Brand {

		@ReferencedEntity
		private int brand;

		@Attribute
		private String market;

	}

}
