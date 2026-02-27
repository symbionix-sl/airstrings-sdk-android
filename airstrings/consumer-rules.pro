# AirStrings SDK - Consumer ProGuard Rules
# These rules are automatically included when an app uses this AAR.

# Keep public API surface
-keep public class com.airstrings.sdk.AirStrings { public *; }
-keep public class com.airstrings.sdk.AirStringsConfiguration { public *; }
-keep public class com.airstrings.sdk.AirStringsLocale { public *; }
-keep public class com.airstrings.sdk.AirStringsLocale$System { public *; }
-keep public class com.airstrings.sdk.AirStringsLocale$Fixed { public *; }
-keep public class com.airstrings.sdk.AirStringsError { public *; }
-keep public class com.airstrings.sdk.AirStringsError$* { public *; }

# Keep Compose integration
-keep public class com.airstrings.sdk.compose.AirStringsLocalKt { public *; }

# BouncyCastle Ed25519 classes used via reflection-free lightweight API
-keep class org.bouncycastle.crypto.params.Ed25519PublicKeyParameters { *; }
-keep class org.bouncycastle.crypto.signers.Ed25519Signer { *; }
-keep class org.bouncycastle.math.ec.rfc8032.Ed25519 { *; }
