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

package io.evitadb.utils;

import javax.annotation.Nonnull;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static java.util.Optional.ofNullable;

/**
 * This utility class allows to extract information about the evitaDB version from the MANIFEST.MF file.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class VersionUtils {
	private static final String DEFAULT_MANIFEST_LOCATION = "META-INF/MANIFEST.MF";
	private static final String IMPLEMENTATION_VENDOR_TITLE = "Implementation-Title";
	private static final String IO_EVITADB_TITLE = "evitaDB - Standalone server";
	private static final String IMPLEMENTATION_VERSION = "Implementation-Version";

	/**
	 * Method reads the current evitaDB version from the Manifest file where the version is injected during Maven build.
	 */
	@Nonnull
	public static String readVersion() {
		try {
			final Enumeration<URL> resources = VersionUtils.class.getClassLoader().getResources(DEFAULT_MANIFEST_LOCATION);
			while (resources.hasMoreElements()) {
				try (final InputStream manifestStream = resources.nextElement().openStream()) {
					final Manifest manifest = new Manifest(manifestStream);
					final Attributes mainAttributes = manifest.getMainAttributes();
					if (IO_EVITADB_TITLE.equals(mainAttributes.getValue(IMPLEMENTATION_VENDOR_TITLE))) {
						return ofNullable(mainAttributes.getValue(IMPLEMENTATION_VERSION)).orElse("?");
					}
				}
			}
		} catch (Exception ignored) {
			// just return unknown value
		}
		return "?";
	}
}
