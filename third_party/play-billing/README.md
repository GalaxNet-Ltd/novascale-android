# Google Play Billing dependency record

Local development pin: `com.android.billingclient:billing:9.1.0`.
Only the `play` source set depends on it. Community builds do not resolve/package
this dependency. NovaScale's adapter is original Apache-2.0 source; no Google
sample code was copied into it.

- [Google Maven POM](https://dl.google.com/dl/android/maven2/com/android/billingclient/billing/9.1.0/billing-9.1.0.pom)
  SHA-256: `99b89f5001a3df1971e5c5b8c3742f1c5197848a64c8105bbc81b4fa6299a545`
- [Google Maven AAR](https://dl.google.com/dl/android/maven2/com/android/billingclient/billing/9.1.0/billing-9.1.0.aar)
  SHA-256: `d28286d4e4c18725510980de01211d9b72501e7146d96817fa0c9999af9b0d22`
- Declared license: Android Software Development Kit License, linking to
  [Google's SDK terms](https://developer.android.com/studio/terms.html).
- The AAR includes `third_party_licenses.json` and `third_party_licenses.txt`.
  These are third-party notices, not an established open-source license for
  the Billing implementation itself.

The Termux-derived view was removed on 2026-11-11 and the owner relicensed
NovaScale-owned source under Apache-2.0. Google's SDK remains under its own terms;
NovaScale does not sublicense it as Apache or require disclosure of its source.
This removes the earlier whole-application GPL conflict. The LGPL editor
dependencies remain separately licensed, unmodified and replaceable from a source
build as described in `../lgpl/README.md`. Source and notices for those components
must accompany a distributed release as specified there.

`validatePlayBillingDistribution` now checks the recorded SDK pin, application
license, independent terminal source boundary, absence of a Termux runtime
dependency and packaged LGPL text. This is a regression check for the documented
distribution basis, not a flag that waives dependency terms. Play account/product
configuration, release signing, immutable public source and actual Play purchase
tests remain separate release requirements. Community builds omit the SDK.

JVM billing tests additionally use `org.json:json:20250517` (Public Domain,
as declared in its POM). It is test-only and not packaged in either APK; Android
runtime parsing uses the platform `org.json` implementation.
