---
title: Building a Developer-friendly Documentation Portal with Next.js and MDX
perex: As a developer, I understand the importance of up-to-date documentation. But creating a documentation portal that's easy to maintain and update can be a challenge. I'm a firm believer that when developers can write and publish documentation in a snap, it motivates them to take the time to document critical information. That's why I took on the challenge of building the new developers documentation portal for evitadb.io.
date: '04.5.2023'
author: 'Miro Alt'
motive: assets/images/05-building-documentation-portal.png
proofreading: 'done'
---

My goal was to create a documentation portal that's easy to maintain and update, with documentation files kept closely
to the code base and versionable for merge-request lifecycles. Seamless integration was a must-have for me, which is why
I made sure our documentation portal supports source files from different repositories using GitLab API. And of course,
I needed to make sure plain Markdown was correctly displayed on both GitLab and GitHub.

But I didn't stop there. I also wanted to provide support for specific functionalities and features that we discussed in
preparation stage.

So how did I do it? In this blog post, I'll walk you through how I used Next.js and Next MDX Remote to build the
evitadb.io documentation portal that met all of these requirements.

## Why I've chosen Next.JS + MDX

As mentioned earlier, the main priority was to allow developers to keep their `.md` or `.mdx` documentation files as
close to their actual code as possible. Additionally, we wanted the documentation portal to be a standalone repository,
separate from developers' busy repositories.

After some research, we discovered
the [MDX Remote example](https://github.com/vercel/next.js/tree/canary/examples/with-mdx-remote), which demonstrated how
a simple blog could be built using the [next-mdx-remote library](https://github.com/hashicorp/next-mdx-remote) library.
This library exposes a function and a component, `serialize` and ``<MDXRemote />``. It also enables MDX content to be
loaded via `getStaticProps` or `getServerSideProps`. Content itself can be loaded from a local folder, a database, or *
*any other location**, which was the crucial part.

One of the features of Next.js is the way it renders on the server side and client side. That’s crucial for the single
page applications (SPAs) it builds, and how it helps those SPAs have much-improved success in terms of SEO (search
engine optimization).

On top of that we knew that Next.js has other positives like:

- Excellent performance in terms of load times
- Load times helped with “lazy loading” and automatic code splitting
- Great support for developers
- Fantastic user-experience
- Faster time to market
- Great SEO

## Gitlab/Github API

At first, documents we "living" in private repository on **Gitlab** and we knew we were able
to [get RAW content](https://docs.gitlab.com/ee/api/repository_files.html#get-raw-file-from-repository) of the files
using their API and therefore provide external source to "pour" into MDX provider.

> Later, decision has been made to move repository where all documentation files are located to Github and adjusting
> to that was only matter of tweeking the actual request according
> to [Github API guide](https://docs.github.com/en/rest/guides/getting-started-with-the-rest-api?apiVersion=2022-11-28).

Here is an example of url outsourcing the data from Gitlab:

```
https://gitlab.example.com/api/v4/projects/13083/repository/files/app%2Fmodels%2Fkey%2Erb/raw?ref=master
```

Important attributes for us were:

- `id` - option to switch between repositories if necessary.
- `file_path` - URL-encoded full path to the file, such as `lib%2Fclass%2test.md`.
- `raw` - we needed RAW content.
- `ref` - branch to source from.

And here is the example of helper function I've used to get my hands on the file content.

```tsx
import config from "../config/default";
import {escapePathGitlab} from "./escapePathGitlab";
// im main config I keep oll important information (HEADERS, ID, etc.)
const {gitlabApiUrl} = config.genericDocsProps;
const {headers, projectId, docsUser} = config.DocProps;
const {branch: postsBranch} = docsUser;

export default async function fetchGlContentData(path) {
    const url = `${gitlabApiUrl}/${projectId}/repository/files/${escapePathGitlab(
        path
    )}/raw?ref=${postsBranch}`;
    try {
        const response = await fetch(url, {
            method: "GET",
            headers,
            redirect: "follow",
        });
        if (response.ok) {
            const text = await response.text();
            return text;
        } else {
            if (response.status === 404) {
            } else {
                console.log("Full error response:", response);
            }
        }
    } catch (error) {
        console.error("Error fetching data:", error);
    }
}
```

With data available I could now start processing them.

## Navigating through Documentation

Defining routes with predefined paths is not enough for complex applications. In Next.js you can add brackets to a
page (`[param]`) to create a dynamic route (URL slugs).

So at the remote repository we set the source folder as `docs`. From there we started to separate the documents into
more topic-related subfolders. As a source point in each subfolder we created `menu.json` file as the source of
navigation for our SPA.

So, at the heart of our documentation portal, the structure is similar to this:

```go
├── docs
|   ├── research
|   |   └── ...
|   ├── user
|       └── en
|           ├── use
|           |   └── doc.md
|           └── menu.json
```

In the following simplified example you can see how we use data, `serialise` (to add additional support for other
Markdown features like Tables using [remarkGfm](https://github.com/remarkjs/remark-gfm)). We also
use [gray-matter](https://www.npmjs.com/package/gray-matter), which is YAML formatted key/value pair data that we use to
display the author's name, for example.

```tsx

import {serialize} from 'next-mdx-remote/serialize'
import {MDXRemote} from 'next-mdx-remote'
import {customMdxComponents} from './utils/customMdxComponents';

export default function DocPage({source}) {
    return (
        <div className="wrapper">
            <h1>{source.scope.title}</h1>
            <MDXRemote {...source!} components={customMdxComponents}/>
        </div>
    )
}

export async function getStaticProps() {
    const documentationPath = CONFIG.docsProps.documentationPath;
    const {params} = props;
    const gitHubFilePath = `${[documentationPath, params?.slug].join('/');
    const text = await fetchGlContentData(gitHubFilePath);
    let post;
    let mdxSource;
    const {content, data} = grayMatter(text);
    post = {content, ...data};
    mdxSource = await serialize(content, {
    mdxOptions: {
    remarkPlugins: [remarkGfm],
    rehypePlugins: [rehypeSlug],
    },
    scope: data,
    });
    return {
    props: {
    source: mdxSource,
    },
    notFound: post == null,
    };
    }

```

> Compiler has support for many [remark](https://github.com/remarkjs/remark/blob/main/doc/plugins.md#list-of-plugins)
> and [rehype](https://github.com/rehypejs/rehype/blob/main/doc/plugins.md#list-of-plugins) plugins.

## Custom Components

At this point I've tried basic setup and I was getting familiar with implementation
of [MDX Remote Custom Components](https://github.com/vercel/next.js/tree/canary/examples/with-mdx-remote#conditional-custom-components).

I knew that basic Markdown just wouldn't cut it. With added support for Custom Components in our documentation files,
the possibilities were endless. As developers started plugging in more content, we couldn't help but come up with
features to enhance the reader's experience.

So, I started by creating Custom Components to provide developers with add-on features. When you look at
the [raw Markdown Content](https://raw.githubusercontent.com/FgForrest/evitaDB/dev/docs/user/en/use/api/write-tests.md)
of one of our source files, you'll see many Custom Components we are using throughout our documentation.

There are many libraries packed with components to suit our needs, and with many, we took inspiration from elsewhere and
tweaked them as necessary. However, some features and components were built from the ground up to suit our needs.

In the next post, I will sum up the core functionalities of some more advanced features we used on our Developers'
Portal. For example, how we optimised our Components to be "transient" with plain Markdown, Gitlab/Github routing and
display.

## What's next?

Our documentation portal is always evolving to meet the needs of our developers. Currently, we're working on
implementing more sophisticated error handling and reporting to make sure everything runs smoothly. Right now, when an
error occurs, developers have to dig through the production server logs to find the problem, which is far from ideal.
Although there is already a guide on [how to torubleshoot MDX](https://mdxjs.com/docs/troubleshooting-mdx/) and what to
look out for, we're dedicated to improving our workflows and adding even more features and functionalities. So stay
tuned, because the best is yet to come!
