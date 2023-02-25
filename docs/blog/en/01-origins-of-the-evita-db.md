---
title: The origins of the evitaDB
perex: Our company set foot in the e-commerce world around 2010. By that time we were in web development for more than 10 years, starting even before the .com bubble.
date: '2022-01-12'
author: 'Ing. Jan Novotný'
motive: assets/images/01-origins-of-the-evita-db.png
---

Our clients were middle-sized companies that have specific demands on their web
presentation and e-commerce integration, but they grew larger over the years along with their requirements. The portfolio
of their products started at thousands of products and went to tens of thousands and hundreds of thousands. They moved
their B2B communication to the web and required specific pricing policies based on many factors. So instead of a few
prices per product, they needed tens and sometimes hundreds of them. The SEO optimization required a lot of information
about a product in multiple languages. Each product required not only a basic description and a name, but also
a complex set of interconnected localized parameters and attributes. All of this information needed to be searchable with
millisecond latency and updatable in a matter of seconds. Our story is pretty similar to stories of other companies in
this industry.

These requirements usually lead to the creation of a really complex ecosystem of interconnected building blocks (relational
databases, nosql search indexes, caching services, queues, load balancers) usually spanning many computational nodes
(VPS, lambdas, physical HW servers - you name it). And even with such hell of resources the final solution is often not
as fast as it is expected to be. So many companies end up with a costly infrastructure requiring many specializations
and know how to operate it and develop for it (or they externalize some costs to cloud providers). That was the
moment when we stopped and started to ask ourselves - is all that necessary?

What if we didn't use general purpose databases and would have a data store, that is designed for e-commerce purposes
from the ground up? Will it perform better? Will it be easier to use? Can it even match the databases that best brains
developed for more than decades?

It sounds crazy - no one of us has a Ph.D. in computer science, and no one of us has ever designed a database. On the other
hand we know our business, we have the data sets to try, we know how the traffic looks like, and we’re craving to make
our life easier (and budget for feeding the cloud providers thinner). We have also a few advantages over our
predecessors:

* our working dataset (or at least the indexes) fits into RAM, and we can use a whole different approach and
  data-structures comparing to databases that must consider the amounts of data exceeding the volatile memory and access
  the indexes on disk
* the majority of the traffic are read queries that access the catalog (products, categories, parameters and so on),
  so we may relax on the write throughput if it brings better read throughput
* we have years of experience with existing databases from the web application developer prospective and we know their
  strengths and weaknesses - we know that a well-designed API makes half of the success

We’ve already been working on a few spike tests when the opportunity to take a research grant came along. We
successfully applied for it, and it allowed us to dive deeper than we could otherwise have been able to. With the grant
we could afford to implement three independent implementations of shared functional test suite that verified our
e-commerce use-cases (more on that topic later) that we can evaluate and compare in terms of performance and hardware
requirements. In cooperation with the University of Hradec Králové and doc. RNDr. Petra Poulová, Ph.D. and her team-mates
we selected the technologies that would participate in the competition. They were:

* PostgreSQL as a representative of widely used open-source relational database
* Elasticsearch as a representative of widely used open-source no-sql database
* newly developed in-memory no-sql database

All teams got all the use-cases up-front, and we all implemented the required functionality on the Java platform.
The datasets were shared among all of us as well as the performance test suite based on [JMH](https://github.com/openjdk/jmh).
Three years later we could see where the path had led us.