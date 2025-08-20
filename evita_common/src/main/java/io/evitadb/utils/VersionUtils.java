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

package io.evitadb.utils;

import io.evitadb.exception.InvalidEvitaVersionException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
	private static final String IO_EVITADB_SERVER_TITLE = "evitaDB - Standalone server";
	private static final String IO_EVITADB_CLIENT_TITLE = "evitaDB - Java driver (gRPC client side)";
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
					if (IO_EVITADB_SERVER_TITLE.equals(mainAttributes.getValue(IMPLEMENTATION_VENDOR_TITLE)) ||
						IO_EVITADB_CLIENT_TITLE.equals(mainAttributes.getValue(IMPLEMENTATION_VENDOR_TITLE))) {
						return ofNullable(mainAttributes.getValue(IMPLEMENTATION_VERSION)).orElse("?");
					}
				}
			}
		} catch (Exception ignored) {
			// just return unknown value
		}
		return "?";
	}

	/**
	 * Compares the provided major and minor versions against a given SemVer object to check if the
	 * provided version is greater than or equal to the compared version.
	 *
	 * @param major the major version to compare
	 * @param minor the minor version to compare
	 * @param comparedVersion the SemVer object to compare against; can be null
	 * @return true if the provided version is greater than or equal to the compared version, false otherwise
	 */
	public static boolean greaterThanEquals(int major, int minor, @Nullable SemVer comparedVersion) {
		return comparedVersion != null &&
			(major > comparedVersion.major() || (major == comparedVersion.major() && minor >= comparedVersion.minor()));
	}

	/**
	 * A class representing a semantic version.
	 *
	 * @param major the major version
	 * @param minor the minor version
	 * @param patch the patch version
	 * @see <a href="https://semver.org/">Semantic Versioning</a>
	 */
	public record SemVer(
		int major,
		int minor,
		@Nullable String patch,
		boolean snapshot
	) implements Comparable<SemVer> {

		public SemVer(int major, int minor) {
			this(major, minor, null, false);
		}

		@Override
		public int compareTo(@Nonnull SemVer o) {
			return compare(this, o);
		}

		/**
		 * Constructs a SemVer object from a string version.
		 *
		 * @param version the string version in the format "major.minor.patch"
		 */
		@Nonnull
		public static SemVer fromString(@Nonnull String version) {
			if (version.equals("?")) {
				throw new InvalidEvitaVersionException(
					"Invalid version string: `" + version + "`.",
					"Invalid version string: `" + version + "`."
				);
			}

			final boolean snapshotVersion = version.contains("-SNAPSHOT");
			final String[] versionParts = version.replace("-SNAPSHOT", "").split("\\.");
			try {
				return new SemVer(
					Integer.parseInt(versionParts[0]),
					Integer.parseInt(versionParts[1]),
					versionParts.length > 2 ? versionParts[2] : null,
					snapshotVersion
				);
			} catch (NumberFormatException e) {
				throw new InvalidEvitaVersionException(
					"Invalid version string: `" + version + "`.",
					"Invalid version string: `" + version + "`.",
					e
				);
			}
		}

		@Nonnull
		@Override
		public String toString() {
			// construct the SemVer string back again
			return this.major + "." + this.minor + (this.patch == null ? "" : "." + this.patch) + (this.snapshot ? "-SNAPSHOT" : "");
		}

		/**
		 * Compares two SemVer objects based on their major and minor versions.
		 *
		 * @param v1 the first SemVer object to compare
		 * @param v2 the second SemVer object to compare
		 * @return 0 if the major and minor versions of the two objects are equal,
		 *         1 if v1 has a greater major or minor version than v2,
		 *        -1 if v1 has a lesser major or minor version than v2
		 */
		public static int compare(@Nonnull SemVer v1, @Nonnull SemVer v2) {
			if (v1.major() > v2.major() || (v1.major() == v2.major() && v1.minor() > v2.minor())) {
				return 1;
			} else if (v1.major() < v2.major() || v1.minor() < v2.minor()) {
				return -1;
			} else {
				return 0;
			}
		}
	}

}
