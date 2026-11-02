import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
    signing
}

val appyGroupId = providers.gradleProperty("appy.groupId").get()
val appyArtifactId = providers.gradleProperty("appy.artifactId").get()
val appySdkVersion = providers.gradleProperty("appy.version").get()
val appyProjectUrl = providers.gradleProperty("appy.projectUrl").get()
val appyScmUrl = providers.gradleProperty("appy.scmUrl").get()

val signingKey = providers.environmentVariable("APPY_SIGNING_KEY")
val signingKeyId = providers.environmentVariable("APPY_SIGNING_KEY_ID")
val signingPassword = providers.environmentVariable("APPY_SIGNING_PASSWORD")

val localMavenDir = rootProject.layout.buildDirectory.dir("local-maven")
val centralStagingDir = rootProject.layout.buildDirectory.dir("central-staging")

group = appyGroupId
version = appySdkVersion

android {
    namespace = "to.appy.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$appySdkVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation("com.android.installreferrer:installreferrer:2.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = appyGroupId
            artifactId = appyArtifactId
            version = appySdkVersion

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("Appy Android SDK")
                description.set("Deep links, deferred deep links, install attribution and event tracking for Android apps that use Appy smart links.")
                url.set(appyProjectUrl)
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("appy")
                        name.set("Appy")
                        url.set("https://appy.to")
                    }
                }
                scm {
                    url.set(appyScmUrl)
                    connection.set("scm:git:$appyScmUrl.git")
                    developerConnection.set("scm:git:$appyScmUrl.git")
                }
            }
        }
    }

    repositories {
        maven {
            name = "local"
            url = uri(localMavenDir)
        }
        maven {
            name = "centralStaging"
            url = uri(centralStagingDir)
        }
    }
}

signing {
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKeyId.orNull, signingKey.get(), signingPassword.orNull)
        sign(publishing.publications["release"])
    }
}

tasks.matching { it.name == "publishReleasePublicationToCentralStagingRepository" }.configureEach {
    doFirst {
        if (!signingKey.isPresent) {
            throw GradleException("Maven Central needs signed artifacts. Set APPY_SIGNING_KEY (ASCII-armored private key) and APPY_SIGNING_PASSWORD.")
        }
        delete(centralStagingDir)
    }
}

tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Builds the signed bundle to upload to the Maven Central Portal."
    dependsOn("publishReleasePublicationToCentralStagingRepository")
    from(centralStagingDir)
    exclude("**/maven-metadata.xml*")
    archiveFileName.set("$appyArtifactId-$appySdkVersion-central-bundle.zip")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("distributions"))
}
