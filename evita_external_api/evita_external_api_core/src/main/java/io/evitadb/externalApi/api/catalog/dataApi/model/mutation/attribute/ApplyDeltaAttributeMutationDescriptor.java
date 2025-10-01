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

package io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute;

import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.dataType.Any;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing {@link ApplyDeltaAttributeMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface ApplyDeltaAttributeMutationDescriptor extends AttributeMutationDescriptor {

	PropertyDescriptor DELTA = PropertyDescriptor.builder()
		.name("delta")
		.description("""
			Delta to change existing value by of this attribute (negative number produces decremental of
			existing number, positive one incrementation).
			""")
		.type(nonNull(Any.class))
		.build();
	PropertyDescriptor REQUIRED_RANGE_AFTER_APPLICATION = PropertyDescriptor.builder()
		.name("requiredRangeAfterApplication")
		.description("""
			Number range that is tolerated for the value after delta application has been finished to
			verify for example that number of items on stock doesn't go below zero.
			""")
		.type(nullable(Any.class))
		.build();


	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ApplyDeltaAttributeMutation")
		.description("""
			Increments or decrements existing numeric value by specified delta (negative number produces decremental of
			existing number, positive one incrementation).
			
			Allows to specify the number range that is tolerated for the value after delta application has been finished to
			verify for example that number of items on stock doesn't go below zero.
			""")
		.staticFields(List.of(MUTATION_TYPE, NAME, LOCALE, DELTA, REQUIRED_RANGE_AFTER_APPLICATION))
		.build();
}
