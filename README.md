# Caching filter for dropwizard.

Licence: Apache 2.0

How to use:

In main application: Register cache
---
  	@Override
  	public void run(final MainConfiguration configuration, final Environment environment) {
   	 environment.jersey().register(
        new CachingFilterFeature(
            Arrays.asList("Accept"), // list of HTTP request headers to use for key in addition to the method (e.g. GET) and URL
            CacheBuilder.newBuilder().softValues().build()
        ));
		...
	}
---

In resource: Instruct caching filter to cache operations per method
---
  	@GET
  	@Produces(MediaType.APPLICATION_JSON)
  	@Path("/cached")
  	@ResponseCachedByFilter(10000)
  	public Object getCached() {
    	return dao.get();
  	}
---

Parameter -- TTL of the cache entry in milliseconds.

# Principal diagram

![Principal diagram](https://github.com/AndreyLebedenko/dropwizard-caching-filter/raw/master/Dropwizard-Caching-Filter.jpg)
