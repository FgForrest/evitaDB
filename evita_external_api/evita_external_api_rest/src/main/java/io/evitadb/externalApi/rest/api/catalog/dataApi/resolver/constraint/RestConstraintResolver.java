/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint.ConstraintResolver;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.exception.ExternalApiInvalidUsageException;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.exception.RestQueryResolvingInternalError;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ancestor for all REST query resolvers. Implements basic resolving logic of {@link ConstraintResolver} specific
 * to REST
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public abstract class RestConstraintResolver<C extends Constraint<?>> extends ConstraintResolver<C> {

	protected RestConstraintResolver(@Nonnull CatalogSchemaContract catalogSchema,
	                                 @Nonnull Map<ConstraintType, AtomicReference<? extends ConstraintResolver<?>>> additionalResolvers) {
		super(catalogSchema, additionalResolvers);
	}

	@Nonnull
	@Override
	protected <T extends ExternalApiInternalError> T createQueryResolvingInternalError(@Nonnull String message) {
		//noinspection unchecked
		return (T) new RestQueryResolvingInternalError(message);
	}

	@Nonnull
	@Override
	protected <T extends ExternalApiInvalidUsageException> T createInvalidArgumentException(@Nonnull String message) {
		//noinspection unchecked
		return (T) new RestInvalidArgumentException(message);
	}
}
