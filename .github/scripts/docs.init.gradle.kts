import org.gradle.api.Action
import org.gradle.api.DomainObjectSet
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import java.net.URI

// An init script has a different classloader from the Dokka plugin in each
// historical checkout. Reflect only across that boundary, then use Gradle APIs.
fun Any.dokkaProperty(name: String): Any = javaClass.methods.first {
    it.name == "get${name.replaceFirstChar(Char::uppercase)}" && it.parameterCount == 0
}.invoke(this)

@Suppress("UNCHECKED_CAST")
gradle.projectsEvaluated {
    val library = rootProject.project(":lib")
    val dokka = library.extensions.getByName("dokka")
    val publicOnly = setOf(
        library.plugins.findPlugin("org.jetbrains.dokka")!!.javaClass.classLoader
            .loadClass("org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier")
            .getField("Public").get(null),
    )
    val sourceCommit = library.providers.gradleProperty("docsSourceCommit").get()
    (dokka.dokkaProperty("moduleVersion") as Property<String>).set(
        library.providers.gradleProperty("docsVersion").get(),
    )
    (dokka.dokkaProperty("dokkaSourceSets") as NamedDomainObjectContainer<Any>).configureEach {
        (dokkaProperty("documentedVisibilities") as SetProperty<Any>).apply {
            set(publicOnly)
            disallowChanges()
        }
        (dokkaProperty("perPackageOptions") as DomainObjectSet<Any>).configureEach {
            (dokkaProperty("documentedVisibilities") as SetProperty<Any>).apply {
                set(publicOnly)
                disallowChanges()
            }
        }
        (dokkaProperty("includes") as ConfigurableFileCollection).from(library.file("README.md"))
        (dokkaProperty("sourceLinks") as DomainObjectSet<Any>).clear()
        val configureLink = object : Action<Any> {
            override fun execute(link: Any) {
                (link.dokkaProperty("localDirectory") as DirectoryProperty).set(library.file("src/main"))
                (link.dokkaProperty("remoteUrl") as Property<URI>).set(
                    URI("https://github.com/connectbot/termlib/blob/$sourceCommit/lib/src/main"),
                )
                (link.dokkaProperty("remoteLineSuffix") as Property<String>).set("#L")
            }
        }
        javaClass.methods.first { method ->
            method.name == "sourceLink" && method.parameterCount == 1 &&
                method.parameterTypes[0].isAssignableFrom(configureLink.javaClass)
        }.invoke(this, configureLink)
    }
}
