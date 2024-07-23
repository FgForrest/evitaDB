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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder;

import graphql.schema.GraphQLFieldDefinition.Builder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.BigDecimalFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLArgumentTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static graphql.schema.GraphQLNonNull.nonNull;

/**
 * Sets field to non-null big decimal and adds parameter for formatting the output value.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class NonNullBigDecimalFieldDecorator implements FieldDecorator {

	@Nonnull protected final PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer;

	@Override
	public void accept(Builder builder) {
		builder.type(nonNull(GraphQLScalars.BIG_DECIMAL));
		builder.argument(BigDecimalFieldHeaderDescriptor.FORMATTED.to(argumentBuilderTransformer));
	}
}
