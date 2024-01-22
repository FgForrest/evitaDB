---
title: We are changing versioning scheme
perex: |
  I have always used the semantic versioning (SemVer) scheme for my projects.  However, after discussing with my friend
  Lukáš Hornych and conducting further research with the team, we have decided to change the versioning scheme for
  evitaDB. The new scheme will be a Calendar Versioning. If you are interested in following our debate, read on.
date: '14.1.2024'
author: 'Jan Novotný'
motive: assets/images/11-versioning-scheme.png
proofreading: 'done'
---

[Semantic Versioning](https://semver.org/) promises that your users can think about the impact of upgrading to a new
version of your software. But is this really true? There are numerous articles that argue otherwise - I'd recommend
[this discussion on Hacker News](https://news.ycombinator.com/item?id=21967879) and especially
[this blog post](https://sedimental.org/designing_a_version.html).

SemVer brings interesting psychological factors into play. Far too often we see libraries with dozens of minor releases
that never reach version 1.0. Why not? Maybe the author is afraid to commit to a stable API. Maybe the production
release looks like too much responsibility. I fell into this trap myself - I was avoiding the 1.0 release until
I screwed up and while testing the automatic release on GitHub, I accidentally released version 10.1 instead of 0.10.
And since Maven Central (for good reasons) does not allow to [delete artifacts](https://central.sonatype.org/faq/can-i-change-a-component/),
we were stuck with it. You might not think this is a big deal, but there are a lot of projects that have fallen into
this trap, including pretty big names like [ReactNative](https://reactnative.dev/versions),
[Elm-Language](https://elm-lang.org/news), and there's even a dedicated [website](https://0ver.org/) that tracks
(or tracked) projects with zero versioning.

On the other hand, there are projects that very quickly increase the major version number to make themselves look more
mature or up-to-date, or maybe just because they "don't care" about backwards compatibility! One of the most famous
examples of the versioning battle was the [Chrome vs. Firefox battle](https://sedimental.org/designing_a_version.html#case-study-chrome-vs-firefox)
for the most significant version number. So - if the major version is more about marketing than actual API stability,
should we even care about it?

The compatibility guarantee is also a very tricky thing - if your project is popular enough, any change in it that
somehow affects its behavior will [break someone's code](https://xkcd.com/1172/), even if the API stays the same.
You will never be able to anticipate other people's expectations and assumptions.

There are also very good and balanced [defenses of the SemVer idea](https://caremad.io/posts/2016/02/versioning-software/)
that we considered, but in the end we decided to go with calendar versioning, specifically the `YYYY.MINOR.MICRO` variant,
for the following reasons:

1. no matter how hard we try, we will never be able to guarantee backwards compatibility - unless we have a very
    thorough test suite that covers all possible use cases, which evitaDB does not and probably never will have (let's be
    honest)
2. we commit to trying to maintain backwards compatibility, and if it is knowingly broken, we will mark the release
    with a "breaking change" label (we already do this at the issue level), and once the project gets out of the
    "pre-release" stage, we will try to consolidate critical changes into larger milestones.
3. if only the `MICRO` part changes, you can rest assured that the only changes in the build are fixes that are intended
    to be backward-compatible.
4. if the `MINOR` part changes, it means there are new features - you should always check the release notes to see
    what's new and whether they contain breaking changes or not.
5. you should always have your own test suite to verify that the new version works for you - if you don't, you shouldn't
    update the library anyway
6. the `YYYY` part changes automatically with the first new `MINOR` release of the year - since our library is
    BSL-licensed, you can easily guess if the library you're using is still BSL-licensed or has already transitioned to
    Apache License 2.0.
7. the `YYYY` part also helps you to see how old the version you're using is and easily identify whether we provide
    security updates and fixes for it or not (if we ever come up with such an offer).

So CalVer makes sense for us and we'll see how it goes. We will be releasing the first version with this schema in
the next few days.