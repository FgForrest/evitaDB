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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder;

import graphql.schema.GraphQLFieldDefinition.Builder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceBigDecimalFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLArgumentTransformer;

import javax.annotation.Nonnull;


/**
 * Extension of {@link NonNullBigDecimalFieldDecorator} to add currency-related formatting parameter to the field.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class PriceBigDecimalFieldDecorator extends NonNullBigDecimalFieldDecorator {

	public PriceBigDecimalFieldDecorator(@Nonnull PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer) {
		super(argumentBuilderTransformer);
	}

	@Override
	public void accept(Builder builder) {
		super.accept(builder);
		builder.argument(PriceBigDecimalFieldHeaderDescriptor.WITH_CURRENCY.to(this.argumentBuilderTransformer));
	}
}
