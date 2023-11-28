---
title: License
perex: |
    evitaDB is licensed under the Business Source License 1.1. Technically, it is not an open source license, but 
    is an open source friendly license, because it automatically converts to one after a period of time specified in the 
    license.
date: '22.2.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
---
evitaDB is licensed under the [Business Source License 1.1](https://github.com/FgForrest/evitaDB/blob/dev/LICENSE). Technically, it is not
an open source license, but is an [open source friendly](https://itsfoss.com/making-the-business-source-license-open-source-compliant/) 
license, because it automatically converts to one after a period of time specified in the license. 

We're fans of open source, and we've benefited a lot from open source software (even the database engine uses some of it).
The database implementation has taken thousands of man-days and, if successful, will take several thousand more. We were
lucky to get an [EU grant](https://evitadb.io/project-info) that partially funded the initial implementation, but we
need to build a self-sustaining project in the long run. [Our company](https://www.fg.cz/en) uses evitaDB for its own
commercial projects, so the development of the database is guaranteed, but without additional income the development 
would be limited. That's why we have chosen this type of license, but in the end we allow you - our users - almost any 
use.

**In a nutshell:**

- the BSL license covers a period of 4 years from the date of the software release
- 4 year old version of evitaDB becomes [permissive Apache License, v.2](https://fossa.com/blog/open-source-licenses-101-apache-license-2-0/)
- both BSL and Apache licenses allow you to use evitaDB for OSS and/or commercial projects free of charge
- there is one exception - you may not offer and sell evitaDB as a service to third parties

That's it.

<Note type="question">

<NoteTitle toggles="true">

##### Can I use evitaDB free of charge for commercial purposes?
</NoteTitle>

You can use it for development, testing, and production use of any software - non-commercial or commercial - that you
develop and distribute to your customers. You can bundle evitaDB with your software distribution or use it as a
separately running service in client-server mode.

You cannot take the evitaDB and sell it as [DBaaS](https://www.geeksforgeeks.org/overview-of-database-as-a-service/)
without negotiating a license with us.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Does the license affect the data stored in the database?
</NoteTitle>

No, you still own your data and decide what to do with it. The BSL license only covers the software and source code of
evitaDB. We don't collect or retrieve any statistics from the running software. However, if you request our help to
solve a problem with the software, we may ask you to provide us with some data that demonstrates the problem.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Can I use evitaDB in an open source library licensed under a different license?
</NoteTitle>

Yes, you can, as long as you don't copy the content of the evitaDB source code into your library and only use the
evitaDB API via a linked library or a web API. If you fork the evitaDB repository and make changes to the source code,
it's still covered by the BSL license.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Can I contribute to the development of evitaDB or provide a bugfix?
</NoteTitle>

Of course you can, and we will be happy if you do. You just need to explicitly license your code under
the [new BSD license](https://opensource.org/license/bsd-3-clause/) so that we can include it in our codebase. Your name
will be added to our contributors list with thanks.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Didn't you find all of the answers to your questions here?
</NoteTitle>

Then you may want to look at the [Questions and Answers on the MariaDB website](https://mariadb.com/bsl-faq-adopting/).
MariaDB is the author and pioneer of the BSL license. You might also be interested
in [article](https://blog.adamretter.org.uk/business-source-license-adoption/), which reflects on database licensing and
its changes in recent years. Of course, you can always [ask us directly](https://evitadb.io/contacts).

</Note>