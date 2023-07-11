# Example queries for spike tests

1. select all products that
    - a. are in a certain hierarchy (ie. category A or its subcategories)
    - b. have 3 attributes equal to some value
    - c. validity within range of two dates
    - d. with most prioritized price (of certain price lists - for example a, b, c) >= X and <= Y
    - e. with CZ localization (at least single CZ localized text)
    - f. use several above conditions at once
        - fa. "1a" with "1d"
        - fb. "1a" with "1c"
        - fc. "1a" with "1c" with "1d"
        - fd. "1a" - "1e"
    - g. order products by most prioritized price ascending 
    - h. order products by localized name ascending
    - i. order products by selected attribute ascending
2. select categories and render complete tree of them
    - a. select whole tree
    - b. select sub tree from specific category
3. select all facets of product in a certain hierarchy (ie. category A or its subcategories)
    - a. compute counts of products that posses such facet
