plugins {
    `java-library`
}

dependencies {
    implementation(project(":mahjong-rule-spi"))
    implementation(project(":mahjong-domain"))
    implementation(project(":mahjong-application"))
    testImplementation("com.h2database:h2:2.4.240")
    // Loaded only by ExternalRankingQueryPlanTest when GitHub supplies real database services.
    // Keep these aligned with the drivers bundled by mahjong-plugin.
    testRuntimeOnly("com.mysql:mysql-connector-j:9.7.0")
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.9")
}
