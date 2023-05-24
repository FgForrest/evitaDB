query(
   collection("Product"),
   filterBy(
      and(
         hierarchyWithin(
            "categories",
            entityHaving(
               attributeEquals("url", "/local-food")
            )
         ),
         entityLocaleEquals(new Locale("cs", "CZ")),
         priceValidInNow(),
         priceInCurrency(Currency.getInstance("CZK")),
         priceInPriceLists("vip", "loyal-customer", "regular-prices"),
         userFilter(
            facetHaving(
               "parameter",
               entityHaving(
                  attributeInSet("code", "gluten-free", "original-recipe")
               )
            ),
            priceBetween(new BigDecimal(600), new BigDecimal(1600))
         )
      )
   ),
   require(
      page(1, 20),
      facetSummary(FacetStatisticsDepth.IMPACT),
      priceType(QueryPriceMode.WITH_TAX),
      priceHistogram(30)
   )
)