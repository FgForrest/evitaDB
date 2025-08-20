---
title: Discover the Advanced Features on our Developers Portal
perex: In my [previous blog post](https://evitadb.io/blog/05-building-documentation-portal), I explained why I chose Next.js and MDX to process Markdown files documenting the core features and functionalities of evitaDB and its advantages. In this article, we will take a closer look at some of the components we use to enhance developers' and users' experience.
date: '1.6.2023'
author: 'Miro Alt'
motive: assets/images/07-advanced-features-on-developers-portal.jpg
proofreading: 'done'
---

## Fueling Innovation - Brainstorming

As a developer, I regularly engage in extensive reading, whether it involves going through documentations, articles, or
any other relevant sources. Whenever I encounter a page that offers additional features, I truly value the effort put
forth by someone to present a fundamental concept. This is precisely why our focus has been directed towards crafting
easily readable pages, packed with **numerous examples** and **captivating showcases** with added interactivity.

Right from the start, I knew that we will be showcasing **numerous examples of syntax in various programming languages**.
Moreover, I was aware that I would need an expandable syntax highlighter to facilitate the implementation of a
custom parser for the new `evitaQL` syntax. To address this need, I made the strategic choice to
adopt [Prism](https://prismjs.com/), a lightweight and highly customizable syntax highlighter with modern web standards
in mind. Currently, numerous documents are being created and updated, and the `evitaQL` parser is included in our
repository. As we progress towards the final stages and the growing popularity of the `evitaQL` language, we intend to
submit a merge request to Prism's repository.

Below is an example of basic syntax and its corresponding highlighting used.

```evitaql
query(
    collection('Product'),
    filterBy(
        entityPrimaryKeyInSet(110066, 106742),
        attributeEquals('code', 'lenovo-thinkpad-t495-2')
    )
)
```

On numerous occasions, it becomes necessary to present multiple code blocks that feature similar queries or other code
examples. Rather than displaying these code blocks one after another, we employ the use of *clickable tabs* to group
them together. Instead of enclosing each code block within backticks, I've created a `<CodeTabs />` custom component.
Each code block is then rendered as an individual tab within the `<CodeTabsBlock />`.

##### Markup

![Example of CodeTabs markup](assets/images/07-codetabs-markup-example.png "Example of CodeTabs markup")

##### Result

<CodeTabs>
<CodeTabsBlock>
```Maven
<dependency>
    <groupId>io.evitadb</groupId>
    <artifactId>evita_test_support</artifactId>
    <version>2025.6.0</version>
    <scope>test</scope>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_test_support:0.6-SNAPSHOT'
```
</CodeTabsBlock>
</CodeTabs>

In a similar fashion, developers writing documents also have the option to display tabs with code snippets from an
external source folder. For this, I created another `<SourceCodeTabs />` custom component. The UI of this component is
almost the same as the `<CodeTabs />` component, but it can handle relative URLs of files or folders in the repository.

When you look at the markup below, you'll notice that it includes a Markdown link within the `<SourceCodeTabs />`
component. This deliberate inclusion serves the purpose of offering a fallback option to readers on GitHub. By providing
a link to the relevant source file or folder, we ensure accessibility to the showcase.

<Note type="info">
If you are working with a similar setup and your Markdown files are hosted on **Github**, it is crucial to ensure that you insert a new line after the opening custom tag. Alternatively, you can place the custom tag, which includes the link, on a single line.
</Note>

##### Markup

```md
<SourceCodeTabs requires="/documentation/user/en/get-started/example/connect-demo-server.java">[EvitaQL example](/documentation/user/en/use/api/example/evita-query-example.java)</SourceCodeTabs>

<SourceCodeTabs requires="/documentation/user/en/get-started/example/connect-demo-server.java"> // notice the empty line after the opening custom tag

[EvitaQL example](/documentation/user/en/use/api/example/evita-query-example.java)
</SourceCodeTabs>
```

##### Result

<SourceCodeTabs requires="/documentation/user/en/get-started/example/connect-demo-server.java">[EvitaQL example](/documentation/user/en/use/api/example/evita-query-example.java)</SourceCodeTabs>

There are more features to this specific component, but I'll get to this later.

## Switching programming languages to user preference

When we first saw the pages of the live documentation, we recognized an opportunity to enhance the user experience by
implementing a feature that would allow for the selective display of content based on the programming language being
used. With evitaDB it is possible to communicate in different languages and this requires the preparation of examples in
different formats. At the same time, it does not make sense to display all formats at once - the developer will probably
be interested only in the variant he intends to use during integration. However, this posed a challenge due to various
obstacles, such as page indexing and print support.

It all started with the `<LS />` component. I believe that from its name and markup, you can already
understand the purpose of this component.

##### Markup

```md
<LS to="e,j">
// content specific to Java and EvitaQL
</LS>
```

Now, let's move on to the next step, which involves creating a user-friendly switch to allow users to easily switch
between programming languages based on their preferences.

To tackle this task, I have chosen to implement a strategy where each custom component that interacts with the language
switch will utilize local storage to efficiently store and retrieve the user's preferred programming language.

```ts
import {useRouter, NextRouter} from 'next/router';
import {useEffect} from 'react';

export default function setLocalStorageCodelang(): void {
    const router: NextRouter = useRouter();

    useEffect(() => {
        const params: URLSearchParams = new URLSearchParams(window.location.search);
        const queryValue: string | null = params.get('codelang');

        const preferredLanguage: string | null =
            localStorage.getItem('PREFERRED_LANGUAGE');

        // Check if query parameter 'codelang' is not present in the URL
        if (queryValue === null) {
            // Check if preferred language is stored in local storage
            if (preferredLanguage) {
                // Push the preferred language to the router query
                router.push(
                    {
                        pathname: router.pathname,
                        query: {
                            ...router.query,
                            codelang: preferredLanguage,
                        },
                    },
                    undefined,
                    {scroll: false}
                );
            } else {
                // Set 'evitaql' as the default language preference in local storage
                localStorage.setItem('PREFERRED_LANGUAGE', 'evitaql');
            }
        } else {
            // Handle the case when 'codelang' query parameter is present in the URL
            if (!preferredLanguage) {
                // If preferred language is not set in local storage, set it to the query parameter value
                localStorage.setItem('PREFERRED_LANGUAGE', queryValue);
            } else if (preferredLanguage !== queryValue) {
                // If preferred language is already set but differs from the query parameter value, update it
                localStorage.setItem('PREFERRED_LANGUAGE', queryValue);
            }
        }
    }, []);
}
```

As you can see the value is pushed to `router query` which results in updated URL
parameter `?codelang={preferredLanguage}`. This will preserve the correct preset language in case the user copies the
link to the clipboard and sends it to someone else. This person will then get the same page variant as the person who
sent the link.

The component is now aware of preferred language. In case of `<LS />` component, show the actual content
depending on your preferred programming language.

<Note type="info">
On smaller screens, you'll display the preferred language switch after clicking on the button with an anchor icon. On larger screens, you will find the preferred language switch in the top right corner of your screen.

![Prefered language swich](assets/images/07-lang-swich.png "Prefered language swich")
</Note>

##### Markup

```md
<LS to="e,j">

---
> This block of text is specific to `evitaql` programming language.
>
> Go ahead and try to switch.

---
</LS>
```

##### Result

<LS to="e,j">

---
> This block of text is specific to `evitaql` and `java` programming language.
>
> Go ahead and try to switch.

---
</LS>

<LS to="r">

---
> This block of text is specific to `Rest` programming language.
>
> Go ahead and try to switch.

---
</LS>

<LS to="g">

---
> This block of text is specific to `GraphQL` programming language.
>
> Go ahead and try to switch.

---
</LS>


Other Custom component aware of the user preferred language is [above shown `<SourceCodeTabs/>`](#result-1).

## Page indexes and print support

To ensure comprehensive indexing of all content, I have chosen to exclusively hide any irrelevant information while
prioritizing visual accessibility, even if it leads to layout shifting. Moreover, when a user opts to print the
document, our component render function incorporates supplementary elements to enhance the visibility of this
functionality.

So when you try to print this page, you'll see that the `<LS />` examples shown above are available and
ready to be printed. The print preview is similar to what you see on the Github itself, where all the content is visible
regardless.

![`<LS />` component print example](assets/images/07-print-example.png "`Custom <LS />` component print example.")

## What's next

In the upcoming post, I will disclose our strategy for future development and outline our plan to implement features
such as document versioning, enabling users to access specific historical versions by switching to different branches.

Stay tuned for more details. 😉
