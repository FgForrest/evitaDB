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

import io.evitadb.api.requestResponse.data.ContentComparator;
import io.evitadb.exception.EvitaInternalError;
import org.junit.jupiter.api.Assertions;

import java.io.File;
import java.nio.file.Path;

/**
 * This class contains shared constants for test suite.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface TestConstants {
	/**
	 * Name of the environment variable that can be used to specify data test folder.
	 */
	String DATA_FOLDER_ENV_VARIABLE = "dataFolder";
	/**
	 * Name of the tag for {@link FunctionalTestSuite} tests.
	 */
	String FUNCTIONAL_TEST = "functional";
	/**
	 * Name of the tag for {@link IntegrationTestSuite} tests.
	 */
	String INTEGRATION_TEST = "integration";
	/**
	 * Name of the tag for long running tests that needs to be executed separately.
	 */
	String LONG_RUNNING_TEST = "longRunning";
	/**
	 * Name of the default catalog when no other name has been specified. Pretty good for most of the tests.
	 */
	String TEST_CATALOG = "testCatalog";

	/**
	 * Returns pointer to the data directory. This method supports proper DATA folder resolution from different working
	 * directories in evitaDB git repository.
	 */
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
	 * Executes full contents compilation and fails the test if contents are not equal.
	 */
	default <T> void assertExactlyEquals(ContentComparator<T> expected, T actual, String message) {
		if (expected.differsFrom(actual)) {
			Assertions.fail(message + "\nExpected:\n" + expected + "\n\nActual:\n" + actual.toString());
		}
	}

}
