/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.externalApi.lab.api.model.entity;

import io.evitadb.externalApi.api.catalog.schemaApi.model.ReferenceSchemaDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;

import java.util.List;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
public interface GenericReferenceSchemaDescriptor extends ReferenceSchemaDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ReferenceSchema")
		.description("""
			This is the definition object for reference that is stored along with
			entity. Definition objects allow to describe the structure of the entity type so that
			in any time everyone can consult complete structure of the entity type.
			
			The references refer to other entities (of same or different entity type).
			Allows entity filtering (but not sorting) of the entities by using `facet_{name}_inSet` query
			and statistics computation if when requested. Reference
			is uniquely represented by int positive number (max. 2<sup>63</sup>-1) and entity type and can be
			part of multiple reference groups, that are also represented by int and entity type.
			
			Reference id in one entity is unique and belongs to single reference group id. Among multiple entities reference may be part
			of different reference groups. Referenced entity type may represent type of another Evita entity or may refer
			to anything unknown to Evita that posses unique int key and is maintained by external systems (fe. tag assignment,
			group assignment, category assignment, stock assignment and so on). Not all these data needs to be present in
			Evita.
			
			References may carry additional key-value data linked to this entity relation (fe. item count present on certain stock).
			""")
		.staticFields(List.of(
			NAME,
			NAME_VARIANTS,
			DESCRIPTION,
			DEPRECATION_NOTICE,
			CARDINALITY,
			REFERENCED_ENTITY_TYPE,
			ENTITY_TYPE_NAME_VARIANTS,
			REFERENCED_ENTITY_TYPE_MANAGED,
			REFERENCED_GROUP_TYPE,
			GROUP_TYPE_NAME_VARIANTS,
			REFERENCED_GROUP_TYPE_MANAGED,
			INDEXED,
			FACETED
		))
		.build();
}
