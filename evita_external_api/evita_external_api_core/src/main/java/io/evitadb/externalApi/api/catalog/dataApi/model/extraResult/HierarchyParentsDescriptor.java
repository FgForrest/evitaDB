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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.catalog.dataApi.model.extraResult;

import io.evitadb.api.requestResponse.extraResult.HierarchyParents;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents.ParentsByReference;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Represents {@link HierarchyParents}.
 *
 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
 * descriptor are supposed to be dynamically registered to target generated DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface HierarchyParentsDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("*HierarchyParents")
		.description("""
			This DTO contains information about full parent paths of hierarchical entities the requested entity is referencing.
			Information can is usually used when rendering breadcrumb path for the entity.
			""")
		.build();

	PropertyDescriptor SELF = PropertyDescriptor.builder()
		.name("self")
		.description("""
			Computes parents for same entity collection as queried.
			""")
		// type is expected be a parents of entity object
		.build();

	/**
	 * Represents single entry of {@link ParentsByReference#getParents()}.
	 *
	 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
	 * descriptor are supposed to be dynamically registered to target generated DTO.
	 */
	interface ParentsOfEntityDescriptor {

		PropertyDescriptor PRIMARY_KEY = PropertyDescriptor.builder()
			.name("primaryKey")
			.description("""
				Primary key of entity for which parents are present in `parentEntities` and `references`.
				""")
			.type(nonNull(Integer.class))
			.build();
		/**
		 * Equivalent of {@link ParentsByReference#getParentsFor(int)} for single specific entity
		 * defined by {@link #PRIMARY_KEY}.
		 */
		PropertyDescriptor PARENT_ENTITIES = PropertyDescriptor.builder()
			.name("parentEntities")
			.description("""
				Returns entities of all parents of this entity.
				*Note: * if there are parents of more than one hierarchical reference, this throws error
				as it cannot decide which reference to choose. In this case use `references` instead.
				""")
			// type is expected to be a collection of `Entity` objects
			.build();
		/**
		 * Equivalent of {@link ParentsByReference#getParentsFor(int, int)} for single specific entity
		 * defined by {@link #PRIMARY_KEY}.
		 */
		PropertyDescriptor REFERENCES = PropertyDescriptor.builder()
			.name("references")
			.description("""
				Returns entities of all parents of this entity categorized by different hierarchical references
				(if there are multiple parent trees present).
				""")
			// type is expected to be a `ParentsOfReference` object
			.build();

		ObjectDescriptor THIS = ObjectDescriptor.builder()
			.name("*ParentsOfEntity")
			.description("""
				This DTO contains parent entities of single entity from data chunk.
				""")
			.staticFields(List.of(PRIMARY_KEY))
			.build();

		/**
		 * Represents output defined by {@link ParentsByReference#getParentsFor(int, int)} for single specific entity.
		 *
		 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
		 * descriptor are supposed to be dynamically registered to target generated DTO.
		 */
		interface ParentsOfReferenceDescriptor {

			PropertyDescriptor PRIMARY_KEY = PropertyDescriptor.builder()
				.name("primaryKey")
				.description("""
					Primary key of reference (referenced entity) which also defines initial parent entity.
					""")
				.type(nonNull(Integer.class))
				.build();
			PropertyDescriptor PARENT_ENTITIES = PropertyDescriptor.builder()
				.name("parentEntities")
				.description("""
					Actual parent entities starting from this reference.
					""")
				// type is expected to be a collection `Entity` objects
				.build();

			ObjectDescriptor THIS = ObjectDescriptor.builder()
				.name("*ParentsOfReference")
				.description("""
					This DTO contains parents of specific reference.
					""")
				.staticFields(List.of(PRIMARY_KEY))
				.build();
		}
	}
}
