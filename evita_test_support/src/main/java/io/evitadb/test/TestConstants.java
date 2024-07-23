/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.test;

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
	 * Name of the tag for functional test suite tests.
	 */
	String FUNCTIONAL_TEST = "functional";
	/**
	 * Name of the tag for documentation code snippets.
	 */
	String DOCUMENTATION_TEST = "documentation";
	/**
	 * Name of the tag for long running tests that needs to be executed separately.
	 */
	String LONG_RUNNING_TEST = "longRunning";
	/**
	 * Name of the default catalog when no other name has been specified. Pretty good for most of the tests.
	 */
	String TEST_CATALOG = "testCatalog";

}
