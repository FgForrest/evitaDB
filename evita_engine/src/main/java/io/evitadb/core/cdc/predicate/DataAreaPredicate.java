/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import io.evitadb.api.requestResponse.cdc.CaptureSite;
import io.evitadb.api.requestResponse.cdc.DataSite;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * Predicate filters out only mutations that are related to data. Predicate supports creating sub-predicates based on
 * the {@link DataSite} configuration.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public final class DataAreaPredicate extends AreaPredicate {

	public DataAreaPredicate(@Nonnull MutationPredicateContext context) {
		super(context);
	}

	@Override
	public boolean test(Mutation mutation) {
		return mutation instanceof EntityMutation || mutation instanceof LocalMutation<?, ?>;
	}

	@Nonnull
	@Override
	public Optional<MutationPredicate> createSitePredicate(@Nonnull CaptureSite<?> site) {
		final DataSite dataSite = (DataSite) site;
		MutationPredicate dataPredicate = null;
		if (!ArrayUtils.isEmpty(dataSite.operation())) {
			dataPredicate = dataSite.operation().length == 1 ?
				new SingleOperationPredicate(this.context, dataSite.operation()[0]) :
				new OperationPredicate(this.context, dataSite.operation());
		}
		if (dataSite.entityType() != null) {
			dataPredicate = dataPredicate == null ?
				new EntityTypePredicate(this.context, dataSite.entityType()) :
				dataPredicate.and(new EntityTypePredicate(this.context, dataSite.entityType()));
		}
		if (dataSite.entityPrimaryKey() != null) {
			dataPredicate = dataPredicate == null ?
				new EntityPrimaryKeyPredicate(this.context, dataSite.entityPrimaryKey()) :
				dataPredicate.and(new EntityPrimaryKeyPredicate(this.context, dataSite.entityPrimaryKey()));
		}
		if (!ArrayUtils.isEmpty(dataSite.containerType())) {
			final MutationPredicate classifierTypePredicate = dataSite.containerType().length == 1 ?
				new SingleContainerTypePredicate(this.context, dataSite.containerType()[0]) :
				new ContainerTypePredicate(this.context, dataSite.containerType());
			dataPredicate = dataPredicate == null ?
				classifierTypePredicate :
				dataPredicate.and(classifierTypePredicate);
		}
		if (!ArrayUtils.isEmpty(dataSite.containerName())) {
			final MutationPredicate classifierNamePredicate = dataSite.containerName().length == 1 ?
				new SingleContainerNamePredicate(this.context, dataSite.containerName()[0]) :
				new ContainerNamePredicate(this.context, dataSite.containerName());
			dataPredicate = dataPredicate == null ? classifierNamePredicate : dataPredicate.and(classifierNamePredicate);
		}
		return ofNullable(dataPredicate);
	}

}
