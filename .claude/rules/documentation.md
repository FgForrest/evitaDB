# Documentation Workflow

## English is the only hand-written source

User documentation lives under `documentation/user/en/**`. Edit only the English files.

## Czech is machine-translated, never hand-edited

`documentation/user/cs/**` is generated from the English source by the
[Comenius Maven plugin](https://github.com/FgForrest/comenius-maven-plugin) (see
`documentation/blog/en/23-maven-comenius-plugin.md`), configured in the root `pom.xml`
(`one.edee.oss:comenius-maven-plugin`) and run via `tools/translate.sh`
(`mvn -N comenius:run -Dcomenius.action=translate`).

Do not write or edit a Czech mirror file by hand when adding/changing English documentation —
the translation step (run by Johnny or CI, not by Claude) regenerates it from the English source.
