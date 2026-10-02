# Third-party notices

Google Drive Backup is licensed under the [Apache License, Version 2.0](LICENSE).
The Windows app-image also contains the third-party software below, each under
its own license. This file lists what the release bundles; the full license
texts are in each library's jar (`META-INF`) and, for the Java runtime, in the
`runtime/legal` folder of the app-image.

## Java runtime and JavaFX

- **Java runtime**: a trimmed Eclipse Temurin 25 runtime, built from OpenJDK
  (GPL-2.0-only with the Classpath Exception). Source:
  <https://github.com/adoptium/jdk25u>. The runtime's own license and notice
  files are in `runtime/legal`.
- **JavaFX 27** (`javafx-base`, `javafx-controls`, `javafx-graphics`): OpenJFX
  (GPL-2.0-only with the Classpath Exception). Source:
  <https://github.com/openjdk/jfx>.

The Classpath Exception permits linking these libraries into an application
under any license, which is how this application uses them. They are used
unmodified.

## Libraries

Licenses are those declared in each library's Maven metadata. Where a library
is dual licensed, this application uses it under the first option listed:
Apache-2.0 for JNA, EPL-2.0 for Logback, and EPL-2.0 for the Jakarta
Annotations API. Logback and the Jakarta Annotations API are unmodified
separate jars, and their source is available from <https://github.com/qos-ch/logback>
and <https://github.com/jakartaee/common-annotations-api>.

| Library | Maven coordinates | License |
|---|---|---|
| Admin SDK API directory_v1-rev20260914-2.0.0 | `com.google.apis:google-api-services-admin-directory` directory_v1-rev20260914-2.0.0 | Apache-2.0 |
| Admin SDK API reports_v1-rev20260823-2.0.0 | `com.google.apis:google-api-services-admin-reports` reports_v1-rev20260823-2.0.0 | Apache-2.0 |
| Apache Commons Codec | `commons-codec:commons-codec` 1.21.0 | Apache-2.0 |
| Apache Commons Logging | `commons-logging:commons-logging` 1.3.6 | Apache-2.0 |
| Apache HTTP transport v2 for the Google HTTP Client Library for Java. | `com.google.http-client:google-http-client-apache-v2` 2.2.0 | Apache-2.0 |
| Apache HttpClient | `org.apache.httpcomponents:httpclient` 4.5.14 | Apache-2.0 |
| Apache HttpCore | `org.apache.httpcomponents:httpcore` 4.4.16 | Apache-2.0 |
| Apache Log4j API | `org.apache.logging.log4j:log4j-api` 2.25.5 | Apache-2.0 |
| API Common | `com.google.api:api-common` 2.70.0 | BSD-3-Clause |
| AutoValue Annotations | `com.google.auto.value:auto-value-annotations` 1.11.0 | Apache-2.0 |
| error-prone annotations | `com.google.errorprone:error_prone_annotations` 2.50.0 | Apache-2.0 |
| FindBugs-jsr305 | `com.google.code.findbugs:jsr305` 3.0.2 | Apache-2.0 |
| Google APIs Client Library for Java | `com.google.api-client:google-api-client` 2.9.1 | Apache-2.0 |
| Google Auth Library for Java - Credentials | `com.google.auth:google-auth-library-credentials` 1.54.0 | BSD-3-Clause |
| Google Auth Library for Java - OAuth2 HTTP | `com.google.auth:google-auth-library-oauth2-http` 1.54.0 | BSD-3-Clause |
| Google Drive API v3-rev20260916-2.0.0 | `com.google.apis:google-api-services-drive` v3-rev20260916-2.0.0 | Apache-2.0 |
| Google HTTP Client Library for Java | `com.google.http-client:google-http-client` 2.2.0 | Apache-2.0 |
| Google OAuth Client Library for Java | `com.google.oauth-client:google-oauth-client` 1.39.0 | Apache-2.0 |
| Gson | `com.google.code.gson:gson` 2.13.2 | Apache-2.0 |
| GSON extensions to the Google HTTP Client Library for Java. | `com.google.http-client:google-http-client-gson` 2.2.0 | Apache-2.0 |
| Guava InternalFutureFailureAccess and InternalFutures | `com.google.guava:failureaccess` 1.0.3 | Apache-2.0 |
| Guava ListenableFuture only | `com.google.guava:listenablefuture` 9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 |
| Guava: Google Core Libraries for Java | `com.google.guava:guava` 33.7.2-jre | Apache-2.0 |
| io.grpc:grpc-api | `io.grpc:grpc-api` 1.83.1 | Apache-2.0 |
| io.grpc:grpc-context | `io.grpc:grpc-context` 1.83.1 | Apache-2.0 |
| J2ObjC Annotations | `com.google.j2objc:j2objc-annotations` 3.1 | Apache-2.0 |
| Jakarta Annotations API | `jakarta.annotation:jakarta.annotation-api` 3.0.0 | EPL 2.0 / GPL2 w/ CPE |
| Java 6 (and higher) extensions to the Google OAuth Client Library for Java. | `com.google.oauth-client:google-oauth-client-java6` 1.39.0 | Apache-2.0 |
| Java Native Access | `net.java.dev.jna:jna` 5.9.0 | Apache-2.0 / LGPL-2.1-or-later |
| Java Native Access Platform | `net.java.dev.jna:jna-platform` 5.9.0 | Apache-2.0 / LGPL-2.1-or-later |
| JSpecify annotations | `org.jspecify:jspecify` 1.0.1 | Apache-2.0 |
| JUL to SLF4J bridge | `org.slf4j:jul-to-slf4j` 2.0.18 | MIT |
| Log4j API to SLF4J Adapter | `org.apache.logging.log4j:log4j-to-slf4j` 2.25.5 | Apache-2.0 |
| Logback Classic Module | `ch.qos.logback:logback-classic` 1.5.38 | EPL-2.0 / LGPL-2.1-only |
| Logback Core Module | `ch.qos.logback:logback-core` 1.5.38 | EPL-2.0 / LGPL-2.1-only |
| micrometer-commons | `io.micrometer:micrometer-commons` 1.17.1 | Apache-2.0 |
| micrometer-observation | `io.micrometer:micrometer-observation` 1.17.1 | Apache-2.0 |
| OAuth 2.0 verification code receiver for Google OAuth Client Library for Java. | `com.google.oauth-client:google-oauth-client-jetty` 1.39.0 | Apache-2.0 |
| OpenCensus | `io.opencensus:opencensus-api` 0.31.1 | Apache-2.0 |
| OpenCensus | `io.opencensus:opencensus-contrib-http-util` 0.31.1 | Apache-2.0 |
| Secure storage for storing credentials or tokens. | `com.microsoft:credential-secure-storage` 1.0.3 | MIT |
| Service Usage API v1-rev20260921-2.0.0 | `com.google.apis:google-api-services-serviceusage` v1-rev20260921-2.0.0 | Apache-2.0 |
| SLF4J API Module | `org.slf4j:slf4j-api` 2.0.18 | MIT |
| SnakeYAML | `org.yaml:snakeyaml` 2.6 | Apache-2.0 |
| Spring AOP | `org.springframework:spring-aop` 7.0.9 | Apache-2.0 |
| Spring Beans | `org.springframework:spring-beans` 7.0.9 | Apache-2.0 |
| Spring Context | `org.springframework:spring-context` 7.0.9 | Apache-2.0 |
| Spring Core | `org.springframework:spring-core` 7.0.9 | Apache-2.0 |
| Spring Expression Language (SpEL) | `org.springframework:spring-expression` 7.0.9 | Apache-2.0 |
| spring-boot | `org.springframework.boot:spring-boot` 4.1.1 | Apache-2.0 |
| spring-boot-autoconfigure | `org.springframework.boot:spring-boot-autoconfigure` 4.1.1 | Apache-2.0 |
| spring-boot-starter | `org.springframework.boot:spring-boot-starter` 4.1.1 | Apache-2.0 |
| spring-boot-starter-logging | `org.springframework.boot:spring-boot-starter-logging` 4.1.1 | Apache-2.0 |
| SQLite JDBC | `org.xerial:sqlite-jdbc` 3.53.4.0 | Apache-2.0 |

SQLite itself, whose native library `sqlite-jdbc` bundles, is in the public
domain.

## Regenerating this list

The table comes from the dependency metadata of a build:

```bash
./mvnw org.codehaus.mojo:license-maven-plugin:2.7.1:add-third-party \
  -Dlicense.includedScopes=compile,runtime -Dlicense.outputDirectory=target/licenses
```

Review it whenever a dependency is added or upgraded. `./mvnw verify` (and so
CI) fails when a runtime dependency declares a license outside the allow-list in
`pom.xml` (the `license-maven-plugin` execution `check-third-party-licenses`),
or none at all. Add a license to that list only after checking it is compatible
with Apache-2.0 for how the library is used.
