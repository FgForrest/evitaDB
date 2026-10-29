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


package io.evitadb.spi.analysis;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;

/**
 * Marks a factory as safe for a schema's `normalized(...)` declaration.
 * Created steps must be pure, deterministic, thread-safe and have stable output across writes and reloads.
 * The chain enters in NFC; each step consumes its predecessor's output and preserves one whole value
 * (never splitting or dropping it into several values), and returns
 * the SAME `String` instance when nothing changes. An empty resulting String remains a value.
 * The compiler applies each step once in declared order and supplies the final NFD representation.
 * Neither steps nor chains promise idempotence or convergence.
 */
public interface NormalizingAnalysisStepFactory extends AnalysisStepFactory {
	/**
	 * Creates an immutable operation for validated parameters and the value tree's fold locale.
	 *
	 * @param parameters validated step parameters
	 * @param foldLocale locale for case folding; null means root, never the JVM default
	 * @return reusable normalization operation satisfying this interface's contract
	 */
	@Nonnull
	StringNormalizationStep create(@Nonnull Map<String, Serializable> parameters, @Nullable Locale foldLocale);
}
