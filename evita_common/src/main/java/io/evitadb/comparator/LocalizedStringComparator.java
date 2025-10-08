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

package io.evitadb.comparator;

import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;

/**
 * This comparator compares two string with respect to a national characters.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class LocalizedStringComparator implements Comparator<String>, Serializable {
	@Serial private static final long serialVersionUID = 8102561596057708303L;
	@Nonnull private final Collator collator;

	public LocalizedStringComparator(@Nonnull Locale locale) {
		this.collator = Collator.getInstance(locale);
	}

	@Override
	public int compare(String o1, String o2) {
		return this.collator.compare(o1, o2);
	}

}
