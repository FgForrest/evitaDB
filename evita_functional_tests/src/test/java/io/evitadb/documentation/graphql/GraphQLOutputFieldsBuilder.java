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

package io.evitadb.documentation.graphql;

import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
public class GraphQLOutputFieldsBuilder {

	private final static String INDENTATION = "  ";

	private final int offset;
	private int level = 1;
	private final List<String> lines = new LinkedList<>();

	public GraphQLOutputFieldsBuilder(int offset) {
		this.offset = offset;
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addPrimitiveField(@Nonnull PropertyDescriptor propertyDescriptor) {
		return addPrimitiveField(propertyDescriptor.name());
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addPrimitiveField(@Nonnull String name) {
		lines.add(getCurrentIndentation() + name);
		return this;
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addObjectField(@Nonnull PropertyDescriptor propertyDescriptor,
	                                                 @Nonnull Consumer<GraphQLOutputFieldsBuilder> objectFieldsBuilder) {
		return addObjectField(propertyDescriptor.name(), objectFieldsBuilder);
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addObjectField(@Nonnull String name, @Nonnull Consumer<GraphQLOutputFieldsBuilder> objectFieldsBuilder) {
		lines.add(getCurrentIndentation() + name + " {");
		level++;

		objectFieldsBuilder.accept(this);

		level--;
		lines.add(getCurrentIndentation() + "}");

		return this;
	}

	public String build() {
		Assert.isPremiseValid(
			level == 1,
			"Premature build, level is not back at 1."
		);
		return String.join("\n", lines);
	}

	@Nonnull
	private String getCurrentIndentation() {
		return INDENTATION.repeat(offset + level);
	}
}
