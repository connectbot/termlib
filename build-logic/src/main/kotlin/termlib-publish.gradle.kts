import com.vanniktech.maven.publish.DeploymentValidation
import com.vanniktech.maven.publish.MavenPublishBaseExtension

private val gitHubUrl = "https://github.com/connectbot/termlib"

plugins {
    id("com.vanniktech.maven.publish")
}

extensions.configure<MavenPublishBaseExtension> {
    publishToMavenCentral(automaticRelease = true, validateDeployment = DeploymentValidation.PUBLISHED)
    signAllPublications()

    pom {
        url.set(gitHubUrl)
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("kruton")
                name.set("Kenny Root")
                url.set("https://github.com/kruton/")
            }
        }
        scm {
            connection.set("scm:git:$gitHubUrl.git")
            developerConnection.set("$gitHubUrl.git")
            url.set(gitHubUrl)
        }
    }
}
