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

package io.evitadb.api.query;

import javax.annotation.Nonnull;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stores the MD5 hash of the source constraint JavaDoc and factory method signature that was used to generate
 * the summarized JavaDoc on this method. Used by {@code JavaDocSummarizer} to detect
 * when regeneration is needed.
 *
 * This annotation is SOURCE-retained only — it exists purely as a marker in source code and is not present
 * in compiled bytecode.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
@interface SourceHash {

	/**
	 * The MD5 hash of the source constraint JavaDoc concatenated with the factory method signature.
	 */
	@Nonnull
	String value();

}
