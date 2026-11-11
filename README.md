# Server-side caching filter for Dropwizard/Jersey.

Licence: Apache 2.0

How to use:

In main application: Register the cache
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

# What is not cached

Only successful responses are cached. A response with a status of 400 or above is never stored in
the cache: the requests waiting for it are released, and one of them calls the resource again.

This is a deliberate decision. An error is usually a temporary condition (an overloaded
downstream, a timeout, a broken dependency), and serving it from the cache would spread that
condition over the whole TTL, turning a short outage into a long one. The price is that during
such an outage every request reaches the resource. If your deployment prefers the predictability
over the load, cache the errors in the resource itself and let the filter deal with the successful
responses only.

# Principal diagram

![Principal diagram](https://github.com/AndreyLebedenko/dropwizard-caching-filter/raw/master/Dropwizard-Caching-Filter.jpg)
