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

package io.evitadb.documentation;

import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaAnnotation;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaMethod;
import com.thoughtworks.qdox.model.impl.DefaultJavaClass;
import com.thoughtworks.qdox.model.impl.DefaultJavaMethod;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This tests copies JavaDoc from constraints to the {@link QueryConstraints} factory methods so
 * that the documentation is 1:1 and developer doesn't need to pay for the maintenance. Unfortunately, JavaDoc doesn't
 * allow easy copying from one class to another.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class JavaDocCopy implements EvitaTestSupport {
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
	 * RegEx pattern used for replacing old JavaDoc with the new one.
	 */
	private static final Pattern JAVA_DOC_REPLACE = Pattern.compile("/\\*\\*.*\\*/", Pattern.MULTILINE | Pattern.DOTALL);
	/**
	 * Path to the class with the factory methods.
	 */
	private static final String QUERY_CONSTRAINTS_PATH = "evita_query/src/main/java/io/evitadb/api/query/QueryConstraints.java";
	/**
	 * Full class name of the class with the factory methods.
	 */
	private static final String QUERY_CONSTRAINTS_CLASS = "io.evitadb.api.query.QueryConstraints";

	/**
	 * Collects information about all factory methods.
	 */
	@Nonnull
	private static List<MethodDescriptor> collectMethodDescriptors(@Nonnull Path queryConstraintsPath, @Nonnull Path rootDirectory) throws IOException {
		final JavaProjectBuilder builder = new JavaProjectBuilder();

		// add all source folders to the QDox library
		for (String constraintRoot : CONSTRAINTS_ROOT) {
			builder.addSourceTree(rootDirectory.resolve(constraintRoot).toFile());
		}
		// now add the class with factory methods
		builder.addSource(queryConstraintsPath.toFile());

		// read parsed form of the class with factory methods
		final DefaultJavaClass queryConstraints = (DefaultJavaClass) builder.getClassByName(QUERY_CONSTRAINTS_CLASS);

		// remember where the class declaration starts
		int startLine = queryConstraints.getLineNumber();

		// create index from all classes found by QDox in passed folders by class name
		final Map<String, JavaClass> classesByName = builder.getClasses()
			.stream()
			.collect(
				Collectors.toMap(
					JavaClass::getName,
					Function.identity()
				)
			);


		// iterate over factory methods
		final List<JavaMethod> factoryMethods = queryConstraints.getMethods();
		final List<MethodDescriptor> descriptors = new ArrayList<>(factoryMethods.size());
		for (JavaMethod method : factoryMethods) {
			if (method.isStatic() && method.isPublic()) {
				final DefaultJavaMethod javaMethod = (DefaultJavaMethod) method;
				// find a class that is created by the factory method
				final JavaClass referencedConstraint = classesByName.get(method.getReturns().getName());
				if (referencedConstraint != null) {
					// create the descriptor
					descriptors.add(
						new MethodDescriptor(
							startLine, javaMethod.getLineNumber(),
							referencedConstraint.getComment()
						)
					);
				}
				// move start of the next factory method to the declaration line of this method
				startLine = method.getLineNumber();
			}
		}

		return descriptors;
	}

	/**
	 * Replaces the original JavaDoc with ones that has been found in the {@link Constraint} class.
	 */
	@Nonnull
	private static List<String> replaceJavaDoc(@Nonnull Path queryConstraintsPath, @Nonnull List<MethodDescriptor> methodDescriptors) throws IOException {
		// read original source and parse line by line
		final List<String> queryConstraintsSource = Files.readAllLines(queryConstraintsPath, StandardCharsets.UTF_8);
		// this compensation allows us to correctly align from lines when longer / shorter javadoc is replaced
		int compensation = 0;
		// iterate over factory method descriptors
		for (MethodDescriptor descriptor : methodDescriptors) {
			final int fromLine = compensation + descriptor.fromLine();
			final int toLine = compensation + descriptor.toLine();

			// extract the block relevant to the factory method
			final List<String> original = queryConstraintsSource.subList(fromLine, toLine);
			final String originalCode = String.join("\n", original);

			// create the new JavaDoc that we want to have for the factory method
			final String javaDocToReplace = getJavaDocForReplacing(descriptor);

			// replace the JavaDoc in the block
			final String result = JAVA_DOC_REPLACE.matcher(originalCode).replaceFirst(javaDocToReplace);
			final String[] newLines = result.split("\n");

			// clear original block of code
			queryConstraintsSource.subList(fromLine, toLine).clear();

			// insert the new block of code with replaced JavaDoc
			for (int i = newLines.length - 1; i >= 0; i--) {
				queryConstraintsSource.add(fromLine, newLines[i]);
			}

			// calculate compensation when we inserted longer/shorter block than the original
			compensation += newLines.length - (toLine - fromLine);
		}
		return queryConstraintsSource;
	}

	/**
	 * Wraps commentary into a JavaDoc format.
	 */
	@Nonnull
	private static String getJavaDocForReplacing(@Nonnull MethodDescriptor descriptor) {
		return Stream.of(
				Stream.of("/**"),
				Arrays.stream(descriptor.comment().split("\n")).map(it -> "\t * " + it),
				Stream.of("\t*/")
			)
			.flatMap(Function.identity())
			.collect(Collectors.joining("\n"));
	}

	/**
	 * Run this method to copy original JavaDoc to factory methods in {@link QueryConstraints}
	 */
	@Test
	void copyJavaDocToQueryConstraints() throws IOException {
		final Path queryConstraintsPath = getRootDirectory().resolve(QUERY_CONSTRAINTS_PATH);
		final List<MethodDescriptor> methodDescriptors = collectMethodDescriptors(queryConstraintsPath, getRootDirectory());
		final List<String> queryConstraintsSource = replaceJavaDoc(queryConstraintsPath, methodDescriptors);

		// rewrite the file with replaced JavaDoc
		Files.writeString(
			queryConstraintsPath,
			String.join("\n", queryConstraintsSource),
			StandardOpenOption.TRUNCATE_EXISTING
		);
	}

	/**
	 * Run this method to copy user documentation links from {@link ConstraintDefinition} to JavaDoc of {@link Constraint} classes.
	 */
	@Test
	void copyConstraintUserDocsLinksToJavaDocs() throws URISyntaxException, IOException {
		final JavaProjectBuilder builder = new JavaProjectBuilder();

		// add all source folders to the QDox library
		for (String constraintRoot : CONSTRAINTS_ROOT) {
			builder.addSourceTree(getRootDirectory().resolve(constraintRoot).toFile());
		}

		final Collection<JavaClass> classesByName = builder.getClasses();

		for (JavaClass constraintClass : classesByName) {
			final Path constraintClassPath = Path.of(constraintClass.getParentSource().getURL().toURI());
			final List<String> constraintSource = Files.readAllLines(constraintClassPath, StandardCharsets.UTF_8);
			final Optional<JavaAnnotation> constraintDefinition = constraintClass.getAnnotations()
				.stream()
				.filter(it -> it.getType().getName().equals(ConstraintDefinition.class.getSimpleName()))
				.findFirst();
			if (constraintDefinition.isEmpty()) {
				// this class is not a constraint
				continue;
			}
			final String userDocsLink = ((String) constraintDefinition.get().getNamedParameter("userDocsLink")).replace("\"", "");

			int commentStartLine = -1;
			for (int i = 0; i < constraintDefinition.get().getLineNumber(); i++) {
				if (constraintSource.get(i).startsWith("/**")) {
					commentStartLine = i;
					break;
				}
			}
			if (commentStartLine == -1) {
				throw new EvitaInternalError("Could not find author line in `" + constraintClass.getName() + "`");
			}
			final String comment = constraintClass.getComment();
			final int commentEndLine = comment.split("\n").length + commentStartLine;

			final int originalUserDocsLinkLineNumber = commentEndLine;
			final String originalUserDocsLinkLine = constraintSource.get(originalUserDocsLinkLineNumber);
			if (originalUserDocsLinkLine.contains("<a href=\"https://evitadb.io")) {
				// there is a link from previous generation, just update it
				constraintSource.set(originalUserDocsLinkLineNumber, " * <p><a href=\"https://evitadb.io" + userDocsLink + "\">Visit detailed user documentation</a></p>");
			} else {
				// there is no link, add it
				constraintSource.add(originalUserDocsLinkLineNumber + 1, " * <a href=\"https://evitadb.io" + userDocsLink + "\">Visit detailed user documentation</a>");
				constraintSource.add(originalUserDocsLinkLineNumber + 1, " *");
			}

			// rewrite the file with replaced JavaDoc
			Files.writeString(
				constraintClassPath,
				String.join("\n", constraintSource),
				StandardOpenOption.TRUNCATE_EXISTING
			);
		}
	}

	/**
	 * Describes the locations of the factory methods in the class
	 *
	 * @param fromLine the line after declaration of the previous code block
	 * @param toLine   the line where the declaration of this factory method starts
	 * @param comment  the JavaDoc comment that relates to the class that is being created by this factory method
	 *                 it's the one we want to have present in JavaDoc of this factory method
	 */
	private record MethodDescriptor(
		int fromLine,
		int toLine,
		@Nonnull String comment
	) {

	}

}
