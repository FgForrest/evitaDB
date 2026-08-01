# Documentation Workflow

## English is the only hand-written source

User documentation lives under `documentation/user/en/**`. Edit only the English files.

## Czech is machine-translated, never hand-edited

`documentation/user/cs/**` is generated from the English source by the
[Comenius Maven plugin](https://github.com/FgForrest/comenius-maven-plugin) (see
`documentation/blog/en/23-maven-comenius-plugin.md`), configured in the root `pom.xml`
(`one.edee.oss:comenius-maven-plugin`). Run it with `tools/translate.sh`
(`mvn -N comenius:run -Dcomenius.action=translate`); it needs an `OPENAI_API_KEY` env var and, per
run, re-translates every English file whose tracked source commit is stale — not just the one you
just edited — so only run it when a Czech sync is actually wanted, not after every English edit.

Do not write or edit a Czech mirror file by hand when adding/changing English documentation — run
the translation step instead (or leave it for Johnny/CI) to regenerate it from the English source.
