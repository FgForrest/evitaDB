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

package io.evitadb.api.query.parser;

/**
 * Mode of parsing query/query. Changes how individual values, classifiers and constraints are parsed.
 * The default mode should be the {@link #SAFE} in most cases. The {@link #UNSAFE} should be used only in edge cases.
 * <p>
 * Currently, only use of mode is to decide whether literal values are allowed, which are allowed only in {@link #UNSAFE}
 * for to prevent attacks described <a href="https://owasp.org/www-pdf-archive/GOD16-NOSQL.pdf">here</a>.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public enum ParseMode {

	/**
	 * Should be default mode in most cases.
	 * Safe mode allows values passed only via parameters.
	 */
	SAFE,
	/**
	 * Should be used ONLY IN EDGE CASES as this is not safe when passing arguments from client.
	 * Unsafe mode allows both literal values and parameters.
	 */
	UNSAFE
}
