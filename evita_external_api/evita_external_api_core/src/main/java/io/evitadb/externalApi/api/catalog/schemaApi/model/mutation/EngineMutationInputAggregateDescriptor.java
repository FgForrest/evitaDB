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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation;

import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.CreateCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.DuplicateCatalogMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.MakeCatalogAliveMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.ModifyCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.ModifyCatalogSchemaNameMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.RemoveCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.RestoreCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.SetCatalogMutabilityMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.SetCatalogStateMutationDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.transaction.model.mutation.TransactionMutationDescriptor;

import java.util.List;

/**
 * Descriptor interface that defines the structure for aggregated top-level catalog schema mutations
 * in external API representations. This descriptor provides property definitions for various types
 * of catalog schema mutations that can be performed at the top level, including creation, modification,
 * and removal operations.
 *
 * @author Lukáš Hornych, 2023
 */
public interface EngineMutationInputAggregateDescriptor {

	PropertyDescriptor CREATE_CATALOG_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"createCatalogSchemaMutationDescriptor",
		CreateCatalogSchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor RESTORE_CATALOG_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"restoreCatalogSchemaMutationDescriptor",
		RestoreCatalogSchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor DUPLICATE_CATALOG_MUTATION = PropertyDescriptor.nullableFromObject(
		"duplicateCatalogMutationDescriptor",
		DuplicateCatalogMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MAKE_CATALOG_ALIVE_MUTATION = PropertyDescriptor.nullableFromObject(
		"makeCatalogAliveMutationDescriptor",
		MakeCatalogAliveMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor SET_CATALOG_MUTABILITY_MUTATION = PropertyDescriptor.nullableFromObject(
		"setCatalogMutabilityMutationDescriptor",
		SetCatalogMutabilityMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor SET_CATALOG_STATE_MUTATION = PropertyDescriptor.nullableFromObject(
		"setCatalogStateMutationDescriptor",
		SetCatalogStateMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_CATALOG_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyCatalogSchemaMutationDescriptor",
		ModifyCatalogSchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_CATALOG_SCHEMA_NAME_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyCatalogSchemaNameMutationDescriptor",
		ModifyCatalogSchemaNameMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor REMOVE_CATALOG_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"removeCatalogSchemaMutationDescriptor",
		RemoveCatalogSchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor TRANSACTION_MUTATION = PropertyDescriptor.nullableFromObject(
		"transactionMutationDescriptor",
		TransactionMutationDescriptor.THIS_INPUT
	);

	ObjectDescriptor THIS_INPUT = ObjectDescriptor.builder()
		.name("EngineMutationInputAggregate")
		.description("""
            Contains all possible top level catalog schema mutations.
            """)
		.staticProperties(List.of(
			CREATE_CATALOG_SCHEMA_MUTATION,
			RESTORE_CATALOG_SCHEMA_MUTATION,
			DUPLICATE_CATALOG_MUTATION,
			MAKE_CATALOG_ALIVE_MUTATION,
			SET_CATALOG_MUTABILITY_MUTATION,
			SET_CATALOG_STATE_MUTATION,
			MODIFY_CATALOG_SCHEMA_MUTATION,
			MODIFY_CATALOG_SCHEMA_NAME_MUTATION,
			REMOVE_CATALOG_SCHEMA_MUTATION,
			TRANSACTION_MUTATION
		))
		.build();
}
