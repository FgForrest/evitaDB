/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

/**
 * This utility class allows to extract information about the evitaDB version from the MANIFEST.MF file.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class VersionUtils {
	/**
	 * Fallback returned by {@link #readVersion()} and {@link #readCommitHash()} when the
	 * manifest cannot be located or the requested attribute is missing — typically inside
	 * the IDE, in unit tests, or when building from a source tarball without a `.git`
	 * directory. Also used in place of unresolved Maven placeholders that may slip into
	 * the manifest when the build runs without a Git working copy.
	 */
	public static final String UNKNOWN_VALUE = "?";
	private static final String DEFAULT_MANIFEST_LOCATION = "META-INF/MANIFEST.MF";
	private static final String IMPLEMENTATION_VENDOR_TITLE = "Implementation-Title";
	private static final String IO_EVITADB_SERVER_TITLE = "evitaDB - Standalone server";
	private static final String IO_EVITADB_CLIENT_TITLE = "evitaDB - Java driver (gRPC client side)";
	private static final String IMPLEMENTATION_VERSION = "Implementation-Version";
	private static final String IMPLEMENTATION_BUILD_COMMIT = "Implementation-Build-Commit";

	/**
	 * Method reads the current evitaDB version from the Manifest file where the version is injected during Maven build.
	 */
	@Nonnull
	public static String readVersion() {
		return readManifestAttribute(IMPLEMENTATION_VERSION);
	}

	/**
	 * Reads the abbreviated git commit hash that was injected into the evitaDB manifest by
	 * `git-commit-id-maven-plugin` during the build. Returns {@link #UNKNOWN_VALUE} when the
	 * manifest cannot be located or the attribute is missing — typically inside the IDE, in
	 * unit tests, or when building from a source tarball without a `.git` directory.
	 */
	@Nonnull
	public static String readCommitHash() {
		return readManifestAttribute(IMPLEMENTATION_BUILD_COMMIT);
	}

	/**
	 * Walks the classpath manifests, picks the one belonging to the evitaDB server or driver
	 * jar (identified by `Implementation-Title`), and returns the requested attribute.
	 *
	 * @param attributeName name of the manifest main-section attribute to read
	 * @return value of the attribute or {@link #UNKNOWN_VALUE} when not found
	 */
	@Nonnull
	private static String readManifestAttribute(@Nonnull String attributeName) {
		try {
			final Enumeration<URL> resources = VersionUtils.class.getClassLoader().getResources(DEFAULT_MANIFEST_LOCATION);
			while (resources.hasMoreElements()) {
				try (final InputStream manifestStream = resources.nextElement().openStream()) {
					final Manifest manifest = new Manifest(manifestStream);
					final Attributes mainAttributes = manifest.getMainAttributes();
					if (IO_EVITADB_SERVER_TITLE.equals(mainAttributes.getValue(IMPLEMENTATION_VENDOR_TITLE)) ||
						IO_EVITADB_CLIENT_TITLE.equals(mainAttributes.getValue(IMPLEMENTATION_VENDOR_TITLE))) {
						final String value = mainAttributes.getValue(attributeName);
						// guard against unresolved Maven placeholders that survive when a build
						// runs outside a Git checkout (e.g. `${git.commit.id.abbrev}` literal)
						if (value == null || value.startsWith("${")) {
							return UNKNOWN_VALUE;
						}
						return value;
					}
				}
			}
		} catch (Exception ignored) {
			// just return unknown value
		}
		return UNKNOWN_VALUE;
	}

	/**
	 * Checks whether `version` is greater than or equal to the given `major.minor` version.
	 * A missing `version` (e.g. a client that declared no version at all) is treated as older
	 * than any `major.minor` and thus always yields `false`.
	 *
	 * @param version the SemVer object whose recency is being checked; can be null
	 * @param major the major version to compare against
	 * @param minor the minor version to compare against
	 * @return true if `version` is greater than or equal to `major.minor`, false otherwise
	 */
	public static boolean isAtLeast(@Nullable SemVer version, int major, int minor) {
		return version != null &&
			(version.major() > major || (version.major() == major && version.minor() >= minor));
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
