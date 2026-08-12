EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		ReferenceHaving(
        			"brand",
        			EntityHaving(
        				AttributeEquals("code", "asus")
        			)
        		),
        		UserFilter(
        			FacetHaving(
        				"categories",
        				EntityHaving(
        					AttributeEquals("code", "laptops")
        				),
        				IncludingChildrenExcept(
        					AttributeContains("code", "books")
        				)
        			)
        		)
        	),
        	Require(
        		ReferenceSummaryOfReference(
        			"categories",
        			Impact,
        			EntityFetch(
        				AttributeContent("code")
        			)
        		)
        	)
        )
	)
);