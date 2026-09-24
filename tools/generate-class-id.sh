#!/usr/bin/env bash
#
# Mints the `CLASS_ID` constant every concrete formula / bitmap-supplier class must return from
# `getClassId()` (see .claude/rules/formula-class-id.md).
#
# The value is derived exactly the way a `serialVersionUID` is derived - SHA-1, first eight bytes
# read little-endian as a signed long - but seeded on the **fully qualified class name alone**,
# never on the class signature. That difference is the whole point: a signature-derived id changes
# every time a field or method is added, and `CLASS_ID` must not change once it exists. Seeding on
# the name makes the value reproducible by anyone, stable across every edit to the class body, and
# distinct for distinct classes by construction.
#
# Uniqueness is still enforced independently, by FormulaClassIdUniquenessTest - this script mints a
# candidate, the test is what guarantees no two classes ended up sharing one.
#
# Usage:  tools/generate-class-id.sh <fully.qualified.ClassName> [...]
#
# Example:
#   tools/generate-class-id.sh io.evitadb.index.hierarchy.suppliers.HierarchyRootsDownBitmapSupplier

set -euo pipefail

if [ "$#" -eq 0 ]; then
	echo "usage: tools/generate-class-id.sh <fully.qualified.ClassName> [...]" >&2
	exit 2
fi

for fqcn in "$@"; do
	python3 - "$fqcn" <<'PY'
import hashlib, sys

fqcn = sys.argv[1]
digest = hashlib.sha1(fqcn.encode('utf-8')).digest()

# identical to java.io.ObjectStreamClass#computeDefaultSUID: fold the first eight bytes of the
# digest into a long, least significant byte first
value = 0
for byte in reversed(digest[:8]):
    value = (value << 8) | byte
# reinterpret the unsigned 64-bit accumulator as a signed Java long
if value >= 1 << 63:
    value -= 1 << 64

print(f"{fqcn}\n\tprivate static final long CLASS_ID = {value}L;")
PY
done
