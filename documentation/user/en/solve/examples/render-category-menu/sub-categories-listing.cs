EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		EntityLocaleEquals(CultureInfo.GetCultureInfo("en")),
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("url", "/en/accessories")
        		)
        	),
        	Require(
        		HierarchyOfReference(
        			"categories",
        			RemoveEmpty,
        			Children(
        				"subcategories",
        				EntityFetch(
        					AttributeContent("code")
        				),
        				StopAt(
        					Distance(1)
        				)
        			)
        		),
        		Page(1, 0)
        	)
        )
	)
);