---
title: Validating examples in documentation using JUnit 5 and JShell
perex: |
  The documentation on evitaDB site is getting bigger and bigger. The more examples we add, the more we're afraid they
  will become obsolete or broken. How do we tame this beast of hundreds of examples?
date: '08.5.2023'
author: 'Jan Novotný'
motive: assets/images/06-document-examples-testing.png
proofreading: 'needed'
---

Because evitaDB is a built on top of a Java platform it gets all its benefits and all its disadvantages. The Java 
language is a statically typed and compiled language. Before you can run any piece of code, you need to compile it,
load it into a classloader and then execute. Our code examples are scattered over different MarkDown files - sometimes
embedded directly, sometimes referenced to separate file in the repository. At first, we thought there is no easy way
for verifying the validity and consistence of the documentation code samples. Initial thoughts went towards writing 
a custom Maven plugin that would generate test source codes, that would envelope the examples, compile them with Javac,
and then execute as a part of our test suite.

Fortunately, there is easier and more dynamic approach. Java provides a [JShell REPL](https://www.geeksforgeeks.org/jshell-java-9-new-feature/)
(since version 9) allowing you to interactively enter, compile, and run Java source code. The JShell can also be 
[executed programmatically,](https://arbitrary-but-fixed.net/teaching/java/jshell/2018/10/18/jshell-exceptions.html),
although it's not its primary use-case (that's why the information about this approach are scarce and hard to find).
We suspected the JShell might be a way for overcoming the difficulties with the compilation step and were determined to
at least try it.

The next piece of puzzle is a [JUnit 5](https://junit.org/junit5/docs/current/user-guide/) and its great support for
[dynamic tests](https://junit.org/junit5/docs/current/user-guide/#writing-tests-dynamic-tests) that we learnt along
the way.

So let's dive in.

<Note type="info">

<NoteTitle toggles="true">

##### How do we organize code samples in the documentation? Read this section if you want to understand next chapters easier.
</NoteTitle>

The example files are usually placed in a separate file that is referenced by a special Next.JS component named 
`SourceCodeTabs`. The exact format is

```mdxjs
<SourceCodeTabs>
[File description](relativePathToFileInRepository.extension)
</SourceCodeTabs>
```

The notation is optimized to work for simple MarkDown rendering on GitHub when Next.JS components are not interpreted. 
The referenced file usually has a `.java` or `.evitaql` extension, but there are multiple files with the same name that 
differ only by an extension. These files are rendered on the documentation portal in an interactive widget that allows 
you to switch between different language versions of the example:

![SourceCodeTabs widget](assets/images/06-sourcetab-component-example.png)

The extensions of these files are the key component for recognizing which language the example was written for. 

</Note>

## Extraction of the code samples from the MarkDown

## Generating JUnit 5 dynamic tests

## Compilation and execution code snippets via. JShell

### Setup & teardown

### Test prerequisites, chaining

### Verification, assertions

### Cleaning the context

### Debugging

Attaching debugger, printing compilation errors, exceptions.

## Summary

