/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.documentation;

import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import io.evitadb.externalApi.observability.metric.EvitaJfrEventRegistry;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Label;
import org.junit.jupiter.api.Test;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * This class generates referential documentation for Java Flight Recorder (JFR) events and metrics generated from them.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class JfrDocumentation implements EvitaTestSupport {
	private static final String JFR_REFERENCE_DOCUMENTATION = "documentation/user/en/operate/reference/jfr-events.md";

	@Test
	void updateJfrReferenceDocumentation() throws IOException {
		final ReflectionLookup lookup = ReflectionLookup.NO_CACHE_INSTANCE;
		final Path jfrReferencePath = getRootDirectory().resolve(JFR_REFERENCE_DOCUMENTATION);
		try (Writer writer = new FileWriter(jfrReferencePath.toFile(), StandardCharsets.UTF_8, false)) {
			writer.write("### Java Flight Recorder (JFR) Events\n\n");

			final Map<String, List<Class<? extends CustomMetricsExecutionEvent>>> groupedEvents = EvitaJfrEventRegistry.getEventClasses()
				.stream()
				.collect(
					Collectors.groupingBy(
						it -> {
							final Category classAnnotation = lookup.getClassAnnotation(it, Category.class);
							Assert.isPremiseValid(classAnnotation != null, "Event class " + it.getName() + " is missing @Category annotation");
							final String[] groups = classAnnotation.value();
							return Arrays.stream(groups).skip(1).collect(Collectors.joining(" / "));
						}
					)
				);
			groupedEvents.entrySet()
				.stream()
				.sorted(Entry.comparingByKey())
				.forEach(
					group -> {
						try {
							writer.write("#### " + group.getKey() + "\n\n");
							writer.write("<dl>\n");
							group.getValue()
								.stream()
								.sorted(Comparator.comparing(Class::getSimpleName))
								.forEach(
									event -> {
										try {
											writer.write("  <dt>" + lookup.getClassAnnotation(event, Label.class).value() + " (<SourceClass>" + event.getName() + "</SourceClass>)</dt>\n");
											writer.write("  <dd>" + lookup.getClassAnnotation(event, Description.class).value() + "</dd>\n");
										} catch (IOException e) {
											throw new RuntimeException(e);
										}
									});
							writer.write("</dl>\n\n");
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					}
				);
		}
	}

}
