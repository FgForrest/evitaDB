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

package io.evitadb.externalApi.rest.io.handler.constraint;

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.require.Require;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.GenericDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint.ConstraintResolver;
import io.evitadb.externalApi.rest.exception.OpenApiSchemaBuildingError;
import io.evitadb.externalApi.rest.exception.RESTApiInternalError;
import io.evitadb.externalApi.rest.io.SchemaUtils;
import io.evitadb.externalApi.rest.io.handler.RESTApiContext;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Implementation of {@link ConstraintResolver} for resolving {@link RequireConstraint} usually with {@link io.evitadb.api.query.require.Require}
 * as root container.
 *
 * It doesn't use any `wrapper container` as `require` constraints don't have any `default` container to use and it is
 * not needed.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class RequireConstraintResolver extends RestConstraintResolver<RequireConstraint> {

	public RequireConstraintResolver(@Nonnull RESTApiContext restApiContext, @Nonnull Operation operation) {
		super(restApiContext, operation);
	}

	@Override
	protected Class<RequireConstraint> getConstraintClass() {
		return RequireConstraint.class;
	}

	@Override
	@Nonnull
	protected ConstraintType getConstraintType() {
		return ConstraintType.REQUIRE;
	}

	@Override
	@Nonnull
	protected ConstraintDescriptor getWrapperContainer() {
		throw new RESTApiInternalError("Wrapper container is not supported for `require` constraints.");
	}

	@Nonnull
	@Override
	protected DataLocator getRootDataLocator() {
		return new GenericDataLocator(restApiContext.getEntityType());
	}

	@Nonnull
	@Override
	protected ConstraintDescriptor getRootConstraintContainerDescriptor() {
		final Set<ConstraintDescriptor> descriptors = ConstraintDescriptorProvider.getConstraints(Require.class);
		Assert.isPremiseValid(
			!descriptors.isEmpty(),
			() -> new OpenApiSchemaBuildingError("Could not find `require` require query.")
		);
		Assert.isPremiseValid(
			descriptors.size() == 1,
			() -> new OpenApiSchemaBuildingError(
				"There multiple variants of `require` require query, cannot decide which to choose."
			)
		);
		return descriptors.iterator().next();
	}

	@Override
	protected boolean isChildrenUnique(@Nonnull ChildParameterDescriptor childParameter) {
		return true;
	}

	protected Schema getSchemaFromOperationProperty(@Nonnull String propertyName) {
		return SchemaUtils.getSchemaFromRequire(restApiContext.getOpenApi(), operation, propertyName);
	}
}
