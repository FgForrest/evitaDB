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

package io.evitadb.test;

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

import static io.evitadb.test.TestConstants.DATA_FOLDER_ENV_VARIABLE;

/**
 * This interface allows unit tests to easily prepare test directory, test file and also clean it up.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface TestFileSupport {
	/**
	 * Default name of the evita configuration file.
	 */
	String DEFAULT_EVITA_CONFIGURATION_FILE = "evita-configuration.yaml";
	/**
	 * Default data folder for evita data in tests.
	 */
	Path BASE_PATH = Path.of(System.getProperty("java.io.tmpdir") + File.separator + "evita" + File.separator);

	/**
	 * Removes test directory with its contents.
	 */
	default void cleanTestDirectory() throws IOException {
		// clear evitaDB directory
		FileUtils.deleteDirectory(BASE_PATH.toFile());
	}

	/**
	 * Removes test directory with its contents.
	 */
	default void cleanTestSubDirectory(String directory) throws IOException {
		// clear evitaDB directory
		FileUtils.deleteDirectory(BASE_PATH.resolve(directory).toFile());
	}

	/**
	 * Returns pointer to the data directory. This method supports proper DATA folder resolution from different working
	 * directories in evitaDB git repository.
	 */
	@Nonnull
	default Path getDataDirectory() {
		final String externallyDefinedPath = System.getProperty(DATA_FOLDER_ENV_VARIABLE);
		final Path dataPath;
		if (externallyDefinedPath == null) {
			final Path workingDirPath = Path.of(System.getProperty("user.dir"));
			if (workingDirPath.toString().contains(File.separator + "evita_")) {
				dataPath = workingDirPath.resolve("../data");
			} else {
				dataPath = workingDirPath.resolve("data");
			}
		} else {
			dataPath = Path.of(externallyDefinedPath);
		}
		if (!dataPath.toFile().exists()) {
			throw new EvitaInternalError("Data directory `" + dataPath + "` does not exist!");
		}
		return dataPath;
	}

	/**
	 * Method copies `evita-configuration.yaml` from the classpath to the temporary directory on the filesystem so that
	 * evita server that is going to be started in tests will be able to find it.
	 *
	 * @return path of the exported configuration file
	 */
	@Nonnull
	static Path bootstrapEvitaServerConfigurationFile() {
		final Path configFilePath = Path.of(System.getProperty("java.io.tmpdir") + File.separator + DEFAULT_EVITA_CONFIGURATION_FILE);
		//TODO JNO: fix that "/", since File.separator does not work on Windows in this case
		try (final InputStream sourceIs = TestConstants.class.getResourceAsStream("/" + DEFAULT_EVITA_CONFIGURATION_FILE)) {
			Files.copy(
				Objects.requireNonNull(sourceIs),
				configFilePath,
				StandardCopyOption.REPLACE_EXISTING
			);
		} catch (IOException e) {
			throw new RuntimeException(
				"Failed to copy evita `" + DEFAULT_EVITA_CONFIGURATION_FILE + "` to `" + configFilePath + "` due to: " + e.getMessage(),
				e
			);
		}

		return configFilePath;
	}

	/**
	 * Removes test directory with its contents.
	 */
	default void cleanTestDirectoryWithRethrow() {
		try {
			cleanTestDirectory();
		} catch (IOException e) {
			throw new EvitaInternalError("Cannot empty target directory!", e);
		}
	}

	/**
	 * Removes test directory with its contents.
	 */
	default void cleanTestSubDirectoryWithRethrow(String directory) {
		try {
			cleanTestSubDirectory(directory);
		} catch (IOException e) {
			throw new EvitaInternalError("Cannot empty target directory!", e);
		}
	}

	/**
	 * Removes test directory with its contents and creates new empty directory on its place.
	 */
	default void prepareEmptyTestDirectory() throws IOException {
		cleanTestDirectory();
		final File dirFile = BASE_PATH.toFile();
		dirFile.mkdirs();
		Assert.isTrue(dirFile.exists() && dirFile.isDirectory(), "Target directory cannot be created!");
	}

	/**
	 * Returns path to the test directory.
	 */
	default Path getTestDirectory() {
		return BASE_PATH;
	}

	/**
	 * Returns path to the file with specified name in the test directory.
	 */
	default Path getPathInTargetDirectory(@Nonnull String fileName) {
		return BASE_PATH.resolve(fileName);
	}

	/**
	 * Returns file reference to the file with specified name in the test directory.
	 */
	default File createFileInTargetDirectory(@Nonnull String fileName) {
		return getPathInTargetDirectory(fileName).toFile();
	}

}
