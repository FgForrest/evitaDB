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

package io.evitadb.server.yaml;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This implementation allows automatic translation of the shortened number formats:
 *
 * NUMBER FORMATS:
 *
 * 1K -> 1 000
 * 1M -> 1 000 000
 * 1G -> 1 000 000 000
 * 1T -> 1 000 000 000 000
 *
 * SIZE FORMATS:
 *
 * 1KB -> 1 024
 * 1MB -> 1 048 576
 * 1GB -> 1 073 741 824
 * 1TB -> 1 099 511 627 776
 *
 * TIME FORMATS:
 *
 * 1s -> 1 secs
 * 1m -> 60 secs (minute)
 * 1h -> 3 600 secs (hour)
 * 1d -> 86 400 secs (day)
 * 1w -> 604 800 secs (week)
 * 1y -> 7 257 600 secs (year)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class SpecialConfigInputFormatsHandler extends DeserializationProblemHandler {
	private final Pattern SIZE_FORMAT = Pattern.compile("(\\d+(\\.\\d+)?)(KB|MB|GB|TB)");
	private final Pattern NUMBER_FORMAT = Pattern.compile("([\\d_]+(\\.\\d+)?)([KMGT])");
	private final Pattern TIME_FORMAT = Pattern.compile("([\\d_]+(\\.\\d+)?)([smhdwy])");

	@Override
	public Object handleWeirdStringValue(DeserializationContext ctxt, Class<?> targetType, String valueToConvert, String failureMsg) throws IOException {
		final String normalizedValue = valueToConvert.trim();
		final Matcher sizeMatcher = this.SIZE_FORMAT.matcher(normalizedValue);
		if (sizeMatcher.matches()) {
			final String magnitude = sizeMatcher.group(3);
			final BigDecimal size = new BigDecimal(sizeMatcher.group(1));
			final BigDecimal resultValue = switch (magnitude) {
				case "KB" -> size.multiply(new BigDecimal(1024L));
				case "MB" -> size.multiply(new BigDecimal(1_048_576L));
				case "GB" -> size.multiply(new BigDecimal(1_073_741_824L));
				case "TB" -> size.multiply(new BigDecimal(1_099_511_627_776L));
				default -> size;
			};
			if (targetType.equals(int.class) || targetType.equals(Integer.class)) {
				return resultValue.intValueExact();
			} else if (targetType.equals(long.class) || targetType.equals(Long.class)) {
				return resultValue.longValueExact();
			} else if (targetType.equals(BigDecimal.class)) {
				return resultValue;
			}
		}
		final Matcher numberMatcher = this.NUMBER_FORMAT.matcher(normalizedValue);
		if (numberMatcher.matches()) {
			final String magnitude = numberMatcher.group(3);
			final BigDecimal size = new BigDecimal(numberMatcher.group(1).replaceAll("_", ""));
			final BigDecimal resultValue = switch (magnitude) {
				case "K" -> size.multiply(new BigDecimal(1_000L));
				case "M" -> size.multiply(new BigDecimal(1_000_000L));
				case "G" -> size.multiply(new BigDecimal(1_000_000_000L));
				case "T" -> size.multiply(new BigDecimal(1_000_000_000_000L));
				default -> size;
			};
			if (targetType.equals(int.class) || targetType.equals(Integer.class)) {
				return resultValue.intValueExact();
			} else if (targetType.equals(long.class) || targetType.equals(Long.class)) {
				return resultValue.longValueExact();
			} else if (targetType.equals(BigDecimal.class)) {
				return resultValue;
			}
		}
		final Matcher timeMatcher = this.TIME_FORMAT.matcher(normalizedValue);
		if (timeMatcher.matches()) {
			final String magnitude = timeMatcher.group(3);
			final BigDecimal time = new BigDecimal(timeMatcher.group(1).replaceAll("_", ""));
			final BigDecimal resultValue = switch (magnitude) {
				case "s" -> time.multiply(new BigDecimal(1L));
				case "m" -> time.multiply(new BigDecimal(60L));
				case "h" -> time.multiply(new BigDecimal(3_600L));
				case "d" -> time.multiply(new BigDecimal(86_400L));
				case "w" -> time.multiply(new BigDecimal(604_800L));
				case "y" -> time.multiply(new BigDecimal(31_556_926L));
				default -> time;
			};
			if (targetType.equals(int.class) || targetType.equals(Integer.class)) {
				return resultValue.intValueExact();
			} else if (targetType.equals(long.class) || targetType.equals(Long.class)) {
				return resultValue.longValueExact();
			} else if (targetType.equals(BigDecimal.class)) {
				return resultValue;
			}
		}
		return super.handleWeirdStringValue(ctxt, targetType, valueToConvert, failureMsg);
	}
}
