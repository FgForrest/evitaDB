/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.documentation.javadoc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaAnnotation;
import com.thoughtworks.qdox.model.JavaClass;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.StringUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exports all constraint definitions to a JSON file for external tools (namely evitaLab).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class ConstraintJavaDocExporter implements EvitaTestSupport {

	/**
	 * Field containing the relative directory paths to the folders with {@link Constraint} classes.
	 */
	private static final String[] CONSTRAINTS_ROOT = {
		"evita_query/src/main/java/io/evitadb/api/query/head",
		"evita_query/src/main/java/io/evitadb/api/query/filter",
		"evita_query/src/main/java/io/evitadb/api/query/order",
		"evita_query/src/main/java/io/evitadb/api/query/require"
	};

	/**
	 * Finds constant with custom name for constraint in class.
	 */
	private static final Pattern CONSTRAINT_CUSTOM_NAME_PATTERN = Pattern.compile("private +static +final +String +CONSTRAINT_NAME += +\"(\\w+)\";");

	public static void main(String[] args) throws URISyntaxException, IOException {
		new ConstraintJavaDocExporter().exportConstraintDefinitions();
	}

	/**
	 * Exports all constraint definitions to a JSON file.
	 */
	@Test
	void exportConstraintDefinitions() throws URISyntaxException, IOException {
		final Path rootDirectory = getRootDirectory();

		final JavaProjectBuilder builder = new JavaProjectBuilder();

		// add all source folders to the QDox library
		for (String constraintRoot : CONSTRAINTS_ROOT) {
			builder.addSourceTree(rootDirectory.resolve(constraintRoot).toFile());
		}

		final Collection<JavaClass> classesByName = builder.getClasses();

		final ObjectMapper objectMapper = new ObjectMapper();
		final ObjectNode export = objectMapper.createObjectNode();
		for (JavaClass constraintClass : classesByName) {
			final Path constraintClassPath = Path.of(constraintClass.getParentSource().getURL().toURI());
			final String constraintSource = Files.readString(constraintClassPath, StandardCharsets.UTF_8);

			final Optional<JavaAnnotation> constraintDefinition = constraintClass.getAnnotations()
				.stream()
				.filter(it -> it.getType().getName().equals(ConstraintDefinition.class.getSimpleName()))
				.findFirst();
			if (constraintDefinition.isEmpty()) {
				// this class is not a constraint
				continue;
			}

			final String constraintName;
			final Matcher constraintCustomNameMatcher = CONSTRAINT_CUSTOM_NAME_PATTERN.matcher(constraintSource);
			if (constraintCustomNameMatcher.find()) {
				constraintName = constraintCustomNameMatcher.group(1);
			} else {
				constraintName = StringUtils.uncapitalize(constraintClass.getName());
			}
			final String type = switch (constraintClass.getPackageName()) {
				case "io.evitadb.api.query.head" -> "HEAD";
				case "io.evitadb.api.query.filter" -> "FILTER";
				case "io.evitadb.api.query.order" -> "ORDER";
				case "io.evitadb.api.query.require" -> "REQUIRE";
				default -> throw new GenericEvitaInternalError("Unknown package name: " + constraintClass.getPackageName());
			};
			final String shortDescription = ((String) constraintDefinition.get().getNamedParameter("shortDescription")).replace("\"", "");
			final String userDocsLink = "https://evitadb.io" + ((String) constraintDefinition.get().getNamedParameter("userDocsLink")).replace("\"", "");

			final ObjectNode exportedConstraintDefinition = objectMapper.createObjectNode();
			exportedConstraintDefinition.put("type", type);
			exportedConstraintDefinition.put("shortDescription", shortDescription);
			exportedConstraintDefinition.put("userDocsLink", userDocsLink);
			export.putIfAbsent(constraintName, exportedConstraintDefinition);
		}

		Files.writeString(rootDirectory.resolve("exported-constraints.json"), objectMapper.writeValueAsString(export), StandardCharsets.UTF_8);
	}
}
