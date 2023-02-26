#!/bin/bash

#
#
#                         _ _        ____  ____
#               _____   _(_) |_ __ _|  _ \| __ )
#              / _ \ \ / / | __/ _` | | | |  _ \
#             |  __/\ V /| | || (_| | |_| | |_) |
#              \___| \_/ |_|\__\__,_|____/|____/
#
#   Copyright (c) 2023
#
#   Licensed under the Business Source License, Version 1.1 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.
#

##
## jacoco-summary.sh
## Parse jacoco.csv and print a summary (similar to phpunit) on stdout
## https://cylab.be/blog/94/compute-the-code-coverage-of-your-tests-with-java-and-maven
## requires gawk (GUN awk)
##

if [ "$#" -ne 1 ]; then
  echo "Print a summary of jacoco code coverage analysis."
  echo "Usage: $0 <path/to/jacoco.csv>";
  exit 1;
fi

awk -F ',' '{
  inst += $4 + $5;
  inst_covered += $5;
  br += $6 + $7;
  br_covered += $7;
  line += $8 + $9;
  line_covered += $9;
  comp += $10 + $11;
  comp_covered += $11;
  meth += $12 + $13;
  meth_covered += $13; }
END {
  print "Code Coverage Summary:";
  printf "  Instructions: %.2f% (%d/%d)\n", 100*inst_covered/inst, inst_covered, inst;
  printf "  Branches:     %.2f% (%d/%d)\n", 100*br_covered/br, br_covered, br;
  printf "  Lines:        %.2f% (%d/%d)\n", 100*line_covered/line, line_covered, line;
  printf "  Complexity:   %.2f% (%d/%d)\n", 100*comp_covered/comp, comp_covered, comp;
  printf "  Methods:      %.2f% (%d/%d)\n", 100*meth_covered/meth, meth_covered, meth; }
' $1