#!/bin/bash
#
#
#                         _ _        ____  ____
#               _____   _(_) |_ __ _|  _ \| __ )
#              / _ \ \ / / | __/ _` | | | |  _ \
#             |  __/\ V /| | || (_| | |_| | |_) |
#              \___| \_/ |_|\__\__,_|____/|____/
#
#   Copyright (c) 2023-2024
#
#   Licensed under the Business Source License, Version 1.1 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.
#

if [ "$1" == "evitaql" ]; then
  echo "Generating EvitaQL.g4..."
  rm src/main/java/io/evitadb/api/query/parser/grammar/*
  mvn -Pevitaql-grammar clean antlr4:antlr4 replacer:replace
elif [ "$1" == "expression" ]; then
  echo "Generating Expression.g4..."
  rm src/main/java/io/evitadb/api/query/expression/parser/grammar/*
  mvn -Pexpression-grammar clean antlr4:antlr4 replacer:replace
else
  echo "Generating EvitaQL.g4..."
  rm src/main/java/io/evitadb/api/query/parser/grammar/*
  mvn -Pevitaql-grammar clean antlr4:antlr4 replacer:replace

  echo "Generating Expression.g4..."
  rm src/main/java/io/evitadb/api/query/expression/parser/grammar/*
  mvn -Pexpression-grammar clean antlr4:antlr4 replacer:replace
fi