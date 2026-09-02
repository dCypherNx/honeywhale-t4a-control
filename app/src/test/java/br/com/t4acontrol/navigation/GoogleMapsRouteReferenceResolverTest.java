package br.com.t4acontrol.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import br.com.t4acontrol.backend.navigation.RouteReferenceResolver.RouteResolutionException;
import org.junit.Test;

public class GoogleMapsRouteReferenceResolverTest {
  @Test public void passesThroughAlreadyExpandedReference() throws Exception {
    GoogleMapsRouteReferenceResolver resolver = new GoogleMapsRouteReferenceResolver();

    String reference = "https://www.google.com/maps/dir/A/B/C";

    assertEquals(reference, resolver.resolve(reference));
  }

  @Test public void rejectsEmptyReference() {
    GoogleMapsRouteReferenceResolver resolver = new GoogleMapsRouteReferenceResolver();

    assertThrows(RouteResolutionException.class, () -> resolver.resolve("  "));
  }
}
