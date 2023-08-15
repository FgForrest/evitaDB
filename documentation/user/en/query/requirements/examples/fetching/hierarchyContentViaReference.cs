EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeEquals("code", "amazfit-gtr-3"),
        		EntityLocaleEquals(CultureInfo.GetCultureInfo("en"))
        	),
        	Require(
        		EntityFetch(
        			ReferenceContent(
        				"categories",
        				EntityFetch(
        					AttributeContent("code", "name"),
        					HierarchyContent(
        						EntityFetch(
        							AttributeContent("code", "name")
        						)
        					)
        				)
        			)
        		)
        	)
        )
	)
);