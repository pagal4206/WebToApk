plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "com.projecttemp.portal.PortalAppKt"
}

dependencies {
    implementation(libs.javalin)
    implementation(libs.jackson.kotlin)
    implementation(libs.slf4j.simple)
    testImplementation(libs.junit)
}
