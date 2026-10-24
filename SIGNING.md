# Signed release builds on GitHub

1. Create a keystore once (keep it safe, never commit it):

       keytool -genkeypair -v -keystore release.jks -alias asterisk \
         -keyalg RSA -keysize 2048 -validity 10000

2. Base64-encode it:

       base64 -w0 release.jks      # macOS: base64 -i release.jks

3. GitHub repo -> Settings -> Secrets and variables -> Actions -> New secret:

   | Secret | Value |
   |---|---|
   | `KEYSTORE_BASE64` | output of step 2 |
   | `KEYSTORE_PASSWORD` | keystore password |
   | `KEY_ALIAS` | `asterisk` |
   | `KEY_PASSWORD` | key password |

4. Push to `main` -> debug + signed release APKs appear under the run's *Artifacts*.
   Push a tag (`git tag v1.1.0 && git push --tags`) -> APKs are also attached to a GitHub Release.

Without the secrets the workflow still builds the debug APK.

Local signed build: create `keystore.properties` (git-ignored) with
`storeFile`, `storePassword`, `keyAlias`, `keyPassword`, then `./gradlew assembleRelease`.
