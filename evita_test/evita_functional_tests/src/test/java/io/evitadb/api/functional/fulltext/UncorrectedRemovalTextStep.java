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

package io.evitadb.api.functional.fulltext;

import io.evitadb.spi.analysis.*;

import javax.annotation.Nonnull;
import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A real test SPI provider deliberately violating offset correction by deleting x through an ordinary reader. */
public final class UncorrectedRemovalTextStep implements FulltextTextAnalysisStepFactory {
	/** Creates the provider discovered by ServiceLoader in the functional-test classpath. */
	public UncorrectedRemovalTextStep() {
	}

	@Override
	public String getName() {
		return "test-uncorrected-removal";
	}

	@Override
	public List<AnalysisStepParameterDescriptor> getParameters() {
		return List.of();
	}

	@Override
	public Set<FulltextAnalysisSide> getSides() {
		return Set.of(FulltextAnalysisSide.INDEX, FulltextAnalysisSide.QUERY);
	}

	@Override
	public Set<String> getIndexSideParameters() {
		return Set.of();
	}

	@Override
	public int getBehaviourVersion() {
		return 1;
	}

	@Override
	public void validate(@Nonnull Map<String, Serializable> parameters) {
		// This provider has no parameters.
	}

	@Override
	public FulltextTextAnalysisOperation create(
		@Nonnull Map<String, Serializable> parameters, @Nonnull FulltextAnalysisContext context
	) {
		return (text, side) -> new FilterReader(text) {
			@Override
			public int read() throws IOException {
				int next;
				do {
					next = super.read();
				} while (next == 'x');
				return next;
			}

			@Override
			public int read(char[] buffer, int offset, int length) throws IOException {
				if (length == 0) {
					return 0;
				}
				int count = 0;
				while (count < length) {
					final int next = read();
					if (next == -1) {
						break;
					}
					buffer[offset + count++] = (char) next;
				}
				return count == 0 ? -1 : count;
			}
		};
	}
}
