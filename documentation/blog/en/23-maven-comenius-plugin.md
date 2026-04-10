---
title: Automated Documentation Translation into World Languages
perex: |
  We are preparing a new version of our website, a significant part of which is user and developer documentation. Maintaining documentation in more than one language is beyond our capacity, so we chose English, but the ambition to provide documentation in developers' native languages never left us. In this article, we offer a look at the mechanism we want to use to achieve this.
date: '3.1.2026'
author: 'Jan Novotný'
motive: assets/images/23-maven-comenius-plugin.png
proofreading: 'done'
---

In a small team, it is practically impossible to keep extensive documentation — let alone in multiple languages — consistent and up to date. Or rather, this was true until recently, but with the rise of LLMs, everything is changing dramatically. One of the areas where many LLMs excel is precisely translation between languages, and thanks to agentic tools, it is now possible to verify the consistency and validity of much larger documentation than would be feasible by human effort alone. Surprisingly, I could not find any tool, integrable with the Maven build system, that would allow automated translation of MarkDown files, so at the turn of the year I decided to test the power of vibe-coding and write my own plugin. Its first version is now complete, and you can [try it out](https://github.com/FgForrest/comenius-maven-plugin) right away.

## Core Functionality of the Comenius Plugin

The principle behind the plugin is simple — you point it to a folder with your primary documentation in MarkDown format (or MDX, where you can also use custom HTML tags representing richer content), and you define a folder (or multiple folders if you want to translate into several languages) where the translated files should be placed. You provide the HTTP address of an LLM API along with the model type to use, and an authorization key. We currently translate our documentation using OpenAI `gpt-4.1`, because its Czech output (my native language) subjectively seemed better than the output of newer models.

**Tip:** to verify your configuration, you can use the `show-config` goal.

Before running the translation, all source documentation needs to be at least committed so that the plugin can obtain the commit hash of the file it is translating. It then inserts this commit hash into the [front matter](https://docs.github.com/en/contributing/writing-for-github-docs/using-yaml-frontmatter), making it clear which specific source version the translation corresponds to. Before the actual translation, it is advisable to check the integrity of the source documentation (i.e., working links) using the `check` goal.

The code then iterates through all files (you have full control over any excludes, of course) and calls the configured LLM model in parallel to translate individual files. Once the full translation completes, a second phase follows in which it fixes links to internal sections (anchors) that no longer match because headings were translated into the target language. To make this possible, the plugin enforces a consistent heading structure even after translation, and when searching for the target heading for an anchor, it relies on the order and structure of headings in the given document. As a final step, the plugin validates links in the translated document (which is why there must be no broken links before translation begins).

During implementation, I discovered that LLMs do not handle translation of large pages well and tend to shorten the translation, replacing portions with a simple "... and so on". Therefore, before translation, the plugin checks the article size, and if it exceeds 32 kB, it attempts to split it into several smaller parts while respecting the heading structure (e.g., it tries to split along H2 headings) so as to produce several pieces that optimally approach the 32 kB threshold, which proved reliable for translations. After all parts are translated, they are merged back into a single comprehensive document matching the original.

**Note:** translating the complete evitaDB documentation, comprising many hundreds of pages, into our native language took only low tens of minutes and cost approximately $5.

## Incremental Translations

Naturally, as your documentation evolves, you do not want to keep translating the entire original documentation from scratch. That is why the plugin implements a special translation variant for incremental updates. When it detects that a corresponding target file already exists, it looks at the commit hash recorded in it. It then checks out the original document at that version and compares it with the current version of the source document. For individual blocks (i.e., chapters), it computes a hash of their content and sends only the blocks (chapters) identified as changed to the LLM for translation, along with some surrounding content before and after for additional context.

After the incremental translation completes, the heading structure integrity is verified again, links across the entire already-translated documentation are updated, and link validity is checked.

## Conclusion

Please be forgiving of the current version of the plugin — it is version `1.0` that we ourselves are still testing, but when you look at [its output](https://github.com/FgForrest/evitaDB/blob/dev/documentation/user/cs/index.md), it already seems usable to us. If you decide to try the plugin, we would be very happy to hear your opinions, experiences, and any bug reports. Also, please do not judge the code quality — the sole author is Claude Code, and for me, it was actually the first complete attempt at full vibe coding with supervision only.
