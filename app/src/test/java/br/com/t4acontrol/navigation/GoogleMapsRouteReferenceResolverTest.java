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

  @Test public void preservesExpandedRouteEncodingExactly() {
    String reference =
        "https://www.google.com/maps/dir/-23.6376874,-46.6588237/-23.6133157,-46.6883815/"
            + "Av.+Brig.+Faria+Lima,+4400+-+Itaim+Bibi/data=!4m14!4m13!3e1"
            + "?utm_source=mstt_0&g_ep=abc%3D%3D";

    assertEquals(
        reference,
        GoogleMapsRouteReferenceResolver.extractExpandedRouteFromNavigationUrl(reference));
  }

  @Test public void rejectsEmptyReference() {
    GoogleMapsRouteReferenceResolver resolver = new GoogleMapsRouteReferenceResolver();

    assertThrows(RouteResolutionException.class, () -> resolver.resolve("  "));
  }

  @Test public void extractsMetaRefreshRedirect() {
    String html = "<html><head><meta http-equiv=\"refresh\" content=\"0; url=https://www.google.com/maps/dir/A/B?x=1&amp;y=2\"></head></html>";

    assertEquals(
        "https://www.google.com/maps/dir/A/B?x=1&amp;y=2",
        GoogleMapsRouteReferenceResolver.extractRedirectTarget(html));
  }

  @Test public void extractsJavascriptLocationRedirect() {
    String html = "<script>window.location.href='https://www.google.com/maps/dir/A/B/data=!4m2!3e1';</script>";

    assertEquals(
        "https://www.google.com/maps/dir/A/B/data=!4m2!3e1",
        GoogleMapsRouteReferenceResolver.extractRedirectTarget(html));
  }

  @Test public void extractsEscapedEmbeddedGoogleRoute() {
    String html = "route=\\\"https://www.google.com/maps/dir/A/B/data!3d1\\u0026foo=bar\\\"";

    assertEquals(
        "https://www.google.com/maps/dir/A/B/data!3d1&foo=bar",
        GoogleMapsRouteReferenceResolver.extractRedirectTarget(html));
  }
}
