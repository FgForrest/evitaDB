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

package io.evitadb.test.client.query;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Constraint to JSON converter for {@link io.evitadb.api.query.RequireConstraint}s.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class RequireConstraintToJsonConverter extends ConstraintToJsonConverter {

	public RequireConstraintToJsonConverter(@Nonnull CatalogSchemaContract catalogSchema,
	                                        @Nonnull AtomicReference<FilterConstraintToJsonConverter> filterConstraintToJsonConverter,
	                                        @Nonnull AtomicReference<OrderConstraintToJsonConverter> orderConstraintToJsonConverter) {
		super(
			catalogSchema,
			Map.of(
				ConstraintType.FILTER, filterConstraintToJsonConverter,
				ConstraintType.ORDER, orderConstraintToJsonConverter
			)
		);
	}

	public RequireConstraintToJsonConverter(@Nonnull CatalogSchemaContract catalogSchema,
	                                        @Nonnull Predicate<Class<? extends Constraint<?>>> constraintPredicate,
	                                        @Nonnull AtomicReference<FilterConstraintToJsonConverter> filterConstraintToJsonConverter,
	                                        @Nonnull AtomicReference<OrderConstraintToJsonConverter> orderConstraintToJsonConverter) {
		super(
			catalogSchema,
			constraintPredicate,
			Map.of(
				ConstraintType.FILTER, filterConstraintToJsonConverter,
				ConstraintType.ORDER, orderConstraintToJsonConverter
			)
		);
	}

	@Nonnull
	@Override
	protected ConstraintDescriptor getDefaultRootConstraintContainerDescriptor() {
		return ConstraintDescriptorProvider.getConstraint(Require.class);
	}
}
