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
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

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
	public GraphQLOutputFieldsBuilder addPrimitiveField(@Nonnull PropertyDescriptor fieldDescriptor) {
		return addPrimitiveField(fieldDescriptor.name());
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addPrimitiveField(@Nonnull String fieldName) {
		lines.add(getCurrentIndentation() + fieldName);
		return this;
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addObjectField(@Nonnull PropertyDescriptor fieldDescriptor,
	                                                 @Nonnull Consumer<GraphQLOutputFieldsBuilder> objectFieldsBuilder) {
		return addObjectField(null, fieldDescriptor, objectFieldsBuilder);
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addObjectField(@Nullable String alias,
	                                                 @Nonnull PropertyDescriptor fieldDescriptor,
	                                                 @Nonnull Consumer<GraphQLOutputFieldsBuilder> objectFieldsBuilder) {
		return addObjectField(alias, fieldDescriptor.name(), objectFieldsBuilder);
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addObjectField(@Nonnull String fieldName,
	                                                 @Nonnull Consumer<GraphQLOutputFieldsBuilder> objectFieldsBuilder) {
		return addObjectField(null, fieldName, objectFieldsBuilder);
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addObjectField(@Nullable String alias,
	                                                 @Nonnull String fieldName,
	                                                 @Nonnull Consumer<GraphQLOutputFieldsBuilder> objectFieldsBuilder) {
		lines.add(getCurrentIndentation() + (alias != null ? alias + ": " : "") + fieldName + " {");
		level++;
		objectFieldsBuilder.accept(this);
		level--;
		lines.add(getCurrentIndentation() + "}");

		return this;
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addObjectField(@Nonnull PropertyDescriptor fieldDescriptor,
	                                                 @Nullable Consumer<GraphQLOutputFieldsBuilder> argumentsBuilder,
	                                                 @Nonnull Consumer<GraphQLOutputFieldsBuilder> objectFieldsBuilder) {
		return addObjectField(null, fieldDescriptor, argumentsBuilder, objectFieldsBuilder);
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addObjectField(@Nonnull String alias,
	                                                 @Nonnull PropertyDescriptor fieldDescriptor,
	                                                 @Nullable Consumer<GraphQLOutputFieldsBuilder> argumentsBuilder,
	                                                 @Nonnull Consumer<GraphQLOutputFieldsBuilder> objectFieldsBuilder) {
		return addObjectField(alias, fieldDescriptor.name(), argumentsBuilder, objectFieldsBuilder);
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addObjectField(@Nonnull String fieldName,
	                                                 @Nullable Consumer<GraphQLOutputFieldsBuilder> argumentsBuilder,
	                                                 @Nonnull Consumer<GraphQLOutputFieldsBuilder> objectFieldsBuilder) {
		return addObjectField(null, fieldName, argumentsBuilder, objectFieldsBuilder);
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addObjectField(@Nullable String alias,
	                                                 @Nonnull String fieldName,
													 @Nullable Consumer<GraphQLOutputFieldsBuilder> argumentsBuilder,
	                                                 @Nonnull Consumer<GraphQLOutputFieldsBuilder> objectFieldsBuilder) {
		if (argumentsBuilder == null) {
			return addObjectField(alias, fieldName, objectFieldsBuilder);
		}

		lines.add(getCurrentIndentation() + (alias != null ? alias + ": " : "") + fieldName + "(");
		level++;
		argumentsBuilder.accept(this);
		level--;
		lines.add(getCurrentIndentation() + ") {");
		level++;
		objectFieldsBuilder.accept(this);
		level--;
		lines.add(getCurrentIndentation() + "}");

		return this;
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addFieldArgument(@Nonnull PropertyDescriptor argumentDescriptor,
	                                                   @Nonnull Function<Integer, String> valueSupplier) {
		final String value = valueSupplier.apply(offset + level);
		if (value == null) {
			return this;
		}
		lines.add(getCurrentIndentation() + argumentDescriptor.name() + ": " + value);
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
