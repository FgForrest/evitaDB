/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.core.cdc.predicate;

import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.ContainerType;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.EnumSet;

/**
 * Predicate filters out only mutations that are related to local mutations that target classifier with particular type
 * in provided set.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ContainerTypePredicate extends MutationPredicate {
	private final EnumSet<ContainerType> classifierType;

	public ContainerTypePredicate(@Nonnull MutationPredicateContext context, @Nonnull ContainerType... containerType) {
		super(context);
		this.classifierType = EnumSet.copyOf(Arrays.asList(containerType));
	}

	@Override
	public boolean test(Mutation mutation) {
		if (mutation instanceof LocalMutation<?, ?> localMutation) {
			return this.classifierType.contains(localMutation.containerType());
		} else if (mutation instanceof EntityMutation || (mutation instanceof EntitySchemaMutation && !(mutation instanceof LocalEntitySchemaMutation))) {
			return this.classifierType.contains(ContainerType.ENTITY);
		} else if (mutation instanceof CatalogSchemaMutation) {
			return this.classifierType.contains(ContainerType.CATALOG);
		} else {
			return false;
		}
	}
}
