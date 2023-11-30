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

package io.evitadb.test.client.query.graphql;

import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.test.client.query.ObjectJsonSerializer;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds output fields in GraphQL query format with proper indentation.
 *
 * @author Lukáš Hornych, FG Forrst a.s. (c) 2023
 */
public class GraphQLOutputFieldsBuilder {

	private final static ObjectJsonSerializer OBJECT_JSON_SERIALIZER = new ObjectJsonSerializer();
	private final static GraphQLInputJsonPrinter INPUT_JSON_PRINTER = new GraphQLInputJsonPrinter();

	private final static String INDENTATION = "  ";

	private final int offset;
	private int level = 1;
	private final List<String> lines = new LinkedList<>();

	public GraphQLOutputFieldsBuilder(int offset) {
		this.offset = offset;
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addPrimitiveField(@Nonnull PropertyDescriptor fieldDescriptor,
	                                                    @Nonnull ArgumentSupplier... arguments) {
		return addPrimitiveField(fieldDescriptor.name(), arguments);
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addPrimitiveField(@Nonnull String fieldName,
	                                                    @Nonnull ArgumentSupplier... arguments) {
		if (arguments.length == 0) {
			lines.add(getCurrentIndentation() + fieldName);
		} else if (arguments.length == 1) {
			final Argument argument = arguments[0].apply(offset + level + 1);
			final String serializedArgument = argument.toString();
			if (serializedArgument.contains("\n")) {
				lines.add(getCurrentIndentation() + fieldName + "(");
				level++;
				lines.add(serializedArgument);
				level--;
				lines.add(getCurrentIndentation() + ") {");
			} else {
				lines.add(getCurrentIndentation() + fieldName + "(" + serializedArgument + ") {");
			}
		} else {
			lines.add(getCurrentIndentation() + fieldName + "(");
			level++;
			for (ArgumentSupplier argumentSupplier : arguments) {
				final Argument argument = argumentSupplier.apply(offset + level);
				lines.add(argument.toString());
			}
			level--;
			lines.add(getCurrentIndentation() + ")");
		}

		return this;
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addObjectField(@Nonnull PropertyDescriptor fieldDescriptor,
	                                                 @Nonnull Consumer<GraphQLOutputFieldsBuilder> objectFieldsBuilder,
	                                                 @Nonnull ArgumentSupplier... arguments) {
		return addObjectField(null, fieldDescriptor, objectFieldsBuilder, arguments);
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addObjectField(@Nullable String alias,
	                                                 @Nonnull PropertyDescriptor fieldDescriptor,
	                                                 @Nonnull Consumer<GraphQLOutputFieldsBuilder> objectFieldsBuilder,
	                                                 @Nonnull ArgumentSupplier... arguments) {
		return addObjectField(alias, fieldDescriptor.name(), objectFieldsBuilder, arguments);
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addObjectField(@Nonnull String fieldName,
	                                                 @Nonnull Consumer<GraphQLOutputFieldsBuilder> objectFieldsBuilder,
	                                                 @Nonnull ArgumentSupplier... arguments) {
		return addObjectField(null, fieldName, objectFieldsBuilder, arguments);
	}

	@Nonnull
	public GraphQLOutputFieldsBuilder addObjectField(@Nullable String alias,
	                                                 @Nonnull String fieldName,
	                                                 @Nonnull Consumer<GraphQLOutputFieldsBuilder> objectFieldsBuilder,
	                                                 @Nonnull ArgumentSupplier... arguments) {
		if (arguments.length == 0) {
			lines.add(getCurrentIndentation() + (alias != null ? alias + ": " : "") + fieldName + " {");
		} else if (arguments.length == 1) {
			final Argument argument = arguments[0].apply(offset + level + 1);
			final String serializedArgument = argument.toString();
			if (serializedArgument.contains("\n")) {
				lines.add(getCurrentIndentation() + (alias != null ? alias + ": " : "") + fieldName + "(");
				level++;
				lines.add(serializedArgument);
				level--;
				lines.add(getCurrentIndentation() + ") {");
			} else {
				lines.add(getCurrentIndentation() + (alias != null ? alias + ": " : "") + fieldName + "(" + serializedArgument + ") {");
			}
		} else {
			lines.add(getCurrentIndentation() + (alias != null ? alias + ": " : "") + fieldName + "(");
			level++;
			for (ArgumentSupplier argumentSupplier : arguments) {
				final Argument argument = argumentSupplier.apply(offset + level);
				lines.add(argument.toString());
			}
			level--;
			lines.add(getCurrentIndentation() + ") {");
		}

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

	@FunctionalInterface
	public interface ArgumentSupplier extends Function<Integer, Argument> {}

	public record Argument(@Nonnull PropertyDescriptor argumentDescriptor,
	                       int multilineOffset,
	                       @Nonnull Object value) {
		@Override
		public String toString() {
			final String serializedValue = INPUT_JSON_PRINTER.print(OBJECT_JSON_SERIALIZER.serializeObject(value));
			return offsetMultilineArgument(multilineOffset, argumentDescriptor.name() + ": " + serializedValue);
		}

		@Nonnull
		private String offsetMultilineArgument(int mutlilineOffset, @Nonnull String argument) {
			if (argument.contains("\n") && mutlilineOffset > 0) {
				return argument.lines()
					.map(line -> INDENTATION.repeat(mutlilineOffset) + line)
					.collect(Collectors.joining("\n"));
			}
			return argument;
		}
	}
}
