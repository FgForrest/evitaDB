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
import java.io.Reader;

/**
 * What a {@link FulltextTextAnalysisStepFactory text phase step} contributes to a chain: a wrapper of the text before
 * it is split into words. Immutable and shared by every chain compiled from the same definition; the reader it
 * returns is created anew for every call.
 */
@FunctionalInterface
public interface FulltextTextAnalysisOperation {

	/**
	 * Wraps the text.
	 *
	 * @param text the text, possibly already wrapped by the preceding text steps
	 * @param side the side the chain is built for, always one the step declared
	 * @return the wrapped text
	 */
	@Nonnull
	Reader wrap(@Nonnull Reader text, @Nonnull FulltextAnalysisSide side);

}
